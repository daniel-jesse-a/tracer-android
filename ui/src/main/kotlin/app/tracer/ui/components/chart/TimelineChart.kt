package app.tracer.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tracer.ui.R
import app.tracer.ui.live.Fmt
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.severityColor
import kotlin.math.abs

private val GUTTER = 52.dp
private const val MIN_SPAN_NS = 5_000_000_000L

/** Visible x-range (pinch zoom scales x only). */
@Immutable
private data class View(val start: Long, val end: Long) { val span: Long get() = (end - start).coerceAtLeast(1L) }

/**
 * Multi-lane timeline over [startNs, endNs] (t_mono_ns): one 30 dp lane per series (TYPE bands,
 * numeric lines with optional dotted threshold and hatched failures), then an EVENTS dot lane and a
 * shared time axis. Numeric lanes are min/max decimated to one column per pixel and cached per
 * (data, visible range, size), so 10k+ points redraw without allocation; cursor moves only re-run
 * the draw phase. Pinch scales x; one-finger horizontal drag / tap scrubs.
 *
 * @param events (t_mono_ns, severity)
 * @param onScrub called with a t_mono_ns on tap / horizontal drag; null = not scrubbable
 * @param height lane height
 * @param onEventTap tap in the EVENTS lane near a dot → its index in [events]
 * @param originNs axis labels show time since this instant
 * @param waypoints t_mono_ns of user waypoints → a WAYPTS tick lane under EVENTS (only drawn when non-empty)
 * @param onWaypointTap tap in the WAYPTS lane near a tick → its index in [waypoints]
 */
@Composable
fun TimelineChart(
    series: List<ChartSeries>,
    startNs: Long,
    endNs: Long,
    cursorNs: Long?,
    events: List<Pair<Long, String>>,
    modifier: Modifier = Modifier,
    onScrub: ((Long) -> Unit)? = null,
    height: Dp = 30.dp,
    onEventTap: ((Int) -> Unit)? = null,
    originNs: Long = startNs,
    zoomable: Boolean = true,
    showAxis: Boolean = true,
    waypoints: List<Long> = emptyList(),
    onWaypointTap: ((Int) -> Unit)? = null,
) {
    val cursor = rememberUpdatedState(cursorNs)
    val scrub by rememberUpdatedState(onScrub)
    val eventTap by rememberUpdatedState(onEventTap)
    val evs by rememberUpdatedState(events)
    val wps by rememberUpdatedState(waypoints)
    val waypointTap by rememberUpdatedState(onWaypointTap)
    var view by remember(startNs, endNs) { mutableStateOf(View(startNs, endNs.coerceAtLeast(startNs + 1))) }
    val density = LocalDensity.current
    val gutterPx = with(density) { GUTTER.toPx() }
    val lanePx = with(density) { height.toPx() }
    val laneCount = series.size

    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(startNs, endNs, zoomable, laneCount, lanePx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val plotW = (size.width - gutterPx).coerceAtLeast(1f)
                    fun toNs(x: Float): Long {
                        val v = view
                        return v.start + (((x - gutterPx) / plotW).coerceIn(0f, 1f) * v.span).toLong()
                    }
                    val slop = viewConfiguration.touchSlop
                    var multi = false
                    var dragging = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2 && zoomable) {
                            multi = true
                            val zoom = ev.calculateZoom()
                            val c = ev.calculateCentroid(useCurrent = true)
                            val pan = ev.calculatePan()
                            val v = view
                            val full = endNs - startNs
                            val newSpan = (v.span / zoom).toLong().coerceIn(minOf(MIN_SPAN_NS, full), full.coerceAtLeast(1L))
                            val frac = ((c.x - gutterPx) / plotW).coerceIn(0f, 1f)
                            val anchor = v.start + (frac * v.span).toLong()
                            var ns = anchor - (frac * newSpan).toLong() - (pan.x / plotW * newSpan).toLong()
                            ns = ns.coerceIn(startNs, (endNs - newSpan).coerceAtLeast(startNs))
                            view = View(ns, ns + newSpan)
                            ev.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: continue
                            if (!dragging) {
                                val dx = ch.position.x - down.position.x
                                val dy = ch.position.y - down.position.y
                                if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture // vertical scroll belongs to the parent
                                if (abs(dx) > slop && scrub != null) dragging = true
                            }
                            if (dragging) {
                                scrub?.invoke(toNs(ch.position.x))
                                ch.consume()
                            }
                        }
                    }
                    if (!multi && !dragging) {
                        val p = down.position
                        val tap = eventTap
                        val eventsTop = laneCount * lanePx
                        fun nearest(ts: List<Long>): Int {
                            val v = view
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            ts.forEachIndexed { i, t ->
                                val x = gutterPx + (t - v.start).toFloat() / v.span * plotW
                                val d = abs(x - p.x)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            return if (best >= 0 && bestD <= 22.dp.toPx()) best else -1
                        }
                        if (tap != null && p.y >= eventsTop && p.y <= eventsTop + lanePx) {
                            val best = nearest(evs.map { it.first })
                            if (best >= 0) { tap(best); return@awaitEachGesture }
                        }
                        val wTap = waypointTap
                        val wTop = eventsTop + lanePx
                        if (wTap != null && wps.isNotEmpty() && p.y >= wTop && p.y <= wTop + lanePx) {
                            val best = nearest(wps)
                            if (best >= 0) { wTap(best); return@awaitEachGesture }
                        }
                        scrub?.invoke(toNs(p.x))
                    }
                }
            },
    ) {
        series.forEach { s ->
            Lane(s, view, cursor, height)
        }
        EventLane(events, view, cursor, height)
        if (waypoints.isNotEmpty()) WaypointLane(waypoints, view, cursor, height)
        if (showAxis) Axis(view, originNs)
    }
}

