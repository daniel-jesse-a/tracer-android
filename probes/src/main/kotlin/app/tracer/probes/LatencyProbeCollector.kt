package app.tracer.probes

import kotlinx.coroutines.async
import android.os.SystemClock
import app.tracer.core.config.RunSettings
import app.tracer.core.model.IntegritySample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import app.tracer.probes.di.ProbeHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * [probe_latency] — one "probe set" per interval: an HTTPS HEAD to every configured endpoint,
 * sequentially, each on a fresh connection, with per-phase timing. One set per network from
 * [ProbeNetworks.networksFor] (settings.paths), run concurrently; the first network is the primary
 * path whose results drive the adaptive interval (see [nextLatencyIntervalMs]).
 *
 * success = reachable (any HTTP response, incl. 3xx/4xx/5xx); a 3xx is followed untimed to fill
 * redirect_count / final_url; expected_response = ProbeEndpoint.isExpected(final status).
 * Each row carries the server identity (remote IP / version, edge PoP + headers, TLS leaf SHA-256).
 *
 * integrity kind "tls_cert": the leaf fingerprint of each endpoint host per path, emitted when first
 * seen in the run and whenever a fingerprint not seen before for that host/path appears (ok = false).
 *
 * WIFI_ONLY with Wi-Fi off writes nothing; failures on a Wi-Fi path whose network vanished during the
 * set are dropped (see [ProbeNetworks.keepRow]).
 */
class LatencyProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_latency"

    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        val paths = settings.paths
        val base = settings.latencyIntervalMs.coerceAtLeast(500L)
        val recent = ArrayDeque<Double?>()
        val certs = CertTracker()
        var interval = base
        networks.acquireFor(paths)
        try {
            networks.awaitLease(paths)
            while (isActive) {
                ctx.signals.awaitResumed()
                val startedMs = SystemClock.elapsedRealtime()
                val targets = networks.networksFor(paths)
                // Secondary paths (e.g. mobile data while on Wi-Fi) run concurrently with the primary:
                // sequential sets stretched a 2 s cadence to ~5 s in "Office test 3".
                val secondary = targets.drop(1).map { b -> async { runSet(ctx, settings, b, certs) } }
                val primarySet = targets.firstOrNull()?.let { runSet(ctx, settings, it, certs) }
                val primary = primarySet?.probes
                    ?: settings.endpoints.flatMap { noNetworkSamples(ctx.runId, ctx.clock.now(), "latency", it.url, paths) }
                for (sample in primary) {
                    send(sample)
                    ctx.status.sample(id, sample.stamp.tMonoNs)
                    recent.addLast(if (sample.success) sample.connectMs ?: sample.totalMs else null)
                    while (recent.size > 10) recent.removeFirst()
                }
                primarySet?.integrity?.forEach { send(it) }
                secondary.forEach { job -> job.await().let { set -> set.probes.forEach { send(it) }; set.integrity.forEach { send(it) } } }
                interval = nextLatencyIntervalMs(base, interval, ctx.signals.degraded.value, settings.adaptiveLatency, recent.toList())
                // start-to-start cadence: subtract the time the sets themselves took
                delay((interval - (SystemClock.elapsedRealtime() - startedMs)).coerceAtLeast(MIN_GAP_MS))
            }
        } finally {
            networks.releaseFor(paths)
        }
    }

    private class SetResult(val probes: List<ProbeSample>, val integrity: List<IntegritySample>)

    private suspend fun runSet(ctx: CollectorContext, settings: RunSettings, bound: BoundNetwork, certs: CertTracker): SetResult {
        // Fresh client per set: no pooled connections, bound to this set's network.
        val client = networks.client(baseClient, bound.network, settings.thresholds.probeTimeoutMs)
        val integrity = ArrayList<IntegritySample>()
        val probes = settings.endpoints.map { endpoint ->
            val url = endpoint.url
            val stamp = ctx.clock.now()
            val request = try {
                Request.Builder().url(url).head().build()
            } catch (e: IllegalArgumentException) {
                null
            }
            if (request == null) {
                ProbeSample(ctx.runId, stamp, "latency", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular, expectedResponse = false)
            } else {
                val r = HttpProbe.execute(client, request)
                tlsCertSample(ctx, stamp, bound, request.url.host, r.identity, certs)?.let { integrity += it }
                r.toProbeSample(ctx.runId, stamp, "latency", url, bound, expected = endpoint.isExpected(r.finalCode))
            }
        }
        return SetResult(probes.filter { networks.keepRow(bound, it.success) }, integrity)
    }

    /** integrity tls_cert row when [id]'s leaf is new for (path, host) in this run, else null. */
    private fun tlsCertSample(ctx: CollectorContext, stamp: app.tracer.core.model.Stamp, bound: BoundNetwork, host: String, id: ServerIdentity, certs: CertTracker): IntegritySample? {
        val sha = id.tlsCertSha256 ?: return null
        val change = certs.observe(bound.iface, host, sha) ?: return null
        val previous = if (change == CertTracker.Change.CHANGED) certs.previous(bound.iface, host, sha) else null
        return IntegritySample(
            runId = ctx.runId,
            stamp = stamp,
            kind = "tls_cert",
            path = bound.iface,
            target = host,
            ok = change == CertTracker.Change.FIRST,
            value = tlsCertValue(sha, id.tlsIssuerCn),
            detailJson = tlsCertDetailJson(sha, id.tlsIssuerCn, id.tlsSubjectCn, id.tlsNotAfterUtcMs, previous, id.remoteIp, id.edgePop),
        )
    }

    private companion object {
        /** Never probe back-to-back: at least this much idle between sets even when a set overran. */
        const val MIN_GAP_MS = 250L
    }
}
