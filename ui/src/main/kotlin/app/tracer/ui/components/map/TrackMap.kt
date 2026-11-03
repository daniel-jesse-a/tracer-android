package app.tracer.ui.components.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tracer.core.quality.QualityLevel
import app.tracer.ui.R
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.TracerExtraColors

/**
 * Track map shared by Live Run, Summary, Replay and Worst segments. Picks Google Maps or MapLibre
 * at runtime (see [MapBackend]). Callers should pass new lists at most ~1 Hz.
 *
 * @param follow keep the camera centred on the newest point; otherwise fit the track once
 * @param overlay linked (paired iPhone) run, drawn dashed in LocalTracerColors.linkedRun
 * @param cursor replay cursor position
 * @param showPosition draw the blue current-position dot (with accuracy halo) at the newest point
 */
@Composable
fun TrackMap(
    points: List<MapPoint>,
    layer: MapLayer,
    modifier: Modifier = Modifier,
    follow: Boolean = false,
    towers: List<TowerMarker> = emptyList(),
    taRing: TaRing? = null,
    events: List<EventMarker> = emptyList(),
    overlay: List<MapPoint> = emptyList(),
    cursor: MapPoint? = null,
    onEventClick: (Long) -> Unit = {},
    selectedEventId: Long? = null,
    showPosition: Boolean = follow,
    showLegend: Boolean = true,
) {
    val backend = rememberMapBackend()
    Box(modifier.clip(MaterialTheme.shapes.medium).background(LocalTracerColors.current.map)) {
        val position = if (showPosition) points.lastOrNull() else null
        when (backend) {
            MapBackend.GOOGLE -> GoogleTrackMap(points, layer, follow, towers, taRing, events, overlay, cursor, position, selectedEventId, onEventClick)
            MapBackend.MAPLIBRE -> MapLibreTrackMap(points, layer, follow, towers, taRing, events, overlay, cursor, position, selectedEventId, onEventClick)
        }
        if (showLegend) {
            TrackLegend(layer, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
    }
}

internal fun keyColor(key: String, c: TracerExtraColors): Color =
    TrackKeys.conn(key)?.let { c.conn(it) } ?: c.quality(TrackKeys.quality(key) ?: QualityLevel.NONE)

/** "Quality ■Exc ■Good ■Fair ■Poor ┅None" — colour + text, dotted glyph for no-data keys. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackLegend(layer: MapLayer, modifier: Modifier) {
    val colors = LocalTracerColors.current
    val keys = TrackKeys.keys(layer)
    FlowRow(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.scrim)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(mapLayerTitle(layer), style = Mono.tiny, color = MaterialTheme.colorScheme.onSurface)
        keys.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (TrackKeys.dotted(k)) "┅" else "■", style = Mono.tiny, color = keyColor(k, colors))
                Spacer(Modifier.width(2.dp))
                Text(TrackKeys.label(k), style = Mono.tiny, color = keyColor(k, colors))
            }
        }
    }
}

@Composable
fun mapLayerTitle(l: MapLayer): String = when (l) {
    MapLayer.QUALITY -> stringResource(R.string.live_layer_quality)
    MapLayer.SINR -> stringResource(R.string.live_layer_sinr)
    MapLayer.TYPE -> stringResource(R.string.live_layer_type)
    MapLayer.LATENCY -> stringResource(R.string.live_layer_latency)
    MapLayer.THROUGHPUT -> stringResource(R.string.live_layer_throughput)
    MapLayer.WIFI -> stringResource(R.string.live_layer_wifi)
    MapLayer.GPS -> stringResource(R.string.live_layer_gps)
}
