package app.tracer.probes

import app.tracer.core.config.ProbePaths
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
 * (debounced 5 s, at most once per 20 s). mbps uses the body transfer time.
 *
 * Paths (settings.paths): the primary network of [ProbeNetworks.networksFor] every cycle; in DUAL
 * mode the parallel mobile-data path only every [PARALLEL_CELLULAR_EVERY]th cycle. Bytes downloaded
 * over cellular are counted per collection; once settings.cellularDataBudgetMb is reached, cellular
 * tests are skipped for the rest of the run (latency/internal probes continue).
 *
 * Time box: the body is read until settings.throughputBytes or settings.throughputMaxMs after the
 * first byte, whichever comes first (hard Okio deadline, see [HttpProbe]); bytes = bytes actually
 * received, mbps = bytes / real first→last byte time. On cellular the request asks for no more
 * than the budget left, so a test never overshoots the budget by more than in-flight data.
 *
 * success = reachable (HTTP response received); expected_response = 2xx at the end of the chain;
 * mbps only for a 2xx first hop. WIFI_ONLY with Wi-Fi off writes nothing.
 */
class ThroughputProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_throughput"

    @OptIn(FlowPreview::class)
    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        val paths = settings.paths
        val interval = settings.throughputIntervalMs.coerceAtLeast(MIN_SPACING_MS)
        val budget = budgetBytes(settings.cellularDataBudgetMb)
        var cellularBytes = 0L
        var budgetReported = false
        val triggers = Channel<Unit>(Channel.CONFLATED)
        launch {
            merge(ctx.signals.rat.drop(1).map { }, ctx.signals.primaryInterface.drop(1).map { })
                .debounce(DEBOUNCE_MS)
                .collect { triggers.trySend(Unit) }
        }

        /** false (and reports degraded once) when the mobile-data budget is used up. */
        fun cellularAllowed(): Boolean {
            if (budget - cellularBytes >= MIN_TEST_BYTES) return true
            if (!budgetReported) {
                budgetReported = true
                ctx.status.degraded(id, "Mobile-data budget of ${settings.cellularDataBudgetMb} MB reached. Cellular throughput tests paused, latency continues.")
            }
            return false
        }

        suspend fun test(bound: BoundNetwork?): ProbeSample? {
            if (bound == null && !paths.emitsNoNetworkRows()) return null
            if (bound?.isCellular == true && !cellularAllowed()) return null
            val cap = if (bound?.isCellular == true) minOf(settings.throughputBytes, budget - cellularBytes) else settings.throughputBytes
            val sample = runOnce(ctx, bound, paths, cap)
            if (bound?.isCellular == true) cellularBytes += sample.bytes ?: 0L
            if (bound != null && !networks.keepRow(bound, sample.success)) return null
            return sample
        }

        networks.acquireFor(paths)
        try {
            networks.awaitLease(paths)
            var lastRunMs = Long.MIN_VALUE / 2
            var cycle = 0
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
                val targets = networks.networksFor(paths)
                test(targets.firstOrNull())?.let { sample ->
                    send(sample)
                    ctx.status.sample(id, sample.stamp.tMonoNs)
                }
                cycle++
                if (cycle % PARALLEL_CELLULAR_EVERY == 0) {
                    targets.drop(1).forEach { b -> test(b)?.let { send(it) } }
                }
            }
        } finally {
            networks.releaseFor(paths)
        }
    }

    private suspend fun runOnce(ctx: CollectorContext, network: BoundNetwork?, paths: ProbePaths, capBytes: Long): ProbeSample {
        val settings = ctx.settings.effective()
        val url = throughputUrl(settings.throughputUrl, capBytes)
        val stamp = ctx.clock.now()
        val bound = network
            ?: return noNetworkSample(ctx.runId, stamp, "throughput", url, paths)
        val request = try {
            Request.Builder().url(url).get().header("Cache-Control", "no-cache").build()
        } catch (e: IllegalArgumentException) {
            return ProbeSample(ctx.runId, stamp, "throughput", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular, expectedResponse = false)
        }
        // Connect phases use the probe timeout; the whole call is bounded by setup + the time box.
        val timeout = settings.thresholds.probeTimeoutMs
        val client = networks.client(baseClient, bound.network, throughputCallTimeoutMs(timeout, settings.throughputMaxMs, TRANSFER_TIMEOUT_MS)).newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout * 2, TimeUnit.MILLISECONDS)
            .build()
        val r = HttpProbe.execute(client, request, BodyMode.Discard(capBytes = capBytes, maxTransferMs = settings.throughputMaxMs))
        val bytes = r.bytes
        // Rate over the body transfer only (excludes DNS/TCP/TLS/TTFB), so short tests aren't dominated by setup.
        // Only a 2xx body is the test payload (a redirect/error page is not a throughput measurement).
        val transfer = r.transferNs ?: r.durationNs
        val rate = if (is2xx(r.code) && bytes != null && bytes > 0 && transfer != null) mbps(bytes, transfer) else null
        return r.toProbeSample(ctx.runId, stamp, "throughput", url, bound, expected = is2xx(r.finalCode))
            .copy(bytes = bytes, mbps = rate)
    }

    private companion object {
        const val DEBOUNCE_MS = 5_000L
        const val MIN_SPACING_MS = 20_000L
        const val TRANSFER_TIMEOUT_MS = 30_000L
        /** Parallel mobile-data throughput (DUAL, while on Wi-Fi) only every Nth cycle, to bound data use. */
        const val PARALLEL_CELLULAR_EVERY = 3
        /** Below this much budget left a cellular test is not worth running (budget counts as reached). */
        const val MIN_TEST_BYTES = 1_000_000L
    }
}
