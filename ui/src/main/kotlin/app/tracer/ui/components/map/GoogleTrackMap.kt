package app.tracer.ui.components.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.TracerExtraColors
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

private fun hex(c: Color): String = String.format("#%06X", c.toArgb() and 0xFFFFFF)

/** Map chrome from the design tokens (map / road / road2 / water). */
private fun mapStyleJson(c: TracerExtraColors, labelColor: Color): String = """[
 {"elementType":"geometry","stylers":[{"color":"${hex(c.map)}"}]},
 {"elementType":"labels.text.fill","stylers":[{"color":"${hex(labelColor)}"}]},
 {"elementType":"labels.text.stroke","stylers":[{"color":"${hex(c.map)}"}]},
 {"featureType":"road","elementType":"geometry","stylers":[{"color":"${hex(c.road)}"}]},
 {"featureType":"road.local","elementType":"geometry","stylers":[{"color":"${hex(c.road2)}"}]},
 {"featureType":"water","elementType":"geometry","stylers":[{"color":"${hex(c.water)}"}]},
 {"featureType":"poi","stylers":[{"visibility":"off"}]},
 {"featureType":"transit","elementType":"labels.icon","stylers":[{"visibility":"off"}]}
]"""

internal fun googleCameraFor(points: List<MapPoint>): CameraUpdate? {
    if (points.isEmpty()) return null
    var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
    points.forEach { p -> n = maxOf(n, p.lat); s = minOf(s, p.lat); e = maxOf(e, p.lon); w = minOf(w, p.lon) }
    return if (n - s < 1e-4 && e - w < 1e-4) {
        CameraUpdateFactory.newLatLngZoom(LatLng(points.last().lat, points.last().lon), 16f)
    } else {
        CameraUpdateFactory.newLatLngBounds(LatLngBounds(LatLng(s, w), LatLng(n, e)), 64)
    }
}

private class GSegment(val color: Color, val dotted: Boolean, val points: List<LatLng>)

