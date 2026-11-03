package app.tracer.probes

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
 * sequentially, each on a fresh connection bound to the current default network, with per-phase
 * timing. Interval adapts (see [nextLatencyIntervalMs]).
 */
class LatencyProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_latency"

    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        val base = settings.latencyIntervalMs.coerceAtLeast(500L)
        val recent = ArrayDeque<Double?>()
        var interval = base
        if (settings.dualPathProbing) networks.acquireCellular()
        try {
            while (isActive) {
                ctx.signals.awaitResumed()
                val default = networks.current()
                for (sample in runSet(ctx, settings, default)) {
                    send(sample)
                    ctx.status.sample(id, sample.stamp.tMonoNs)
                    recent.addLast(if (sample.success) sample.connectMs ?: sample.totalMs else null)
                    while (recent.size > 10) recent.removeFirst()
                }
                // Dual path: while Wi-Fi is the default, also measure the mobile-data path.
                if (settings.dualPathProbing && default?.isCellular != true) {
                    networks.cellularIfNotDefault()?.let { cell -> for (sample in runSet(ctx, settings, cell)) send(sample) }
                }
                interval = nextLatencyIntervalMs(base, interval, ctx.signals.degraded.value, settings.adaptiveLatency, recent.toList())
                delay(interval)
            }
        } finally {
            if (settings.dualPathProbing) networks.releaseCellular()
        }
    }

    private suspend fun runSet(ctx: CollectorContext, settings: RunSettings, bound: BoundNetwork?): List<ProbeSample> {
        val urls = settings.endpoints.map { it.url }
        if (bound == null) {
            return urls.map { url ->
                ProbeSample(runId = ctx.runId, stamp = ctx.clock.now(), probeType = "latency", endpoint = url, success = false, error = "no_network")
            }
        }
        // Fresh client per set: no pooled connections, bound to this set's network.
        val client = networks.client(baseClient, bound.network, settings.thresholds.probeTimeoutMs)
        return urls.map { url ->
            val stamp = ctx.clock.now()
            val request = try {
                Request.Builder().url(url).head().build()
            } catch (e: IllegalArgumentException) {
                null
            }
            if (request == null) {
                ProbeSample(ctx.runId, stamp, "latency", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular)
            } else {
                // Any HTTP response proves the path works (redirects/4xx included); only 5xx counts as failure.
                val r = HttpProbe.execute(client, request, isSuccess = { it < 500 })
                ProbeSample(
                    runId = ctx.runId,
                    stamp = stamp,
                    probeType = "latency",
                    endpoint = url,
                    success = r.success,
                    error = r.error,
                    dnsMs = r.phases.dnsMs,
                    connectMs = r.phases.connectMs,
                    tlsMs = r.phases.tlsMs,
                    ttfbMs = r.phases.ttfbMs,
                    totalMs = r.phases.totalMs,
                    iface = bound.iface,
                    isCellular = bound.isCellular,
                )
            }
        }
    }
}
