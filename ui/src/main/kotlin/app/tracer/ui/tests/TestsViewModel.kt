package app.tracer.ui.tests

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.RunRecord
import app.tracer.core.model.RunState
import app.tracer.core.report.Comparison
import app.tracer.core.report.TestReport
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunListItem
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.companion.CompanionDevices
import app.tracer.ui.report.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** One row of the Tests list. [report] is null until it has been built. */
data class TestRow(
    val run: RunRecord,
    val title: String,
    val time: String,
    /** "3:12 · walking" / "Pixel 10 Pro" / "6:06 · old build" */
    val detail: String,
    /** run recorded on another phone (imported / linked companion run) */
    val otherDevice: Boolean,
    val report: TestReport?,
)

data class TestSection(val caption: String, val rows: List<TestRow>)

/** "● Recording · Office · Test 4 · 01:24". */
data class RecordingBanner(val paused: Boolean, val label: String, val elapsedMs: Long)

data class TestsUi(
    val loaded: Boolean,
    val sections: List<TestSection>,
    val comparison: Comparison?,
    val recording: RecordingBanner?,
)

@HiltViewModel
class TestsViewModel @Inject constructor(
    private val repository: RunRepository,
    private val reports: ReportRepository,
    controller: RunController,
) : ViewModel() {

    private val runs: StateFlow<List<RunListItem>?> =
        repository.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _reports = MutableStateFlow<Map<String, TestReport>>(emptyMap())
    private val requested = HashSet<String>()
    private val permits = Semaphore(2)

    private val recording = combine(controller.state, controller.runId, controller.live, runs) { st, id, live, list ->
        if (id == null || (st != RunState.RECORDING && st != RunState.PAUSED)) null
        else {
            val run = list?.firstOrNull { it.run.runId == id }?.run
            val label = run?.let { r ->
                val site = r.site?.trim()?.takeIf { it.isNotEmpty() }
                val n = r.testNumber
                when {
                    site != null && n != null -> "$site · Test $n"
                    site != null -> site
                    else -> r.name
                }
            }
            RecordingBanner(paused = st == RunState.PAUSED, label = label ?: "Test", elapsedMs = live.elapsedMs)
        }
    }

    private val activeRunId = combine(controller.state, controller.runId) { st, id ->
        if (st == RunState.RECORDING || st == RunState.PAUSED || st == RunState.STOPPING) id else null
    }

    val ui: StateFlow<TestsUi> = combine(runs, _reports, recording, activeRunId) { list, reps, rec, active ->
        if (list == null) return@combine TestsUi(false, emptyList(), null, rec)
        val finished = list.map { it.run }.filter { it.runId != active }
        TestsUi(
            loaded = true,
            sections = buildSections(finished, reps),
            comparison = comparisonOf(finished, reps),
            recording = rec,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TestsUi(false, emptyList(), null, null))

    init {
        // Eagerly build the reports the comparison card needs: every finished test at the newest test's site.
        viewModelScope.launch {
            runs.collect { list ->
                val done = list.orEmpty().map { it.run }.filter { it.endedAt != null }
                val newest = done.filter { it.site != null }.maxByOrNull { it.startedAt } ?: return@collect
                done.filter { it.site.equals(newest.site, ignoreCase = true) }.forEach { request(it.runId) }
            }
        }
        // Reports rebuilt elsewhere (feedback, rename, delete): re-read what we already have.
        viewModelScope.launch {
            reports.version.drop(1).collect {
                val ids = synchronized(requested) { requested.toList().also { requested.clear() } }
                ids.forEach { request(it) }
            }
        }
    }

    /** Builds (or reads the cached) report of [runId] once; rows call this when they appear. */
    fun request(runId: String) {
        synchronized(requested) { if (!requested.add(runId)) return }
        viewModelScope.launch(Dispatchers.IO) {
            permits.withPermit {
                val r = runCatching { reports.report(runId) }.onFailure { Log.w(TAG, "report $runId failed", it) }.getOrNull()
                if (r != null) _reports.update { it + (runId to r) }
            }
        }
    }

    fun delete(runId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteRun(runId) }.onFailure { Log.w(TAG, "delete $runId failed", it) }
            _reports.update { it - runId }
            synchronized(requested) { requested.remove(runId) }
            reports.invalidate(runId)
        }
    }

    private fun comparisonOf(runs: List<RunRecord>, reps: Map<String, TestReport>): Comparison? {
        val pairs = runs.filter { it.endedAt != null }.mapNotNull { r -> reps[r.runId]?.let { r to it } }
        return runCatching { reports.comparison(pairs) }.getOrNull()
    }

    private fun buildSections(runs: List<RunRecord>, reps: Map<String, TestReport>): List<TestSection> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val sorted = runs.sortedByDescending { it.startedAt }
        // newest run per site on this device: older runs with another build / method get flagged
        val newestAtSite = sorted.filter { !isOtherDevice(it) && it.site != null }
            .groupBy { it.site!!.trim().lowercase() }.mapValues { it.value.first() }
        val groups = LinkedHashMap<Pair<LocalDate, String>, MutableList<TestRow>>()
        for (r in sorted) {
            val date = Instant.ofEpochMilli(r.startedAt).atZone(zone).toLocalDate()
            val site = r.site?.trim()?.takeIf { it.isNotEmpty() }.orEmpty()
            groups.getOrPut(date to site.lowercase()) { mutableListOf() } += row(r, reps[r.runId], newestAtSite, zone)
        }
        return groups.map { (key, rows) ->
            val day = dayLabel(key.first, today)
            val site = rows.first().run.site?.trim()?.takeIf { it.isNotEmpty() }
            TestSection(if (site != null) "$day · $site" else day, rows)
        }
    }

    private fun row(r: RunRecord, rep: TestReport?, newestAtSite: Map<String, RunRecord>, zone: ZoneId): TestRow {
        val other = isOtherDevice(r)
        val title = r.testNumber?.let { "Test $it" }?.takeIf { r.site != null } ?: r.name
        val time = TIME.format(Instant.ofEpochMilli(r.startedAt).atZone(zone))
        val detail = if (other) {
            r.deviceModel.ifBlank { if (r.devicePlatform == "ios") "iPhone" else "Other phone" }
        } else {
            val durS = rep?.durationS ?: r.endedAt?.let { (it - r.startedAt) / 1000.0 }
            val parts = mutableListOf<String>()
            if (durS != null) parts += clock(durS)
            val newest = r.site?.trim()?.lowercase()?.let { newestAtSite[it] }
            val flag = when {
                newest == null || newest.runId == r.runId -> null
                newest.appVersion.isNotBlank() && r.appVersion.isNotBlank() && newest.appVersion != r.appVersion -> "old build"
                newest.methodFingerprint != null && r.methodFingerprint != null && newest.methodFingerprint != r.methodFingerprint -> "different settings"
                else -> null
            }
            parts += flag ?: movementWord(r.mode)
            parts.joinToString(" · ")
        }
        return TestRow(r, title, time, detail, other, rep)
    }

    private companion object {
        const val TAG = "TestsViewModel"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        fun isOtherDevice(r: RunRecord): Boolean = !CompanionDevices.isThisDevice(r)

        fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DAY.format(d)
        }

        fun movementWord(mode: String): String = when (mode) {
            "walk" -> "walking"
            "drive" -> "driving"
            "rail" -> "on a train"
            "static" -> "staying still"
            "simulated" -> "simulated"
            else -> mode
        }

        fun clock(s: Double): String {
            val t = Math.round(s).toInt().coerceAtLeast(0)
            return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
        }
    }
}
