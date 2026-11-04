package app.tracer.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tracer.core.report.Pt
import app.tracer.core.runtime.HPoint
import app.tracer.ui.theme.TType
import kotlin.math.roundToInt

/*
 * Reusable Canvas line chart for the v2 screens (Moment ±30 s, Diagnostics, Wi-Fi / Mobile /
 * Response / Location details). Higher values are always drawn higher; for lower-is-better metrics
 * the caller puts the good band at the bottom (green), shades the bad region at the top and labels
 * the reference line ("Good below 150 ms") plus a "lower is better" caption.
 */

/** One series. [points] use seconds on the chart's x axis (test offset, or seconds before now for live charts). */
@Immutable
data class ChartLine(
    val points: List<Pt>,
    val color: Color,
    val dashed: Boolean = false,
    /** a gap longer than this breaks the line */
    val maxGapS: Double = 15.0,
    val width: Dp = 2.5.dp,
)

/** Horizontal band between two y values (order-independent), optional label drawn at its top-left. */
@Immutable
data class ChartBand(val from: Double, val to: Double, val fill: Color, val label: String? = null, val labelColor: Color = Color.Unspecified)

/** Dashed reference line with a label drawn just above it on the left. */
@Immutable
data class ChartRef(val value: Double, val color: Color, val label: String? = null)

/** Hollow ring at a point (e.g. "new access point"). */
@Immutable
data class ChartRing(val x: Double, val y: Double)