// ------------------------------------------------------------------------------------ shared draw

private fun DrawScope.drawCursor(t: Long?, v: View, color: Color) {
    if (t == null || t < v.start || t > v.end) return
    val x = (t - v.start).toFloat() / v.span * size.width
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
}

/** Diagonal hatch inside [x0, x1] × [top, bottom]; no allocation. */
private fun DrawScope.hatch(x0: Float, x1: Float, top: Float, bottom: Float, color: Color, step: Float) {
    val h = bottom - top
    clipRect(x0, top, x1, bottom) {
        var x = x0 - h
        while (x < x1) {
            drawLine(color, Offset(x, bottom), Offset(x + h, top), strokeWidth = 1f)
            x += step
        }
    }
}

@Immutable
private class Bands(val x0: FloatArray, val x1: FloatArray, val colors: Array<Color>, val hatched: BooleanArray) {
    companion object { val EMPTY = Bands(FloatArray(0), FloatArray(0), emptyArray(), BooleanArray(0)) }
}

/** Runs of equal categories (or of `true` flags) → pixel bands; a band ends at the next sample or after maxGap. */
private fun buildBands(
    xs: LongArray, n: Int, maxGapNs: Long, v: View, width: Int,
    same: (Int, Int) -> Boolean, include: (Int) -> Boolean, color: (Int) -> Color, hatched: (Int) -> Boolean,
): Bands {
    if (width <= 0 || n == 0) return Bands.EMPTY
    val span = v.span.toFloat()
    fun px(t: Long) = ((t - v.start) / span * width).coerceIn(0f, width.toFloat())
    val x0 = ArrayList<Float>(); val x1 = ArrayList<Float>(); val cs = ArrayList<Color>(); val hs = ArrayList<Boolean>()
    var i = 0
    while (i < n) {
        var j = i
        var bandEnd: Long
        while (true) {
            val nextT = if (j + 1 < n) xs[j + 1] else Long.MAX_VALUE
            val gapEnd = xs[j] + maxGapNs
            if (j + 1 < n && same(j + 1, i) && nextT <= gapEnd) { j++; continue }
            bandEnd = minOf(nextT, gapEnd)
            break
        }
        if (include(i) && bandEnd >= v.start && xs[i] <= v.end) {
            x0 += px(xs[i]); x1 += px(bandEnd); cs += color(i); hs += hatched(i)
        }
        i = j + 1
    }
    return Bands(x0.toFloatArray(), x1.toFloatArray(), cs.toTypedArray(), hs.toBooleanArray())
}

