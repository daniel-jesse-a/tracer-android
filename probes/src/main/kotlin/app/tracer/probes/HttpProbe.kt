package app.tracer.probes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Per-phase durations of one HTTP call, in milliseconds (null = phase did not happen). */
data class Phases(
    val dnsMs: Double? = null,
    /** TCP connect only (excludes TLS). */
    val connectMs: Double? = null,
    val tlsMs: Double? = null,
    /** Request headers sent → first response header byte. */
    val ttfbMs: Double? = null,
    val totalMs: Double? = null,
)

/**
 * OkHttp [EventListener] recording phase timestamps with System.nanoTime(). One instance per
 * call. Retried connects overwrite earlier attempts, so phases describe the attempt that won.
 */
class PhaseTimingListener(private val nanoTime: () -> Long = System::nanoTime) : EventListener() {
    @Volatile private var callStart = -1L
    @Volatile private var dnsStart = -1L
    @Volatile private var dnsEnd = -1L
    @Volatile private var connectStart = -1L
    @Volatile private var secureStart = -1L
    @Volatile private var secureEnd = -1L
    @Volatile private var connectEnd = -1L
    @Volatile private var requestHeadersEnd = -1L
    @Volatile private var responseHeadersStart = -1L
    @Volatile private var callEnd = -1L

    override fun callStart(call: Call) { callStart = nanoTime() }
    override fun dnsStart(call: Call, domainName: String) { dnsStart = nanoTime() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dnsEnd = nanoTime() }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = nanoTime(); secureStart = -1L; secureEnd = -1L; connectEnd = -1L
    }
    override fun secureConnectStart(call: Call) { secureStart = nanoTime() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { secureEnd = nanoTime() }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connectEnd = nanoTime() }
    override fun requestHeadersEnd(call: Call, request: Request) { requestHeadersEnd = nanoTime() }
    override fun responseHeadersStart(call: Call) { responseHeadersStart = nanoTime() }
    override fun callEnd(call: Call) { callEnd = nanoTime() }
    override fun callFailed(call: Call, ioe: IOException) { callEnd = nanoTime() }

    /** Phases so far; [endNs] overrides the call end (e.g. when the body was read manually). */
    fun phases(endNs: Long? = null): Phases {
        fun ms(a: Long, b: Long): Double? = if (a >= 0 && b >= a) (b - a) / 1e6 else null
        val tcpEnd = if (secureStart >= 0) secureStart else connectEnd
        val end = endNs ?: callEnd
        return Phases(
            dnsMs = ms(dnsStart, dnsEnd),
            connectMs = ms(connectStart, tcpEnd),
            tlsMs = ms(secureStart, secureEnd),
            ttfbMs = ms(requestHeadersEnd, responseHeadersStart),
            totalMs = ms(callStart, end),
        )
    }
}

/** What to do with a response body. */
sealed interface BodyMode {
    /** Close immediately (HEAD / status-only probes). */
    data object Ignore : BodyMode
    /** Stream and count bytes into a reusable buffer, stopping at [capBytes]. */
    data class Discard(val capBytes: Long, val maxTransferMs: Long = Long.MAX_VALUE) : BodyMode
    /** Read as text, at most [maxBytes]. */
    data class Text(val maxBytes: Long = 64 * 1024) : BodyMode
}

/**
 * Outcome of one probe call.
 *
 * [success] = *reachable*: the first, timed hop returned an HTTP response — any status, including
 * 3xx/4xx/5xx. [error] is set only for transport failures (timeout | dns | connect | tls | io); a
 * failure while reading the body of a received response keeps success = true and sets [error].
 * Whether the response was the *expected* one is decided by the caller from [finalCode].
 */
data class HttpProbeResult(
    val success: Boolean,
    /** Status of the first (timed) hop. */
    val code: Int?,
    val error: String?,
    /** Timing of the first hop only (redirects are followed untimed). */
    val phases: Phases,
    val bytes: Long? = null,
    /** Whole call duration (start → body fully read / headers for Ignore), ns. */
    val durationNs: Long? = null,
    val body: String? = null,
    /** Body transfer time from first body byte to last read byte (Discard mode), ns. */
    val transferNs: Long? = null,
    /** Redirects followed after a 3xx first hop (0 when the first hop was not a redirect). */
    val redirectCount: Int = 0,
    /** URL of the last response in the redirect chain (the request URL when nothing was followed). */
    val finalUrl: String? = null,
    /** Status at the end of the redirect chain (= [code] when nothing was followed; null if a follow-up failed). */
    val finalCode: Int? = null,
)

/** Executes probe requests with per-call phase timing and error classification. */
object HttpProbe {

