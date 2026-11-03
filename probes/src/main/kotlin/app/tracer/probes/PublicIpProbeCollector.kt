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
 * settings.publicIpIntervalMs and after each default-network change (2 s settle), on every network
 * of [ProbeNetworks.networksFor] (settings.paths) — so DUAL also yields the mobile-data public IP.
 * success = reachable; expected_response = 2xx at the end of the chain.
 */
class PublicIpProbeCollector @Inject constructor(
    private val networks: ProbeNetworks,
    @ProbeHttpClient private val baseClient: OkHttpClient,
) : Collector {
    override val id = "probe_public_ip"

    @OptIn(FlowPreview::class)
    override fun samples(ctx: CollectorContext): Flow<Sample> = channelFlow {
        val settings = ctx.settings.effective()
        val paths = settings.paths
        val interval = settings.publicIpIntervalMs.coerceAtLeast(10_000L)
        val triggers = Channel<Unit>(Channel.CONFLATED)
        launch {
            networks.defaultNetworkHandles().drop(1).debounce(SETTLE_MS).collect { triggers.trySend(Unit) }
        }
        networks.acquireFor(paths)
        try {
            networks.awaitLease(paths)
            while (isActive) {
                ctx.signals.awaitResumed()
                val targets = networks.networksFor(paths)
                if (targets.isEmpty()) {
                    val sample = noNetworkSample(ctx.runId, ctx.clock.now(), "public_ip", settings.publicIpUrl, paths)
                    send(sample)
                    ctx.status.sample(id, sample.stamp.tMonoNs)
                }
                for (bound in targets) {
                    val sample = runOnce(ctx, bound)
                    send(sample)
                    ctx.status.sample(id, sample.stamp.tMonoNs)
                }
                withTimeoutOrNull(interval) { triggers.receive() }
            }
        } finally {
            networks.releaseFor(paths)
        }
    }

    private suspend fun runOnce(ctx: CollectorContext, bound: BoundNetwork): ProbeSample {
        val settings = ctx.settings.effective()
        val url = settings.publicIpUrl
        val stamp = ctx.clock.now()
        val request = try {
            Request.Builder().url(url).get().header("Accept", "application/json").build()
        } catch (e: IllegalArgumentException) {
            return ProbeSample(ctx.runId, stamp, "public_ip", url, success = false, error = "io", iface = bound.iface, isCellular = bound.isCellular, expectedResponse = false)
        }
        val client = networks.client(baseClient, bound.network, 10_000L).newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build() // not a latency KPI: allow slow first connects
        var r = HttpProbe.execute(client, request, BodyMode.Text())
        var info = if (is2xx(r.code)) r.body?.let(::parseIpInfo) else null
        var endpoint = url
        if (info?.ip == null) {
            // Enterprise networks often block IP-lookup services (ipinfo.io timed out 3/3 on ER-Mobile);
            // Cloudflare's trace endpoint still yields the public IP (ASN unknown).
            val fb = Request.Builder().url(FALLBACK_URL).get().build()
            val r2 = HttpProbe.execute(client, fb, BodyMode.Text())
            val info2 = if (is2xx(r2.code)) r2.body?.let(::parseCloudflareTrace) else null
            if (info2 != null) { r = r2; info = info2; endpoint = FALLBACK_URL }
        }
        return r.toProbeSample(ctx.runId, stamp, "public_ip", endpoint, bound, expected = is2xx(r.finalCode))
            .copy(publicIp = info?.ip, asn = info?.asn, asnOrg = info?.asnOrg)
    }

    private companion object {
        const val SETTLE_MS = 2_000L
        const val FALLBACK_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    }
}
