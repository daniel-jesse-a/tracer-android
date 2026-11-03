package app.tracer.probes

import android.net.LinkProperties
import android.os.SystemClock
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * [probe_internal] — the local legs of each probed path, every max(latency interval, 2 s), for every
 * network of [ProbeNetworks.networksFor] (settings.paths):
 *  - probe_type "gateway": one ICMP echo to the default gateway (first IPv4 gateway of a default
 *    route) via `/system/bin/ping -c 1 -W 1 -I <ifname> <gw>`; skipped when there is no gateway
 *    (typical on cellular point-to-point links).
 *  - probe_type "dns_server": one UDP DNS A query for [DNS_PROBE_QNAME] to each DHCP DNS server
 *    (max 3) on a socket bound to that network; http_status = DNS RCODE.
 * success = a reply arrived (ping reply / DNS response with our id); total_ms = round trip.
 * Enabled by settings.internalProbes.
 */
class InternalProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
) : Collector {
    override val id = "probe_internal"

    private val random = SecureRandom()

    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        if (!settings.internalProbes) {
            ctx.status.disabled(id, "Internal probes (gateway / DNS server) are turned off in settings.")
            awaitCancellation()
        }
        val paths = settings.paths
        val interval = internalProbeIntervalMs(settings.latencyIntervalMs)
        networks.acquireFor(paths)
        try {
            networks.awaitLease(paths)
            while (isActive) {
                ctx.signals.awaitResumed()
                val startedMs = SystemClock.elapsedRealtime()
                val results = coroutineScope {
                    networks.networksFor(paths).map { bound -> async { probeNetwork(ctx, bound) } }.awaitAll()
                }
                for (sample in results.flatten()) {
                    send(sample)
                    ctx.status.sample(id, sample.stamp.tMonoNs)
                }
                delay((interval - (SystemClock.elapsedRealtime() - startedMs)).coerceAtLeast(MIN_GAP_MS))
            }
        } finally {
            networks.releaseFor(paths)
        }
    }

    /** Gateway ping and DNS-server queries of one network, concurrently. */
    private suspend fun probeNetwork(ctx: CollectorContext, bound: BoundNetwork): List<ProbeSample> = coroutineScope {
        val lp = networks.linkProperties(bound.network) ?: return@coroutineScope emptyList()
        val gateway = defaultGateway(lp)
        val dns = lp.dnsServers.take(MAX_DNS_SERVERS)
        val ifname = lp.interfaceName ?: bound.ifname
        val jobs = buildList<Deferred<ProbeSample>> {
            if (gateway != null) add(async { pingGateway(ctx, bound, gateway, ifname) })
            dns.forEach { server -> add(async { queryDns(ctx, bound, server) }) }
        }
        jobs.awaitAll()
    }

    /** First IPv4 gateway of a default route. */
    private fun defaultGateway(lp: LinkProperties): Inet4Address? =
        lp.routes.firstOrNull { it.isDefaultRoute && it.hasGateway() && it.gateway is Inet4Address }?.gateway as? Inet4Address

    private suspend fun pingGateway(ctx: CollectorContext, bound: BoundNetwork, gateway: Inet4Address, ifname: String?): ProbeSample =
        withContext(Dispatchers.IO) {
            val gw = gateway.hostAddress ?: gateway.toString().trimStart('/')
            val stamp = ctx.clock.now()
            var outcome = runPing(pingCommand(gw, ifname))
            // Some builds refuse SO_BINDTODEVICE (-I) to apps. Unbound, the kernel routes via the app's
            // default network — only meaningful when this network is the default.
            if (!outcome.first.replied && ifname != null && pingBindFailed(outcome.second) && networks.current()?.network == bound.network) {
                outcome = runPing(pingCommand(gw, null))
            }
            val result = outcome.first
            ProbeSample(
                runId = ctx.runId,
                stamp = stamp,
                probeType = "gateway",
                endpoint = gw,
                success = result.replied,
                error = result.error,
                totalMs = result.rttMs,
                iface = bound.iface,
                isCellular = bound.isCellular,
            )
        }

    /** Runs ping with a 2 s deadline → (outcome, raw output). Blocking; call on Dispatchers.IO. */
    private fun runPing(command: List<String>): Pair<PingOutcome, String> {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return PingOutcome(null, "io") to ""
        } catch (e: SecurityException) {
            return PingOutcome(null, "io") to ""
        }
        return try {
            val finished = process.waitFor(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                PingOutcome(null, "timeout") to ""
            } else {
                // The process has exited; its few hundred bytes of output are already in the pipe.
                val output = process.inputStream.bufferedReader().use { it.readText() }
                pingOutcome(process.exitValue(), output) to output
            }
        } catch (e: IOException) {
            PingOutcome(null, "io") to ""
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            PingOutcome(null, "timeout") to ""
        } finally {
            try { process.inputStream.close() } catch (e: IOException) { }
            try { process.outputStream.close() } catch (e: IOException) { }
            process.destroy()
        }
    }

    private suspend fun queryDns(ctx: CollectorContext, bound: BoundNetwork, server: InetAddress): ProbeSample =
        withContext(Dispatchers.IO) {
            val endpoint = (server.hostAddress ?: server.toString().trimStart('/')).substringBefore('%')
            val stamp = ctx.clock.now()
            val queryId = random.nextInt(0x10000)
            var rcode: Int? = null
            var rttMs: Double? = null
            var error: String? = null
            try {
                DatagramSocket().use { socket ->
                    bound.network.bindSocket(socket)
                    socket.soTimeout = DNS_TIMEOUT_MS
                    val query = buildDnsQuery(queryId)
                    val buf = ByteArray(512)
                    val t0 = System.nanoTime()
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val deadline = t0 + DNS_TIMEOUT_MS * 1_000_000L
                    while (rcode == null) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet) // SocketTimeoutException after soTimeout
                        val reply = parseDnsReply(buf, packet.length, queryId)
                        if (reply != null) {
                            rttMs = (System.nanoTime() - t0) / 1e6
                            rcode = reply.rcode
                        } else {
                            // Stray datagram (late answer to an older query): keep waiting within the deadline.
                            val left = (deadline - System.nanoTime()) / 1_000_000L
                            if (left <= 0) throw SocketTimeoutException("dns timeout")
                            socket.soTimeout = left.toInt().coerceAtLeast(1)
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                error = "timeout"
            } catch (e: IOException) {
                error = classifyError(e).let { if (it == "dns") "io" else it }
            } catch (e: SecurityException) {
                error = "io"
            }
            ProbeSample(
                runId = ctx.runId,
                stamp = stamp,
                probeType = "dns_server",
                endpoint = endpoint,
                success = rcode != null,
                error = if (rcode != null) null else error ?: "io",
                totalMs = rttMs,
                iface = bound.iface,
                isCellular = bound.isCellular,
                httpStatus = rcode,
            )
        }

    private companion object {
        const val MAX_DNS_SERVERS = 3
        const val DNS_TIMEOUT_MS = 2_000
        const val PING_TIMEOUT_MS = 2_000L
        const val MIN_GAP_MS = 250L
    }
}
