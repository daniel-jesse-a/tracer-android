package app.tracer.ui.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.RunRecord
import app.tracer.core.report.Comparison
import app.tracer.core.report.ReportJson
import app.tracer.core.report.TestReport
import app.tracer.core.runtime.BundleService
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.report.ReportRepository
import app.tracer.ui.settings.SettingsRepository
import app.tracer.ui.summary.ReportState
import app.tracer.ui.summary.ReportViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject

enum class ShareDest { SHARE, FILES, SERVER }

data class ShareUi(
    val pdf: Boolean = true,
    val summary: Boolean = true,
    val zip: Boolean = false,
    val dest: ShareDest = ShareDest.SHARE,
    /** null = no server configured (Settings › Upload URL) */
    val uploadUrl: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
    /** 0..1 while the ZIP is being built */
    val progress: Float? = null,
    val result: String? = null,
    val failed: Boolean = false,
) {
    val count: Int get() = listOf(pdf, summary, zip).count { it }
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    reports: ReportRepository,
    runs: RunRepository,
    private val bundles: BundleService,
    private val settings: SettingsRepository,
) : ReportViewModel(reports, runs) {

    val authority: String get() = bundles.fileProviderAuthority

    private val _ui = MutableStateFlow(ShareUi())
    val ui: StateFlow<ShareUi> = _ui.asStateFlow()

    /** Files ready for the share sheet; the screen launches the chooser and calls [shareLaunched]. */
    private val _pendingShare = MutableStateFlow<List<File>?>(null)
    val pendingShare: StateFlow<List<File>?> = _pendingShare.asStateFlow()

    init {
        viewModelScope.launch {
            val url = runCatching { settings.settings.first().uploadUrl }.getOrNull()?.takeIf { it.isNotBlank() }
            _ui.update { it.copy(uploadUrl = url) }
        }
    }

    fun togglePdf() = _ui.update { if (it.busy) it else it.copy(pdf = !it.pdf, result = null) }
    fun toggleSummary() = _ui.update { if (it.busy) it else it.copy(summary = !it.summary, result = null) }
    fun toggleZip() = _ui.update { if (it.busy) it else it.copy(zip = !it.zip, result = null) }
    fun setDest(d: ShareDest) = _ui.update {
        if (it.busy || (d == ShareDest.SERVER && it.uploadUrl == null)) it else it.copy(dest = d, result = null)
    }

    private fun ready(): Pair<RunRecord, TestReport>? = (state.value as? ReportState.Ready)?.let { it.run to it.report }

    /** Share… and Server. Files goes through [saveToTree] after the folder picker. */
    fun send() {
        val (run, report) = ready() ?: return
        val u = _ui.value
        if (u.busy || u.count == 0) return
        when (u.dest) {
            ShareDest.SHARE -> job("Shared") { files ->
                _pendingShare.value = files
                "Opened the share sheet with ${items(files.size)}"
            }.invoke(run, report)
            ShareDest.SERVER -> {
                val url = u.uploadUrl ?: return
                job("Sent") { files ->
                    val bad = mutableListOf<String>()
                    files.forEachIndexed { i, f ->
                        status("Sending ${f.name} (${i + 1} of ${files.size})…", null)
                        val code = try { bundles.upload(f, url) } catch (e: Exception) { Log.w(TAG, "upload failed", e); -1 }
                        if (code !in 200..299) bad += "${f.name}: ${if (code < 0) "network error" else "HTTP $code"}"
                    }
                    if (bad.isNotEmpty()) throw IllegalStateException(bad.joinToString("; "))
                    "Sent ${items(files.size)} to the server"
                }.invoke(run, report)
            }
            ShareDest.FILES -> Unit
        }
    }

    /** Files: copy every prepared file into the folder the user picked. */
    fun saveToTree(tree: Uri) {
        val (run, report) = ready() ?: return
        if (_ui.value.busy) return
        job("Saved") { files ->
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                files.forEach { f ->
                    val doc = DocumentsContract.createDocument(resolver, parent, mimeOf(f), f.name)
                        ?: throw IllegalStateException("Couldn't create ${f.name}")
                    val out = resolver.openOutputStream(doc) ?: throw IllegalStateException("Couldn't write ${f.name}")
                    out.use { o -> f.inputStream().use { it.copyTo(o) } }
                }
            }
            "Saved ${items(files.size)} to the folder"
        }.invoke(run, report)
    }

    fun shareLaunched() { _pendingShare.value = null }

    fun shareFailed(message: String) {
        _pendingShare.value = null
        _ui.update { it.copy(result = message, failed = true) }
    }

    private fun job(verb: String, deliver: suspend (List<File>) -> String): (RunRecord, TestReport) -> Unit = { run, report ->
        _ui.update { it.copy(busy = true, result = null, failed = false, status = "Preparing…", progress = null) }
        viewModelScope.launch {
            try {
                val files = prepare(run, report)
                val msg = deliver(files)
                _ui.update { it.copy(busy = false, status = null, progress = null, result = msg, failed = false) }
            } catch (e: Exception) {
                Log.w(TAG, "$verb failed", e)
                _ui.update { it.copy(busy = false, status = null, progress = null, result = "Failed: ${e.message ?: e.javaClass.simpleName}", failed = true) }
            }
        }
    }

    private fun status(s: String, p: Float?) = _ui.update { it.copy(status = s, progress = p) }

    /** Writes the selected items into cacheDir/exports/share-<runId>/ (covered by the export FileProvider). */
    private suspend fun prepare(run: RunRecord, r: TestReport): List<File> {
        val u = _ui.value
        val dir = File(context.cacheDir, "exports/share-${safe(run.runId)}")
        withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }
        val base = ReportPdf.baseName(run, run.runId)
        val out = mutableListOf<File>()
        if (u.pdf) {
            status("Making the report…", null)
            val f = File(dir, ReportPdf.fileName(run, run.runId))
            withContext(Dispatchers.Default) { ReportPdf.write(r, run, f) }
            out += f
        }
        if (u.summary) {
            status("Writing the summary…", null)
            val compare = runCatching { compareJson(run, r) }.onFailure { Log.w(TAG, "comparison failed", it) }.getOrNull()
            val obj = ReportJson.encode(r).let { if (compare != null) JsonObject(it + ("compare" to compare)) else it }
            val f = File(dir, "${base}_summary.json")
            withContext(Dispatchers.IO) { f.writeText(PRETTY.encodeToString(JsonObject.serializer(), obj)) }
            out += f
        }
        if (u.zip) {
            status("Packing the full data…", 0f)
            val f = bundles.export(run.runId) { p -> _ui.update { it.copy(progress = p.coerceIn(0f, 1f)) } }
            out += f
        }
        status("Ready", null)
        return out
    }

    /** Comparison vs the earliest earlier test at the same site, if any. */
    private suspend fun compareJson(run: RunRecord, r: TestReport): JsonObject? {
        val site = run.site ?: return null
        val candidates = runs.observeRuns().first().map { it.run }
            .filter { it.site.equals(site, ignoreCase = true) && it.runId != run.runId && it.endedAt != null && it.startedAt < run.startedAt }
        val older = reports.reports(candidates).minByOrNull { it.first.startedAt } ?: return null
        fun label(x: RunRecord) = x.testNumber?.let { "Test $it" } ?: x.name
        val c = Comparison(r, older.second, label(run), label(older.first))
        return buildJsonObject {
            put("newer", c.newerLabel)
            put("older", c.olderLabel)
            put("older_run_id", older.first.runId)
            put("checks_passed_before", c.checksBefore)
            put("checks_passed_after", c.checksAfter)
            put("improved", c.improved)
            put("deltas", buildJsonArray { c.deltas.forEach { add(it) } })
        }
    }

    private companion object {
        const val TAG = "Share"
        val PRETTY = Json { prettyPrint = true }
        fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_")
        fun items(n: Int) = if (n == 1) "1 item" else "$n items"
    }
}

internal fun mimeOf(f: File): String = when (f.extension.lowercase()) {
    "pdf" -> "application/pdf"
    "json" -> "application/json"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