    /**
     * Runs [request] on [client] (which should have redirects disabled) as one timed hop with a fresh
     * [PhaseTimingListener]. When that hop is a 3xx with a Location and [followRedirects] is set, the
     * chain is followed with a separate, untimed request on the same client (same bound network), at
     * most [MAX_REDIRECTS] redirects in total, to fill redirectCount / finalUrl / finalCode.
     * Never throws for network failures; cancellation cancels the call.
     */
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
        body: BodyMode = BodyMode.Ignore,
        followRedirects: Boolean = true,
    ): HttpProbeResult {
        val timer = PhaseTimingListener()
        val timed = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .eventListenerFactory(object : EventListener.Factory {
                override fun create(call: Call): EventListener = timer
            })
            .build()
        val startNs = System.nanoTime()
        val response = try {
            timed.newCall(request).await()
        } catch (e: IOException) {
            return HttpProbeResult(success = false, code = null, error = classifyError(e), phases = timer.phases(System.nanoTime()))
        }
        val first = withContext(Dispatchers.IO) {
            response.use { r ->
                var bytes: Long? = null
                var text: String? = null
                var transferNs: Long? = null
                var bodyError: String? = null
                when (body) {
                    BodyMode.Ignore -> Unit
                    is BodyMode.Discard -> {
                        val d = discard(r, body.capBytes, body.maxTransferMs)
                        bytes = d.bytes; transferNs = d.transferNs; bodyError = d.error?.let(::classifyError)
                    }
                    is BodyMode.Text -> try {
                        val raw = r.peekBody(body.maxBytes).bytes()
                        text = String(raw, Charsets.UTF_8)
                        bytes = raw.size.toLong()
                    } catch (e: IOException) {
                        bodyError = classifyError(e)
                    }
                }
                val endNs = System.nanoTime()
                HttpProbeResult(
                    success = true,
                    code = r.code,
                    error = bodyError,
                    phases = timer.phases(endNs),
                    bytes = bytes,
                    durationNs = endNs - startNs,
                    body = text,
                    transferNs = transferNs,
                    redirectCount = 0,
                    finalUrl = r.request.url.toString(),
                    finalCode = r.code,
                ) to r.header("Location")
            }
        }
        val (result, location) = first
        if (!followRedirects || !isRedirect(result.code) || location == null) return result
        val chain = follow(client, request, location)
        return result.copy(redirectCount = chain.count, finalUrl = chain.finalUrl, finalCode = chain.finalCode)
    }

    private data class Chain(val count: Int, val finalUrl: String, val finalCode: Int?)

    /**
     * Follows the redirect from [original]'s first hop to [location], untimed. The first hop already
     * counts as one redirect; the follower may follow [MAX_REDIRECTS] − 1 more — after that the
     * Location header is dropped so OkHttp returns the 3xx as the final response.
     */
    private suspend fun follow(client: OkHttpClient, original: Request, location: String): Chain {
        val target = original.url.resolve(location)
            ?: return Chain(1, location, null)
        val hops = AtomicInteger(0)
        val follower = client.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .eventListener(EventListener.NONE)
            .addNetworkInterceptor(Interceptor { chain ->
                val resp = chain.proceed(chain.request())
                if (hops.incrementAndGet() >= MAX_REDIRECTS && isRedirect(resp.code)) {
                    resp.newBuilder().removeHeader("Location").build()
                } else {
                    resp
                }
            })
            .build()
        // Same method (probes only use HEAD/GET without a body) and headers as the timed hop.
        val next = original.newBuilder().url(target).build()
        return try {
            val r = follower.newCall(next).await()
            r.use {
                var prior = 0
                var p = it.priorResponse
                while (p != null) { prior++; p = p.priorResponse }
                Chain(1 + prior, it.request.url.toString(), it.code)
            }
        } catch (e: IOException) {
            Chain(1, target.toString(), null)
        }
    }

    private class Discarded(val bytes: Long, val transferNs: Long?, val error: IOException?)

    /**
     * Reads and discards up to [capBytes] or until [maxTransferMs] after the first byte. A transport
     * failure mid-body keeps the bytes read so far (they count against the mobile-data budget).
     */
    private fun discard(r: Response, capBytes: Long, maxTransferMs: Long): Discarded {
        // Typed as nullable so this compiles whether or not ResponseBody is platform-nullable.
        val responseBody: ResponseBody? = r.body
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var firstNs = 0L
        var lastNs = 0L
        var error: IOException? = null
        val capNs = if (maxTransferMs == Long.MAX_VALUE) Long.MAX_VALUE else maxTransferMs * 1_000_000L
        try {
            val input = responseBody?.byteStream() ?: return Discarded(0L, null, null)
            while (total < capBytes) {
                val want = minOf(buf.size.toLong(), capBytes - total).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                lastNs = System.nanoTime()
                if (firstNs == 0L) firstNs = lastNs
                total += n
                if (lastNs - firstNs >= capNs) break
            }
        } catch (e: IOException) {
            error = e
        }
        return Discarded(total, if (firstNs == 0L) null else (lastNs - firstNs).coerceAtLeast(1L), error)
    }
}

/** Suspends until the call returns a response; cancels the call on coroutine cancellation. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
