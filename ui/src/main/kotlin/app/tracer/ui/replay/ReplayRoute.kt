package app.tracer.ui.replay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.ui.R
import app.tracer.ui.components.chart.ChartSeries
import app.tracer.ui.components.chart.NumericSeries
import app.tracer.ui.components.chart.SeriesFactory
import app.tracer.ui.components.chart.TimelineChart
import app.tracer.ui.components.map.MapLayer
import app.tracer.ui.components.map.TrackMap
import app.tracer.ui.components.map.pointAt
import app.tracer.ui.event.EventDetailSheet
import app.tracer.ui.live.Fmt
import app.tracer.ui.live.LayerChips
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive

private val SPEEDS = listOf(1, 2, 5, 10, 30, 60)
private val REPLAY_LAYERS = listOf(MapLayer.QUALITY, MapLayer.TYPE, MapLayer.LATENCY, MapLayer.THROUGHPUT, MapLayer.GPS)

/** Labels + "no X data" reasons for every lane (resolved once per composition). */
private class LaneText(
    val type: String, val rat: String, val rsrp: String, val sinr: String, val lat: String, val loss: String,
    val thru: String, val wifi: String, val gnss: String, val linkedLat: String, val linkedRsrp: String,
    val noRadio: String, val noCell: String, val noProbe: String, val noThru: String, val noWifi: String, val noGnss: String,
)

@Composable
private fun laneText() = LaneText(
    stringResource(R.string.live_lane_type), stringResource(R.string.live_lane_rat), stringResource(R.string.live_lane_rsrp),
    stringResource(R.string.live_lane_sinr), stringResource(R.string.live_lane_latency), stringResource(R.string.live_lane_loss),
    stringResource(R.string.live_lane_thru), stringResource(R.string.live_lane_wifi), stringResource(R.string.live_lane_gnss),
    stringResource(R.string.live_lane_linked_lat), stringResource(R.string.live_lane_linked_rsrp),
    stringResource(R.string.live_nodata_radio), stringResource(R.string.live_nodata_cell), stringResource(R.string.live_nodata_probe),
    stringResource(R.string.live_nodata_thru), stringResource(R.string.live_nodata_wifi), stringResource(R.string.live_nodata_gnss),
)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReplayRoute(runId: String, atMonoNs: Long?, onBack: () -> Unit, onWorstSegments: (String) -> Unit, onExport: (String) -> Unit) {
    val vm: ReplayViewModel = hiltViewModel()
    LaunchedEffect(runId) { vm.load(runId, atMonoNs) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cursor by vm.cursor.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val layer by vm.layer.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }

    // playback: advance the cursor by real frame time × speed
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive && vm.playing.value) {
            withFrameNanos { now -> vm.advance(now - last); last = now }
        }
    }
    // map cursor throttled to 1 Hz while playing (responsive while scrubbing)
    var mapCursorNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) { vm.cursor.sample(if (playing) 1_000L else 150L).collect { mapCursorNs = it } }

    val rerunMsg = ui.rerunResult?.let { stringResource(R.string.live_replay_rerun_done, it) }
    LaunchedEffect(rerunMsg) { if (rerunMsg != null) { snackbar.showSnackbar(rerunMsg); vm.clearRerunResult() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.live_back)) } },
                title = {
                    Column {
                        Text(ui.data?.run?.name ?: stringResource(R.string.live_replay_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ui.data?.linkedName?.let {
                            Text(stringResource(R.string.live_replay_linked, it), style = Mono.tiny, color = LocalTracerColors.current.linkedRun)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.live_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.live_replay_rerun)) }, enabled = !ui.rerunning,
                            onClick = { menu = false; vm.rerunRules() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.live_replay_worst)) }, onClick = { menu = false; onWorstSegments(runId) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.live_replay_export)) }, onClick = { menu = false; onExport(runId) })
                    }
                },
            )
        },
    ) { padding ->
        val d = ui.data
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                d == null -> Text(stringResource(R.string.live_summary_error, ui.error.orEmpty()), Modifier.align(Alignment.Center).padding(Space.xl))
                else -> ReplayContent(
                    d, cursor, mapCursorNs, playing, speed, layer, ui.rerunning, selected?.id,
                    onLayer = vm::setLayer, onScrub = vm::scrub, onTogglePlay = vm::togglePlay,
                    onSpeed = vm::setSpeed, onPrev = vm::prevEvent, onNext = vm::nextEvent,
                    onEventId = vm::openEvent, onEventIndex = vm::openEventIndex,
                )
            }
        }
    }

    val d = ui.data
    selected?.let { sel ->
        if (d != null) {
            val context = rememberReplaySeries(d, laneText()).filterIsInstance<NumericSeries>()
                .filter { it.label in setOf(stringResource(R.string.live_lane_rsrp), stringResource(R.string.live_lane_sinr),
                    stringResource(R.string.live_lane_latency), stringResource(R.string.live_lane_gnss)) }
            EventDetailSheet(
                event = sel.event, eventId = sel.id, label = sel.label, contextSeries = context,
                onLabelChange = vm::setLabel, onDismiss = vm::closeEvent, runStartMonoNs = d.startNs,
            )
        }
    }
}

