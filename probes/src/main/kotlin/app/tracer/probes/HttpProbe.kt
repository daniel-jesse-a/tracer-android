package app.tracer.probes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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

/** Outcome of one probe call. */
data class HttpProbeResult(
    val success: Boolean,
    val code: Int?,
    val error: String?,
    val phases: Phases,
    val bytes: Long? = null,
    /** Whole call duration (start → body fully read / headers for Ignore), ns. */
    val durationNs: Long? = null,
    val body: String? = null,
    /** Body transfer time from first body byte to last read byte (Discard mode), ns. */
    val transferNs: Long? = null,
)

/** Executes probe requests with per-call phase timing and error classification. */
object HttpProbe {

    /**
     * Runs [request] on [client] (which should have redirects disabled), with a fresh
     * [PhaseTimingListener]. Never throws for network failures; cancellation cancels the call.
     * @param isSuccess decides success from the status code (default 2xx)
     */
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
        body: BodyMode = BodyMode.Ignore,
        isSuccess: (Int) -> Boolean = { it in 200..299 },
    ): HttpProbeResult {
        val timer = PhaseTimingListener()
        val timed = client.newBuilder()
            .eventListenerFactory(object : EventListener.Factory {
                override fun create(call: Call): EventListener = timer
            })
            .build()
        val startNs = System.nanoTime()
        return try {
            val response = timed.newCall(request).await()
            withContext(Dispatchers.IO) {
                response.use { r ->
                    var bytes: Long? = null
                    var text: String? = null
                    var transferNs: Long? = null
                    when (body) {
                        BodyMode.Ignore -> Unit
                        is BodyMode.Discard -> {
                            val d = discard(r, body.capBytes, body.maxTransferMs)
                            bytes = d.first; transferNs = d.second
                        }
                        is BodyMode.Text -> {
                            val raw = r.peekBody(body.maxBytes).bytes()
                            text = String(raw, Charsets.UTF_8)
                            bytes = raw.size.toLong()
                        }
                    }
                    val endNs = System.nanoTime()
                    val ok = isSuccess(r.code)
                    HttpProbeResult(
                        success = ok,
                        code = r.code,
                        error = if (ok) null else "http_${r.code}",
                        phases = timer.phases(endNs),
                        bytes = bytes,
                        durationNs = endNs - startNs,
                        body = text,
                        transferNs = transferNs,
                    )
                }
            }
        } catch (e: IOException) {
            HttpProbeResult(success = false, code = null, error = classifyError(e), phases = timer.phases(System.nanoTime()))
        }
    }

    /** Reads and discards up to [capBytes] or until [maxTransferMs] after the first byte. Returns (bytes, transferNs). */
    private fun discard(r: Response, capBytes: Long, maxTransferMs: Long): Pair<Long, Long?> {
        // Typed as nullable so this compiles whether or not ResponseBody is platform-nullable.
        val responseBody: ResponseBody? = r.body
        val input = responseBody?.byteStream() ?: return 0L to null
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var firstNs = 0L
        var lastNs = 0L
        val capNs = if (maxTransferMs == Long.MAX_VALUE) Long.MAX_VALUE else maxTransferMs * 1_000_000L
        while (total < capBytes) {
            val want = minOf(buf.size.toLong(), capBytes - total).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            lastNs = System.nanoTime()
            if (firstNs == 0L) firstNs = lastNs
            total += n
            if (lastNs - firstNs >= capNs) break
        }
        return total to (if (firstNs == 0L) null else (lastNs - firstNs).coerceAtLeast(1L))
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
