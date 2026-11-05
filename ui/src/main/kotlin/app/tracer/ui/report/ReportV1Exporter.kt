package app.tracer.ui.report

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import app.tracer.core.model.*
import app.tracer.core.report.v1.RailData
import app.tracer.core.report.v1.ReportData
import app.tracer.core.report.v1.ReportInput
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The one export: "Tracer report v1" PDF (assets/report/tracer-report-template.html + ReportData).
 * Raw run data stays in the on-device database so the report can be regenerated; it is never
 * exported from here. Works offline; only the optional basemap needs the network.
 */
@Singleton
class ReportV1Exporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runs: RunRepository,
    private val settings: SettingsRepository,
) {
    enum class Step(val label: String) { DATA("Reading the run"), MAP("Fetching the map"), RENDER("Rendering the report"), DONE("Ready") }

    private val rail: RailData? by lazy {
        runCatching { context.assets.open("report/rail_ergt.json").bufferedReader().use { RailData.parse(it.readText()) } }
            .onFailure { Log.i(TAG, "no rail overlay asset (internal ERGT file not bundled in this build)") }.getOrNull()
    }
    private val template: String by lazy { context.assets.open("report/tracer-report-template.html").bufferedReader().use { it.readText() } }

    /** Station names for the New Test destination picker (empty without the ERGT asset). */
    fun stations(): List<String> = rail?.stations?.mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    /** Builds the ReportData JSON for a finished run. */
    suspend fun data(runId: String, withBasemap: Boolean = true, onStep: (Step) -> Unit = {}): Pair<RunRecord, JsonObject> = withContext(Dispatchers.IO) {
        onStep(Step.DATA)
        val run = runs.getRun(runId) ?: error("Run not found")
        @Suppress("UNCHECKED_CAST")
        suspend fun <T : Sample> t(table: Table): List<T> = runs.samples(runId, table) as List<T>
        val completeness = run.completenessJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val dest = settings.destinationFor(runId)?.let { name ->
            rail?.stations?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == name }?.let { s ->
                buildJsonObject { put("name", name); put("lat", s["lat"] as JsonPrimitive); put("lon", s["lon"] as JsonPrimitive) }
            }
        }
        var input = ReportInput(
            run = run, completeness = completeness,
            probes = t(Table.PROBE), events = runs.events(runId), radio = t(Table.RADIO), networkPath = t(Table.NETWORK_PATH),
            locations = t(Table.LOCATION), device = t(Table.DEVICE), wifi = t(Table.WIFI_CONNECTION), collectorLog = t(Table.X_COLLECTOR_LOG),
            rail = rail, destination = dest,
        )
        coroutineContext.ensureActive()
        if (withBasemap) {
            val provider = Basemap.provider(settings.basemapProvider.first())
            if (provider != null && online()) {
                onStep(Step.MAP)
                val pts = input.locations.map { it.lat to it.lon }
                val bm = Basemap.build(pts, provider, "Tracer/${run.appVersion} (Android report export)")
                if (bm != null) input = ReportInput(input.run, input.completeness, input.probes, input.events, input.radio, input.networkPath,
                    input.locations, input.device, input.wifi, input.collectorLog, input.rail, bm, input.destination)
            }
        }
        coroutineContext.ensureActive()
        run to ReportData.build(input)
    }

    /** Builds the PDF into cacheDir/exports/report-<runId>/ (covered by the export FileProvider). */
    suspend fun pdf(runId: String, onStep: (Step) -> Unit = {}): File {
        val (run, data) = data(runId, true, onStep)
        onStep(Step.RENDER)
        val dir = File(context.cacheDir, "exports/report-${runId.take(8)}").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, ReportData.fileName(run))
        val html = ReportData.inject(template, data)
        ReportPdfRenderer.render(context, html, out)
        onStep(Step.DONE)
        return out
    }

    @android.annotation.SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE is declared in :app
    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object { const val TAG = "ReportV1" }
}
