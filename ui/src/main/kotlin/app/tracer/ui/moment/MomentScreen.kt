package app.tracer.ui.moment

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tracer.core.report.Carrier
import app.tracer.core.report.Feedback
import app.tracer.core.report.Moment
import app.tracer.core.report.Plain
import app.tracer.core.report.Pt
import app.tracer.core.report.TestReport
import app.tracer.core.report.Verdict
import app.tracer.core.report.fmtClock
import app.tracer.core.report.fmtDuration
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.kit.*
import app.tracer.ui.report.ReportRepository
import app.tracer.ui.summary.ReportGate
import app.tracer.ui.summary.ReportState
import app.tracer.ui.summary.ReportViewModel
import app.tracer.ui.theme.TType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MomentViewModel @Inject constructor(
    reports: ReportRepository,
    runs: RunRepository,
) : ReportViewModel(reports, runs) {
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Stores the label; the report is rebuilt and [state] re-emits with the new feedback. */
    fun setFeedback(eventId: Long, label: String) {
        val id = boundRunId ?: return
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            runCatching { reports.setFeedback(id, eventId, label) }.onFailure { _error.value = "Couldn't save — try again" }
            _saving.value = false
        }
    }
}

/** Design v2 frame 6 — Moment detail. */
@Composable
fun MomentScreen(runId: String, index: Int, onBack: () -> Unit, onOpenIndex: (Int) -> Unit) {
    val vm: MomentViewModel = hiltViewModel()
    LaunchedEffect(runId) { vm.bind(runId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Screen {
        if (state !is ReportState.Ready) TopLinks(back = "Summary", onBack = onBack)
        ReportGate(state) { _, r ->
            val n = r.moments.size
            if (n == 0) {
                TopLinks(back = "Summary", onBack = onBack)
                Text("No moments in this test.", style = TType.sub.copy(fontSize = 17.sp), color = tc.text3, modifier = Modifier.padding(top = 24.dp))
                return@ReportGate
            }
            val i = index.coerceIn(0, n - 1)
            val m = r.moments[i]
            val prev = if (i > 0) i - 1 else null
            val next = if (i < n - 1) i + 1 else null
            Box(Modifier.fillMaxWidth()) {
                TopLinks(back = "Summary", onBack = onBack)
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    StepArrow(left = true, enabled = prev != null) { prev?.let(onOpenIndex) }
                    Text("${i + 1} of $n", style = TType.sub.copy(fontSize = 16.sp).merge(TType.num), color = tc.text3)
                    StepArrow(left = false, enabled = next != null) { next?.let(onOpenIndex) }
                }
            }
            var drag by remember(i) { mutableFloatStateOf(0f) }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(i, n) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                val threshold = 80.dp.toPx()
                                if (drag < -threshold) next?.let(onOpenIndex)
                                else if (drag > threshold) prev?.let(onOpenIndex)
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        ) { change, dx -> change.consume(); drag += dx }
                    }
                    .verticalScroll(rememberScrollState()),
            ) { MomentBody(r, m) }
            FeedbackBar(m, saving, error, onYes = { id -> vm.setFeedback(id, Feedback.YES) }, onNo = { id, cause -> vm.setFeedback(id, Feedback.no(cause)) })
        }
    }
}

@Composable
private fun StepArrow(left: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        if (left) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = if (left) "Previous moment" else "Next moment",
        tint = if (enabled) tc.link else tc.text4,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(6.dp),
    )
}

private fun verdictFor(r: TestReport, c: Carrier?): Verdict = when (c) {
    Carrier.WIFI -> r.wifiVerdict
    Carrier.MOBILE -> r.mobileVerdict
    Carrier.NONE -> Verdict.BAD
    null -> Verdict.NONE
}

@Composable
private fun MomentBody(r: TestReport, m: Moment) {
    val lasted = m.lastedS
    val eyebrow = fmtClock(m.tS) + if (lasted != null && lasted >= 1) " · lasted ${fmtDuration(lasted)}" else ""
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp)) {
        Text(eyebrow, style = TType.sub.copy(fontSize = 16.sp).merge(TType.num), color = tc.text3)
        Text(m.title, style = TType.largeTitle, color = tc.text)
        Spacer(Modifier.height(10.dp))
        if (m.stayedOnline) StatusCapsule("You stayed online", tc.veryGood, tc.veryGoodBg)
        else StatusCapsule("You were offline", tc.bad, tc.badBg)
    }
    val before = m.before
    val after = m.after
    if (before != null || after != null) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            CarrierTile("Before", before, verdictFor(r, before), Modifier.weight(1f).fillMaxHeight())
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = tc.text3, modifier = Modifier.padding(horizontal = 6.dp).size(22.dp))
            CarrierTile("After", after, verdictFor(r, after), Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(16.dp))
    }
    StrengthWindow(r, m)
    Spacer(Modifier.height(16.dp))
    Card {
        Text("Likely cause", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3)
        Spacer(Modifier.height(8.dp))
        Text(m.causeTitle ?: "No cause found", style = TType.title.copy(fontSize = 23.sp), color = tc.text)
        val detail = m.causeDetail
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(detail, style = TType.footnote.copy(fontSize = 15.sp), color = tc.text3)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun CarrierTile(label: String, c: Carrier?, v: Verdict, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(K.tileRadius)).background(tc.bgOf(v)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(label, style = TType.sub.copy(fontSize = 16.sp), color = tc.text2)
        Spacer(Modifier.height(2.dp))
        Text(c?.label ?: "—", style = TType.title.copy(fontSize = 28.sp), color = if (c != null) tc.of(c) else tc.text3)
        Spacer(Modifier.height(2.dp))
        Text(v.label, style = TType.headline.copy(fontSize = 19.sp), color = tc.fg(v))
    }
}

