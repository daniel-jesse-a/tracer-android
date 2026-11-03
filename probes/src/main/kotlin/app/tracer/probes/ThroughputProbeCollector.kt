package app.tracer.probes

import app.tracer.core.model.ProbeSample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import app.tracer.probes.di.ProbeHttpClient
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * [probe_throughput] — downloads settings.throughputBytes from settings.throughputUrl every
 * settings.throughputIntervalMs, and additionally when the RAT or primary interface changes
 * (debounced 5 s, at most once per 20 s). mbps uses the whole call duration.
 */
class ThroughputProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_throughput"

    @OptIn(FlowPreview::class)
    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        val interval = settings.throughputIntervalMs.coerceAtLeast(MIN_SPACING_MS)
        val triggers = Channel<Unit>(Channel.CONFLATED)
        launch {
            merge(ctx.signals.rat.drop(1).map { }, ctx.signals.primaryInterface.drop(1).map { })
                .debounce(DEBOUNCE_MS)
                .collect { triggers.trySend(Unit) }
        }

        var lastRunMs = Long.MIN_VALUE / 2
        var nextDueMs = ctx.clock.now().tMonoMs // first run at start
        while (isActive) {
            val waitMs = nextDueMs - ctx.clock.now().tMonoMs
            val triggered = withTimeoutOrNull(waitMs.coerceAtLeast(0L)) { triggers.receive() } != null
            ctx.signals.awaitResumed()
            val now = ctx.clock.now().tMonoMs
            if (triggered && now - lastRunMs < MIN_SPACING_MS) {
                // Too soon after the previous test: defer, don't drop.
                nextDueMs = minOf(nextDueMs, lastRunMs + MIN_SPACING_MS)
                continue
            }
            lastRunMs = now
            nextDueMs = now + interval
            val sample = runOnce(ctx)
            send(sample)
            ctx.status.sample(id, sample.stamp.tMonoNs)
        }
    }

    private suspend fun runOnce(ctx: CollectorContext): ProbeSample {
        val settings = ctx.settings.effective()
        val url = throughputUrl(settings.throughputUrl, settings.throughputBytes)
        val stamp = ctx.clock.now()
        val bound = networks.current()
            ?: return ProbeSample(ctx.runId, stamp, "throughput", url, success = false, error = "no_network")
        val request = try {
            Request.Builder().url(url).get().header("Cache-Control", "no-cache").build()
        } catch (e: IllegalArgumentException) {
            return ProbeSample(ctx.runId, stamp, "throughput", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular)
        }
        // Connect phases use the probe timeout; the whole transfer may take up to TRANSFER_TIMEOUT_MS.
        val timeout = settings.thresholds.probeTimeoutMs
        val client = networks.client(baseClient, bound.network, TRANSFER_TIMEOUT_MS).newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout * 2, TimeUnit.MILLISECONDS)
            .build()
        val r = HttpProbe.execute(client, request, BodyMode.Discard(capBytes = settings.throughputBytes * 2))
        val bytes = r.bytes
        val rate = if (r.success && bytes != null && r.durationNs != null) mbps(bytes, r.durationNs) else null
        return ProbeSample(
            runId = ctx.runId,
            stamp = stamp,
            probeType = "throughput",
            endpoint = url,
            success = r.success && rate != null,
            error = r.error ?: if (rate == null) "io" else null,
            dnsMs = r.phases.dnsMs,
            connectMs = r.phases.connectMs,
            tlsMs = r.phases.tlsMs,
            ttfbMs = r.phases.ttfbMs,
            totalMs = r.phases.totalMs,
            bytes = bytes,
            mbps = rate,
            iface = bound.iface,
            isCellular = bound.isCellular,
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 5_000L
        const val MIN_SPACING_MS = 20_000L
        const val TRANSFER_TIMEOUT_MS = 30_000L
    }
}
