package app.tracer.ui.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.tracer.core.model.RunRecord
import app.tracer.core.report.Metric
import app.tracer.core.report.TestReport
import app.tracer.core.report.fmtClock
import app.tracer.core.runtime.BundleService
import app.tracer.ui.report.ReportV1Service
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Builds the one export, the Tracer report v1 PDF, and delivers it: Downloads folder, email composer, share sheet.
 * Nothing else leaves the phone (no HTML, ZIP or summary files); the raw run stays in the local database so the
 * report can be regenerated. Shared by the Share screen and the email step after each test.
 */
@Singleton
class ShareFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bundles: BundleService,
    private val reportV1: ReportV1Service,
) {
    val authority: String get() = bundles.fileProviderAuthority

    /** Writes the report PDF into cacheDir/exports/share-<runId>/ (covered by the export FileProvider). Cancel the caller to stop. */
    suspend fun preparePdf(run: RunRecord, status: (String, Float?) -> Unit = { _, _ -> }): File {
        val dir = File(context.cacheDir, "exports/share-${safe(run.runId)}")
        withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }
        return reportV1.pdf(run, dir) { s, p -> status(s, p) }
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
        appendLine("${r.title}: ${r.headline}")
        appendLine(r.subline)
        appendLine()
        appendLine("Length ${fmtClock(r.durationS)} · ${run.deviceModel} · Tracer ${run.appVersion}")
        r.onlinePct?.let { appendLine("Online ${it.roundToInt()}% (${r.onlineVerdict.label})") }
        if (r.wifiTested) line("Wi-Fi speed", r.wifiSpeedMedian ?: r.wifiSpeedMax, "Mbps", r.wifiSpeedVerdict.label)
        if (r.mobileTested) line("Mobile speed", r.mobileSpeedMedian ?: r.mobileSpeedMax, "Mbps", r.mobileSpeedVerdict.label)
        line("Response", r.responseMs, "ms", "${r.responseVerdict.label}, ${Metric.RESPONSE.hint}")
        line("Wi-Fi signal (median)", r.wifiRssiMedian, "dBm", r.wifiVerdict.label)
        line("Mobile signal (median RSRP)", r.rsrpMedian, "dBm", r.mobileVerdict.label)
        appendLine("Switches ${r.switches} (${r.stabilityVerdict.label}, ${Metric.SWITCHES.hint})")
        r.checksPassedPct?.let { appendLine("Checks passed ${it.roundToInt()}% · test quality ${r.qualityVerdict.label}") }
        if (r.moments.isNotEmpty()) {
            appendLine()
            appendLine("Moments")
            r.moments.take(20).forEach { m ->
                append("  ${fmtClock(m.tS)}  ${m.title}")
                m.causeTitle?.let { append(": $it") }
                appendLine()
            }
            if (r.moments.size > 20) appendLine("  … ${r.moments.size - 20} more in the report")
        }
        if (note != null) { appendLine(); appendLine(note) }
        r.methodFingerprint?.let { appendLine("Method $it") }
        appendLine()
        append("Attached: network test report (PDF).")
    }

    private fun StringBuilder.line(label: String, v: Double?, unit: String, verdict: String) {
        if (v != null) appendLine("$label ${v.roundToInt().toString().replace('-', '−')} $unit ($verdict)")
    }

    companion object {
        private const val TAG = "ShareFiles"
        const val FOLDER = "Tracer"
        private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }
}