@Composable
fun LineChart(
    lines: List<ChartLine>,
    yMin: Double,
    yMax: Double,
    xMin: Double,
    xMax: Double,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    bands: List<ChartBand> = emptyList(),
    ref: ChartRef? = null,
    /** vertical dotted lines (carrier switches) */
    markers: List<Double> = emptyList(),
    /** the moment being inspected: dashed full-height line */
    cursorX: Double? = null,
    rings: List<ChartRing> = emptyList(),
    /** shown centred in the empty frame when no line has a point in range */
    emptyReason: String? = null,
    /** dot on the latest point of every line (live charts) */
    currentDot: Boolean = false,
    /** labels under the chart: x value → text */
    xLabels: List<Pair<Double, String>> = emptyList(),
    /** small value labels on the right edge with faint grid lines */
    yTicks: List<Pair<Double, String>> = emptyList(),
    /** "lower is better" / "higher is better", under the chart on the right */
    caption: String? = null,
) {
    val c = tc
    val measurer = rememberTextMeasurer()
    val spanX = (xMax - xMin).takeIf { it > 0 } ?: 1.0
    val spanY = (yMax - yMin).takeIf { it > 0 } ?: 1.0
    val visible = lines.map { l -> l.copy(points = l.points.filter { it.tS in xMin..xMax }) }
    val hasData = visible.any { it.points.isNotEmpty() }
    val bandLabel = TType.tick.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val tickStyle = TType.tick.copy(fontSize = 10.sp, color = c.text3)
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(6.dp)).background(c.card2.copy(alpha = 0.35f))) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                fun x(t: Double) = ((t - xMin) / spanX * w).toFloat()
                fun y(v: Double) = ((yMax - v.coerceIn(yMin, yMax)) / spanY * h).toFloat()
                val alpha = if (hasData) 1f else 0.45f
                // bands
                for (b in bands) {
                    val top = y(maxOf(b.from, b.to)); val bottom = y(minOf(b.from, b.to))
                    if (bottom - top < 1f) continue
                    drawRect(b.fill.copy(alpha = b.fill.alpha * alpha), Offset(0f, top), Size(w, bottom - top))
                    val label = b.label
                    if (label != null && hasData) {
                        val m = measurer.measure(label, bandLabel.copy(color = if (b.labelColor == Color.Unspecified) c.text3 else b.labelColor))
                        if (bottom - top >= m.size.height + 4) drawText(m, topLeft = Offset(8.dp.toPx(), top + 3.dp.toPx()))
                    }
                }
                // y grid + ticks (right edge)
                for ((v, label) in yTicks) {
                    if (v < yMin || v > yMax) continue
                    val yy = y(v)
                    drawLine(c.sep.copy(alpha = 0.6f), Offset(0f, yy), Offset(w, yy), strokeWidth = 0.5.dp.toPx())
                    val m = measurer.measure(label, tickStyle)
                    val ty = (yy - m.size.height - 1f).coerceIn(0f, h - m.size.height)
                    drawText(m, topLeft = Offset(w - m.size.width - 4.dp.toPx(), ty))
                }
                // reference line
                if (ref != null && ref.value in yMin..yMax) {
                    val ry = y(ref.value)
                    drawLine(ref.color.copy(alpha = alpha), Offset(0f, ry), Offset(w, ry), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                    val label = ref.label
                    if (label != null && hasData) {
                        val m = measurer.measure(label, bandLabel.copy(color = ref.color))
                        val above = ry - m.size.height - 2.dp.toPx()
                        val ty = if (above >= 0f) above else ry + 2.dp.toPx()
                        drawText(m, topLeft = Offset(8.dp.toPx(), ty))
                    }
                }
                if (!hasData) return@Canvas
                // carrier-switch markers
                val dotted = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                for (mk in markers) {
                    if (mk < xMin || mk > xMax) continue
                    drawLine(c.text3, Offset(x(mk), 0f), Offset(x(mk), h), strokeWidth = 1.dp.toPx(), pathEffect = dotted)
                }
                // lines
                for (l in visible) {
                    if (l.points.isEmpty()) continue
                    val sw = l.width.toPx()
                    if (l.points.size == 1) { drawCircle(l.color, sw * 1.2f, Offset(x(l.points[0].tS), y(l.points[0].v))); continue }
                    val path = Path()
                    var prev: Pt? = null
                    for (p in l.points) {
                        val pv = prev
                        if (pv == null || p.tS - pv.tS > l.maxGapS) path.moveTo(x(p.tS), y(p.v)) else path.lineTo(x(p.tS), y(p.v))
                        prev = p
                    }
                    val effect = if (l.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
                    drawPath(path, l.color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
                }
                // rings
                val rad = 5.dp.toPx()
                for (r in rings) {
                    if (r.x < xMin || r.x > xMax) continue
                    val o = Offset(x(r.x), y(r.y))
                    drawCircle(c.bg, rad, o)
                    drawCircle(c.text, rad, o, style = Stroke(2.dp.toPx()))
                }
                // current value
                if (currentDot) for (l in visible) {
                    val last = l.points.lastOrNull() ?: continue
                    val o = Offset(x(last.tS), y(last.v))
                    drawCircle(c.bg, 6.dp.toPx(), o)
                    drawCircle(l.color, 4.5.dp.toPx(), o)
                }
                // moment cursor
                if (cursorX != null && cursorX in xMin..xMax) {
                    drawLine(c.text2, Offset(x(cursorX), 0f), Offset(x(cursorX), h), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                }
            }
            if (!hasData) {
                Text(
                    emptyReason ?: "No readings", style = TType.sub.copy(fontSize = 14.sp), color = c.text3, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                )
            }
        }
        if (xLabels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                xLabels.forEachIndexed { i, (xv, label) ->
                    val m = measurer.measure(label, tickStyle.copy(fontSize = 11.sp))
                    val px = ((xv - xMin) / spanX * size.width).toFloat()
                    val left = when (i) {
                        0 -> px
                        xLabels.lastIndex -> px - m.size.width
                        else -> px - m.size.width / 2f
                    }.coerceIn(0f, (size.width - m.size.width).coerceAtLeast(0f))
                    drawText(m, topLeft = Offset(left, 0f))
                }
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            Text(caption, style = TType.footnote.copy(fontSize = 12.sp), color = c.text3, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "— Wi-Fi  - - 5G" legend row; dashed entries draw a broken swatch. */
@Composable
fun ChartLegend(items: List<Triple<String, Color, Boolean>>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (label, color, dashed) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(16.dp).height(10.dp)) {
                    val yy = size.height / 2
                    drawLine(color, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
                }
                Spacer(Modifier.width(6.dp))
                Text(label, style = TType.footnote.copy(fontSize = 13.sp), color = tc.text3, maxLines = 1)
            }
        }
    }
}

/** Live x labels for a window of [windowS] seconds ending now: "−5 min", "−2:30", "now". */
fun liveXLabels(windowS: Double): List<Pair<Double, String>> {
    fun f(s: Double): String {
        val t = s.roundToInt()
        return if (t % 60 == 0) "−${t / 60} min" else "−%d:%02d".format(t / 60, t % 60)
    }
    return listOf(-windowS to f(windowS), -windowS / 2 to f(windowS / 2), 0.0 to "now")
}

/** Rolling live series → chart points in seconds relative to [nowMs] (≤ 0). */
fun List<HPoint>.toPts(nowMs: Long): List<Pt> = map { Pt((it.tMonoMs - nowMs) / 1000.0, it.v) }

/** One part of a [BreakdownBar]. */
@Immutable
data class BarPart(val label: String, val ms: Double?, val color: Color)

/** Stacked horizontal bar (DNS · connect · TLS · first byte) with a legend of values. */
@Composable
fun BreakdownBar(parts: List<BarPart>, modifier: Modifier = Modifier) {
    val known = parts.filter { (it.ms ?: 0.0) > 0.0 }
    val total = known.sumOf { it.ms ?: 0.0 }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(tc.card2), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (total > 0) known.forEach { p ->
                Box(Modifier.weight(((p.ms ?: 0.0) / total).toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(p.color))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            parts.forEach { p ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(p.color))
                        Spacer(Modifier.width(5.dp))
                        Text(p.label, style = TType.footnote.copy(fontSize = 12.sp), color = tc.text3, maxLines = 1)
                    }
                    Text(p.ms?.let { "${it.roundToInt()} ms" } ?: "–", style = TType.footnote.copy(fontSize = 13.sp).merge(TType.num), color = tc.text)
                }
            }
        }
    }
}

/** Four rising signal bars, [level] 0..4 lit in [color]. */
@Composable
fun SignalBars(level: Int, color: Color, modifier: Modifier = Modifier) {
    val off = tc.meterOff
    Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val gap = 2.dp.toPx()
        val bw = (size.width - 3 * gap) / 4
        for (i in 0 until 4) {
            val bh = size.height * (i + 1) / 4f
            drawRect(if (i < level) color else off, Offset(i * (bw + gap), size.height - bh), Size(bw, bh))
        }
    }
}