@Composable
private fun rememberReplaySeries(d: ReplayData, t: LaneText): List<ChartSeries> {
    val c = LocalTracerColors.current
    return remember(d, c) {
        buildList<ChartSeries> {
            add(SeriesFactory.connType(t.type, d.type.xs, d.type.values, c, t.noRadio))
            add(SeriesFactory.connType(t.rat, d.rat.xs, d.rat.values, c, t.noRadio))
            add(SeriesFactory.rsrp(t.rsrp, d.rsrp.xs, d.rsrp.ys, c, t.noCell))
            add(SeriesFactory.sinr(t.sinr, d.sinr.xs, d.sinr.ys, c, t.noCell))
            add(SeriesFactory.latency(t.lat, d.latency.xs, d.latency.ys, d.latency.failed, c, t.noProbe))
            add(SeriesFactory.loss(t.loss, d.loss.xs, d.loss.ys, c, t.noProbe))
            add(SeriesFactory.throughput(t.thru, d.throughput.xs, d.throughput.ys, c, t.noThru))
            add(SeriesFactory.wifiRssi(t.wifi, d.wifi.xs, d.wifi.ys, c, t.noWifi))
            add(SeriesFactory.satellites(t.gnss, d.gnssUsed.xs, d.gnssUsed.ys, c, t.noGnss))
            d.linkedLatency?.let {
                add(NumericSeries(t.linkedLat, "ms", it.xs, it.ys, 0f, 400f, c.linkedRun, threshold = 250f, failed = it.failed))
            }
            d.linkedRsrp?.let { add(NumericSeries(t.linkedRsrp, "dBm", it.xs, it.ys, -140f, -44f, c.linkedRun, threshold = -110f)) }
        }
    }
}

@Composable
private fun ReplayContent(
    d: ReplayData, cursor: Long, mapCursorNs: Long, playing: Boolean, speed: Int, layer: MapLayer, rerunning: Boolean, selectedId: Long?,
    onLayer: (MapLayer) -> Unit, onScrub: (Long) -> Unit, onTogglePlay: () -> Unit, onSpeed: (Int) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, onEventId: (Long) -> Unit, onEventIndex: (Int) -> Unit,
) {
    val series = rememberReplaySeries(d, laneText())
    val ticks = remember(d) { d.events.map { it.second.stamp.tMonoNs to it.second.severity } }
    val mapCursor = remember(d, mapCursorNs) { pointAt(d.points, mapCursorNs) }
    Column(Modifier.fillMaxSize()) {
        if (rerunning) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().weight(0.4f).padding(horizontal = Space.m)) {
            TrackMap(
                points = d.points, layer = layer, modifier = Modifier.fillMaxSize(),
                events = d.markers, overlay = d.overlay, cursor = mapCursor,
                onEventClick = onEventId, selectedEventId = selectedId,
            )
            LayerChips(layer, onLayer, Modifier.padding(Space.s), layers = REPLAY_LAYERS)
        }
        // playback controls
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            IconButton(onClick = onPrev) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.live_replay_prev)) }
            FilledIconButton(onClick = onTogglePlay) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.live_pause else R.string.live_replay_play))
            }
            IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, stringResource(R.string.live_replay_next)) }
            Spacer(Modifier.weight(1f))
            Text("${Fmt.clock((cursor - d.startNs) / 1_000_000)} / ${Fmt.clock((d.endNs - d.startNs) / 1_000_000)}", style = Mono.small)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.m)) {
            SPEEDS.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = speed == s, onClick = { onSpeed(s) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = SPEEDS.size),
                    icon = {},
                ) { Text("$s×", style = Mono.small) }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(0.6f).padding(Space.m),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.m)) {
                TimelineChart(
                    series = series, startNs = d.startNs, endNs = d.endNs, cursorNs = cursor, events = ticks,
                    onScrub = onScrub, onEventTap = onEventIndex,
                )
                Spacer(Modifier.height(Space.s))
                Text(stringResource(R.string.live_replay_hint), style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
