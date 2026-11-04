package app.tracer.ui.signals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.model.ProbeSample
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.ui.kit.ChartLegend
import app.tracer.ui.kit.ChartLine
import app.tracer.ui.kit.Dot
import app.tracer.ui.kit.PresetChart
import app.tracer.ui.kit.Presets
import app.tracer.ui.kit.fg
import app.tracer.ui.kit.liveXLabels
import app.tracer.ui.kit.tc
import app.tracer.ui.kit.toPts
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.TColors
import app.tracer.ui.theme.TType
import kotlin.math.roundToInt

/** Jitter ms: lower is better (≤ 20 good, ≤ 50 so-so). */
private fun jitterColor(c: TColors, ms: Double?): Color = when {
    ms == null -> c.text3
    ms <= 20 -> c.veryGood
    ms <= 50 -> c.average
    else -> c.belowAvg
}

/** Loss %: lower is better (0 good, < 2 so-so). */
private fun lossColor(c: TColors, pct: Double?): Color = when {
    pct == null -> c.text3
    pct <= 0.0 -> c.veryGood
    pct < 2.0 -> c.average
    else -> c.bad
}

/** Signals › Response: live round trip per path, median / jitter / loss, endpoints, probe breakdown, speed tests. */
@Composable
fun ResponseDetailScreen(onBack: () -> Unit) {
    val vm: SignalDetailViewModel = hiltViewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    var win by rememberSaveable { mutableIntStateOf(1) }
    val now = liveNowMs(snap)
    val windowS = WINDOWS_S[win]
    val rtt = snap.latencyMs
    val v = Verdict.response(rtt)
    val onPath = when (snap.transport) { "wifi", "ethernet" -> "Wi-Fi"; "cellular" -> "mobile data"; else -> null }

    DetailScreen("Response", onBack) {
        Hero(
            eyebrow = "Response time" + (onPath?.let { " · on $it" } ?: ""),
            value = rtt?.roundToInt()?.toString() ?: "–", unit = if (rtt != null) "ms" else null, v = v,
            sub = listOfNotNull(
                "Lower is better. Good below 150 ms",
                snap.cellularLatencyMs?.takeIf { snap.transport != "cellular" }?.let { "mobile path ${it.roundToInt()} ms" },
            ).joinToString(" · "),
            verdictText = if (rtt == null) "No data" else v.label,
        )

        val w = snap.history.latencyWifi.toPts(now)
        val m = snap.history.latencyMobile.toPts(now)
        ChartCard("Response time", trailing = rtt?.let { "${it.roundToInt()} ms" }) {
            WindowPicker(win) { win = it }
            Spacer(Modifier.height(12.dp))
            val maxSeen = (w + m).filter { it.tS >= -windowS }.maxOfOrNull { it.v }
            PresetChart(
                Presets.response(tc, maxSeen),
                listOf(ChartLine(w, tc.wifi, maxGapS = 30.0), ChartLine(m, tc.mobile, maxGapS = 30.0)),
                xMin = -windowS, xMax = 0.0, height = 170.dp, currentDot = true, xLabels = liveXLabels(windowS),
                emptyReason = "No response checks yet",
            )
            Spacer(Modifier.height(8.dp))
            ChartLegend(listOf(Triple("Wi-Fi", tc.wifi, false), Triple("Mobile", tc.mobile, false)))
        }

        StatChips(listOf(
            Triple("Median", snap.medianLatencyMs?.let { "${it.roundToInt()} ms" } ?: "–", tc.fg(Verdict.response(snap.medianLatencyMs)).takeIf { snap.medianLatencyMs != null } ?: tc.text3),
            Triple("Jitter", snap.jitterMs?.let { "${it.roundToInt()} ms" } ?: "–", jitterColor(tc, snap.jitterMs)),
            Triple("Loss", snap.lossPct?.let { "${it.roundToInt()}%" } ?: "–", lossColor(tc, snap.lossPct)),
        ))
        Text("All three: lower is better. Last 30 checks on the network in use.", style = TType.footnote.copy(fontSize = 13.sp), color = tc.text3,
            modifier = Modifier.padding(horizontal = 4.dp))

        EndpointsCard(snap, now)
        SpeedCard(snap, now)

        ExpandSection("Technical", sub = "Last check breakdown and server") {
            val latest = snap.lastProbes.values.filter { it.probeType == "latency" }.maxByOrNull { it.stamp.tMonoNs }
            if (latest == null) {
                Text("No checks yet", style = TType.sub, color = tc.text3)
            } else {
                SubHead("Last check · ${pathOf(latest)} · ${ago(now, latest.stamp.tMonoMs)}")
                ProbeBreakdown(latest)
                Spacer(Modifier.height(8.dp))
                ServerIdentity(latest)
            }
            KV("Public IP", snap.publicIp)
            KV("Network (ASN)", snap.asnOrg)
        }
    }
}

