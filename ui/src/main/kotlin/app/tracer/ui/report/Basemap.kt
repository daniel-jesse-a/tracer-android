package app.tracer.ui.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.tan

/** Web-Mercator raster basemap for the report's route map (contract: map.basemap). */
object Basemap {
    data class Provider(val id: String, val label: String, val urlTemplate: String, val attribution: String)

    val PROVIDERS = listOf(
        Provider("esri_street", "Esri World Street Map",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
            "Basemap © Esri, HERE, Garmin, OpenStreetMap contributors"),
        Provider("esri_imagery", "Esri World Imagery",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            "Imagery © Esri, Maxar, Earthstar Geographics"),
        Provider("carto_light", "CARTO Positron",
            "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
            "Basemap © CARTO, © OpenStreetMap contributors"),
        Provider("osm", "OpenStreetMap",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "© OpenStreetMap contributors"),
    )
    const val OFF = "off"
    fun provider(id: String?): Provider? = PROVIDERS.firstOrNull { it.id == id }

    private fun merc(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val n = Math.pow(2.0, z.toDouble())
        return (lon + 180) / 360 * n to (1 - asinh(tan(lat * PI / 180)) / PI) / 2 * n
    }

    /**
     * Fetches and stitches tiles covering [points] (lat, lon). Returns null when offline or when any
     * part fails, so the template falls back to a plain background. Target width ≈ 1,000–1,400 px.
     */
    suspend fun build(points: List<Pair<Double, Double>>, p: Provider, userAgent: String, timeoutMs: Long = 25_000): JsonObject? =
        withContext(Dispatchers.IO) {
            if (points.isEmpty()) return@withContext null
            withTimeoutOrNull(timeoutMs) { runCatching { stitch(points, p, userAgent) }.getOrNull() }
        }

    /** Never frame less than this (m): a building test still shows its streets and neighbours. */
    private const val MIN_FRAME_M = 300.0
    /** Highest zoom requested; street tiles above it are often missing ("map data not available"). */
    private const val MAX_Z = 18

    private suspend fun stitch(points: List<Pair<Double, Double>>, p: Provider, ua: String): JsonObject? {
        // try the best zoom, then step out if tiles are missing at that zoom for this area
        val best = pickZoom(points)
        val lowest = max(3, best - 3)
        for (z in best downTo lowest) stitchAt(points, z, p, ua, strict = z > lowest)?.let { return it }
        return null
    }

    /** Highest zoom ≤ [MAX_Z] at which the (padded) track fits in 2,800 px. */
    private fun pickZoom(points: List<Pair<Double, Double>>): Int {
        var z = MAX_Z
        while (z > 3) {
            val f = frame(points, z)
            if (max(f[1] - f[0], f[3] - f[2]) * 256 <= 2800) break
            z--
        }
        return z
    }

    /** x0, x1, y0, y1 in tile units at [z]: track bbox + 6 % padding, at least [MIN_FRAME_M] each way, centred. */
    private fun frame(points: List<Pair<Double, Double>>, z: Int): DoubleArray {
        val m = points.map { merc(it.first, it.second, z) }
        var x0 = m.minOf { it.first }; var x1 = m.maxOf { it.first }; var y0 = m.minOf { it.second }; var y1 = m.maxOf { it.second }
        val padX = (x1 - x0) * 0.06; val padY = (y1 - y0) * 0.06
        x0 -= padX; x1 += padX; y0 -= padY; y1 += padY
        val lat = points.map { it.first }.average()
        val mPerTile = 156_543.03 * Math.cos(Math.toRadians(lat)) / (1 shl z) * 256
        val minHalf = MIN_FRAME_M / mPerTile / 2
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val hx = max((x1 - x0) / 2, minHalf); val hy = max((y1 - y0) / 2, minHalf)
        return doubleArrayOf(cx - hx, cx + hx, cy - hy, cy + hy)
    }

