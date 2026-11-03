package app.tracer.ui.library

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.CellSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.RunState
import app.tracer.core.model.Table
import app.tracer.core.quality.QualityLevel
import app.tracer.core.quality.QualityScale
import app.tracer.core.runtime.BundleService
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunListItem
import app.tracer.core.runtime.RunRepository
import app.tracer.core.util.Geo
import app.tracer.ui.R
import app.tracer.ui.common.UiText
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject

enum class RunFilter { ALL, TRAIN, LINKED, THIS_WEEK, HAS_DROPS }

/** Lazily computed per-run card data (cached for the VM's lifetime). */
data class RunExtras(
    /** ~60 RSRP quality buckets over the run's duration (NONE where no serving cell was logged). */
    val quality: List<QualityLevel>,
    val distanceM: Double?,
    val drops: Int,
    /** Rough on-disk size estimate from row counts. */
    val approxBytes: Long,
)

data class ShareRequest(val files: List<File>, val authority: String)

@HiltViewModel
class RunLibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RunRepository,
    private val bundles: BundleService,
    private val settings: SettingsRepository,
    controller: RunController,
) : ViewModel() {

    val runs: StateFlow<List<RunListItem>?> = repository.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val runState: StateFlow<RunState> = controller.state
    val live = controller.live

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _filter = MutableStateFlow(RunFilter.ALL)
    val filter: StateFlow<RunFilter> = _filter.asStateFlow()
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()
    private val _extras = MutableStateFlow<Map<String, RunExtras>>(emptyMap())
    val extras: StateFlow<Map<String, RunExtras>> = _extras.asStateFlow()
    private val _busy = MutableStateFlow<Float?>(null)
    /** Export progress 0..1 while sharing/importing, null when idle. */
    val busy: StateFlow<Float?> = _busy.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _share = Channel<ShareRequest>(Channel.BUFFERED)
    val shareRequests = _share.receiveAsFlow()

    private val inflight = HashSet<String>()
    private val extrasPermits = Semaphore(2)

    /**
     * Search matches run name, mode and run id. PCI/SSID search would need a per-run scan of the
     * cell/Wi-Fi tables and is intentionally not done here.
     */
    val visible: StateFlow<List<RunListItem>> = combine(runs, _query, _filter, _extras) { list, q, f, ex ->
        val weekStart = startOfWeek()
        (list ?: emptyList()).filter { item ->
            val r = item.run
            val matches = q.isBlank() || r.name.contains(q, true) || r.mode.contains(q, true) || r.runId.startsWith(q, true)
            matches && when (f) {
                RunFilter.ALL -> true
                RunFilter.TRAIN -> r.mode == "rail"
                RunFilter.LINKED -> r.linkedRunId != null || item.hasLinkedImport
                RunFilter.THIS_WEEK -> r.startedAt >= weekStart
                RunFilter.HAS_DROPS -> (ex[r.runId]?.drops ?: 0) > 0
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // retention: on screen start, delete runs older than settings.retentionDays
            val days = settings.settings.first().retentionDays
            if (days > 0) {
                val cutoff = System.currentTimeMillis() - days * 86_400_000L
                runCatching { repository.deleteOlderThan(cutoff) }
                    .onSuccess { n -> if (n > 0) _messages.send(UiText.Res(R.string.library_retention_deleted, listOf(n, days))) }
                    .onFailure { Log.w(TAG, "retention failed", it) }
            }
        }
    }

    fun setQuery(q: String) { _query.value = q }

    fun setFilter(f: RunFilter) {
        _filter.value = f
        if (f == RunFilter.HAS_DROPS) runs.value?.forEach { requestExtras(it.run.runId) }
    }

    fun toggleSelection(runId: String) = _selection.update { if (runId in it) it - runId else it + runId }
    fun clearSelection() { _selection.value = emptySet() }

    fun requestExtras(runId: String) {
        synchronized(inflight) { if (runId in _extras.value || !inflight.add(runId)) return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                extrasPermits.withPermit {
                    val ex = computeExtras(runId)
                    _extras.update { it + (runId to ex) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "extras for $runId failed", e)
            } finally {
                synchronized(inflight) { inflight.remove(runId) }
            }
        }
    }

    private suspend fun computeExtras(runId: String): RunExtras {
        val serving = repository.samples(runId, Table.CELL_SERVING).filterIsInstance<CellSample>()
        val quality = if (serving.isEmpty()) {
            emptyList()
        } else {
            val t0 = serving.first().stamp.tMonoNs
            val t1 = serving.last().stamp.tMonoNs
            val span = (t1 - t0).coerceAtLeast(1)
            val buckets = List(BUCKETS) { mutableListOf<Int>() }
            for (c in serving) {
                val r = c.rsrp ?: continue
                val i = (((c.stamp.tMonoNs - t0).toDouble() / span) * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)
                buckets[i] += r
            }
            buckets.map { b -> if (b.isEmpty()) QualityLevel.NONE else QualityScale.rsrp(b.sorted()[b.size / 2]) }
        }
        val locs = repository.samples(runId, Table.LOCATION).filterIsInstance<LocationSample>()
        var dist = 0.0
        for (k in 1 until locs.size) {
            val step = Geo.haversineM(locs[k - 1].lat, locs[k - 1].lon, locs[k].lat, locs[k].lon)
            if (step < 500) dist += step // ignore jumps, same rule as LiveAggregator
        }
        val drops = repository.events(runId).count { it.second.eventType == "drop" }
        val rows = repository.counts(runId).values.sum()
        return RunExtras(quality, if (locs.size > 1) dist else null, drops, rows * BYTES_PER_ROW)
    }

    fun delete(ids: Collection<String>) {
        viewModelScope.launch {
            ids.forEach { id -> runCatching { repository.deleteRun(id) }.onFailure { Log.w(TAG, "delete $id failed", it) } }
            _extras.update { it - ids.toSet() }
            _selection.value = emptySet()
            _messages.send(UiText.Res(R.string.library_deleted, listOf(ids.size)))
        }
    }

    /** Exports each run to a bundle and emits a share request (ACTION_SEND / ACTION_SEND_MULTIPLE). */
    fun share(ids: Collection<String>) {
        if (_busy.value != null || ids.isEmpty()) return
        viewModelScope.launch {
            _busy.value = 0f
            val files = mutableListOf<File>()
            try {
                ids.forEachIndexed { i, id ->
                    files += bundles.export(id) { p -> _busy.value = (i + p.coerceIn(0f, 1f)) / ids.size }
                }
                _share.send(ShareRequest(files, bundles.fileProviderAuthority))
                _selection.value = emptySet()
            } catch (e: Exception) {
                Log.e(TAG, "export failed", e)
                _messages.send(UiText.Res(R.string.library_export_failed, listOf(e.message ?: e.javaClass.simpleName)))
            } finally {
                _busy.value = null
            }
        }
    }

    fun import(uri: Uri) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = 0f
            try {
                val bundle = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { bundles.import(it) } ?: error("cannot open file")
                }
                val run = repository.importBundle(bundle)
                val existing = runs.value.orEmpty()
                val linkedTo = run.linkedRunId?.let { id -> existing.firstOrNull { it.run.runId == id } ?: repository.getRun(id)?.let { r -> RunListItem(r, 0, null, false) } }
                    ?: existing.firstOrNull { it.run.linkedRunId == run.runId }
                val msg = when {
                    !bundle.ok -> UiText.Res(R.string.library_import_integrity, listOf(run.name, bundle.integrity.size))
                    linkedTo != null -> UiText.Res(R.string.library_import_linked, listOf(run.name, linkedTo.run.name))
                    else -> UiText.Res(R.string.library_import_ok, listOf(run.name))
                }
                _messages.send(msg)
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                _messages.send(UiText.Res(R.string.library_import_failed, listOf(e.message ?: e.javaClass.simpleName)))
            } finally {
                _busy.value = null
            }
        }
    }

    private fun startOfWeek(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val TAG = "RunLibrary"
        const val BUCKETS = 60
        const val BYTES_PER_ROW = 110L
    }
}
