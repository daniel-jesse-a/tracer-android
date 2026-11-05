package app.tracer.ui.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.RunRecord
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
import java.io.File
import javax.inject.Inject

enum class ShareDest { DOWNLOAD, EMAIL, SHARE, FILES, SERVER }

data class ShareUi(
    val pdf: Boolean = true,
    val summary: Boolean = true,
    val zip: Boolean = false,
    val dest: ShareDest = ShareDest.DOWNLOAD,
    val recipients: List<String> = emptyList(),
    /** null = no server configured (Settings › Upload URL) */
    val uploadUrl: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
    /** 0..1 while the ZIP is being built */
    val progress: Float? = null,
    val result: String? = null,
    val failed: Boolean = false,
) {
    /** The export is always exactly one file: the report PDF. */
    val count: Int get() = 1
}

@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    reports: ReportRepository,
    runs: RunRepository,
    private val bundles: BundleService,
    private val settings: SettingsRepository,
    private val files: ShareFiles,
) : ReportViewModel(reports, runs) {

    val authority: String get() = bundles.fileProviderAuthority

    /** Email composer ready to launch; the screen starts it and calls [shareLaunched]. */
    private val _pendingEmail = MutableStateFlow<android.content.Intent?>(null)
    val pendingEmail: StateFlow<android.content.Intent?> = _pendingEmail.asStateFlow()

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
        viewModelScope.launch { settings.emailRecipients.collect { to -> _ui.update { it.copy(recipients = to) } } }
    }

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
            ShareDest.DOWNLOAD -> job("Downloaded") { list ->
                status("Saving to Downloads…", null)
                val where = files.saveToDownloads(list)
                "Saved ${items(list.size)} to $where"
            }.invoke(run, report)
            ShareDest.EMAIL -> job("Emailed") { list ->
                _pendingEmail.value = files.emailIntent(run, report, list, u.recipients, null)
                "Opened email to ${u.recipients.joinToString()}"
            }.invoke(run, report)
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

    fun shareLaunched() { _pendingShare.value = null; _pendingEmail.value = null }

    fun shareFailed(message: String) {
        _pendingShare.value = null; _pendingEmail.value = null
        _ui.update { it.copy(result = message, failed = true) }
    }

    private var running: kotlinx.coroutines.Job? = null

    /** Cancels a report that is still being built. */
    fun cancel() {
        running?.cancel(); running = null
        _ui.update { it.copy(busy = false, status = null, progress = null, result = "Cancelled", failed = false) }
    }

    private fun job(verb: String, deliver: suspend (List<File>) -> String): (RunRecord, TestReport) -> Unit = { run, report ->
        _ui.update { it.copy(busy = true, result = null, failed = false, status = "Preparing…", progress = null) }
        running = viewModelScope.launch {
            try {
                val files = prepare(run, report)
                val msg = deliver(files)
                _ui.update { it.copy(busy = false, status = null, progress = null, result = msg, failed = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$verb failed", e)
                _ui.update { it.copy(busy = false, status = null, progress = null, result = "Failed: ${e.message ?: e.javaClass.simpleName}", failed = true) }
            }
        }
    }

    private fun status(s: String, p: Float?) = _ui.update { it.copy(status = s, progress = p) }

    private suspend fun prepare(run: RunRecord, r: TestReport): List<File> {
        return files.prepare(run) { st, p -> status(st, p) }
    }

    private companion object {
        const val TAG = "Share"
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
