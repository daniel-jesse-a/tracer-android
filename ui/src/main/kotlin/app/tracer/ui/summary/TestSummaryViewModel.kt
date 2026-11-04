package app.tracer.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.RunRecord
import app.tracer.core.report.TestReport
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.report.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent
import android.util.Log
import app.tracer.ui.settings.SettingsRepository
import app.tracer.ui.share.ShareFiles
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** What a report-backed screen (Summary, Diagnostics, Moment, Share) can show. */
sealed interface ReportState {
    data object Loading : ReportState
    /** The run has not ended yet (ended_at null); re-checked every 2 s. */
    data object Recording : ReportState
    data object Missing : ReportState
    data class Ready(val run: RunRecord, val report: TestReport) : ReportState
}

/**
 * Report states for [runId]: re-reads whenever [ReportRepository.version] changes (feedback saved,
 * run renamed) and polls while the run is still recording.
 */
fun reportStates(reports: ReportRepository, runs: RunRepository, runId: String): Flow<ReportState> = channelFlow {
    reports.version.collectLatest {
        while (true) {
            val run = runCatching { runs.getRun(runId) }.getOrNull()
            if (run == null) {
                send(ReportState.Missing)
                return@collectLatest
            }
            if (run.endedAt == null) {
                send(ReportState.Recording)
                delay(2_000)
                continue
            }
            val r = runCatching { reports.report(runId) }.getOrNull()
            send(if (r != null) ReportState.Ready(run, r) else ReportState.Missing)
            return@collectLatest
        }
    }
}

/** Base for the report screens: [bind] once per runId from the composable. */
abstract class ReportViewModel(
    protected val reports: ReportRepository,
    protected val runs: RunRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ReportState>(ReportState.Loading)
    val state: StateFlow<ReportState> = _state.asStateFlow()
    protected var boundRunId: String? = null
        private set
    private var job: Job? = null

    fun bind(runId: String) {
        if (boundRunId == runId) return
        boundRunId = runId
        job?.cancel()
        _state.value = ReportState.Loading
        job = viewModelScope.launch { reportStates(reports, runs, runId).collect { _state.value = it } }
    }
}

/** Delivery line on the summary: auto-email step and the Download button. */
data class Delivery(val busy: Boolean = false, val text: String? = null, val failed: Boolean = false)

@HiltViewModel
class TestSummaryViewModel @Inject constructor(
    reports: ReportRepository,
    runs: RunRepository,
    private val files: ShareFiles,
    private val settings: SettingsRepository,
) : ReportViewModel(reports, runs) {

    private val _delivery = MutableStateFlow(Delivery())
    val delivery: StateFlow<Delivery> = _delivery.asStateFlow()

    /** Email composer to launch (auto step or "Email report"); cleared by [emailLaunched]. */
    private val _email = MutableStateFlow<Intent?>(null)
    val email: StateFlow<Intent?> = _email.asStateFlow()

    private var autoChecked = false

    /** Recipients to confirm with the user before the auto email step (null = no prompt). */
    private val _askEmail = MutableStateFlow<List<String>?>(null)
    val askEmail: StateFlow<List<String>?> = _askEmail.asStateFlow()

    fun confirmEmail() { _askEmail.value = null; email(auto = true) }
    fun declineEmail(never: Boolean) {
        _askEmail.value = null
        if (never) viewModelScope.launch { settings.setAutoEmail(false) }
    }

    init {
        viewModelScope.launch {
            state.collect { st ->
                val ready = st as? ReportState.Ready ?: return@collect
                if (autoChecked) return@collect
                autoChecked = true
                val run = ready.run
                val ended = run.endedAt ?: return@collect
                val fresh = System.currentTimeMillis() - ended < AUTO_WINDOW_MS
                val own = run.devicePlatform == "android" && run.linkedRunId == null
                if (fresh && own && settings.autoEmail.first() && run.runId !in settings.emailedRuns.first() &&
                    settings.emailRecipients.first().isNotEmpty()) {
                    settings.markEmailed(run.runId)
                    _askEmail.value = settings.emailRecipients.first()
                }
            }
        }
    }

    /** Saves report + summary + full data to Downloads/Tracer. */
    fun download() {
        val ready = state.value as? ReportState.Ready ?: return
        if (_delivery.value.busy) return
        viewModelScope.launch {
            _delivery.value = Delivery(busy = true, text = "Saving to Downloads…")
            _delivery.value = try {
                val list = files.prepare(ready.run, ready.report, pdf = true, summary = true, zip = true) { s, _ -> _delivery.value = Delivery(true, s) }
                val where = files.saveToDownloads(list)
                Delivery(text = "Saved ${list.size} files to $where")
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e); Delivery(text = "Download failed: ${e.message ?: e.javaClass.simpleName}", failed = true)
            }
        }
    }

    /** Builds the files, keeps a copy in Downloads, and opens the pre-filled email. */
    fun email(auto: Boolean = false) {
        val ready = state.value as? ReportState.Ready ?: return
        if (_delivery.value.busy) return
        viewModelScope.launch {
            val to = settings.emailRecipients.first()
            if (to.isEmpty()) { _delivery.value = Delivery(text = "Add email recipients in Settings › Email reports", failed = true); return@launch }
            _delivery.value = Delivery(busy = true, text = if (auto) "Preparing the report email…" else "Preparing email…")
            try {
                val withZip = settings.emailZip.first()
                val list = files.prepare(ready.run, ready.report, pdf = true, summary = true, zip = withZip) { s, _ -> _delivery.value = Delivery(true, s) }
                val where = files.saveToDownloads(list)
                val big = list.filter { it.extension == "zip" && it.length() > ShareFiles.MAX_EMAIL_BYTES }
                val note = if (big.isNotEmpty())
                    "Full data (${big.sumOf { it.length() } / 1_048_576} MB) is too big for email. It is saved on the phone in $where." else null
                _email.value = files.emailIntent(ready.run, ready.report, list - big.toSet(), to, note)
                _delivery.value = Delivery(text = "Email to ${to.joinToString()}: tap Send. Copy saved to $where.")
            } catch (e: Exception) {
                Log.w(TAG, "email failed", e)
                _delivery.value = Delivery(text = "Email failed: ${e.message ?: e.javaClass.simpleName}", failed = true)
            }
        }
    }

    fun emailLaunched() { _email.value = null }
    fun emailFailed(msg: String) { _email.value = null; _delivery.value = Delivery(text = msg, failed = true) }

    private companion object {
        const val TAG = "Summary"
        /** Auto-email only for a test that just finished (not when opening an old one). */
        const val AUTO_WINDOW_MS = 30 * 60_000L
    }
}