private const val WIN_S = 30.0
private const val TOP = -45.0
private const val BOTTOM = -95.0

/** A Wi-Fi-equivalent level for a verdict, so mobile can be drawn on the same scale after a switch. */
private fun levelOf(v: Verdict): Double = when (v) {
    Verdict.VERY_GOOD -> -52.0; Verdict.ABOVE_AVG -> -60.0; Verdict.AVERAGE -> -68.0
    Verdict.BELOW_AVG -> -76.0; Verdict.BAD -> -86.0; Verdict.NONE -> -68.0
}

@Composable
private fun StrengthWindow(r: TestReport, m: Moment) {
    val c = tc
    val t0 = m.tS - WIN_S
    val t1 = m.tS + WIN_S
    val pts = r.wifiRssi.filter { it.tS in t0..t1 }
    val mobileAfter = m.after == Carrier.MOBILE && m.before != Carrier.MOBILE
    val mobileUntil = minOf(t1, m.tS + (m.lastedS ?: WIN_S))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi strength", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3, modifier = Modifier.weight(1f))
            Text("±30 s", style = TType.footnote.copy(fontSize = 14.sp).merge(TType.num), color = tc.text3)
        }
        Spacer(Modifier.height(14.dp))
        if (pts.isEmpty() && !mobileAfter) {
            Text("No Wi-Fi readings around this moment", style = TType.sub, color = tc.text3)
            return@Card
        }
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val x = { t: Double -> ((t - t0) / (t1 - t0) * size.width).toFloat() }
            val y = { v: Double -> ((TOP - v.coerceIn(BOTTOM, TOP)) / (TOP - BOTTOM) * size.height).toFloat() }
            // bad band (≤ −80 dBm)
            val badTop = y(-80.0)
            drawRect(c.chartBad, Offset(0f, badTop), Size(size.width, size.height - badTop))
            val w = 2.5.dp.toPx()
            val solid = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(sel: List<Pt>) {
                if (sel.isEmpty()) return
                val p = Path()
                var prevT: Double? = null
                for (pt in sel) {
                    val pv = prevT
                    if (pv == null || pt.tS - pv > 8) p.moveTo(x(pt.tS), y(pt.v)) else p.lineTo(x(pt.tS), y(pt.v))
                    prevT = pt.tS
                }
                drawPath(p, c.wifi, style = solid)
            }
            if (mobileAfter) {
                line(pts.filter { it.tS <= m.tS })
                line(pts.filter { it.tS >= mobileUntil })
                val ly = y(levelOf(r.mobileVerdict))
                drawLine(c.mobile, Offset(x(m.tS), ly), Offset(x(mobileUntil), ly), strokeWidth = w, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 3.dp.toPx() + w)))
            } else {
                line(pts)
            }
            val mx = x(m.tS)
            drawLine(c.text2, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackBar(m: Moment, saving: Boolean, error: String?, onYes: (Long) -> Unit, onNo: (Long, String) -> Unit) {
    val eventId = m.eventId
    val fb = m.feedback
    var sheet by remember { mutableStateOf(false) }
    val message = when {
        eventId == null -> "Feedback isn't available for this moment"
        error != null -> error
        fb == Feedback.YES -> "Thanks — marked correct"
        fb != null && Feedback.isNo(fb) -> Feedback.corrected(fb)?.let { "Changed to ${Plain.causeTitle(it)}" } ?: "Marked as wrong"
        else -> "Is this right?"
    }
    val enabled = eventId != null && !saving
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).navigationBarsPadding()) {
        Text(message, style = TType.sub.copy(fontSize = 17.sp), color = tc.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap)) {
            Pill("Yes", { if (eventId != null) onYes(eventId) }, Modifier.weight(1f), kind = PillKind.SECONDARY, enabled = enabled, height = 52.dp)
            Pill("No, change", { sheet = true }, Modifier.weight(1f), kind = PillKind.SECONDARY, enabled = enabled, height = 52.dp)
        }
    }
    if (sheet && eventId != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = sheetState, containerColor = tc.card) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text("What was it?", style = TType.title, color = tc.text, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                val current = Feedback.corrected(fb)
                Feedback.CHOICES.forEach { key ->
                    ListRow(
                        Plain.causeTitle(key),
                        onClick = {
                            onNo(eventId, key)
                            scope.launch { sheetState.hide() }.invokeOnCompletion { sheet = false }
                        },
                        chevron = false,
                        trailing = { if (key == current) Text("✓", style = TType.headline, color = tc.link) },
                    )
                }
            }
        }
    }
}
