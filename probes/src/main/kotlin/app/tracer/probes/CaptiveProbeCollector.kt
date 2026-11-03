package app.tracer.probes

import app.tracer.core.model.ProbeSample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import app.tracer.probes.di.ProbeHttpClient
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onStart
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * [probe_captive] — GET settings.captiveUrl at start and after each default-network change, on every
 * network of [ProbeNetworks.networksFor] (settings.paths). success = reachable (any HTTP response);
 * expected_response = final status 204. A portal typically answers 302 → login page: http_status
 * 302, redirect_count ≥ 1, final_url = the portal, expected_response = false.
 */
class CaptiveProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_captive"

    @OptIn(FlowPreview::class)
    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val paths = ctx.settings.effective().paths
        networks.acquireFor(paths)
        try {
            networks.awaitLease(paths)
            networks.defaultNetworkHandles()
                .drop(1)
                .debounce(SETTLE_MS)
                .onStart { emit(-2L) } // run once at start
                .collect {
                    ctx.signals.awaitResumed()
                    val targets = networks.networksFor(paths)
                    val samples = if (targets.isEmpty()) {
                        listOf(noNetworkSample(ctx.runId, ctx.clock.now(), "captive", ctx.settings.effective().captiveUrl, paths))
                    } else {
                        targets.map { runOnce(ctx, it) }
                    }
                    for (sample in samples) {
                        send(sample)
                        ctx.status.sample(id, sample.stamp.tMonoNs)
                    }
                }
        } finally {
            networks.releaseFor(paths)
        }
    }

    private suspend fun runOnce(ctx: CollectorContext, bound: BoundNetwork): ProbeSample {
        val settings = ctx.settings.effective()
        val url = settings.captiveUrl
        val stamp = ctx.clock.now()
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: IllegalArgumentException) {
            return ProbeSample(ctx.runId, stamp, "captive", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular, expectedResponse = false)
        }
        val client = networks.client(baseClient, bound.network, 10_000L).newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build() // not a latency KPI: allow slow first connects
        val r = HttpProbe.execute(client, request)
        return r.toProbeSample(ctx.runId, stamp, "captive", url, bound, expected = r.finalCode == 204)
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
