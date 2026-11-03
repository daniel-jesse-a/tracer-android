package app.tracer.ui.worst

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.analysis.WorstSegments
import app.tracer.core.quality.QualityScale
import app.tracer.events.EventType
import app.tracer.ui.R
import app.tracer.ui.components.map.EventMarker
import app.tracer.ui.components.map.MapLayer
import app.tracer.ui.components.map.TrackMap
import app.tracer.ui.event.eventTypeLabel
import app.tracer.ui.live.Fmt
import app.tracer.ui.panels.PanelRow
import app.tracer.ui.theme.GroupedShapes
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space

private val WINDOWS = listOf(15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorstSegmentsRoute(runId: String, onBack: () -> Unit, onOpenAt: (runId: String, monoNs: Long) -> Unit) {
    val vm: WorstSegmentsViewModel = hiltViewModel()
    LaunchedEffect(runId) { vm.load(runId) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.live_back)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.live_worst_title))
                        ui.runName?.let { Text(it, style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
            )
        },
    ) { padding ->
        // markers: rank 1–3 critical, the rest warning (pins also numbered by list order)
        val markers = remember(ui.segments) {
            ui.segments.mapIndexedNotNull { i, s ->
                val la = s.lat; val lo = s.lon
                if (la != null && lo != null) EventMarker(i.toLong(), la, lo, if (i < 3) "critical" else "warning", "#${i + 1}") else null
            }
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.rowGap),
        ) {
            item(key = "map") {
                if (ui.points.isNotEmpty()) {
                    TrackMap(
                        points = ui.points, layer = MapLayer.QUALITY, events = markers,
                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(bottom = Space.s),
                        onEventClick = { i -> ui.segments.getOrNull(i.toInt())?.let { onOpenAt(runId, it.startMonoNs) } },
                    )
                }
            }
            item(key = "window") {
                Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.live_worst_window), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow {
                        WINDOWS.forEachIndexed { i, w ->
                            SegmentedButton(
                                selected = ui.windowS == w, onClick = { vm.setWindow(w) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = WINDOWS.size),
                            ) { Text("$w s", style = Mono.small) }
                        }
                    }
                }
            }
            when {
                ui.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(Space.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                ui.error != null -> item(key = "error") { Text(stringResource(R.string.live_summary_error, ui.error.orEmpty())) }
                ui.segments.isEmpty() -> item(key = "empty") {
                    PanelRow(0, 1) { Text(stringResource(R.string.live_worst_empty), style = MaterialTheme.typography.bodyMedium) }
                }
                else -> itemsIndexed(ui.segments, key = { _, s -> s.startMonoNs }) { i, s ->
                    SegmentRow(i, ui.segments.size, s, onClick = { onOpenAt(runId, s.startMonoNs) })
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(Space.l)) }
        }
    }
}

@Composable
private fun reasonLabel(r: String): String = when (r) {
    "weak_rsrp" -> stringResource(R.string.live_reason_weak_rsrp)
    "no_cell_data" -> stringResource(R.string.live_reason_no_cell)
    "high_latency" -> stringResource(R.string.live_reason_high_latency)
    "packet_loss" -> stringResource(R.string.live_reason_loss)
    else -> if (r in EventType.all) eventTypeLabel(r) else r
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SegmentRow(index: Int, count: Int, s: WorstSegments.Segment, onClick: () -> Unit) {
    val colors = LocalTracerColors.current
    Surface(onClick = onClick, shape = GroupedShapes.of(index, count), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${index + 1}", style = Mono.value, color = if (index < 3) colors.critical else colors.warning)
                Spacer(Modifier.width(Space.m))
                Text(Fmt.timestamp(s.startUtcMs) + "–" + Fmt.timestamp(s.endUtcMs), style = Mono.small, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.live_worst_score, Fmt.d0(s.score)), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.reasons.forEach { r ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(reasonLabel(r), style = Mono.tiny, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
            val rsrpQ = QualityScale.rsrp(s.medianRsrp?.toInt())
            Text(
                listOf(
                    "RSRP ${Fmt.d0(s.medianRsrp)} (${rsrpQ.label})",
                    "${Fmt.d0(s.medianLatencyMs)} ms",
                    "loss ${Fmt.pct(s.lossRate)}%",
                    stringResource(R.string.live_worst_events, s.eventCount),
                    Fmt.distance(s.distanceM),
                ).joinToString(" · "),
                style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
