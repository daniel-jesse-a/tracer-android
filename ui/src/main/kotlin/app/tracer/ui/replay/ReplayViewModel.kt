package app.tracer.ui.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.CellSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.GnssSatelliteSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.Table
import app.tracer.core.model.WifiConnectionSample
import app.tracer.core.runtime.PairingService
import app.tracer.core.runtime.RunRepository
import app.tracer.events.EventEngine
import app.tracer.ui.components.map.EventMarker
import app.tracer.ui.components.map.MapLayer
import app.tracer.ui.components.map.TrackBuilder
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ReplayUi(
    val loading: Boolean = true,
    val error: String? = null,
    val data: ReplayData? = null,
    val rerunning: Boolean = false,
    /** message after a rules re-run, e.g. "41 events" */
    val rerunResult: Int? = null,
)

data class ReplaySelection(val id: Long, val event: EventSample, val label: String?)

@HiltViewModel
class ReplayViewModel @Inject constructor(
    private val repository: RunRepository,
    private val pairing: PairingService,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReplayUi())
    val ui: StateFlow<ReplayUi> = _ui.asStateFlow()

    private val _cursor = MutableStateFlow(0L)
    val cursor: StateFlow<Long> = _cursor.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _speed = MutableStateFlow(1)
    val speed: StateFlow<Int> = _speed.asStateFlow()

    private val _layer = MutableStateFlow(MapLayer.QUALITY)
    val layer: StateFlow<MapLayer> = _layer.asStateFlow()

    private val _selected = MutableStateFlow<ReplaySelection?>(null)
    val selected: StateFlow<ReplaySelection?> = _selected.asStateFlow()

    private var runId: String? = null

    fun load(runId: String, atMonoNs: Long?) {
        if (this.runId == runId) return
        this.runId = runId
        reload(atMonoNs)
    }

    private fun reload(atMonoNs: Long?) {
        val id = runId ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = _ui.value.data == null, error = null)
            val res = runCatching { withContext(Dispatchers.IO) { build(id) } }
            res.onSuccess { d ->
                _ui.value = _ui.value.copy(loading = false, data = d)
                val target = atMonoNs ?: _cursor.value.takeIf { it in d.startNs..d.endNs } ?: d.startNs
                _cursor.value = target.coerceIn(d.startNs, d.endNs)
            }.onFailure { e ->
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    private suspend fun build(id: String): ReplayData {
        val run = repository.getRun(id) ?: error("Run not found")
        val loc = repository.samples(id, Table.LOCATION).filterIsInstance<LocationSample>()
        val serving = repository.samples(id, Table.CELL_SERVING).filterIsInstance<CellSample>()
        val probes = repository.samples(id, Table.PROBE).filterIsInstance<ProbeSample>()
        val radio = repository.samples(id, Table.RADIO).filterIsInstance<RadioSample>()
        val paths = repository.samples(id, Table.NETWORK_PATH).filterIsInstance<NetworkPathSample>()
        val wifi = repository.samples(id, Table.WIFI_CONNECTION).filterIsInstance<WifiConnectionSample>()
        val sats = repository.samples(id, Table.GNSS_SATELLITE).filterIsInstance<GnssSatelliteSample>()
        val events = repository.events(id).sortedBy { it.second.stamp.tMonoNs }
        val labels = repository.eventLabels(id)
        val epochs = TrackBuilder.gnssEpochs(sats)
        val (rsrp, sinr) = ReplayBuilder.serving(serving)

        val allTimes = listOfNotNull(
            loc.firstOrNull()?.stamp?.tMonoNs, loc.lastOrNull()?.stamp?.tMonoNs,
            serving.firstOrNull()?.stamp?.tMonoNs, serving.lastOrNull()?.stamp?.tMonoNs,
            probes.firstOrNull()?.stamp?.tMonoNs, probes.lastOrNull()?.stamp?.tMonoNs,
            radio.firstOrNull()?.stamp?.tMonoNs, radio.lastOrNull()?.stamp?.tMonoNs,
            events.firstOrNull()?.second?.stamp?.tMonoNs, events.lastOrNull()?.second?.stamp?.tMonoNs,
        )
        val start = allTimes.minOrNull() ?: run.tMonoNs
        val end = (allTimes.maxOrNull() ?: start).coerceAtLeast(start + 1_000_000_000L)

        // linked (paired iPhone) run, if present in the DB
        val linkedId = run.linkedRunId
        val linkedRun = linkedId?.let { runCatching { repository.getRun(it) }.getOrNull() }
        val k = ReplayBuilder.monoMinusUtcMs(loc.ifEmpty { serving })
        var overlay = emptyList<app.tracer.ui.components.map.MapPoint>()
        var linkedLat: NumData? = null
        var linkedRsrp: NumData? = null
        if (linkedRun != null && k != null) {
            runCatching {
                overlay = ReplayBuilder.overlay(pairing.alignedOverlay(id, linkedRun.runId, Table.LOCATION).filterIsInstance<LocationSample>(), k)
                linkedLat = ReplayBuilder.linkedLatency(pairing.alignedOverlay(id, linkedRun.runId, Table.PROBE).filterIsInstance<ProbeSample>(), k)
                linkedRsrp = ReplayBuilder.linkedRsrp(pairing.alignedOverlay(id, linkedRun.runId, Table.CELL_SERVING).filterIsInstance<CellSample>(), k)
            }
        }

        return ReplayData(
            run = run, startNs = start, endNs = end,
            points = TrackBuilder.build(loc, serving, probes, wifi, radio, epochs, paths),
            overlay = overlay,
            type = ReplayBuilder.connType(radio, paths),
            rat = ReplayBuilder.rat(radio),
            rsrp = rsrp, sinr = sinr,
            latency = ReplayBuilder.latency(probes),
            loss = ReplayBuilder.rollingLoss(probes),
            throughput = ReplayBuilder.throughput(probes),
            wifi = ReplayBuilder.wifi(wifi),
            gnssUsed = ReplayBuilder.gnss(epochs),
            linkedLatency = linkedLat?.takeIf { !it.isEmpty },
            linkedRsrp = linkedRsrp?.takeIf { !it.isEmpty },
            events = events, labels = labels,
            markers = events.mapNotNull { (rowId, e) ->
                val la = e.lat; val lo = e.lon
                if (la != null && lo != null) EventMarker(rowId, la, lo, e.severity, e.eventType) else null
            },
            linkedName = linkedRun?.name,
        )
    }

    // ---------------------------------------------------------------- playback

    fun setLayer(l: MapLayer) { _layer.value = l }
    fun setSpeed(s: Int) { _speed.value = s }
    fun togglePlay() {
        val d = _ui.value.data ?: return
        if (!_playing.value && _cursor.value >= d.endNs) _cursor.value = d.startNs
        _playing.value = !_playing.value
    }

    fun scrub(t: Long) {
        val d = _ui.value.data ?: return
        _cursor.value = t.coerceIn(d.startNs, d.endNs)
    }

    /** Advance by real elapsed [frameNs] × speed; stops at the end. */
    fun advance(frameNs: Long) {
        val d = _ui.value.data ?: return
        val next = _cursor.value + frameNs * _speed.value
        if (next >= d.endNs) { _cursor.value = d.endNs; _playing.value = false } else _cursor.value = next
    }

    fun prevEvent() { _ui.value.data?.let { d -> ReplayBuilder.prevEvent(d.events, _cursor.value)?.let { _cursor.value = it.second.stamp.tMonoNs } } }
    fun nextEvent() { _ui.value.data?.let { d -> ReplayBuilder.nextEvent(d.events, _cursor.value)?.let { _cursor.value = it.second.stamp.tMonoNs } } }

    // ---------------------------------------------------------------- events

    fun openEvent(rowId: Long) {
        val d = _ui.value.data ?: return
        val e = d.events.firstOrNull { it.first == rowId } ?: return
        _cursor.value = e.second.stamp.tMonoNs
        _selected.value = ReplaySelection(rowId, e.second, d.labels[rowId])
    }

    fun openEventIndex(i: Int) { _ui.value.data?.events?.getOrNull(i)?.let { openEvent(it.first) } }

    fun closeEvent() { _selected.value = null }

    fun setLabel(label: String) {
        val sel = _selected.value ?: return
        val id = runId ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.setEventLabel(id, sel.id, label) } }.onSuccess {
                _selected.value = sel.copy(label = label)
                _ui.value.data?.let { d -> _ui.value = _ui.value.copy(data = d.withLabel(sel.id, label)) }
            }
        }
    }

    private fun ReplayData.withLabel(id: Long, label: String) = ReplayData(
        run, startNs, endNs, points, overlay, type, rat, rsrp, sinr, latency, loss, throughput, wifi, gnssUsed,
        linkedLatency, linkedRsrp, events, labels + (id to label), markers, linkedName,
    )

    // ---------------------------------------------------------------- re-run rules

    /** Re-runs the event engine over every sample (merged by t_mono_ns) with current thresholds, then reloads. */
    fun rerunRules() {
        val id = runId ?: return
        if (_ui.value.rerunning) return
        _ui.value = _ui.value.copy(rerunning = true, rerunResult = null)
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.Default) {
                    val th = settings.settings.first().thresholds
                    val tables = listOf(
                        Table.LOCATION, Table.GNSS_SATELLITE, Table.CELL_SERVING, Table.CELL_NEIGHBOUR, Table.RADIO,
                        Table.WIFI_CONNECTION, Table.WIFI_SCAN, Table.NETWORK_PATH, Table.PROBE,
                    )
                    val merged = ReplayBuilder.mergeByTime(tables.map { t -> withContext(Dispatchers.IO) { repository.samples(id, t) } })
                    val events = EventEngine(id, th).replay(merged.asSequence())
                    withContext(Dispatchers.IO) { repository.replaceEvents(id, events) }
                    events.size
                }
            }
            _ui.value = _ui.value.copy(rerunning = false, rerunResult = res.getOrNull(), error = res.exceptionOrNull()?.message)
            _selected.value = null
            reload(null)
        }
    }

    fun clearRerunResult() { _ui.value = _ui.value.copy(rerunResult = null) }
}
