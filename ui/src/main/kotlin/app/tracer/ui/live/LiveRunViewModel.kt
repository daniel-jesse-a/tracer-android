package app.tracer.ui.live

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.cell.CellNormalizer
import app.tracer.core.model.CellSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.RunState
import app.tracer.core.runtime.CollectorHealth
import app.tracer.core.runtime.CollectorStatus
import app.tracer.core.runtime.CollectorStatusBoard
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunRepository
import app.tracer.core.runtime.TowerLocation
import app.tracer.core.runtime.TowerLookup
import app.tracer.core.runtime.TrackPoint
import app.tracer.core.util.Stats
import app.tracer.ui.components.map.EventMarker
import app.tracer.ui.components.map.MapLayer
import app.tracer.ui.components.map.MapPoint
import app.tracer.ui.components.map.TaRing
import app.tracer.ui.components.map.TowerMarker
import app.tracer.ui.components.map.taLabel
import app.tracer.ui.components.map.toMapPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Map inputs for the live screen, rebuilt at most once per second. */
@Immutable
data class LiveMapData(
    val points: List<MapPoint> = emptyList(),
    val tower: TowerMarker? = null,
    val taRing: TaRing? = null,
    val events: List<EventMarker> = emptyList(),
)

/** A neighbour currently stronger than its serving cell. */
data class Overtake(val deltaDb: Int, val durationMs: Long)

/** Values derived across successive snapshots (the snapshot itself is memoryless for these). */
@Immutable
data class LiveStats(
    val p95LatencyMs: Double? = null,
    /** ms since the last throughput result changed; null = unknown (seen already when screen opened) */
    val mbpsAgeMs: Long? = null,
    /** neighbourKey → overtake */
    val overtakes: Map<String, Overtake> = emptyMap(),
    /** subscription → serving poll rate (Hz) */
    val pollHz: Map<Int, Double> = emptyMap(),
)

/** An event opened in the detail sheet. [eventId] < 0 until the writer has persisted it. */
data class SelectedEvent(val event: EventSample, val eventId: Long, val label: String?)

enum class LiveTab { LIVE, CELLS, WIFI, GNSS }

