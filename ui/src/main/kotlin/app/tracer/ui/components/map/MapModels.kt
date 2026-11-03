package app.tracer.ui.components.map

import app.tracer.core.quality.ConnType
import app.tracer.core.quality.QualityLayer
import app.tracer.core.quality.QualityLevel
import app.tracer.core.quality.QualityScale
import app.tracer.core.runtime.TrackPoint
import app.tracer.core.util.Geo

/** One track vertex with every metric a [MapLayer] can colour by. */
data class MapPoint(
    val lat: Double,
    val lon: Double,
    val tMonoNs: Long,
    val rsrp: Int? = null,
    val sinr: Int? = null,
    val latencyMs: Double? = null,
    val failed: Boolean = false,
    val wifiRssi: Int? = null,
    val rat: String? = null,
    val usedSats: Int? = null,
    /** network_path.primary_interface (wifi | cellular | …) for the TYPE layer */
    val iface: String? = null,
    val mbps: Double? = null,
    val hAccM: Double? = null,
)

/** Serving tower, drawn as an outlined triangle with [label] (e.g. "PCI 214"). */
data class TowerMarker(val lat: Double, val lon: Double, val label: String, val rangeM: Double? = null)

/** Timing-advance ring: dashed circle of [radiusM] around ([lat], [lon]), labelled e.g. "TA 12 · 937 m". */
data class TaRing(val lat: Double, val lon: Double, val radiusM: Double, val label: String = "")

/** Event pin; [id] is passed back through onEventClick. */
data class EventMarker(val id: Long, val lat: Double, val lon: Double, val severity: String, val type: String)

data class TrackSegment(val key: String, val points: List<MapPoint>)

/** Track colouring layers (Replay chips: quality · type · latency · throughput · GPS). */
enum class MapLayer { QUALITY, SINR, TYPE, LATENCY, THROUGHPUT, WIFI, GPS;

    companion object {
        fun of(q: QualityLayer): MapLayer = when (q) {
            QualityLayer.RSRP -> QUALITY
            QualityLayer.SINR -> SINR
            QualityLayer.LATENCY -> LATENCY
            QualityLayer.WIFI_RSSI -> WIFI
            QualityLayer.RAT -> TYPE
            QualityLayer.SATELLITES -> GPS
        }
    }
}

fun TrackPoint.toMapPoint(): MapPoint = MapPoint(lat, lon, tMonoNs, rsrp, sinr, latencyMs, failed, wifiRssi, rat, usedSats)

/** Categorical colour keys. Quality keys = QualityLevel.name; TYPE keys = "c_" + ConnType.name. */
object TrackKeys {
    private const val CONN_PREFIX = "c_"

    /** Used-satellite count → quality (≥12 excellent, ≥8 good, ≥5 fair, ≥3 poor, else no service). */
    fun satellites(used: Int?): QualityLevel = when {
        used == null -> QualityLevel.NONE
        used >= 12 -> QualityLevel.EXCELLENT
        used >= 8 -> QualityLevel.GOOD
        used >= 5 -> QualityLevel.FAIR
        used >= 3 -> QualityLevel.POOR
        else -> QualityLevel.BAD
    }

    /** Downlink Mb/s → quality (≥50 / ≥20 / ≥5 / ≥1 / below). */
    fun throughput(mbps: Double?): QualityLevel = when {
        mbps == null -> QualityLevel.NONE
        mbps >= 50 -> QualityLevel.EXCELLENT
        mbps >= 20 -> QualityLevel.GOOD
        mbps >= 5 -> QualityLevel.FAIR
        mbps >= 1 -> QualityLevel.POOR
        else -> QualityLevel.BAD
    }

    fun level(p: MapPoint, layer: MapLayer): QualityLevel? = when (layer) {
        MapLayer.QUALITY -> QualityScale.rsrp(p.rsrp)
        MapLayer.SINR -> QualityScale.sinr(p.sinr)
        MapLayer.LATENCY -> QualityScale.latency(p.latencyMs, p.failed)
        MapLayer.THROUGHPUT -> throughput(p.mbps)
        MapLayer.WIFI -> QualityScale.wifiRssi(p.wifiRssi)
        MapLayer.GPS -> satellites(p.usedSats)
        MapLayer.TYPE -> null
    }

