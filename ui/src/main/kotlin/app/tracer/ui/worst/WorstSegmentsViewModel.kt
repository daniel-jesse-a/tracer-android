package app.tracer.ui.worst

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.analysis.WorstSegments
import app.tracer.core.model.CellSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.Table
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.components.map.MapPoint
import app.tracer.ui.components.map.TrackBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WorstUi(
    val loading: Boolean = true,
    val error: String? = null,
    val runName: String? = null,
    val windowS: Int = 30,
    val segments: List<WorstSegments.Segment> = emptyList(),
    val points: List<MapPoint> = emptyList(),
)

@HiltViewModel
class WorstSegmentsViewModel @Inject constructor(
    private val repository: RunRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(WorstUi())
    val ui: StateFlow<WorstUi> = _ui.asStateFlow()

    private class Inputs(val loc: List<LocationSample>, val serving: List<CellSample>, val probes: List<ProbeSample>, val events: List<EventSample>)

    private var runId: String? = null
    private var inputs: Inputs? = null
    private var rankJob: Job? = null

    fun load(runId: String) {
        if (this.runId == runId) return
        this.runId = runId
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    val run = repository.getRun(runId)
                    val loc = repository.samples(runId, Table.LOCATION).filterIsInstance<LocationSample>()
                    val serving = repository.samples(runId, Table.CELL_SERVING).filterIsInstance<CellSample>()
                    val probes = repository.samples(runId, Table.PROBE).filterIsInstance<ProbeSample>()
                    val events = repository.events(runId).map { it.second }
                    Triple(run?.name, Inputs(loc, serving, probes, events), TrackBuilder.build(loc, serving, probes))
                }
            }
            res.onSuccess { (name, inp, pts) ->
                inputs = inp
                _ui.value = _ui.value.copy(runName = name, points = pts)
                rank(_ui.value.windowS)
            }.onFailure { e -> _ui.value = _ui.value.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    fun setWindow(s: Int) {
        if (s == _ui.value.windowS && !_ui.value.loading) return
        rank(s)
    }

    private fun rank(windowS: Int) {
        val inp = inputs ?: return
        rankJob?.cancel()
        _ui.value = _ui.value.copy(windowS = windowS, loading = true)
        rankJob = viewModelScope.launch {
            val segs = withContext(Dispatchers.Default) {
                WorstSegments.rank(inp.loc, inp.serving, inp.probes, inp.events, windowS = windowS, topN = 20)
            }
            _ui.value = _ui.value.copy(loading = false, segments = segs)
        }
    }
}
