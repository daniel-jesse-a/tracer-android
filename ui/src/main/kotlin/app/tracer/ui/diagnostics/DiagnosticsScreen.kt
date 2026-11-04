package app.tracer.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tracer.core.report.Carrier
import app.tracer.core.report.Moment
import app.tracer.core.report.Pt
import app.tracer.core.report.TestReport
import app.tracer.core.report.Verdict
import app.tracer.core.report.fmtClock
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.kit.*
import app.tracer.ui.report.ReportRepository
import app.tracer.ui.settings.SettingsRepository
import app.tracer.ui.theme.TType
import app.tracer.ui.summary.CardTitle
import app.tracer.ui.summary.ReportGate
import app.tracer.ui.summary.ReportViewModel
import app.tracer.ui.summary.TickLabels
import app.tracer.ui.summary.eyebrowOf
import app.tracer.ui.summary.evenTicks
import app.tracer.ui.summary.momentColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    reports: ReportRepository,
    runs: RunRepository,
    settings: SettingsRepository,
) : ReportViewModel(reports, runs) {
    val engineerView: StateFlow<Boolean> = settings.engineerView.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}

/** Design v2 frame 5 — Diagnostics (Simple | Detailed). */
@Composable
fun DiagnosticsScreen(
    runId: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onMoment: (Int) -> Unit,
    onReplay: (Long?) -> Unit,
    onWorst: () -> Unit,
) {
    val vm: DiagnosticsViewModel = hiltViewModel()
    LaunchedEffect(runId) { vm.bind(runId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val engineer by vm.engineerView.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    val selected = if (tab < 0) (if (engineer) 1 else 0) else tab
    Screen {
        TopLinks(back = "Summary", onBack = onBack, action = "Share", onAction = onShare)
        ReportGate(state) { _, r ->
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                TitleBlock(eyebrow = eyebrowOf(r), title = "Diagnostics")
                Segmented(listOf("Simple", "Detailed"), selected, { tab = it })
                Spacer(Modifier.height(16.dp))
                VerdictRows(r)
                Spacer(Modifier.height(16.dp))
                NetworkCard(r, onMoment)
                Spacer(Modifier.height(16.dp))
                WifiStrengthCard(r)
                Spacer(Modifier.height(16.dp))
                ResponseCard(r)
                Spacer(Modifier.height(16.dp))
                SpeedTestsCard(r)
                Spacer(Modifier.height(16.dp))
                MovementCard(r)
                if (selected == 1) Detailed(r, onMoment, onReplay, onWorst)
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

// ------------------------------------------------------------------------------ simple rows

private data class Row7(val icon: ImageVector, val name: String, val value: String, val v: Verdict)

private fun rowsOf(r: TestReport): List<Row7> {
    val dl = r.wifiSpeedMedian ?: r.wifiSpeedMax
    val dlM = r.mobileSpeedMedian ?: r.mobileSpeedMax
    val (dlValue, dlVerdict) = when {
        dl != null -> dl to r.wifiSpeedVerdict
        dlM != null -> dlM to r.mobileSpeedVerdict
        else -> null to Verdict.NONE
    }
    return listOf(
        Row7(Icons.Outlined.CheckCircle, "Online", r.onlinePct?.let { "${it.roundToInt()}%" } ?: "–", r.onlineVerdict),
        Row7(Icons.Outlined.Wifi, "Wi-Fi signal", if (r.wifiTested) r.wifiRssiMedian?.dbm() ?: "–" else "Not tested", if (r.wifiTested) r.wifiVerdict else Verdict.NONE),
        Row7(Icons.Outlined.SignalCellularAlt, "Mobile signal", if (r.mobileTested) r.rsrpMedian?.dbm() ?: "–" else "Not tested", if (r.mobileTested) r.mobileVerdict else Verdict.NONE),
        Row7(Icons.Outlined.Download, "Download", dlValue?.let { "${it.roundToInt()}" } ?: "–", dlVerdict),
        Row7(Icons.Outlined.Schedule, "Response", r.responseMs?.let { "≤${it.roundToInt()}" } ?: "–", r.responseVerdict),
        Row7(Icons.Outlined.LocationOn, "Position", r.positionAccM?.let { "±${it.roundToInt()} m" } ?: "–", r.positionVerdict),
        Row7(Icons.Outlined.Description, "Test quality", r.checksPassedPct?.let { "${it.roundToInt()}%" } ?: "–", r.qualityVerdict),
    )
}

@Composable
private fun VerdictRows(r: TestReport) {
    GroupCard(rows = rowsOf(r).map<Row7, @Composable () -> Unit> { row -> { VerdictRow(row) } })
}

@Composable
private fun VerdictRow(row: Row7) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconWell(row.icon, tint = tc.fg(row.v), bg = tc.bgOf(row.v), size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = TType.bodyRegular.copy(fontSize = 18.sp), color = tc.text, maxLines = 1)
            Text(row.v.label, style = TType.label.copy(fontWeight = FontWeight.Bold), color = tc.fg(row.v))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.value, style = TType.headline.copy(fontSize = 20.sp).merge(TType.num), color = tc.text, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            MiniMeter(row.v)
        }
    }
}

// ------------------------------------------------------------------------------ network

@Composable
private fun CardHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CardTitle, color = tc.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Nearest moment to [tS] within 8 % of the test, if any. */
private fun nearestMoment(moments: List<Moment>, tS: Double, durationS: Double): Int? {
    val i = moments.indices.minByOrNull { abs(moments[it].tS - tS) } ?: return null
    return i.takeIf { abs(moments[i].tS - tS) <= durationS * 0.08 }
}

@Composable
private fun NetworkCard(r: TestReport, onMoment: (Int) -> Unit) {
    Card {
        CardHeader("Network") { CarrierLegend() }
        Spacer(Modifier.height(14.dp))
        if (r.segments.isEmpty() || r.durationS <= 0) {
            Text("No network data", style = TType.sub, color = tc.text3)
        } else {
            Box(Modifier.fillMaxWidth().pointerInput(r.runId, r.moments.size) {
                detectTapGestures { pos ->
                    val t = pos.x / size.width * r.durationS
                    nearestMoment(r.moments, t, r.durationS)?.let(onMoment)
                }
            }) { NetworkBar(r.segments, r.durationS, ticks = evenTicks(r.durationS)) }
        }
    }
}

// ------------------------------------------------------------------------------ charts

private const val RSSI_TOP = -45.0
private const val RSSI_BOTTOM = -90.0

private fun DrawScope.series(pts: List<Pt>, x: (Double) -> Float, y: (Double) -> Float, color: Color, width: Float, maxGapS: Double, effect: PathEffect? = null) {
    if (pts.isEmpty()) return
    val path = Path()
    var prev: Pt? = null
    for (p in pts) {
        val pv = prev
        if (pv == null || p.tS - pv.tS > maxGapS) path.moveTo(x(p.tS), y(p.v)) else path.lineTo(x(p.tS), y(p.v))
        prev = p
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun valueAt(pts: List<Pt>, t: Double): Double? {
    if (pts.isEmpty()) return null
    val after = pts.indexOfFirst { it.tS >= t }
    return when {
        after < 0 -> pts.last().v
        after == 0 -> pts.first().v
        else -> {
            val a = pts[after - 1]; val b = pts[after]
            val f = if (b.tS > a.tS) (t - a.tS) / (b.tS - a.tS) else 0.0
            a.v + (b.v - a.v) * f
        }
    }
}

@Composable
private fun WifiStrengthCard(r: TestReport) {
    val c = tc
    Card {
        CardHeader("Wi-Fi strength") { Hint("○ = new access point") }
        Spacer(Modifier.height(14.dp))
        if (r.wifiRssi.isEmpty() || r.durationS <= 0) {
            Text("No Wi-Fi readings", style = TType.sub, color = tc.text3)
            return@Card
        }
        val h = 170.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val third = size.height / 3
                drawRect(c.veryGoodBg, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, third))
                drawRect(c.averageBg, Offset(0f, third), androidx.compose.ui.geometry.Size(size.width, third))
                drawRect(c.badBg, Offset(0f, 2 * third), androidx.compose.ui.geometry.Size(size.width, size.height - 2 * third))
                val d = r.durationS
                val x = { t: Double -> (t / d * size.width).toFloat() }
                val y = { v: Double -> ((RSSI_TOP - v.coerceIn(RSSI_BOTTOM, RSSI_TOP)) / (RSSI_TOP - RSSI_BOTTOM) * size.height).toFloat() }
                series(r.wifiRssi, x, y, c.wifi, 2.5.dp.toPx(), maxGapS = maxOf(15.0, d / 30))
                val rad = 6.dp.toPx()
                for (t in r.newApAtS) {
                    val v = valueAt(r.wifiRssi, t) ?: continue
                    val o = Offset(x(t), y(v))
                    drawCircle(c.bg, rad, o)
                    drawCircle(c.text, rad, o, style = Stroke(2.dp.toPx()))
                }
            }
            BandLabel("Strong", c.veryGood, 0f, h)
            BandLabel("OK", c.average, 1f / 3, h)
            BandLabel("Weak", c.bad, 2f / 3, h)
        }
        Spacer(Modifier.height(8.dp))
        TickLabels(evenTicks(r.durationS), r.durationS)
    }
}

@Composable
private fun BandLabel(text: String, color: Color, frac: Float, h: Dp) {
    Text(text, style = TType.footnote.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = color,
        modifier = Modifier.offset(x = 10.dp, y = h * frac + 6.dp))
}

@Composable
private fun ResponseCard(r: TestReport) {
    val c = tc
    val all = r.responseMobile + r.responseWifi
    val mLo = r.responseMobileMinMs
    val mHi = r.responseMobileMaxMs
    Card {
        val caption = when {
            mLo != null && mHi != null -> "mobile · ${mLo.roundToInt()}–${mHi.roundToInt()} ms"
            r.responseWifi.isNotEmpty() ->
                "Wi-Fi · ${r.responseWifi.minOf { it.v }.roundToInt()}–${r.responseWifi.maxOf { it.v }.roundToInt()} ms"
            else -> null
        }
        CardHeader("Response time") { if (caption != null) Hint(caption) }
        Spacer(Modifier.height(14.dp))
        if (all.isEmpty() || r.durationS <= 0) {
            Text("No response checks", style = TType.sub, color = tc.text3)
            return@Card
        }
        val yMax = maxOf(150.0, all.maxOf { it.v }) * 1.25
        val h = 150.dp
        Box(Modifier.fillMaxWidth().height(h)) {
            Canvas(Modifier.fillMaxSize()) {
                val d = r.durationS
                val x = { t: Double -> (t / d * size.width).toFloat() }
                val y = { v: Double -> (size.height * (1 - v.coerceIn(0.0, yMax) / yMax)).toFloat() }
                val refY = y(150.0)
                drawLine(c.veryGood, Offset(0f, refY), Offset(size.width, refY), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                drawLine(c.sep, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), strokeWidth = 1.dp.toPx())
                val gap = maxOf(30.0, d / 20)
                series(r.responseWifi, x, y, c.wifi, 2.5.dp.toPx(), gap)
                series(r.responseMobile, x, y, c.mobile, 2.5.dp.toPx(), gap)
            }
            Text("150 ms", style = TType.footnote.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = c.veryGood,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().offset(y = h * (1 - (150.0 / yMax).toFloat()) - 20.dp))
        }
        if (r.responseWifi.isNotEmpty() && r.responseMobile.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            CarrierLegend()
        }
    }
}

@Composable
private fun SpeedTestsCard(r: TestReport) {
    Card {
        CardHeader("Speed tests")
        Spacer(Modifier.height(12.dp))
        if (r.speedTests.isEmpty()) {
            Text("No speed tests ran", style = TType.sub, color = tc.text3)
            return@Card
        }
        val max = r.speedTests.maxOf { it.mbps }.coerceAtLeast(1.0)
        r.speedTests.forEach { st ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(st.carrier.label, style = TType.sub.copy(fontSize = 16.sp), color = tc.text3, modifier = Modifier.width(92.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(11.dp)).background(tc.card2)) {
                    Box(Modifier.fillMaxWidth((st.mbps / max).toFloat().coerceIn(0.04f, 1f)).fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp)).background(if (st.carrier == Carrier.MOBILE) tc.mobile else tc.wifi))
                }
                Text("${st.mbps.roundToInt()}", style = TType.headline.merge(TType.num), color = tc.text,
                    textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Hint(r.speedTestSeconds?.let { "Mbps · $it s each" } ?: "Mbps")
    }
}

private fun activityLabel(k: String): String = when (k) {
    "still" -> "Still"; "walking", "on_foot" -> "Walking"; "running" -> "Running"
    "in_vehicle", "automotive", "driving" -> "In vehicle"; "on_bicycle", "cycling" -> "Cycling"
    "unknown" -> "Unknown"
    else -> k.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun MovementCard(r: TestReport) {
    Card {
        val pos = r.positionAccM?.let { a -> "Position ±${a.roundToInt()} m" + (r.positionSource?.let { " · $it" } ?: "") }
        CardHeader("Movement") { if (pos != null) Hint(pos) }
        Spacer(Modifier.height(14.dp))
        val entries = r.movement.entries.toList()
        if (entries.isEmpty()) {
            Text("No motion data", style = TType.sub, color = tc.text3)
            return@Card
        }
        val shades = listOf(tc.text, tc.card3, tc.text3, tc.text4)
        val total = entries.sumOf { it.value }.coerceAtLeast(1.0)
        Row(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            entries.forEachIndexed { i, e ->
                Box(Modifier.weight((e.value / total).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(shades[i % shades.size]))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            entries.forEach { e ->
                Text("${activityLabel(e.key)} ${e.value.roundToInt()}%", style = TType.sub.copy(fontSize = 16.sp), color = tc.text2)
            }
        }
    }
}

// ------------------------------------------------------------------------------ detailed

@Composable
private fun Detailed(r: TestReport, onMoment: (Int) -> Unit, onReplay: (Long?) -> Unit, onWorst: () -> Unit) {
    fun fmtDbm(v: Double?) = v?.let { "${it.dbm()} dBm" } ?: "–"
    fun ms(v: Double?) = v?.let { "${it.roundToInt()} ms" } ?: "–"
    val numbers: List<Pair<String, String>> = buildList {
        add("Wi-Fi RSSI median" to fmtDbm(r.wifiRssiMedian))
        add("Wi-Fi RSSI worst" to fmtDbm(r.wifiRssiWorst))
        add("Mobile RSRP median" to fmtDbm(r.rsrpMedian))
        add("Mobile SINR median" to (r.sinrMedian?.let { "${it.roundToInt()} dB" } ?: "–"))
        add("Mobile technology" to ((r.mobileTech ?: "–") + if (r.nrSeen && r.mobileTech != "5G") " · 5G seen" else ""))
        add("Mobile band" to (r.mobileBand ?: "–"))
        add("Response · Wi-Fi median" to ms(r.responseWifiMs))
        add("Response · mobile median" to ms(r.responseMobileMs))
        val lo = r.responseMobileMinMs; val hi = r.responseMobileMaxMs
        if (lo != null && hi != null) add("Response · mobile range" to "${lo.roundToInt()}–${hi.roundToInt()} ms")
        add("Network switches" to "${r.pathSwitches}")
        add("Access point changes" to "${r.apChanges}")
        add("Checks" to "${r.checks}" + (r.checksPassedPct?.let { " · ${it.roundToInt()}% passed" } ?: ""))
        add("False drops" to "${r.falseDrops}")
        add("On schedule" to (r.onSchedulePct?.let { "${it.roundToInt()}%" } ?: "–"))
        add("Positions" to "${r.positions}")
        add("GPS fixes" to "${r.gpsFixes}")
        add("Satellites used (max)" to (r.satellitesMax?.toString() ?: "–"))
        add("Position source" to (r.positionSource ?: "–"))
        add("Device" to r.deviceModel.ifBlank { "–" })
        add("App version" to r.appVersion.ifBlank { "–" })
    }
    Caption("Engineer numbers")
    GroupCard(rows = numbers.map<Pair<String, String>, @Composable () -> Unit> { (k, v) -> { ListRow(k, v) } } +
        listOf<@Composable () -> Unit>({ ListRow("Method", sub = r.methodFingerprint ?: "–") }), inset = 18.dp)

    if (r.moments.isNotEmpty()) {
        Caption("Moments")
        GroupCard(rows = r.moments.mapIndexed<Moment, @Composable () -> Unit> { i, m ->
            {
                ListRow(
                    m.title, value = fmtClock(m.tS), onClick = { onMoment(i) },
                    sub = m.causeTitle ?: m.kind,
                    leading = { Dot(momentColor(m, tc), 9.dp) },
                )
            }
        }, inset = 18.dp)
    }

    Caption("Tools")
    GroupCard(rows = listOf<@Composable () -> Unit>(
        { ListRow("Open replay", onClick = { onReplay(null) }) },
        { ListRow("Worst segments", onClick = onWorst) },
    ), inset = 18.dp)
}