/** Decimated polyline for a numeric lane. Pure apart from the Path allocation. */
private fun buildNumericPath(s: NumericSeries, v: View, width: Int, height: Int, padPx: Float): Path? {
    if (width <= 0 || height <= 0) return null
    val cols = width
    val dec = decimateMinMax(s.xs, s.ys, v.start, v.end, cols)
    val nsPerCol = v.span.toDouble() / cols
    val gapCols = (s.maxGapNs / nsPerCol).toInt().coerceAtLeast(1)
    val range = (s.yMax - s.yMin).takeIf { it > 0f } ?: 1f
    val usable = height - 2 * padPx
    fun y(value: Float): Float = padPx + usable * (1f - ((value - s.yMin) / range).coerceIn(0f, 1f))
    val path = Path()
    var lastCol = Int.MIN_VALUE / 2
    for (c in 0 until cols) {
        val lo = dec[2 * c]
        if (lo.isNaN()) continue
        val hi = dec[2 * c + 1]
        val x = c + 0.5f
        if (c - lastCol > gapCols) {
            path.moveTo(x, y(hi))
            if (hi == lo) path.lineTo(x + 1f, y(lo)) else path.lineTo(x, y(lo))
        } else {
            path.lineTo(x, y(hi))
            if (hi != lo) path.lineTo(x, y(lo))
        }
        lastCol = c
    }
    return path
}

// ------------------------------------------------------------------------------------ lanes

@Composable
private fun Gutter(label: String, enabled: Boolean, value: () -> String?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.width(GUTTER).fillMaxHeight().padding(end = 4.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text(label, style = Mono.tiny, color = if (enabled) dim else dim.copy(alpha = 0.5f), maxLines = 1)
        val v = value()
        if (v != null) Text(v, style = Mono.tiny, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun Lane(s: ChartSeries, v: View, cursor: State<Long?>, height: Dp) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        when (s) {
            is NumericSeries -> {
                Gutter(s.label, s.hasData) {
                    cursor.value?.let { t ->
                        if (s.failedAt(t)) "×" else s.valueAt(t)?.let { s.format(it) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (s.hasData) NumericPlot(s, v, cursor) else NoData(s.noDataText, v, cursor)
                }
            }
            is CategoricalSeries -> {
                Gutter(s.label, s.hasData) { cursor.value?.let { s.valueAt(it) }?.let { s.nameOf(it) } }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (s.hasData) CategoricalPlot(s, v, cursor) else NoData(s.noDataText, v, cursor)
                }
            }
        }
    }
}

@Composable
private fun NoData(text: String?, v: View, cursor: State<Long?>) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val cursorColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            drawRect(bg, Offset(0f, size.height * 0.2f), Size(size.width, size.height * 0.6f))
            drawCursor(cursor.value, v, cursorColor)
        }
        Text(text ?: stringResource(R.string.live_no_data), style = Mono.tiny, color = grey)
    }
}

@Composable
private fun NumericPlot(s: NumericSeries, v: View, cursor: State<Long?>) {
    var w by remember { mutableIntStateOf(0) }
    var h by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val pad = with(density) { 3.dp.toPx() }
    val stroke = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    val dotted = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())) } }
    val path = remember(s, v, w, h) { buildNumericPath(s, v, w, h, pad) }
    val failBands = remember(s, v, w) {
        val f = s.failed
        if (f == null) Bands.EMPTY
        else buildBands(s.xs, s.xs.size, s.maxGapNs, v, w, same = { a, b -> f[a] == f[b] }, include = { f[it] }, color = { Color.Unspecified }, hatched = { true })
    }
    val colors = LocalTracerColors.current
    val thresholdColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val cursorColor = MaterialTheme.colorScheme.primary
    val hatchStep = with(density) { 5.dp.toPx() }
    Canvas(Modifier.fillMaxWidth().fillMaxHeight().onSizeChanged { w = it.width; h = it.height }) {
        for (i in failBands.x0.indices) {
            val x0 = failBands.x0[i]; val x1 = maxOf(failBands.x1[i], x0 + 2f)
            drawRect(colors.bad.copy(alpha = 0.18f), Offset(x0, 0f), Size(x1 - x0, size.height))
            hatch(x0, x1, 0f, size.height, colors.bad.copy(alpha = 0.7f), hatchStep)
        }
        s.threshold?.let { th ->
            val range = (s.yMax - s.yMin).takeIf { it > 0f } ?: 1f
            val y = pad + (size.height - 2 * pad) * (1f - ((th - s.yMin) / range).coerceIn(0f, 1f))
            // tint the bad side of the threshold: above it when lower is better, below it otherwise
            if (s.higherIsBetter) drawRect(colors.bad.copy(alpha = 0.08f), Offset(0f, y), Size(size.width, size.height - y))
            else drawRect(colors.bad.copy(alpha = 0.08f), Offset(0f, 0f), Size(size.width, y))
            drawLine(thresholdColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = dotted)
        }
        path?.let { drawPath(it, s.color, style = stroke) }
        drawCursor(cursor.value, v, cursorColor)
    }
}