private fun pathOf(p: ProbeSample): String = if (p.isCellular == true || p.iface == "cellular") "Mobile" else "Wi-Fi"

private fun hostOf(endpoint: String): String = endpoint.removePrefix("https://").removePrefix("http://").substringBefore('/')

@Composable
private fun EndpointsCard(snap: LiveSnapshot, now: Long) {
    val rows = snap.lastProbes.values.filter { it.probeType == "latency" }
        .sortedWith(compareBy<ProbeSample> { pathOf(it) }.thenBy { hostOf(it.endpoint) })
    ChartCard("Servers checked", trailing = if (rows.isEmpty()) null else "${rows.size}") {
        if (rows.isEmpty()) {
            Text("No checks yet", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        rows.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
            val rt = (p.connectMs?.takeIf { it > 0 } ?: p.totalMs).takeIf { p.success }
            val pv = Verdict.response(rt)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (pathOf(p) == "Wi-Fi") tc.wifi else tc.mobile, 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(hostOf(p.endpoint), style = TType.bodyRegular.copy(fontSize = 16.sp), color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (p.success) (p.httpStatus?.let { "HTTP $it" } ?: "OK") else (p.error ?: "failed"),
                            p.totalMs?.let { "total ${it.roundToInt()} ms" },
                            p.edgePop,
                            p.ipVersion?.let { "IPv$it" },
                            ago(now, p.stamp.tMonoMs),
                        ).joinToString(" · "),
                        style = Mono.tiny.copy(fontSize = 11.sp), color = if (p.success) tc.text3 else tc.bad, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(rt?.let { "${it.roundToInt()} ms" } ?: "–", style = TType.callout.merge(TType.num), color = if (rt != null) tc.fg(pv) else tc.bad)
            }
        }
    }
}

@Composable
private fun SpeedCard(snap: LiveSnapshot, now: Long) {
    val w = snap.history.mbpsWifi
    val m = snap.history.mbpsMobile
    val lastW = w.lastOrNull(); val lastM = m.lastOrNull()
    ChartCard("Speed tests", trailing = "Mbps") {
        val parts = listOfNotNull(
            lastW?.let { "Wi-Fi ${it.v.roundToInt()} Mbps · ${ago(now, it.tMonoMs)}" },
            lastM?.let { "Mobile ${it.v.roundToInt()} Mbps · ${ago(now, it.tMonoMs)}" },
        )
        if (parts.isEmpty()) {
            Text("No speed test yet. They run every few minutes when enabled in Settings.", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        parts.forEach { Text(it, style = TType.bodyRegular.copy(fontSize = 16.sp).merge(TType.num), color = tc.text) }
        Spacer(Modifier.height(10.dp))
        val wp = w.toPts(now); val mp = m.toPts(now)
        val oldest = (wp + mp).minOfOrNull { it.tS } ?: -600.0
        val span = (-oldest).coerceAtLeast(120.0)
        PresetChart(
            Presets.mbps(tc, (wp + mp).maxOfOrNull { it.v }),
            listOf(ChartLine(wp, tc.wifi, maxGapS = 1e9), ChartLine(mp, tc.mobile, maxGapS = 1e9)),
            xMin = -span * 1.05, xMax = 0.0, height = 120.dp, currentDot = true, xLabels = liveXLabels(span * 1.05),
        )
    }
}