@Composable
internal fun GoogleTrackMap(
    points: List<MapPoint>,
    layer: MapLayer,
    follow: Boolean,
    towers: List<TowerMarker>,
    taRing: TaRing?,
    events: List<EventMarker>,
    overlay: List<MapPoint>,
    cursor: MapPoint?,
    position: MapPoint?,
    selectedEventId: Long?,
    onEventClick: (Long) -> Unit,
) {
    val colors = LocalTracerColors.current
    val density = LocalDensity.current.density
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val onClick by rememberUpdatedState(onEventClick)
    val thinned = remember(points) { thinForMap(points) }
    val segments = remember(thinned, layer, colors) {
        segmentTrack(thinned, layer).map { seg ->
            GSegment(keyColor(seg.key, colors), TrackKeys.dotted(seg.key), seg.points.map { LatLng(it.lat, it.lon) })
        }
    }
    val overlayLatLng = remember(overlay) { thinForMap(overlay).map { LatLng(it.lat, it.lon) } }
    val camera = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    val last = points.lastOrNull()

    LaunchedEffect(loaded, follow, last?.lat, last?.lon) {
        if (!loaded || last == null) return@LaunchedEffect
        if (follow) {
            val zoom = if (camera.position.zoom < 12f) 16f else camera.position.zoom
            camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), zoom), 600)
        } else if (!fitted) {
            googleCameraFor(thinned)?.let { camera.move(it) }
            fitted = true
        }
    }

    val properties = remember(colors, labelColor) {
        MapProperties(mapType = MapType.NORMAL, mapStyleOptions = MapStyleOptions(mapStyleJson(colors, labelColor)))
    }
    val uiSettings = remember { MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false) }
    val cursorState = remember { MarkerState(cursor?.let { LatLng(it.lat, it.lon) } ?: LatLng(0.0, 0.0)) }
    LaunchedEffect(cursor) { cursor?.let { cursorState.position = LatLng(it.lat, it.lon) } }
    val positionState = remember { MarkerState(position?.let { LatLng(it.lat, it.lon) } ?: LatLng(0.0, 0.0)) }
    LaunchedEffect(position) { position?.let { positionState.position = LatLng(it.lat, it.lon) } }
    val ground = colors.map.toArgb()

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = camera,
        properties = properties,
        uiSettings = uiSettings,
        onMapLoaded = { loaded = true },
    ) {
        if (overlayLatLng.size >= 2) {
            Polyline(points = overlayLatLng, color = colors.linkedRun, width = 8f, pattern = listOf(Dash(24f), Gap(12f)))
        }
        // accuracy halo under the track
        segments.forEach { s -> if (s.points.size >= 2) Polyline(points = s.points, color = s.color.copy(alpha = 0.25f), width = 40f) }
        segments.forEach { s -> if (s.points.size >= 2 && !s.dotted) Polyline(points = s.points, color = androidx.compose.ui.graphics.Color.White, width = 22f) }
        segments.forEach { s ->
            if (s.points.size >= 2) {
                Polyline(points = s.points, color = s.color, width = 15f, pattern = if (s.dotted) listOf(Dot(), Gap(12f)) else null)
            }
        }
        taRing?.let { r ->
            Circle(
                center = LatLng(r.lat, r.lon), radius = r.radiusM,
                strokeColor = colors.info, strokeWidth = 4f, fillColor = Color.Transparent,
                strokePattern = listOf(Dash(20f), Gap(12f)),
            )
            if (r.label.isNotEmpty()) {
                key(r.label, r.lat, r.lon) {
                    val (la, lo) = remember { taRingLabelPoint(r) }
                    val icon = remember { BitmapDescriptorFactory.fromBitmap(MapBitmaps.label(density, colors.info.toArgb(), ground, r.label)) }
                    Marker(state = remember { MarkerState(LatLng(la, lo)) }, icon = icon, anchor = Offset(0.5f, 0.5f), zIndex = 1f)
                }
            }
        }
        towers.forEach { t ->
            key(t) {
                val icon = remember { BitmapDescriptorFactory.fromBitmap(MapBitmaps.tower(density, colors.info.toArgb(), labelColor.toArgb(), ground, t.label)) }
                // the triangle's apex sits at ~ (tri/2) / width; bottom anchor keeps it on the tower position
                Marker(state = remember { MarkerState(LatLng(t.lat, t.lon)) }, title = t.label, icon = icon, anchor = Offset(0.15f, 1f), zIndex = 1f)
            }
        }
        events.forEach { e ->
            val selected = e.id == selectedEventId
            key(e.id, selected) {
                val icon = remember {
                    BitmapDescriptorFactory.fromBitmap(
                        MapBitmaps.pin(density, MapBitmaps.pinColor(e.severity, colors).toArgb(), ground, selected, primary.toArgb()),
                    )
                }
                Marker(
                    state = remember { MarkerState(LatLng(e.lat, e.lon)) },
                    title = e.type, icon = icon, anchor = Offset(0.5f, 0.5f), zIndex = 2f,
                    onClick = { onClick(e.id); true },
                )
            }
        }
        if (position != null) {
            position.hAccM?.takeIf { it > 0 }?.let { acc ->
                Circle(center = LatLng(position.lat, position.lon), radius = acc, fillColor = colors.good.copy(alpha = 0.15f), strokeColor = Color.Transparent, strokeWidth = 0f)
            }
            val icon = remember { BitmapDescriptorFactory.fromBitmap(MapBitmaps.position(density, colors.good.toArgb())) }
            Marker(state = positionState, icon = icon, anchor = Offset(0.5f, 0.5f), zIndex = 3f, flat = true)
        }
        if (cursor != null) {
            val icon = remember { BitmapDescriptorFactory.fromBitmap(MapBitmaps.pin(density, primary.toArgb(), ground, true, Color.White.toArgb())) }
            Marker(state = cursorState, icon = icon, anchor = Offset(0.5f, 0.5f), zIndex = 4f)
        }
    }
}
