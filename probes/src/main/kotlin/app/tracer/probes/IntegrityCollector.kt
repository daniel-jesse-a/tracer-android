package app.tracer.probes

import android.os.SystemClock
import app.tracer.core.model.IntegritySample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import app.tracer.probes.di.ProbeHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * [integrity] — path integrity checks at run start and every [INTERVAL_MS], per probed network of
 * [ProbeNetworks.networksFor] (settings.paths), written to the shared "integrity" table:
 *  - kind "dns_doh": for each probe host, the system resolver of that network
 *    (Network.getAllByName) vs DNS-over-HTTPS JSON (cloudflare-dns.com, A + AAAA, over OkHttp bound
 *    to the same network). ok = the answer sets overlap (CDNs rotate, so overlap, not equality);
 *    null when DoH itself failed.
 *  - kind "first_hop": first public hop toward 1.1.1.1 by TTL-limited ping (TTL 1..8). ping cannot
 *    be bound to a Network, so it runs only for the current default network, and only when that
 *    network is one of the probed paths; skipped when /system/bin/ping is missing.
 * (kind "tls_cert" comes from [LatencyProbeCollector], which reuses each probe's handshake.)
 *
 * Every check is time-boxed and runs off the probe collectors' coroutines, so it never delays them.
 */
class IntegrityCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "integrity"

    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        val paths = settings.paths
        val hosts = hostsOf(settings.endpoints.map { it.url } + settings.throughputUrl + settings.captiveUrl + settings.publicIpUrl)
            .take(MAX_HOSTS)
        networks.acquireFor(paths)
        try {
            networks.awaitLease(paths)
            while (isActive) {
                ctx.signals.awaitResumed()
                val startedMs = SystemClock.elapsedRealtime()
                val targets = networks.networksFor(paths)
                val rows = withTimeoutOrNull(CYCLE_TIMEOUT_MS) {
                    coroutineScope {
                        val dns = targets.map { b -> async { dnsChecks(ctx, b, hosts) } }
                        val hop = async { firstHop(ctx, targets) }
                        dns.awaitAll().flatten() + listOfNotNull(hop.await())
                    }
                }.orEmpty()
                for (row in rows) {
                    send(row)
                    ctx.status.sample(id, row.stamp.tMonoNs)
                }
                delay((INTERVAL_MS - (SystemClock.elapsedRealtime() - startedMs)).coerceAtLeast(MIN_GAP_MS))
            }
        } finally {
            networks.releaseFor(paths)
        }
    }

    // ------------------------------------------------------------ dns_doh

    private suspend fun dnsChecks(ctx: CollectorContext, bound: BoundNetwork, hosts: List<String>): List<IntegritySample> {
        val client = networks.client(baseClient, bound.network, DOH_TIMEOUT_MS)
        val rows = coroutineScope {
            hosts.map { host -> async { dnsCheck(ctx, bound, client, host) } }.awaitAll()
        }
        // Wi-Fi went away during the check: write nothing for Wi-Fi rather than failed comparisons.
        return if (bound.isWifi && !networks.isUp(bound)) emptyList() else rows
    }

    private suspend fun dnsCheck(ctx: CollectorContext, bound: BoundNetwork, client: OkHttpClient, host: String): IntegritySample = coroutineScope {
        val stamp = ctx.clock.now()
        val system = async { systemLookup(bound, host) }
        val a = async { doh(client, host, "A") }
        val aaaa = async { doh(client, host, "AAAA") }
        val (sysList, sysError) = system.await()
        val (aList, aError) = a.await()
        val (aaaaList, aaaaError) = aaaa.await()
        val dohList = if (aList == null && aaaaList == null) null else aList.orEmpty() + aaaaList.orEmpty()
        val cmp = compareDns(host, sysList, dohList, sysError, if (dohList == null) aError ?: aaaaError else null)
        IntegritySample(
            runId = ctx.runId,
            stamp = stamp,
            kind = "dns_doh",
            path = bound.iface,
            target = host,
            ok = cmp.ok,
            value = cmp.value,
            detailJson = cmp.detailJson,
        )
    }

    /** System resolver of [bound] → (addresses, null) or (null, error: nxdomain | timeout | io). */
    private suspend fun systemLookup(bound: BoundNetwork, host: String): Pair<List<String>?, String?> {
        // getAllByName blocks and is not interruptible: on timeout the IO thread finishes on its own.
        val result = withTimeoutOrNull(DNS_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    bound.network.getAllByName(host).mapNotNull { it.hostAddress }.let { it to null }
                } catch (e: UnknownHostException) {
                    null to "nxdomain"
                } catch (e: IOException) {
                    null to "io"
                } catch (e: SecurityException) {
                    null to "io"
                }
            }
        }
        return result ?: (null to "timeout")
    }

    /** DoH JSON answers for [type] → (addresses, null) or (null, error). */
    private suspend fun doh(client: OkHttpClient, host: String, type: String): Pair<List<String>?, String?> {
        val request = try {
            Request.Builder().url(dohUrl(host, type)).get().header("Accept", "application/dns-json").build()
        } catch (e: IllegalArgumentException) {
            return null to "io"
        }
        val r = HttpProbe.execute(client, request, BodyMode.Text(), followRedirects = false)
        if (!r.success) return null to (r.error ?: "io")
        if (!is2xx(r.code)) return null to "http_${r.code}"
        val answers = r.body?.let(::parseDohAnswers) ?: return null to "parse"
        return answers to null
    }

    // ------------------------------------------------------------ first_hop

    private suspend fun firstHop(ctx: CollectorContext, targets: List<BoundNetwork>): IntegritySample? {
        if (!File(PING_BINARY).exists()) return null
        val current = networks.current() ?: return null
        // Unbound ping follows the default network: only meaningful when that is a probed path.
        val bound = targets.firstOrNull { it.network == current.network } ?: return null
        val stamp = ctx.clock.now()
        val result = withContext(Dispatchers.IO) {
            findFirstPublicHop { ttl ->
                if (!isActive) return@findFirstPublicHop null
                val proc = runProcess(pingTtlCommand(ttl, binary = PING_BINARY), PING_TIMEOUT_MS) ?: return@findFirstPublicHop null
                // exit 2 without any parsable line = ping unusable here (bad option / not permitted)
                if (proc.exitCode == 2 && !proc.output.contains("From", ignoreCase = true) && !proc.output.contains("bytes from")) {
                    return@findFirstPublicHop null
                }
                parseTtlPing(ttl, proc.output, proc.elapsedMs)
            }
        }
        if (result.hops.isEmpty()) return null
        if (bound.isWifi && !networks.isUp(bound)) return null
        return IntegritySample(
            runId = ctx.runId,
            stamp = stamp,
            kind = "first_hop",
            path = bound.iface,
            target = FIRST_HOP_TARGET,
            ok = result.ok,
            value = result.publicHop?.ip,
            detailJson = result.detailJson,
        )
    }

    private class ProcResult(val exitCode: Int?, val output: String, val elapsedMs: Double)

    /** Runs [command] with a deadline; null when it could not be started. Blocking: call on Dispatchers.IO. */
    private fun runProcess(command: List<String>, timeoutMs: Long): ProcResult? {
        val t0 = System.nanoTime()
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        return try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsedMs = (System.nanoTime() - t0) / 1e6
            if (!finished) {
                process.destroyForcibly()
                ProcResult(null, "", elapsedMs)
            } else {
                ProcResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() }, elapsedMs)
            }
        } catch (e: IOException) {
            ProcResult(null, "", (System.nanoTime() - t0) / 1e6)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            null
        } finally {
            try { process.inputStream.close() } catch (e: IOException) { }
            try { process.outputStream.close() } catch (e: IOException) { }
            process.destroy()
        }
    }

    private companion object {
        const val INTERVAL_MS = 10L * 60 * 1000
        const val MIN_GAP_MS = 60_000L
        const val CYCLE_TIMEOUT_MS = 45_000L
        const val DNS_TIMEOUT_MS = 5_000L
        const val DOH_TIMEOUT_MS = 5_000L
        const val PING_TIMEOUT_MS = 2_000L
        const val MAX_HOSTS = 8
        const val PING_BINARY = "/system/bin/ping"
    }
}