    fun key(p: MapPoint, layer: MapLayer): String =
        if (layer == MapLayer.TYPE) CONN_PREFIX + ConnType.of(p.rat, p.iface).name else level(p, layer)!!.name

    /** All keys a layer can produce, in legend order. */
    fun keys(layer: MapLayer): List<String> =
        if (layer == MapLayer.TYPE) ConnType.entries.map { CONN_PREFIX + it.name } else QualityLevel.entries.map { it.name }

    /** Every key of every layer (MapLibre colour expression). */
    val allKeys: List<String> = QualityLevel.entries.map { it.name } + ConnType.entries.map { CONN_PREFIX + it.name }

    fun quality(key: String): QualityLevel? = QualityLevel.entries.firstOrNull { it.name == key }
    fun conn(key: String): ConnType? =
        if (key.startsWith(CONN_PREFIX)) ConnType.entries.firstOrNull { it.name == key.removePrefix(CONN_PREFIX) } else null

    /** "No service"/"No data"/"None" keys are drawn dotted so they never rely on hue. */
    fun dotted(key: String): Boolean = quality(key)?.dotted ?: conn(key)?.dotted ?: false

    /** Short legend label: Exc · Good · Fair · Poor · None / 5G SA · LTE … */
    fun label(key: String): String = conn(key)?.label ?: when (quality(key)) {
        QualityLevel.EXCELLENT -> "Exc"
        QualityLevel.GOOD -> "Good"
        QualityLevel.FAIR -> "Fair"
        QualityLevel.POOR -> "Poor"
        QualityLevel.BAD -> "No svc"
        QualityLevel.NONE, null -> "None"
    }
}

/**
 * Groups consecutive points with the same colour key. Each segment after the first starts with the
 * previous segment's last point so the drawn line has no gaps at colour changes.
 */
fun segmentTrack(points: List<MapPoint>, layer: MapLayer): List<TrackSegment> {
    if (points.isEmpty()) return emptyList()
    val out = ArrayList<TrackSegment>()
    var curKey = TrackKeys.key(points[0], layer)
    var cur = ArrayList<MapPoint>().apply { add(points[0]) }
    for (i in 1 until points.size) {
        val p = points[i]
        val k = TrackKeys.key(p, layer)
        if (k == curKey) {
            cur.add(p)
        } else {
            cur.add(p) // close the segment at the boundary point
            out += TrackSegment(curKey, cur)
            curKey = k
            cur = ArrayList<MapPoint>().apply { add(p) }
        }
    }
    if (cur.size >= 2 || out.isEmpty()) out += TrackSegment(curKey, cur)
    return out
}

/** Keeps every k-th point (plus the last) so map geometry stays ≤ [max] vertices. */
fun thinForMap(points: List<MapPoint>, max: Int = 4_000): List<MapPoint> {
    if (points.size <= max || max < 2) return points
    val step = (points.size + max - 1) / max
    val out = ArrayList<MapPoint>(max + 1)
    var i = 0
    while (i < points.size) { out += points[i]; i += step }
    if (out.last() !== points.last()) out += points.last()
    return out
}

/** Closed polygon (lat, lon) approximating a TA ring. */
fun taRingPolygon(ring: TaRing, steps: Int = 72): List<Pair<Double, Double>> =
    (0..steps).map { i -> Geo.destination(ring.lat, ring.lon, i * 360.0 / steps, ring.radiusM) }

/** Where the TA ring label sits (top of the ring). */
fun taRingLabelPoint(ring: TaRing): Pair<Double, Double> = Geo.destination(ring.lat, ring.lon, 0.0, ring.radiusM)

/** Nearest point at or before [tMonoNs] (binary search on ascending time), or the first point. */
fun pointAt(points: List<MapPoint>, tMonoNs: Long): MapPoint? {
    if (points.isEmpty()) return null
    var lo = 0
    var hi = points.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].tMonoNs <= tMonoNs) lo = mid + 1 else hi = mid
    }
    return points[(lo - 1).coerceAtLeast(0)]
}

/** "TA 12 · 937 m" */
fun taLabel(ta: Int, distanceM: Double): String = "TA $ta · ${Math.round(distanceM)} m"
