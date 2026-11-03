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

@HiltViewModel
class TestSummaryViewModel @Inject constructor(
    reports: ReportRepository,
    runs: RunRepository,
) : ReportViewModel(reports, runs)
