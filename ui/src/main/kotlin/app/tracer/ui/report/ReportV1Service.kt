package app.tracer.ui.report

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.print.PdfPrintBridge
import android.print.PrintAttributes
import android.util.Base64
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Table
import app.tracer.core.runtime.RunRepository
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Tracer report v1 export: run tables (from the local DB; the raw run stays on the phone and is never exported) →
 * ReportData → the bundled template → a single A4-landscape PDF, rendered by an off-screen WebView and written
 * through the print pipeline (no print dialog). Works offline; only the optional basemap needs the network.
 * Cancel by cancelling the calling coroutine.
 */
@Singleton
class ReportV1Service @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runs: RunRepository,
    private val settings: SettingsRepository,
) {
    /** Stations of the internal ERGT rail asset (empty when the asset is not in this build). */
    fun stations(): List<Station> = rail()?.stations.orEmpty()

    fun rail(): Rail? = runCatching {
        context.assets.open(ReportRender.RAIL_ASSET).bufferedReader().use { Rail.parse(Json.parseToJsonElement(it.readText()) as JsonObject) }
    }.getOrNull()

    /** Builds the PDF into [dir]; [progress] gets a status line and 0..1. */
    suspend fun pdf(run: RunRecord, dir: File, progress: (String, Float) -> Unit = { _, _ -> }): File {
        progress("Reading the run…", 0.05f)
        val (tables, annotations) = withContext(Dispatchers.IO) { loadTables(run) }
        coroutineContext.ensureActive()
        progress("Working out the report…", 0.35f)
        val tz = tzOffsetMin(run)
        val rail = ReportMap.railWithDestination(rail(), ReportMap.lastFixOf(tables.location), ReportMap.pickedDestination(annotations))
        val reportRun = ReportRun(run.runId, run.name, run.startedAt, run.endedAt, run.devicePlatform, run.deviceModel, run.osVersion, run.appVersion,
            run.timeZone, run.completenessJson?.let { raw -> runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) } })
        var data = withContext(Dispatchers.Default) { ReportDataBuilder.build(reportRun, tables, rail, null, tz) }
        coroutineContext.ensureActive()
        progress("Fetching the map…", 0.5f)
        val provider = ReportMap.provider(settings.reportTileProvider.first())
        val fixes = (data["track"] as JsonObject)["fixes"] as JsonArray
        val points = fixes.map { f -> (f as JsonArray).let { (it[0] as JsonPrimitive).content.toDouble() to (it[1] as JsonPrimitive).content.toDouble() } }
        val basemap = if (provider.url.isEmpty() || points.isEmpty() || !online()) null
            else runCatching { fetchBasemap(provider, ReportMap.framePoints(points, rail)) }.onFailure { Log.i(TAG, "basemap skipped: ${it.message}") }.getOrNull()
        if (basemap != null) data = withContext(Dispatchers.Default) { ReportDataBuilder.build(reportRun, tables, rail, basemap, tz) }
        coroutineContext.ensureActive()
        progress("Making the PDF…", 0.75f)
        val html = withContext(Dispatchers.IO) { ReportRender.inject(context.assets.open(ReportRender.TEMPLATE_ASSET).bufferedReader().use { it.readText() }, data) }
        val out = File(dir.apply { mkdirs() }, ReportRender.fileName(run.site, run.mode, run.testNumber, run.startedAt, run.runId, tz))
        val part = File(dir, "${out.name}.part")
        try {
            render(html, part)
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
        } finally { part.delete() }
        progress("Ready", 1f)
        return out
    }

    private fun tzOffsetMin(run: RunRecord): Int = runCatching {
        val zone = run.timeZone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        zone.rules.getOffset(Instant.ofEpochMilli(run.startedAt)).totalSeconds / 60
    }.getOrDefault(0)

    /** Rows keyed by column name, in DB order; radio limited to the busiest SIM and mapped to template RAT keys. */
    private suspend fun loadTables(run: RunRecord): Pair<ReportTables, List<Row>> {
        suspend fun rows(table: Table): List<Row> {
            val cols = Schema.of(table).columnNames
            return runs.samples(run.runId, table).map { s -> cols.zip(SampleCodec.encode(s)).toMap() }
        }
        val events = runs.events(run.runId).map { (id, e) -> mapOf<String, Any?>("id" to id) + Schema.of(Table.EVENT).columnNames.zip(SampleCodec.encode(e)) }
        val radioAll = rows(Table.RADIO)
        val sim = radioAll.groupingBy { it["service_id"] }.eachCount().maxByOrNull { it.value }?.key
        val radio = radioAll.filter { it["service_id"] == sim }.map { it + ("rat" to ReportDataBuilder.templateRat(it["rat"] as String?)) }
        return ReportTables(rows(Table.PROBE), events, rows(Table.LOCATION), radio, rows(Table.NETWORK_PATH), rows(Table.WIFI_CONNECTION),
            rows(Table.DEVICE), rows(Table.X_COLLECTOR_LOG)) to rows(Table.ANNOTATION)
    }

    private fun online(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** Web-Mercator tiles covering the route, stitched, scaled and cropped; any failed tile → no basemap (null). */
    private suspend fun fetchBasemap(provider: ReportMap.TileProvider, points: List<Pair<Double, Double>>): JsonObject? {
        val plan = ReportMap.planBasemap(points) ?: return null
        val gate = Semaphore(6)
        val tiles = withContext(Dispatchers.IO) {
            coroutineScope {
                plan.tiles.map { (x, y) -> async { gate.withPermit { x to y to fetchTile(ReportMap.tileUrl(provider, plan.z, x, y)) } } }.awaitAll()
            }
        }
        return withContext(Dispatchers.Default) {
            val s = plan.scale.toFloat(); val px = 256f * s
            val full = Bitmap.createBitmap((plan.nx * px).roundToInt().coerceAtLeast(1), (plan.ny * px).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full).apply { drawColor(Color.WHITE) }
            for ((xy, bmp) in tiles) {
                val l = (xy.first - plan.x0) * px; val t = (xy.second - plan.y0) * px
                canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(l, t, l + px, t + px), null)
                bmp.recycle()
            }
            val c = plan.crop
            val cw = c[2].coerceAtMost(full.width - c[0]); val ch = c[3].coerceAtMost(full.height - c[1])
            val cropped = Bitmap.createBitmap(full, c[0], c[1], cw, ch)
            val jpeg = ByteArrayOutputStream().also { cropped.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            full.recycle(); if (cropped !== full) cropped.recycle()
            JsonObject(linkedMapOf(
                "image" to JsonPrimitive("data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                "z" to JsonPrimitive(plan.z), "x0" to JsonPrimitive(plan.x0), "y0" to JsonPrimitive(plan.y0), "scale" to JsonPrimitive(plan.scale),
                "crop" to JsonArray(listOf(c[0], c[1], cw, ch).map { JsonPrimitive(it) }), "attribution" to JsonPrimitive(provider.attribution),
            ))
        }
    }

    private fun fetchTile(url: String): Bitmap {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TILE_TIMEOUT_MS; readTimeout = TILE_TIMEOUT_MS
            setRequestProperty("User-Agent", "Tracer/1 (network test report)")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("tile HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("tile not an image")
        } finally { conn.disconnect() }
    }

    /** Off-screen WebView: load, wait for onPageFinished plus one frame, then print to [out]. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(html: String, out: File) {
        val adapterAndView = withContext(Dispatchers.Main) {
            val web = WebView(context)
            web.settings.javaScriptEnabled = true          // the template draws everything from the JSON with inline JS
            web.settings.allowFileAccess = false
            web.settings.blockNetworkLoads = true          // self-contained: no network from the page
            // lay out at A4 landscape CSS size (96 dpi) so the page script measures a real viewport
            val w = (297 / 25.4 * 96).roundToInt(); val h = (210 / 25.4 * 96).roundToInt()
            web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, w, h)
            withTimeout(RENDER_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) }
                        }
                    }
                    cont.invokeOnCancellation { web.stopLoading() }
                    web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
                }
            }
            web.createPrintDocumentAdapter("Tracer report") to web
        }
        val (adapter, web) = adapterAndView
        try {
            val attrs = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                // the template's @page rule sets the 8 mm margins; adding them here as well would double them
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
            withContext(Dispatchers.Main) { PdfPrintBridge.write(adapter, attrs, out) }
        } finally {
            withContext(Dispatchers.Main) { web.destroy() }
        }
    }

    private companion object {
        const val TAG = "ReportV1"
        const val TILE_TIMEOUT_MS = 8_000
        const val RENDER_TIMEOUT_MS = 60_000L
    }
}
