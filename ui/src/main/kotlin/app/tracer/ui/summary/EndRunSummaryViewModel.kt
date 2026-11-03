package app.tracer.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.analysis.RunSummary
import app.tracer.core.model.CellSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Table
import app.tracer.core.quality.ConnType
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.components.map.EventMarker
import app.tracer.ui.components.map.MapPoint
import app.tracer.ui.components.map.TrackBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** One completeness badge: GPS / NET / PRB / RF / GNSS. */
data class Badge(val label: String, val state: State, val ratio: Double?) {
    enum class State { OK, PARTIAL, MISSING, PENDING, NA }
}

data class SummaryState(
    val loading: Boolean = true,
    val error: String? = null,
    val run: RunRecord? = null,
    val summary: RunSummary? = null,
    val drops: Int = 0,
    val points: List<MapPoint> = emptyList(),
    val events: List<EventMarker> = emptyList(),
    val badges: List<Badge> = emptyList(),
    /** connection-type share of radio samples, largest first */
    val connShare: List<Pair<ConnType, Double>> = emptyList(),
    val saving: Boolean = false,
)

object CompletenessBadges {
    /** Parses runs.completeness_json (+ table counts for NET, which the ratio set does not cover). */
    fun badges(json: String?, counts: Map<Table, Long>): List<Badge> {
        val collectors: JsonObject? = json?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject["collectors"]?.jsonObject }.getOrNull()
        }
        fun ratio(key: String): Pair<Badge.State, Double?> {
            if (json == null) return Badge.State.PENDING to null
            val r = collectors?.get(key)?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: return Badge.State.NA to null
            return when {
                r >= 0.7 -> Badge.State.OK
                r > 0 -> Badge.State.PARTIAL
                else -> Badge.State.MISSING
            } to r
        }
        val net = (counts[Table.NETWORK_PATH] ?: 0L) > 0
        return listOf(
            ratio("location").let { (s, r) -> Badge("GPS", s, r) },
            Badge("NET", if (net) Badge.State.OK else Badge.State.MISSING, null),
            ratio("latency_probes").let { (s, r) -> Badge("PRB", s, r) },
            ratio("cell_serving_polls").let { (s, r) -> Badge("RF", s, r) },
            ratio("gnss_epochs").let { (s, r) -> Badge("GNSS", s, r) },
        )
    }
}

@HiltViewModel
class EndRunSummaryViewModel @Inject constructor(
    private val repository: RunRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state.asStateFlow()
    private var loadedRunId: String? = null

    fun load(runId: String) {
        if (loadedRunId == runId) return
        loadedRunId = runId
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val run = repository.getRun(runId) ?: error("Run not found")
                    val loc = repository.samples(runId, Table.LOCATION).filterIsInstance<LocationSample>()
                    val serving = repository.samples(runId, Table.CELL_SERVING).filterIsInstance<CellSample>()
                    val probes = repository.samples(runId, Table.PROBE).filterIsInstance<ProbeSample>()
                    val radio = repository.samples(runId, Table.RADIO).filterIsInstance<RadioSample>()
                    val paths = repository.samples(runId, Table.NETWORK_PATH).filterIsInstance<NetworkPathSample>()
                    val events = repository.events(runId).map { it.second }
                    val counts = repository.counts(runId)
                    val end = run.endedAt ?: (loc.lastOrNull()?.stamp?.tUtcMs ?: run.startedAt)
                    val summary = RunSummary.of(loc, serving, probes, radio, events, (end - run.startedAt).coerceAtLeast(0))
                    val share = radio.groupingBy { ConnType.of(it.rat) }.eachCount()
                    val total = share.values.sum().coerceAtLeast(1)
                    SummaryState(
                        loading = false,
                        run = run,
                        summary = summary,
                        drops = events.count { it.eventType == "drop" },
                        points = TrackBuilder.build(loc, serving, probes, paths = paths),
                        events = events.mapNotNull { e ->
                            val la = e.lat; val lo = e.lon
                            if (la != null && lo != null) EventMarker(e.stamp.tMonoNs, la, lo, e.severity, e.eventType) else null
                        },
                        badges = CompletenessBadges.badges(run.completenessJson, counts),
                        connShare = share.map { it.key to it.value.toDouble() / total }.sortedByDescending { it.second },
                    )
                }
            }
            _state.value = result.getOrElse { SummaryState(loading = false, error = it.message ?: it.toString()) }
        }
    }

    /** Persists an edited name, then [onSaved]. */
    fun save(name: String, onSaved: () -> Unit) {
        val run = _state.value.run ?: return onSaved()
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == run.name) return onSaved()
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.updateRun(run.copy(name = trimmed)) } }
            _state.value = _state.value.copy(saving = false, run = run.copy(name = trimmed))
            onSaved()
        }
    }
}
