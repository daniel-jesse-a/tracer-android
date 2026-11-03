package app.tracer.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.tracer.core.quality.QualityScale
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.runtime.SatView
import app.tracer.ui.R
import app.tracer.ui.live.Fmt
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import kotlin.math.cos
import kotlin.math.sin

internal fun constellationLetter(c: String): String = when (c) {
    "GPS" -> "G"; "GLONASS" -> "R"; "GALILEO" -> "E"; "BEIDOU" -> "C"; "QZSS" -> "J"; "SBAS" -> "S"; "IRNSS" -> "I"; else -> "?"
}

/** GNSS tab: sky plot, counts per constellation, C/N0 per satellite (sorted). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GnssPanel(snap: LiveSnapshot) {
    val sats = snap.satellites
    val sorted = remember(sats) { sats.sortedByDescending { it.cn0 } }
    val counts = remember(sats) {
        sats.groupBy { it.constellation }.map { (c, l) -> Triple(c, l.count { it.used }, l.size) }.sortedByDescending { it.third }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.rowGap),
    ) {
        item(key = "sky") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.l)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.live_gnss_sky), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.live_gnss_used_visible, snap.usedSats, sats.size), style = Mono.small)
                    }
                    Spacer(Modifier.height(Space.s))
                    SkyPlot(sats, Modifier.fillMaxWidth(0.8f).aspectRatio(1f).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(Space.s))
                    Text(stringResource(R.string.live_gnss_legend), style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "counts") {
            FlowRow(Modifier.fillMaxWidth().padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                counts.forEach { (c, used, total) ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("${constellationLetter(c)} $c  $used/$total", style = Mono.tiny,
                            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
        item(key = "cn0head") {
            Text(stringResource(R.string.live_gnss_cn0), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Space.s, bottom = Space.s))
        }
        if (sorted.isEmpty()) {
            item(key = "none") {
                PanelRow(0, 1) {
                    Text(stringResource(R.string.live_gnss_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(sorted, key = { i, s -> "${s.constellation}:${s.svid}:$i" }) { i, s ->
            PanelRow(i, sorted.size) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${constellationLetter(s.constellation)}${s.svid}", style = Mono.small, modifier = Modifier.width(48.dp))
                    LevelBar(s.cn0.toInt(), 0, 50, QualityScale.cn0(s.cn0), Modifier.weight(1f))
                    Spacer(Modifier.width(Space.s))
                    Text(Fmt.d0(s.cn0), style = Mono.small, modifier = Modifier.width(28.dp))
                    Text(
                        if (s.used) stringResource(R.string.live_gnss_used) else stringResource(R.string.live_gnss_not_used),
                        style = Mono.tiny,
                        color = if (s.used) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(40.dp),
                    )
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(Space.l)) }
    }
}

/** Polar sky plot: centre = zenith, rim = horizon, north up; filled = used in fix, ring = visible only. */
@Composable
private fun SkyPlot(sats: List<SatView>, modifier: Modifier) {
    val colors = LocalTracerColors.current
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = minOf(cx, cy) - 14.dp.toPx()
        val gridStroke = Stroke(width = 1f)
        listOf(1f, 2f / 3f, 1f / 3f).forEach { f -> drawCircle(grid, r * f, Offset(cx, cy), style = gridStroke) }
        drawLine(grid, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1f)
        drawLine(grid, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1f)
        listOf("N" to Offset(cx, cy - r - 12.dp.toPx()), "E" to Offset(cx + r + 6.dp.toPx(), cy), "S" to Offset(cx, cy + r + 4.dp.toPx()), "W" to Offset(cx - r - 12.dp.toPx(), cy))
            .forEach { (s, o) ->
                val m = measurer.measure(s, Mono.tiny.copy(color = label))
                drawText(m, topLeft = Offset(o.x - m.size.width / 2f, o.y - m.size.height / 2f))
            }
        val dot = 5.dp.toPx()
        val ring = Stroke(width = 1.5.dp.toPx())
        sats.forEach { s ->
            val rr = r * ((90.0 - s.elevation.coerceIn(0.0, 90.0)) / 90.0).toFloat()
            val a = Math.toRadians(s.azimuth)
            val p = Offset(cx + rr * sin(a).toFloat(), cy - rr * cos(a).toFloat())
            val c = colors.quality(QualityScale.cn0(s.cn0))
            if (s.used) drawCircle(c, dot, p) else drawCircle(c, dot, p, style = ring)
            val m = measurer.measure(constellationLetter(s.constellation), Mono.tiny.copy(color = text))
            drawText(m, topLeft = Offset(p.x + dot + 1f, p.y - m.size.height / 2f))
        }
    }
}
