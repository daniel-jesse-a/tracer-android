package app.tracer.ui.report

import android.content.Context
import android.util.Log
import app.tracer.core.config.RunSettings
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Table
import app.tracer.core.report.Comparison
import app.tracer.core.report.ReportBuilder
import app.tracer.core.report.ReportJson
import app.tracer.core.report.TestReport
import app.tracer.core.runtime.RunRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain-language [TestReport]s per run, built in one streaming pass and cached as JSON in
 * filesDir/reports/<runId>.json (keyed by run end time + label count, so edits rebuild).
 * Used by Tests, Test summary, Diagnostics, Moment detail, Share and the comparison card.
 */
@Singleton
class ReportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runs: RunRepository,
) {
    private val dir = File(context.filesDir, "reports").apply { mkdirs() }
    private val mem = HashMap<String, TestReport>()
    private val locks = HashMap<String, Mutex>()
    private val _version = MutableStateFlow(0)
    /** Bumped whenever a report is rebuilt (feedback saved, run renamed); screens re-read on change. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun lock(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    /** Cached report, building it if needed. Null while the run is still recording or does not exist. */
    suspend fun report(runId: String, force: Boolean = false): TestReport? = lock(runId).withLock {
        withContext(Dispatchers.IO) {
            val run = runs.getRun(runId)?.takeIf { it.endedAt != null } ?: return@withContext null
            val key = cacheKey(run)
            if (!force) {
                synchronized(mem) { mem[runId] }?.takeIf { it.startedAt == run.startedAt && cachedKey[runId] == key }?.let { return@withContext it }
                val f = File(dir, "$runId.json")
                if (f.exists()) {
                    val txt = runCatching { f.readText() }.getOrNull()
                    val k = txt?.substringBefore('\n')
                    if (k == key) ReportJson.decodeString(txt.substringAfter('\n'))?.let { r ->
                        synchronized(mem) { mem[runId] = r; cachedKey[runId] = key }; return@withContext r
                    }
                }
            }
            val r = runCatching { build(run) }.onFailure { Log.w(TAG, "report build failed for $runId", it) }.getOrNull()
                ?: return@withContext null
            runCatching { File(dir, "$runId.json").writeText(key + "\n" + ReportJson.encodeString(r)) }
            synchronized(mem) { mem[runId] = r; cachedKey[runId] = key }
            r
        }
    }

    private val cachedKey = HashMap<String, String>()

    private suspend fun cacheKey(run: RunRecord): String {
        val labels = runCatching { runs.eventLabels(run.runId).hashCode() }.getOrDefault(0)
        return "k1:${run.endedAt}:${run.name.hashCode()}:${run.site}:${run.testNumber}:$labels"
    }

    private suspend fun build(run: RunRecord): TestReport {
        val settings = run.settingsJson?.let { runCatching { RunSettings.fromJson(it, migrate = false) }.getOrNull() }
        val b = ReportBuilder(run, probeIntervalS = settings?.latencyIntervalMs?.div(1000.0))
        settings?.throughputMaxMs?.let { b.throughputDurationMs(it) }
        for (table in TABLES) runs.stream(run.runId, table).collect { b.add(it) }
        runs.events(run.runId).forEach { (id, e) -> b.event(id, e) }
        runs.eventLabels(run.runId).forEach { (id, l) -> b.label(id, l) }
        return b.build()
    }

    /** "Is this right?" — stores yes / no:<cause> on the moment's event and rebuilds the report. */
    suspend fun setFeedback(runId: String, eventId: Long, label: String) {
        withContext(Dispatchers.IO) { runs.setEventLabel(runId, eventId, label) }
        report(runId, force = true)
        _version.update { it + 1 }
    }

    fun invalidate(runId: String) {
        synchronized(mem) { mem.remove(runId); cachedKey.remove(runId) }
        File(dir, "$runId.json").delete()
        _version.update { it + 1 }
    }

    /** Reports for finished runs (newest first), skipping runs whose report cannot be built. */
    suspend fun reports(list: List<RunRecord>): List<Pair<RunRecord, TestReport>> =
        list.filter { it.endedAt != null }.sortedByDescending { it.startedAt }.mapNotNull { r -> report(r.runId)?.let { r to it } }

    /** Newest test vs the first test at the same site. */
    fun comparison(reports: List<Pair<RunRecord, TestReport>>): Comparison? = Comparison.pick(reports)

    private companion object {
        const val TAG = "ReportRepository"
        val TABLES = listOf(
            Table.NETWORK_PATH, Table.WIFI_CONNECTION, Table.CELL_SERVING, Table.RADIO, Table.PROBE,
            Table.LOCATION, Table.GNSS_SATELLITE, Table.MOTION,
        )
    }
}

