package app.tracer.probes

import kotlinx.coroutines.async
import android.os.SystemClock
import app.tracer.core.config.RunSettings
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
                val secondary = targets.drop(1).map { b -> async { runSet(ctx, settings, b) } }
                val primary = targets.firstOrNull()?.let { runSet(ctx, settings, it) }
                    ?: settings.endpoints.map { noNetworkSample(ctx.runId, ctx.clock.now(), "latency", it.url, paths) }
                for (sample in primary) {
                    send(sample)
                    ctx.status.sample(id, sample.stamp.tMonoNs)
                    recent.addLast(if (sample.success) sample.connectMs ?: sample.totalMs else null)
                    while (recent.size > 10) recent.removeFirst()
                }
                secondary.forEach { job -> job.await().forEach { send(it) } }
                interval = nextLatencyIntervalMs(base, interval, ctx.signals.degraded.value, settings.adaptiveLatency, recent.toList())
                // start-to-start cadence: subtract the time the sets themselves took
                delay((interval - (SystemClock.elapsedRealtime() - startedMs)).coerceAtLeast(MIN_GAP_MS))
            }
        } finally {
            networks.releaseFor(paths)
        }
    }

    private suspend fun runSet(ctx: CollectorContext, settings: RunSettings, bound: BoundNetwork): List<ProbeSample> {
        // Fresh client per set: no pooled connections, bound to this set's network.
        val client = networks.client(baseClient, bound.network, settings.thresholds.probeTimeoutMs)
        return settings.endpoints.map { endpoint ->
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
                r.toProbeSample(ctx.runId, stamp, "latency", url, bound, expected = endpoint.isExpected(r.finalCode))
            }
        }
    }

    private companion object {
        /** Never probe back-to-back: at least this much idle between sets even when a set overran. */
        const val MIN_GAP_MS = 250L
    }
}