    private suspend fun stitchAt(points: List<Pair<Double, Double>>, z: Int, p: Provider, ua: String, strict: Boolean): JsonObject? = coroutineScope {
        val f = frame(points, z)
        val x0 = f[0]; val x1 = f[1]; val y0 = f[2]; val y1 = f[3]
        val span = max(x1 - x0, y1 - y0) * 256
        // ~1,200 px on the long side; up to 2× enlargement when we had to step out a zoom level
        val scale = (1200.0 / span).coerceIn(0.35, 2.0)
        val tx0 = floor(x0).toInt(); val ty0 = floor(y0).toInt(); val tx1 = ceil(x1).toInt() - 1; val ty1 = ceil(y1).toInt() - 1
        val tiles = (tx1 - tx0 + 1) * (ty1 - ty0 + 1)
        if (tiles > 120) return@coroutineScope null
        val tilePx = 256 * scale
        val cw = ((tx1 - tx0 + 1) * tilePx).toInt(); val ch = ((ty1 - ty0 + 1) * tilePx).toInt()
        val canvasBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp); canvas.drawColor(Color.rgb(243, 241, 234))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val n = 1 shl z
        val jobs = (tx0..tx1).flatMap { x -> (ty0..ty1).map { y -> x to y } }.map { (x, y) ->
            async(Dispatchers.IO) {
                ensureActive()
                val url = p.urlTemplate.replace("{z}", "$z").replace("{x}", "${Math.floorMod(x, n)}").replace("{y}", "$y")
                runCatching {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8_000; c.readTimeout = 10_000; c.setRequestProperty("User-Agent", ua)
                    try {
                        if (c.responseCode != 200) null
                        else c.inputStream.use { BitmapFactory.decodeStream(it) }?.let { bmp -> if (strict && isPlaceholder(bmp)) { bmp.recycle(); null } else Triple(x, y, bmp) }
                    } finally { c.disconnect() }
                }.getOrNull()
            }
        }
        val got = jobs.awaitAll()
        // missing / "map data not yet available" tiles at this zoom: let the caller step out
        if (got.count { it == null } > tiles * if (strict) 0.15 else 0.5) { got.filterNotNull().forEach { it.third.recycle() }; canvasBmp.recycle(); return@coroutineScope null }
        for ((x, y, bmp) in got.filterNotNull()) {
            val l = ((x - tx0) * tilePx).toFloat(); val t = ((y - ty0) * tilePx).toFloat()
            canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(l, t, l + tilePx.toFloat(), t + tilePx.toFloat()), paint)
            bmp.recycle()
        }
        val cx = ((x0 - tx0) * tilePx).toInt().coerceAtLeast(0); val cy = ((y0 - ty0) * tilePx).toInt().coerceAtLeast(0)
        val w = ((x1 - x0) * tilePx).toInt().coerceAtMost(cw - cx); val h = ((y1 - y0) * tilePx).toInt().coerceAtMost(ch - cy)
        val crop = Bitmap.createBitmap(canvasBmp, cx, cy, w, h)
        canvasBmp.recycle()
        val bytes = ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.JPEG, 78, it) }.toByteArray()
        crop.recycle()
        buildJsonObject {
            put("image", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("z", z); put("x0", tx0); put("y0", ty0); put("scale", scale)
            putJsonArray("crop") { add(cx); add(cy); add(w); add(h) }
            put("attribution", p.attribution)
        }
    }

    /** Esri / CARTO "no data" tiles are a single flat colour: count distinct colours on a coarse grid. */
    private fun isPlaceholder(b: Bitmap): Boolean {
        val seen = HashSet<Int>()
        for (i in 0 until 8) for (j in 0 until 8) {
            seen += b.getPixel(i * (b.width - 1) / 7, j * (b.height - 1) / 7) and 0xF0F0F0
            if (seen.size > 2) return false
        }
        return true
    }
}
