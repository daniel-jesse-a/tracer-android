package app.tracer.ui.report

import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Route-map inputs for Tracer report v1 (same rules as iOS src/report/v1/map.ts). Pure Kotlin. */
object ReportMap {
    /** Annotation text the New Test picker writes at run start: `destination:<station name>`. */
    const val DESTINATION_PREFIX = "destination:"

    private fun rad(d: Double) = d * Math.PI / 180
    fun haversineM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371008.8; val dLat = rad(b.first - a.first); val dLon = rad(b.second - a.second)
        val h = sin(dLat / 2).pow(2) + cos(rad(a.first)) * cos(rad(b.first)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /** The rail overlay with `destination` set: the picked station when there is one, else the station nearest the last fix. */
    fun railWithDestination(rail: Rail?, lastFix: Pair<Double, Double>?, picked: String?): Rail? {
        if (rail == null) return null
        var dest = picked?.let { p -> rail.stations.firstOrNull { it.name.equals(p, ignoreCase = true) } }
        if (dest == null && lastFix != null && rail.stations.isNotEmpty()) dest = rail.stations.minByOrNull { haversineM(lastFix, it.lat to it.lon) }
        return rail.copy(destination = dest?.let { Destination(it.name, it.lat, it.lon) })
    }

    fun lastFixOf(location: List<Row>): Pair<Double, Double>? =
        location.filter { ReportDataBuilder.num(it, "lat") != null && ReportDataBuilder.num(it, "lon") != null }
            .maxByOrNull { ReportDataBuilder.num(it, "t_utc_ms") ?: 0.0 }
            ?.let { ReportDataBuilder.num(it, "lat")!! to ReportDataBuilder.num(it, "lon")!! }

    /** Latest `destination:<name>` annotation of the run, if any. */
    fun pickedDestination(annotations: List<Row>): String? = annotations
        .filter { (ReportDataBuilder.str(it, "text") ?: "").startsWith(DESTINATION_PREFIX) }
        .maxByOrNull { ReportDataBuilder.num(it, "t_utc_ms") ?: 0.0 }
        ?.let { ReportDataBuilder.str(it, "text")!!.removePrefix(DESTINATION_PREFIX).trim().ifEmpty { null } }

    fun merc(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z)
        return (lon + 180) / 360 * n to (1 - asinh(tan(rad(lat))) / Math.PI) / 2 * n
    }

    data class Plan(val z: Int, val x0: Int, val y0: Int, val nx: Int, val ny: Int, val scale: Double, val crop: IntArray, val tiles: List<Pair<Int, Int>>)

    /**
     * Tiles covering the route box (fixes + stations + destination, 10 % margin) at the highest zoom whose long side
     * spans at most 2 × 1,400 tile pixels and ≤ [maxTiles] tiles; scaled so the long side is about [target] px, cropped.
     */
    fun planBasemap(points: List<Pair<Double, Double>>, target: Int = 1200, maxTiles: Int = 64): Plan? {
        val pts = points.filter { it.first.isFinite() && it.second.isFinite() && kotlin.math.abs(it.first) < 85 }
        if (pts.isEmpty()) return null
        var la0 = pts.minOf { it.first }; var la1 = pts.maxOf { it.first }; var lo0 = pts.minOf { it.second }; var lo1 = pts.maxOf { it.second }
        val minSpan = 0.01
        if (la1 - la0 < minSpan) { val c = (la0 + la1) / 2; la0 = c - minSpan / 2; la1 = c + minSpan / 2 }
        if (lo1 - lo0 < minSpan) { val c = (lo0 + lo1) / 2; lo0 = c - minSpan / 2; lo1 = c + minSpan / 2 }
        val padLa = (la1 - la0) * 0.1; val padLo = (lo1 - lo0) * 0.1
        la0 -= padLa; la1 += padLa; lo0 -= padLo; lo1 += padLo
        for (z in 18 downTo 2) {
            val (ax, ay) = merc(la1, lo0, z); val (bx, by) = merc(la0, lo1, z)
            val wPx = (bx - ax) * 256; val hPx = (by - ay) * 256
            if (max(wPx, hPx) > 2 * 1400) continue
            val x0 = floor(ax).toInt(); val y0 = floor(ay).toInt(); val x1 = floor(bx).toInt(); val y1 = floor(by).toInt()
            val nx = x1 - x0 + 1; val ny = y1 - y0 + 1
            if (nx * ny > maxTiles) continue
            val scale = min(1.0, target / max(wPx, hPx))
            val crop = intArrayOf(((ax - x0) * 256 * scale).roundToInt(), ((ay - y0) * 256 * scale).roundToInt(),
                max(1, (wPx * scale).roundToInt()), max(1, (hPx * scale).roundToInt()))
            val tiles = (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
            return Plan(z, x0, y0, nx, ny, scale, crop, tiles)
        }
        return null
    }

    fun framePoints(fixes: List<Pair<Double, Double>>, rail: Rail?): List<Pair<Double, Double>> =
        fixes + rail?.stations.orEmpty().filter { it.raw?.get("label")?.toString() != "false" }.map { it.lat to it.lon } +
            listOfNotNull(rail?.destination?.let { it.lat to it.lon })

    data class TileProvider(val id: String, val name: String, val url: String, val attribution: String)
    val TILE_PROVIDERS = listOf(
        TileProvider("esri-street", "Esri World Street Map", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
            "Basemap © Esri, HERE, Garmin, OpenStreetMap contributors"),
        TileProvider("esri-topo", "Esri World Topographic", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
            "Basemap © Esri, HERE, Garmin, FAO, NOAA, USGS, OpenStreetMap contributors"),
        TileProvider("osm", "OpenStreetMap", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors"),
        TileProvider("off", "Off (plain background)", "", ""),
    )
    const val DEFAULT_PROVIDER = "esri-street"
    fun provider(id: String?) = TILE_PROVIDERS.firstOrNull { it.id == id } ?: TILE_PROVIDERS.first { it.id == DEFAULT_PROVIDER }
    fun tileUrl(p: TileProvider, z: Int, x: Int, y: Int) = p.url.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
}
