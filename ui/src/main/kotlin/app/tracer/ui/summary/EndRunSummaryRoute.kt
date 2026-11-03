package app.tracer.ui.summary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.quality.QualityScale
import app.tracer.ui.R
import app.tracer.ui.common.MetricTile
import app.tracer.ui.components.map.MapLayer
import app.tracer.ui.components.map.TrackMap
import app.tracer.ui.live.Fmt
import app.tracer.ui.live.modeLabel
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space

@Composable
fun EndRunSummaryRoute(runId: String, onDone: () -> Unit, onOpenReplay: (String) -> Unit, onExport: (String) -> Unit) {
    val vm: EndRunSummaryViewModel = hiltViewModel()
    LaunchedEffect(runId) { vm.load(runId) }
    val st by vm.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when {
            st.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            st.error != null || st.run == null -> Column(Modifier.fillMaxSize().padding(Space.xl), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.live_summary_error, st.error.orEmpty()), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Space.l))
                Button(onClick = onDone) { Text(stringResource(R.string.live_summary_done)) }
            }
            else -> SummaryContent(st, runId, onSave = { name -> vm.save(name, onDone) }, onOpenReplay, onExport)
        }
    }
}

@Composable
private fun SummaryContent(st: SummaryState, runId: String, onSave: (String) -> Unit, onOpenReplay: (String) -> Unit, onExport: (String) -> Unit) {
    val run = st.run!!
    val s = st.summary
    var name by rememberSaveable(run.runId) { mutableStateOf(run.name) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.live_summary_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                listOf(modeLabel(run.mode), Fmt.timestamp(run.startedAt)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.live_summary_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (s != null) {
                val tiles = listOf(
                    Triple(stringResource(R.string.live_summary_duration), Fmt.clock(s.durationMs), null),
                    Triple(stringResource(R.string.live_summary_distance), Fmt.distance(s.distanceM), null),
                    Triple(stringResource(R.string.live_summary_events), s.eventsByType.values.sum().toString(), null),
                    Triple(stringResource(R.string.live_summary_drops), st.drops.toString(), null),
                )
                TileRow(tiles)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetricTile(stringResource(R.string.live_summary_rsrp), Fmt.d0(s.medianRsrp), "dBm",
                        QualityScale.rsrp(s.medianRsrp?.toInt()), Modifier.weight(1f), sub = s.p10Rsrp?.let { "p10 ${Fmt.d0(it)}" })
                    MetricTile(stringResource(R.string.live_summary_latency), Fmt.d0(s.medianLatencyMs), "ms",
                        QualityScale.latency(s.medianLatencyMs), Modifier.weight(1f), sub = s.p95LatencyMs?.let { "p95 ${Fmt.d0(it)}" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetricTile(stringResource(R.string.live_summary_loss), Fmt.d1(s.lossRate?.let { it * 100 }), "%", null, Modifier.weight(1f))
                    MetricTile(stringResource(R.string.live_summary_mbps), Fmt.d1(s.medianMbps), "Mb/s", null, Modifier.weight(1f))
                    MetricTile(stringResource(R.string.live_summary_handovers), s.handovers.toString(), null, null, Modifier.weight(1f))
                }
            }
            BadgeRow(st.badges)
            if (st.connShare.isNotEmpty()) ConnShareBar(st)
            if (st.points.isNotEmpty()) {
                TrackMap(
                    points = st.points, layer = MapLayer.QUALITY, events = st.events,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
            Spacer(Modifier.height(Space.s))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = { onOpenReplay(runId) }) { Text(stringResource(R.string.live_summary_replay)) }
            FilledTonalButton(onClick = { onExport(runId) }) { Text(stringResource(R.string.live_summary_export)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onSave(name) }, enabled = !st.saving) { Text(stringResource(R.string.live_summary_save)) }
        }
    }
}

@Composable
private fun TileRow(tiles: List<Triple<String, String, String?>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.forEach { (l, v, u) -> MetricTile(l, v, u, null, Modifier.weight(1f)) }
    }
}

/** GPS ✓ NET ✓ PRB ✓ RF ✓ GNSS ✓ — tonal chips; pending dashed; missing ✕; n/a. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgeRow(badges: List<Badge>) {
    val colors = LocalTracerColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.forEach { b ->
            val (suffix, fg) = when (b.state) {
                Badge.State.OK -> "✓" to colors.ok
                Badge.State.PARTIAL -> "${Fmt.pct(b.ratio)}%" to colors.warning
                Badge.State.MISSING -> "✕" to colors.critical
                Badge.State.PENDING -> "…" to colors.warning
                Badge.State.NA -> "n/a" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val shape = RoundedCornerShape(6.dp)
            if (b.state == Badge.State.PENDING) {
                // dashed outline for pending
                Box {
                    Canvas(Modifier.matchParentSize()) {
                        drawRoundRect(fg, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                    }
                    Text("${b.label} $suffix", style = Mono.tiny, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            } else {
                Surface(shape = shape, color = MaterialTheme.colorScheme.secondaryContainer, border = if (b.state == Badge.State.MISSING) BorderStroke(1.dp, fg) else null) {
                    Text("${b.label} $suffix", style = Mono.tiny, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

/** Connection-type share as a segmented bar with named percentages (never colour alone). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnShareBar(st: SummaryState) {
    val colors = LocalTracerColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.live_summary_conn_share), style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            var x = 0f
            st.connShare.forEach { (c, f) ->
                val w = size.width * f.toFloat()
                drawRect(colors.conn(c), Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            st.connShare.forEach { (c, f) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("■", style = Mono.tiny, color = colors.conn(c))
                    Spacer(Modifier.width(3.dp))
                    Text("${c.label} ${Fmt.pct(f)}%", style = Mono.tiny)
                }
            }
        }
    }
}
