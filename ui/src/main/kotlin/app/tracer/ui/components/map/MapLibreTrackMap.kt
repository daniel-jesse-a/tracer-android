package app.tracer.ui.components.map

import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.TracerExtraColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val SRC_TRACK = "tracer-track"
private const val SRC_OVERLAY = "tracer-overlay"
private const val SRC_TA = "tracer-ta"
private const val SRC_LABELS = "tracer-labels"
private const val SRC_EVENTS = "tracer-events"
private const val SRC_POSITION = "tracer-position"
private const val SRC_CURSOR = "tracer-cursor"
private const val LAYER_PINS = "tracer-pins"
private const val LAYER_SELECTED = "tracer-pin-selected"
private const val LAYER_CLUSTERS = "tracer-clusters"
private const val IMG_POSITION = "tracer-img-position"

private val EMPTY_FC: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

/** Map + sources once the style has loaded; remembers what was pushed last (identity). */
private class MapLibreHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var selectedLayer: CircleLayer? = null
    private var selectedId: Long? = Long.MIN_VALUE
    val sources = HashMap<String, GeoJsonSource>()
    private val applied = HashMap<String, FeatureCollection>()
    private val images = HashSet<String>()

    fun push(id: String, fc: FeatureCollection) {
        val src = sources[id] ?: return
        if (applied[id] === fc) return
        src.setGeoJson(fc)
        applied[id] = fc
    }

    fun ensureImage(id: String, make: () -> android.graphics.Bitmap) {
        val s = style ?: return
        if (images.add(id)) s.addImage(id, make())
    }

    fun select(id: Long?) {
        if (id == selectedId) return
        selectedId = id
        selectedLayer?.setFilter(Expression.eq(Expression.get("id"), Expression.literal(id ?: Long.MIN_VALUE)))
    }
}

private fun pt(lat: Double, lon: Double): Point = Point.fromLngLat(lon, lat)

private fun buildTrackFc(points: List<MapPoint>, layer: MapLayer): FeatureCollection =
    FeatureCollection.fromFeatures(
        segmentTrack(thinForMap(points), layer).filter { it.points.size >= 2 }.map { seg ->
            Feature.fromGeometry(LineString.fromLngLats(seg.points.map { pt(it.lat, it.lon) })).also {
                it.addStringProperty("q", seg.key)
                it.addBooleanProperty("d", TrackKeys.dotted(seg.key))
            }
        },
    )

private fun buildLineFc(points: List<MapPoint>): FeatureCollection =
    if (points.size < 2) EMPTY_FC
    else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(thinForMap(points).map { pt(it.lat, it.lon) })))

private fun colorMatch(colors: TracerExtraColors): Expression {
    val stops = TrackKeys.allKeys.map { k -> Expression.stop(k, Expression.color(keyColor(k, colors).toArgb())) }
    return Expression.match(Expression.get("q"), Expression.color(colors.noData.toArgb()), *stops.toTypedArray())
}

