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
 * [probe_captive] — GET settings.captiveUrl without following redirects, at start and after each
 * default-network change. 204 = success; anything else is error "http_<code>" (typically a
 * captive portal redirect).
 */
class CaptiveProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_captive"

    @OptIn(FlowPreview::class)
    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        networks.defaultNetworkHandles()
            .drop(1)
            .debounce(SETTLE_MS)
            .onStart { emit(-2L) } // run once at start
            .collect {
                ctx.signals.awaitResumed()
                val sample = runOnce(ctx)
                send(sample)
                ctx.status.sample(id, sample.stamp.tMonoNs)
            }
    }

    private suspend fun runOnce(ctx: CollectorContext): ProbeSample {
        val settings = ctx.settings.effective()
        val url = settings.captiveUrl
        val stamp = ctx.clock.now()
        val bound = networks.current()
            ?: return ProbeSample(ctx.runId, stamp, "captive", url, success = false, error = "no_network")
        val request = try {
            Request.Builder().url(url).get().build()
        } catch (e: IllegalArgumentException) {
            return ProbeSample(ctx.runId, stamp, "captive", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular)
        }
        val client = networks.client(baseClient, bound.network, 10_000L).newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build() // not a latency KPI: allow slow first connects
        val r = HttpProbe.execute(client, request, isSuccess = { it == 204 })
        return ProbeSample(
            runId = ctx.runId,
            stamp = stamp,
            probeType = "captive",
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

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