@Composable
private fun CategoricalPlot(s: CategoricalSeries, v: View, cursor: State<Long?>) {
    var w by remember { mutableIntStateOf(0) }
    val bands = remember(s, v, w) {
        buildBands(
            s.xs, s.xs.size, s.maxGapNs, v, w,
            same = { a, b -> s.values[a] == s.values[b] }, include = { true },
            color = { s.colorOf(s.values[it]) }, hatched = { s.values[it] in s.hatched },
        )
    }
    val density = LocalDensity.current
    val hatchStep = with(density) { 4.dp.toPx() }
    val vPad = with(density) { 6.dp.toPx() }
    val cursorColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().fillMaxHeight().onSizeChanged { w = it.width }) {
        val top = vPad
        val bottom = size.height - vPad
        for (i in bands.x0.indices) {
            val x0 = bands.x0[i]
            val x1 = maxOf(bands.x1[i], x0 + 1f)
            if (bands.hatched[i]) {
                drawRect(bands.colors[i].copy(alpha = 0.35f), Offset(x0, top), Size(x1 - x0, bottom - top))
                hatch(x0, x1, top, bottom, bands.colors[i], hatchStep)
            } else {
                drawRect(bands.colors[i], Offset(x0, top), Size(x1 - x0, bottom - top))
            }
        }
        drawCursor(cursor.value, v, cursorColor)
    }
}

@Immutable
private class Dots(val xs: FloatArray, val colors: Array<Color>, val radii: FloatArray)

@Composable
private fun EventLane(events: List<Pair<Long, String>>, v: View, cursor: State<Long?>, height: Dp) {
    val colors = LocalTracerColors.current
    val density = LocalDensity.current
    var w by remember { mutableIntStateOf(0) }
    val dots = remember(events, v, w, colors) {
        val inRange = events.filter { it.first in v.start..v.end }
        val span = v.span.toFloat()
        Dots(
            FloatArray(inRange.size) { (inRange[it].first - v.start) / span * w },
            Array(inRange.size) { severityColor(inRange[it].second, colors) },
            // size encodes severity too (not colour alone)
            FloatArray(inRange.size) { with(density) { (if (inRange[it].second == "critical") 4.5.dp else 3.5.dp).toPx() } },
        )
    }
    val cursorColor = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Gutter(stringResource(R.string.live_lane_events), events.isNotEmpty()) { null }
        Canvas(Modifier.weight(1f).fillMaxHeight().onSizeChanged { w = it.width }) {
            val cy = size.height / 2
            for (i in dots.xs.indices) drawCircle(dots.colors[i], dots.radii[i], Offset(dots.xs[i], cy))
            drawCursor(cursor.value, v, cursorColor)
        }
    }
}

/** Waypoint ticks: short vertical bars (shape differs from the event dots, not colour alone). */
@Composable
private fun WaypointLane(waypoints: List<Long>, v: View, cursor: State<Long?>, height: Dp) {
    var w by remember { mutableIntStateOf(0) }
    val xs = remember(waypoints, v, w) {
        val inRange = waypoints.filter { it in v.start..v.end }
        val span = v.span.toFloat()
        FloatArray(inRange.size) { (inRange[it] - v.start) / span * w }
    }
    val tick = LocalTracerColors.current.info
    val cursorColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Gutter(stringResource(R.string.live_lane_waypoints), true) { null }
        Canvas(Modifier.weight(1f).fillMaxHeight().onSizeChanged { w = it.width }) {
            val stroke = with(density) { 2.5.dp.toPx() }
            val top = size.height * 0.2f
            val bottom = size.height * 0.8f
            for (x in xs) drawLine(tick, Offset(x, top), Offset(x, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCursor(cursor.value, v, cursorColor)
        }
    }
}

@Composable
private fun Axis(v: View, originNs: Long) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    fun label(t: Long) = Fmt.clock((t - originNs) / 1_000_000)
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(GUTTER))
        Box(Modifier.weight(1f)) {
            Text(label(v.start), style = Mono.tiny, color = c, modifier = Modifier.align(Alignment.CenterStart))
            Text(label(v.start + v.span / 2), style = Mono.tiny, color = c, modifier = Modifier.align(Alignment.Center))
            Text(label(v.end), style = Mono.tiny, color = c, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}