private fun installLayers(style: Style, holder: MapLibreHolder, colors: TracerExtraColors, primary: Int, density: Float) {
    listOf(SRC_OVERLAY, SRC_TRACK, SRC_TA, SRC_LABELS, SRC_POSITION, SRC_CURSOR).forEach { id ->
        val src = GeoJsonSource(id, EMPTY_FC)
        style.addSource(src)
        holder.sources[id] = src
    }
    val events = GeoJsonSource(SRC_EVENTS, EMPTY_FC, GeoJsonOptions().withCluster(true).withClusterRadius(40).withClusterMaxZoom(14))
    style.addSource(events)
    holder.sources[SRC_EVENTS] = events

    val ground = colors.map.toArgb()
    val dotted = Expression.eq(Expression.get("d"), Expression.literal(true))
    val solid = Expression.eq(Expression.get("d"), Expression.literal(false))

    style.addLayer(
        LineLayer("$SRC_OVERLAY-line", SRC_OVERLAY).withProperties(
            PropertyFactory.lineColor(colors.linkedRun.toArgb()),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
        ),
    )
    style.addLayer(
        LineLayer("$SRC_TRACK-halo", SRC_TRACK).withProperties(
            PropertyFactory.lineColor(colorMatch(colors)),
            PropertyFactory.lineWidth(13f),
            PropertyFactory.lineOpacity(0.22f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("$SRC_TRACK-solid", SRC_TRACK).withProperties(
            PropertyFactory.lineColor(colorMatch(colors)),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(solid),
    )
    style.addLayer(
        LineLayer("$SRC_TRACK-dotted", SRC_TRACK).withProperties(
            PropertyFactory.lineColor(colorMatch(colors)),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineDasharray(arrayOf(0.6f, 1.6f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ).withFilter(dotted),
    )
    style.addLayer(
        LineLayer("$SRC_TA-line", SRC_TA).withProperties(
            PropertyFactory.lineColor(colors.info.toArgb()),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
        ),
    )
    // tower triangle + TA label are bitmaps keyed by the feature's "img" property
    style.addLayer(
        SymbolLayer("$SRC_LABELS-symbols", SRC_LABELS).withProperties(
            PropertyFactory.iconImage(Expression.get("img")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Expression.get("anchor")),
        ),
    )
    // event pins: 16 dp dot + 2.5 dp ground halo; clusters as numbered circles
    style.addLayer(
        CircleLayer(LAYER_CLUSTERS, SRC_EVENTS).withProperties(
            PropertyFactory.circleRadius(12f),
            PropertyFactory.circleColor(ground),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(1.5f),
        ).withFilter(Expression.has("point_count")),
    )
    style.addLayer(
        SymbolLayer("$LAYER_CLUSTERS-count", SRC_EVENTS).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor(android.graphics.Color.WHITE),
            PropertyFactory.textAllowOverlap(true),
        ).withFilter(Expression.has("point_count")),
    )
    style.addLayer(
        CircleLayer(LAYER_PINS, SRC_EVENTS).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("sev"), Expression.color(android.graphics.Color.WHITE),
                    Expression.stop("critical", Expression.color(colors.bad.toArgb())),
                    Expression.stop("warning", Expression.color(colors.fair.toArgb())),
                    Expression.stop("waypoint", Expression.color(colors.info.toArgb())),
                ),
            ),
            PropertyFactory.circleStrokeColor(ground),
            PropertyFactory.circleStrokeWidth(2.5f),
        ).withFilter(Expression.not(Expression.has("point_count"))),
    )
    val selected = CircleLayer(LAYER_SELECTED, SRC_EVENTS).withProperties(
        PropertyFactory.circleRadius(14f),
        PropertyFactory.circleColor(android.graphics.Color.TRANSPARENT),
        PropertyFactory.circleStrokeColor(primary),
        PropertyFactory.circleStrokeWidth(3f),
    ).withFilter(Expression.eq(Expression.get("id"), Expression.literal(Long.MIN_VALUE)))
    style.addLayer(selected)
    holder.selectedLayer = selected

    style.addImage(IMG_POSITION, MapBitmaps.position(density, colors.good.toArgb()))
    style.addLayer(
        SymbolLayer("$SRC_POSITION-dot", SRC_POSITION).withProperties(
            PropertyFactory.iconImage(IMG_POSITION),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
    style.addLayer(
        CircleLayer("$SRC_CURSOR-dot", SRC_CURSOR).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(primary),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

@Composable
internal fun MapLibreTrackMap(
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
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = LocalTracerColors.current
    val density = LocalDensity.current.density
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val onClick by rememberUpdatedState(onEventClick)
    val holder = remember { MapLibreHolder() }
    var styleReady by remember { mutableStateOf(false) }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(MapBackend.MAPLIBRE_STYLE_URL)) { style ->
                holder.style = style
                installLayers(style, holder, colors, primary, density)
                holder.map = map
                map.addOnMapClickListener { latLng ->
                    val sp = map.projection.toScreenLocation(latLng)
                    val r = 22f * density // 44 dp tap target
                    val box = RectF(sp.x - r, sp.y - r, sp.x + r, sp.y + r)
                    val pin = map.queryRenderedFeatures(box, LAYER_PINS).firstOrNull { it.hasProperty("id") }
                    if (pin != null) {
                        onClick(pin.getNumberProperty("id").toLong())
                        true
                    } else if (map.queryRenderedFeatures(box, LAYER_CLUSTERS).isNotEmpty()) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, map.cameraPosition.zoom + 2.0), 400)
                        true
                    } else {
                        false
                    }
                }
                styleReady = true
            }
        }
    }

    val trackFc = remember(points, layer) { buildTrackFc(points, layer) }
    val overlayFc = remember(overlay) { buildLineFc(overlay) }
    val taFc = remember(taRing) {
        taRing?.let { r -> FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(taRingPolygon(r).map { pt(it.first, it.second) }))) }
            ?: EMPTY_FC
    }
    val labelsFc = remember(towers, taRing) {
        val fs = ArrayList<Feature>()
        towers.forEach { t ->
            fs += Feature.fromGeometry(pt(t.lat, t.lon)).also {
                it.addStringProperty("img", "tower:${t.label}")
                it.addStringProperty("anchor", Property.ICON_ANCHOR_BOTTOM_LEFT)
            }
        }
        taRing?.takeIf { it.label.isNotEmpty() }?.let { r ->
            val (la, lo) = taRingLabelPoint(r)
            fs += Feature.fromGeometry(pt(la, lo)).also {
                it.addStringProperty("img", "ta:${r.label}")
                it.addStringProperty("anchor", Property.ICON_ANCHOR_CENTER)
            }
        }
        FeatureCollection.fromFeatures(fs)
    }
    val eventsFc = remember(events) {
        FeatureCollection.fromFeatures(events.map { e ->
            Feature.fromGeometry(pt(e.lat, e.lon)).also {
                it.addNumberProperty("id", e.id)
                it.addStringProperty("sev", e.severity)
            }
        })
    }
    val positionFc = remember(position) { position?.let { FeatureCollection.fromFeature(Feature.fromGeometry(pt(it.lat, it.lon))) } ?: EMPTY_FC }
    val cursorFc = remember(cursor) { cursor?.let { FeatureCollection.fromFeature(Feature.fromGeometry(pt(it.lat, it.lon))) } ?: EMPTY_FC }

    // Camera: follow newest point, or fit the whole track once.
    var fitted by remember { mutableStateOf(false) }
    val last = points.lastOrNull()
    LaunchedEffect(styleReady, follow, last?.lat, last?.lon) {
        val map = holder.map
        if (!styleReady || map == null || last == null) return@LaunchedEffect
        if (follow) {
            val z = map.cameraPosition.zoom.let { if (it < 12.0) 16.0 else it }
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), z), 600)
        } else if (!fitted) {
            var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
            points.forEach { p -> n = maxOf(n, p.lat); s = minOf(s, p.lat); e = maxOf(e, p.lon); w = minOf(w, p.lon) }
            if (n - s < 1e-4 && e - w < 1e-4) map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lon), 16.0))
            else map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(n, e, s, w), 64))
            fitted = true
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = {
            if (styleReady) {
                val ground = colors.map.toArgb()
                towers.forEach { t ->
                    holder.ensureImage("tower:${t.label}") { MapBitmaps.tower(density, colors.info.toArgb(), labelColor, ground, t.label) }
                }
                taRing?.takeIf { it.label.isNotEmpty() }?.let { r ->
                    holder.ensureImage("ta:${r.label}") { MapBitmaps.label(density, colors.info.toArgb(), ground, r.label) }
                }
                holder.push(SRC_TRACK, trackFc)
                holder.push(SRC_OVERLAY, overlayFc)
                holder.push(SRC_TA, taFc)
                holder.push(SRC_LABELS, labelsFc)
                holder.push(SRC_EVENTS, eventsFc)
                holder.push(SRC_POSITION, positionFc)
                holder.push(SRC_CURSOR, cursorFc)
                holder.select(selectedEventId)
            }
        },
    )
}
