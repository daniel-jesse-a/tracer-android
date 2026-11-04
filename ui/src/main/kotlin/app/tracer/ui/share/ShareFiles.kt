package app.tracer.ui.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import app.tracer.core.model.RunRecord
import app.tracer.core.report.Comparison
import app.tracer.core.report.ReportJson
import app.tracer.core.report.TestReport
import app.tracer.core.report.fmtClock
import app.tracer.core.runtime.BundleService
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.report.ReportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Builds the share items (PDF report, summary JSON with comparison, full-data ZIP) and delivers
 * them: Downloads folder, email composer, share sheet. Shared by the Share screen and the
 * automatic email step after each test.
 */
@Singleton
class ShareFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reports: ReportRepository,
    private val runs: RunRepository,
    private val bundles: BundleService,
) {
    val authority: String get() = bundles.fileProviderAuthority

    /** Writes the selected items into cacheDir/exports/share-<runId>/ (covered by the export FileProvider). */
    suspend fun prepare(
        run: RunRecord, r: TestReport, pdf: Boolean, summary: Boolean, zip: Boolean,
        status: (String, Float?) -> Unit = { _, _ -> },
    ): List<File> {
        val dir = File(context.cacheDir, "exports/share-${safe(run.runId)}")
        withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }
        val base = ReportPdf.baseName(run, run.runId)
        val out = mutableListOf<File>()
        if (pdf) {
            status("Making the report…", null)
            val f = File(dir, ReportPdf.fileName(run, run.runId))
            withContext(Dispatchers.Default) { ReportPdf.write(r, run, f) }
            out += f
        }
        if (summary) {
            status("Writing the summary…", null)
            val compare = runCatching { compareJson(run, r) }.onFailure { Log.w(TAG, "comparison failed", it) }.getOrNull()
            val obj = ReportJson.encode(r).let { if (compare != null) JsonObject(it + ("compare" to compare)) else it }
            val f = File(dir, "${base}_summary.json")
            withContext(Dispatchers.IO) { f.writeText(PRETTY.encodeToString(JsonObject.serializer(), obj)) }
            out += f
        }
        if (zip) {
            status("Packing the full data…", 0f)
            out += bundles.export(run.runId) { p -> status("Packing the full data…", p.coerceIn(0f, 1f)) }
        }
        status("Ready", null)
        return out
    }

    /**
     * Copies files into the public Downloads/Tracer folder (MediaStore; no permission needed on
     * Android 10+). Returns the folder shown to the user.
     */
    suspend fun saveToDownloads(files: List<File>): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        for (f in files) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create ${f.name} in Downloads")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Couldn't write ${f.name}")
                out.use { o -> f.inputStream().use { it.copyTo(o) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        "Downloads/$FOLDER"
    }

    /**
     * Email composer pre-filled with recipients, subject, a plain-text summary and the files.
     * Restricted to email apps (mailto selector). Android does not let an app send mail without
     * the user tapping Send, so this is one tap.
     */
    fun emailIntent(run: RunRecord, r: TestReport, files: List<File>, to: List<String>, note: String? = null): Intent {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
        val send = if (uris.size <= 1) Intent(Intent.ACTION_SEND).apply { uris.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) } }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        send.putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
        send.putExtra(Intent.EXTRA_SUBJECT, subject(run, r))
        send.putExtra(Intent.EXTRA_TEXT, body(run, r, files, note))
        send.selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri(files[0].name, uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            send.clipData = clip
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return send
    }

    fun subject(run: RunRecord, r: TestReport): String =
        "Tracer · ${r.title} · ${r.overallVerdict.label} · ${DATE.format(Date(run.startedAt))}"

    private fun body(run: RunRecord, r: TestReport, files: List<File>, note: String?): String = buildString {
        appendLine("${r.title} — ${r.headline}")
        appendLine(r.subline)
        appendLine()
        appendLine("Length ${fmtClock(r.durationS)} · ${run.deviceModel} · Tracer ${run.appVersion}")
        r.onlinePct?.let { appendLine("Online ${it.roundToInt()}% (${r.onlineVerdict.label})") }
        line("Wi-Fi speed", r.wifiSpeedMedian ?: r.wifiSpeedMax, "Mbps", r.wifiSpeedVerdict.label)
        line("Mobile speed", r.mobileSpeedMedian ?: r.mobileSpeedMax, "Mbps", r.mobileSpeedVerdict.label)
        line("Response", r.responseMs, "ms", r.responseVerdict.label)
        line("Wi-Fi signal (median)", r.wifiRssiMedian, "dBm", r.wifiVerdict.label)
        line("Mobile signal (median RSRP)", r.rsrpMedian, "dBm", r.mobileVerdict.label)
        appendLine("Switches ${r.switches} (${r.stabilityVerdict.label})")
        r.checksPassedPct?.let { appendLine("Checks passed ${it.roundToInt()}% · test quality ${r.qualityVerdict.label}") }
        if (r.moments.isNotEmpty()) {
            appendLine()
            appendLine("Moments")
            r.moments.take(20).forEach { m ->
                append("  ${fmtClock(m.tS)}  ${m.title}")
                m.causeTitle?.let { append(" — $it") }
                appendLine()
            }
            if (r.moments.size > 20) appendLine("  … ${r.moments.size - 20} more in the report")
        }
        appendLine()
        appendLine("Attached: ${files.joinToString { it.name }}")
        if (note != null) appendLine(note)
        r.methodFingerprint?.let { appendLine("Method $it") }
    }

    private fun StringBuilder.line(label: String, v: Double?, unit: String, verdict: String) {
        if (v != null) appendLine("$label ${v.roundToInt().toString().replace('-', '−')} $unit ($verdict)")
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

    companion object {
        private const val TAG = "ShareFiles"
        const val FOLDER = "Tracer"
        /** Keep total attachments under typical Exchange/Gmail limits (20–25 MB incl. base64 overhead). */
        const val MAX_EMAIL_BYTES = 17L * 1024 * 1024
        private val PRETTY = Json { prettyPrint = true }
        private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }
}
