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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * [probe_public_ip] — public IP and ASN (ipinfo.io JSON) at start, every
 * settings.publicIpIntervalMs and after each default-network change (2 s settle).
 */
class PublicIpProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_public_ip"

    @OptIn(FlowPreview::class)
    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        val interval = settings.publicIpIntervalMs.coerceAtLeast(10_000L)
        val triggers = Channel<Unit>(Channel.CONFLATED)
        launch {
            networks.defaultNetworkHandles().drop(1).debounce(SETTLE_MS).collect { triggers.trySend(Unit) }
        }
        while (isActive) {
            ctx.signals.awaitResumed()
            val sample = runOnce(ctx)
            send(sample)
            ctx.status.sample(id, sample.stamp.tMonoNs)
            withTimeoutOrNull(interval) { triggers.receive() }
        }
    }

    private suspend fun runOnce(ctx: CollectorContext): ProbeSample {
        val settings = ctx.settings.effective()
        val url = settings.publicIpUrl
        val stamp = ctx.clock.now()
        val bound = networks.current()
            ?: return ProbeSample(ctx.runId, stamp, "public_ip", url, success = false, error = "no_network")
        val request = try {
            Request.Builder().url(url).get().header("Accept", "application/json").build()
        } catch (e: IllegalArgumentException) {
            return ProbeSample(ctx.runId, stamp, "public_ip", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular)
        }
        val client = networks.client(baseClient, bound.network, 10_000L).newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build() // not a latency KPI: allow slow first connects
        val r = HttpProbe.execute(client, request, BodyMode.Text())
        val info = if (r.success) r.body?.let(::parseIpInfo) else null
        val ok = r.success && info?.ip != null
        return ProbeSample(
            runId = ctx.runId,
            stamp = stamp,
            probeType = "public_ip",
            endpoint = url,
            success = ok,
            error = r.error ?: if (!ok) "io" else null,
            dnsMs = r.phases.dnsMs,
            connectMs = r.phases.connectMs,
            tlsMs = r.phases.tlsMs,
            ttfbMs = r.phases.ttfbMs,
            totalMs = r.phases.totalMs,
            iface = bound.iface,
            isCellular = bound.isCellular,
            publicIp = info?.ip,
            asn = info?.asn,
            asnOrg = info?.asnOrg,
        )
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