/** Stable identity of a neighbour within a subscription (for overtake tracking). */
internal fun neighbourKey(sub: Int, c: CellSample): String = "$sub:${c.rat}:${c.pci}@${c.earfcnOrArfcn}"

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveRunViewModel @Inject constructor(
    private val controller: RunController,
    private val repository: RunRepository,
    private val towerLookup: TowerLookup,
    statusBoard: CollectorStatusBoard,
) : ViewModel() {

    val snapshot: StateFlow<LiveSnapshot> = controller.live
    val state: StateFlow<RunState> = controller.state

    private val _layer = MutableStateFlow(MapLayer.QUALITY)
    val layer: StateFlow<MapLayer> = _layer.asStateFlow()
    fun setLayer(l: MapLayer) { _layer.value = l }

    private val _tab = MutableStateFlow(LiveTab.LIVE)
    val tab: StateFlow<LiveTab> = _tab.asStateFlow()
    fun setTab(t: LiveTab) { _tab.value = t }

    /** SIM shown in the Cells tab (subscription id); null = first. */
    private val _sub = MutableStateFlow<Int?>(null)
    val selectedSub: StateFlow<Int?> = _sub.asStateFlow()
    fun selectSub(sub: Int) { _sub.value = sub }

    /** The running run's record (mode for the map pill). */
    val run: StateFlow<RunRecord?> = controller.runId
        .filterNotNull()
        .distinctUntilChanged()
        .mapLatest { id -> runCatching { repository.getRun(id) }.getOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Collector problems worth a banner (throttled scans, missing permission, …). */
    val banners: StateFlow<List<CollectorStatus>> = statusBoard.state
        .map { m -> m.values.filter { it.message != null && it.health != CollectorHealth.OK }.sortedBy { it.id } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------------------------------------------------------- cross-snapshot stats

    // Mutable state below is only touched by the single collector of [stats].
    private val latencies = ArrayDeque<Double>()
    private var lastLatency: Double? = null
    private var lastMbps: Double? = null
    private var mbpsChangedAtMs: Long? = null
    private var firstSnapshot = true
    private val overtakeSince = HashMap<String, Long>()
    private val lastServingNs = HashMap<Int, Long>()
    private val pollHz = HashMap<Int, Double>()

    private fun runClockMs(s: LiveSnapshot) = s.elapsedMs + s.pausedMs

    private fun fold(s: LiveSnapshot): LiveStats {
        s.latencyMs?.let { l ->
            if (l != lastLatency) {
                latencies.addLast(l)
                while (latencies.size > 30) latencies.removeFirst()
            }
        }
        lastLatency = s.latencyMs

        if (s.lastMbps != lastMbps) {
            mbpsChangedAtMs = if (firstSnapshot) null else runClockMs(s)
            lastMbps = s.lastMbps
        }
        firstSnapshot = false

        val now = HashMap<String, Overtake>()
        s.neighboursBySub.forEach { (sub, neigh) ->
            val serving = s.servingBySub[sub].orEmpty()
            neigh.forEach { n ->
                val ref = (serving.firstOrNull { it.rat == n.rat } ?: serving.firstOrNull())?.rsrp
                val r = n.rsrp
                if (ref != null && r != null && r > ref) {
                    val k = neighbourKey(sub, n)
                    val since = overtakeSince.getOrPut(k) { n.stamp.tMonoNs }
                    now[k] = Overtake(r - ref, ((n.stamp.tMonoNs - since) / 1_000_000).coerceAtLeast(0))
                }
            }
        }
        overtakeSince.keys.retainAll(now.keys)

        s.servingBySub.forEach { (sub, cells) ->
            val t = cells.firstOrNull()?.stamp?.tMonoNs ?: return@forEach
            val prev = lastServingNs[sub]
            if (prev != null && t > prev) pollHz[sub] = 1e9 / (t - prev)
            lastServingNs[sub] = t
        }

        return LiveStats(
            p95LatencyMs = Stats.percentile(latencies.toList(), 95.0),
            mbpsAgeMs = mbpsChangedAtMs?.let { (runClockMs(s) - it).coerceAtLeast(0) },
            overtakes = now,
            pollHz = HashMap(pollHz),
        )
    }

    val stats: StateFlow<LiveStats> = controller.live
        .map { fold(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveStats())

    // ---------------------------------------------------------------- tower lookup

    private data class CellKey(val rat: String, val mcc: String, val mnc: String, val tac: Int, val cellId: Long)

    private fun CellSample.key(): CellKey? {
        val mcc = mcc ?: return null
        val mnc = mnc ?: return null
        val tac = tac ?: return null
        val id = cellId ?: return null
        return CellKey(rat, mcc, mnc, tac, id)
    }

    /** OpenCelliD radio naming. */
    private fun lookupRat(rat: String): String = when (rat) { "WCDMA", "TDSCDMA" -> "UMTS"; else -> rat }

    // Only touched from the mapLatest coroutine (single collector of a shared flow).
    private val towerCache = HashMap<CellKey, TowerLocation?>()

    private val tower: Flow<TowerLocation?> = controller.live
        .map { it.primaryServing?.key() }
        .distinctUntilChanged()
        .debounce(2_000)
        .mapLatest { k ->
            when {
                k == null -> null
                towerCache.containsKey(k) -> towerCache[k]
                else -> runCatching { towerLookup.locate(lookupRat(k.rat), k.mcc, k.mnc, k.tac, k.cellId) }.getOrNull()
                    .also { towerCache[k] = it }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------------------------------------------------------------- map (1 Hz)

    private var lastTrack: List<TrackPoint>? = null
    private var lastPoints: List<MapPoint> = emptyList()

    private fun pointsFor(track: List<TrackPoint>, s: LiveSnapshot): List<MapPoint> {
        if (track !== lastTrack) {
            lastPoints = track.map { it.toMapPoint() }
            lastTrack = track
        }
        // newest point carries the current fix accuracy for the position halo
        val loc = s.location
        val last = lastPoints.lastOrNull()
        return if (loc != null && last != null && last.hAccM != loc.hAccM) lastPoints.dropLast(1) + last.copy(hAccM = loc.hAccM) else lastPoints
    }

    val mapData: StateFlow<LiveMapData> = combine(controller.live.sample(1_000), tower) { snap, tl ->
        val serving = snap.primaryServing
        val towerMarker = tl?.let { t ->
            TowerMarker(t.lat, t.lon, serving?.pci?.let { "PCI $it" } ?: serving?.rat.orEmpty(), t.rangeM)
        }
        val ta = serving?.timingAdvance
        val taDist = serving?.let { CellNormalizer.timingAdvanceDistanceM(it.rat, ta) }?.takeIf { it > 0 }
        val ring = if (ta != null && taDist != null) {
            val loc = snap.location
            when {
                tl != null -> TaRing(tl.lat, tl.lon, taDist, taLabel(ta, taDist))
                loc != null -> TaRing(loc.lat, loc.lon, taDist, taLabel(ta, taDist))
                else -> null
            }
        } else null
        LiveMapData(
            points = pointsFor(snap.track, snap),
            tower = towerMarker,
            taRing = ring,
            events = snap.recentEvents.mapNotNull { e ->
                val la = e.lat
                val lo = e.lon
                if (la != null && lo != null) EventMarker(e.stamp.tMonoNs, la, lo, e.severity, e.eventType) else null
            },
        )
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveMapData())

    // ---------------------------------------------------------------- run end

    private val endedChannel = Channel<String>(Channel.BUFFERED)
    /** Emits the runId once when a run this screen saw active returns to IDLE. */
    val ended: Flow<String> = endedChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            var active: String? = null
            combine(controller.state, controller.runId) { s, id -> s to id }.collect { (s, id) ->
                if (s != RunState.IDLE && id != null) active = id
                val a = active
                if (s == RunState.IDLE && a != null) {
                    active = null
                    endedChannel.send(a)
                }
            }
        }
    }

    // ---------------------------------------------------------------- actions

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
    fun markMoment(text: String?) = controller.markMoment(text?.trim()?.ifBlank { null })

    // ---------------------------------------------------------------- event sheet

    private val _selected = MutableStateFlow<SelectedEvent?>(null)
    val selected: StateFlow<SelectedEvent?> = _selected.asStateFlow()

    fun openEvent(e: EventSample) {
        _selected.value = SelectedEvent(e, -1, null)
        viewModelScope.launch {
            val found = runCatching {
                repository.events(e.runId).firstOrNull { (_, ev) -> ev.stamp.tMonoNs == e.stamp.tMonoNs && ev.eventType == e.eventType }
            }.getOrNull()
            if (found != null && _selected.value?.event === e) {
                val label = runCatching { repository.eventLabels(e.runId)[found.first] }.getOrNull()
                _selected.value = SelectedEvent(e, found.first, label)
            }
        }
    }

    /** Map pin ids are the event's t_mono_ns. */
    fun openEventAt(tMonoNs: Long) {
        snapshot.value.recentEvents.firstOrNull { it.stamp.tMonoNs == tMonoNs }?.let { openEvent(it) }
    }

    fun closeEvent() { _selected.value = null }

    fun setLabel(label: String) {
        val sel = _selected.value ?: return
        if (sel.eventId < 0) return
        viewModelScope.launch {
            runCatching { repository.setEventLabel(sel.event.runId, sel.eventId, label) }
                .onSuccess { if (_selected.value?.eventId == sel.eventId) _selected.value = sel.copy(label = label) }
        }
    }
}
