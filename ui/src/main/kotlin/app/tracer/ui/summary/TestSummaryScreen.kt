package app.tracer.ui.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.model.RunRecord
import app.tracer.core.report.Moment
import app.tracer.core.report.TestReport
import app.tracer.core.report.Verdict
import app.tracer.core.report.fmtClock
import app.tracer.core.report.fmtDuration
import app.tracer.ui.kit.*
import app.tracer.ui.theme.TColors
import app.tracer.ui.theme.TType
import kotlin.math.abs
import kotlin.math.roundToInt

/** Design v2 frame 4 — Test summary. */
@Composable
fun TestSummaryScreen(
    runId: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDiagnostics: () -> Unit,
    onMoment: (Int) -> Unit,
) {
    val vm: TestSummaryViewModel = hiltViewModel()
    LaunchedEffect(runId) { vm.bind(runId) }
    val state by vm.state.collectAsStateWithLifecycle()
    Screen {
        TopLinks(back = "Tests", onBack = onBack, action = "Share", onAction = onShare)
        ReportGate(state) { _, r -> SummaryBody(r, onShare, onDiagnostics, onMoment) }
    }
}

@Composable
private fun ColumnScope.SummaryBody(r: TestReport, onShare: () -> Unit, onDiagnostics: () -> Unit, onMoment: (Int) -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        TitleBlock(eyebrow = eyebrowOf(r), title = r.headline)

        // ---- online ring + subline
        Card(padding = PaddingValues(horizontal = 22.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pct = r.onlinePct
                Ring(
                    fraction = ((pct ?: 0.0) / 100.0).toFloat(),
                    color = if (pct == null) tc.text4 else tc.fg(r.onlineVerdict),
                    center = pct?.let { "${it.roundToInt()}%" } ?: "—",
                    under = "online",
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    boldDurations(r.subline, listOf(r.mobileCoveredS, r.offlineS), tc.text),
                    style = TType.sub.copy(fontSize = 18.sp, lineHeight = 25.sp), color = tc.text2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- 2×2 tiles
        TileRow {
            val ws = r.wifiSpeedMedian ?: r.wifiSpeedMax
            VerdictTile("Wi-Fi speed", ws?.let { "${it.roundToInt()}" } ?: "—", r.wifiSpeedVerdict, Modifier.weight(1f), unit = ws?.let { "Mbps" })
            val ms = r.mobileSpeedMedian ?: r.mobileSpeedMax
            VerdictTile("Mobile speed", ms?.let { "${it.roundToInt()}" } ?: "—", r.mobileSpeedVerdict, Modifier.weight(1f), unit = ms?.let { "Mbps" })
        }
        TileRow {
            val rt = r.responseMs
            VerdictTile("Response", rt?.let { "≤${it.roundToInt()}" } ?: "—", r.responseVerdict, Modifier.weight(1f), unit = rt?.let { "ms" })
            VerdictTile("Stability", "${r.switches}", r.stabilityVerdict, Modifier.weight(1f), unit = if (r.switches == 1) "switch" else "switches")
        }
        Spacer(Modifier.height(10.dp))

        // ---- timeline
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Timeline", style = CardTitle, color = tc.text, modifier = Modifier.weight(1f))
                CarrierLegend()
            }
            Spacer(Modifier.height(14.dp))
            if (r.segments.isEmpty()) Text("No network data", style = TType.sub, color = tc.text3)
            else NetworkBar(r.segments, r.durationS, ticks = boundaryTicks(r))
        }
        Spacer(Modifier.height(16.dp))

        // ---- moments
        if (r.moments.isEmpty()) {
            Card { Text("No moments — nothing unusual happened.", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3) }
        } else {
            GroupCard(rows = r.moments.mapIndexed<Moment, @Composable () -> Unit> { i, m -> { MomentRow(m) { onMoment(i) } } }, inset = 18.dp)
        }
        Spacer(Modifier.height(16.dp))

        // ---- test quality
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Test quality", style = CardTitle, color = tc.text, modifier = Modifier.weight(1f))
                VerdictCapsule(r.qualityVerdict)
            }
            Spacer(Modifier.height(8.dp))
            val checks = r.checksPassedPct
            ProgressLine("Checks passed", checks?.let { "${it.roundToInt()}%" } ?: "—",
                ((checks ?: 0.0) / 100).toFloat(), tc.fg(Verdict.checks(checks)))
            val posFrac = positionsFraction(r)
            ProgressLine("Positions", "${r.positions}", posFrac.toFloat(), tc.fg(Verdict.checks(posFrac * 100)))
            val sched = r.onSchedulePct
            if (sched != null) ProgressLine("On schedule", "${sched.roundToInt()}%", (sched / 100).toFloat(), tc.fg(Verdict.checks(sched)))
        }
        Spacer(Modifier.height(20.dp))
        Pill("Full diagnostics", onDiagnostics, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Pill("Share report", onShare, Modifier.fillMaxWidth(), kind = PillKind.SECONDARY_BLUE)
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun MomentRow(m: Moment, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(momentColor(m, tc), 9.dp)
        Spacer(Modifier.width(16.dp))
        Text(m.title, style = TType.body, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(fmtClock(m.tS), style = TType.bodyRegular.merge(TType.num), color = tc.text3)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tc.text4, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

// ------------------------------------------------------------------------------ shared (report screens)

internal val CardTitle = TType.title.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

/** "Office · Test 3 · 3:12". */
internal fun eyebrowOf(r: TestReport) = "${r.title} · ${fmtClock(r.durationS)}"

/** Loading / still-recording / missing placeholder, else [content]. */
@Composable
internal fun ColumnScope.ReportGate(state: ReportState, content: @Composable ColumnScope.(RunRecord, TestReport) -> Unit) {
    when (state) {
        is ReportState.Ready -> content(state.run, state.report)
        ReportState.Loading -> Centered {
            CircularProgressIndicator(color = tc.link, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(16.dp))
            Text("Building report…", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3)
        }
        ReportState.Recording -> Centered {
            CircularProgressIndicator(color = tc.link, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(16.dp))
            Text("This test is still recording", style = TType.headline, color = tc.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("The report is built as soon as it ends.", style = TType.sub, color = tc.text3, textAlign = TextAlign.Center)
        }
        ReportState.Missing -> Centered {
            Text("Report unavailable", style = TType.headline, color = tc.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("This test could not be found or has no data.", style = TType.sub, color = tc.text3, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ColumnScope.Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content,
    )
}

/** Dot colour: recovered green, GPS yellow, warning orange, critical red, info blue. */
internal fun momentColor(m: Moment, c: TColors): Color = when {
    m.kind == "reconnect" || m.title.startsWith("Back on") -> c.veryGood
    m.kind == "gps_degraded" -> c.average
    m.severity == "critical" -> c.bad
    m.severity == "warning" || m.kind == "drop" -> c.belowAvg
    else -> c.link
}

/** Ticks at 0, segment boundaries (≤ 2, spaced ≥ 15 % apart) and the end. */
internal fun boundaryTicks(r: TestReport): List<Double> {
    val d = r.durationS
    if (d <= 0) return emptyList()
    val chosen = mutableListOf(0.0, d)
    val minGap = d * 0.15
    for (b in r.segments.drop(1).map { it.fromS }.distinct().sorted()) {
        if (chosen.size >= 4) break
        if (chosen.all { abs(it - b) >= minGap }) chosen += b
    }
    return chosen.sorted()
}

/** Even ticks (0, 1:00, 2:00 … end), ≤ 5 labels, none too close to the end. */
internal fun evenTicks(d: Double): List<Double> {
    if (d <= 0) return emptyList()
    val steps = doubleArrayOf(10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0, 7200.0)
    val step = steps.firstOrNull { d / it <= 3.5 } ?: (d / 3)
    val out = mutableListOf(0.0)
    var t = step
    while (t < d - step * 0.4) { out += t; t += step }
    out += d
    return out
}

/** Tick labels under a chart drawn across the full width (0 left-aligned, end right-aligned). */
@Composable
internal fun TickLabels(ticks: List<Double>, durationS: Double, modifier: Modifier = Modifier) {
    if (ticks.isEmpty() || durationS <= 0) return
    val color = tc.text3
    BoxWithConstraints(modifier.fillMaxWidth().height(16.dp)) {
        val w = maxWidth
        ticks.forEachIndexed { i, tk ->
            val frac = (tk / durationS).toFloat().coerceIn(0f, 1f)
            val align = when (i) { 0 -> 0f; ticks.lastIndex -> 1f; else -> 0.5f }
            Text(
                fmtClock(tk), style = TType.tick, color = color,
                modifier = Modifier.offset(x = (w * frac) - (48.dp * align)).width(48.dp),
                textAlign = when (i) { 0 -> TextAlign.Start; ticks.lastIndex -> TextAlign.End; else -> TextAlign.Center },
            )
        }
    }
}

/** Positions received vs ≈ one per second of test. */
internal fun positionsFraction(r: TestReport): Double =
    if (r.durationS <= 0) 0.0 else (r.positions / r.durationS).coerceIn(0.0, 1.0)

/** Bold every occurrence of the formatted durations ("95 s") inside [text]. */
internal fun boldDurations(text: String, seconds: List<Double>, boldColor: Color = Color.Unspecified): AnnotatedString {
    val needles = seconds.filter { it >= 1 }.map { fmtDuration(it) }.distinct().sortedByDescending { it.length }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val hit = needles.firstOrNull { text.startsWith(it, i) }
            if (hit != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldColor)) { append(hit) }
                i += hit.length
            } else { append(text[i]); i++ }
        }
    }
}
