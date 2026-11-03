package app.tracer.ui.live

import app.tracer.core.runtime.FixAction
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.content.Context
import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.model.RunState
import app.tracer.core.runtime.CollectorHealth
import app.tracer.core.runtime.CollectorStatus
import app.tracer.ui.R
import app.tracer.ui.components.map.MapLayer
import app.tracer.ui.components.map.TrackMap
import app.tracer.ui.components.map.mapLayerTitle
import app.tracer.ui.event.EventDetailSheet
import app.tracer.ui.panels.CellsPanel
import app.tracer.ui.panels.GnssPanel
import app.tracer.ui.panels.WifiPanel
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRunRoute(onRunEnded: (runId: String) -> Unit, onOpenSettings: () -> Unit) {
    val vm: LiveRunViewModel = hiltViewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val mapData by vm.mapData.collectAsStateWithLifecycle()
    val layer by vm.layer.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val banners by vm.banners.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val selectedSub by vm.selectedSub.collectAsStateWithLifecycle()
    val protocol by vm.protocol.collectAsStateWithLifecycle()
    val pendingWaypoint by vm.pendingWaypoint.collectAsStateWithLifecycle()
    val ended by rememberUpdatedState(onRunEnded)
    LaunchedEffect(vm) { vm.ended.collect { ended(it) } }

    var showMark by rememberSaveable { mutableStateOf(false) }
    var showEnd by rememberSaveable { mutableStateOf(false) }
    val active = state == RunState.RECORDING || state == RunState.PAUSED

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- map, top ~40 %
            Box(Modifier.fillMaxWidth().weight(0.4f)) {
                TrackMap(
                    points = mapData.points,
                    layer = layer,
                    modifier = Modifier.fillMaxSize(),
                    follow = true,
                    towers = listOfNotNull(mapData.tower),
                    taRing = mapData.taRing,
                    events = mapData.events,
                    onEventClick = vm::openEventAt,
                    selectedEventId = selected?.event?.stamp?.tMonoNs,
                )
                Column(Modifier.statusBarsPadding().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    RunPill(
                        elapsedMs = snap.elapsedMs,
                        state = state,
                        mode = modeLabel(run?.mode),
                        distance = Fmt.distance(snap.distanceM),
                        // protocol countdown: fixed duration − recorded (un-paused) time
                        remainingMs = protocol?.let { p -> (p.durationS * 1_000L - snap.elapsedMs).coerceAtLeast(0L) },
                    )
                    LayerChips(layer, vm::setLayer)
                }
            }
            banners.forEach { b -> CollectorBanner(b, onOpenSettings) }
            // ---- tabs
            val tabs = LiveTab.entries
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
                tabs.forEach { t ->
                    Tab(selected = tab == t, onClick = { vm.setTab(t) }, text = { Text(tabLabel(t)) })
                }
            }
            Box(Modifier.weight(0.6f).fillMaxWidth()) {
                when (tab) {
                    LiveTab.LIVE -> LiveOverview(snap, stats, onEventClick = vm::openEvent)
                    LiveTab.CELLS -> CellsPanel(snap, stats, selectedSub, vm::selectSub)
                    LiveTab.WIFI -> WifiPanel(snap)
                    LiveTab.GNSS -> GnssPanel(snap)
                }
            }
            LiveActionRow(
                state = state, enabled = active,
                onMark = vm::tapWaypoint,
                onMarkLong = { showMark = true },
                onPauseResume = { if (state == RunState.PAUSED) vm.resume() else vm.pause() },
                onEnd = { showEnd = true },
            )
        }
    }

    if (showMark) MarkMomentDialog(onDismiss = { showMark = false }, onConfirm = { note -> vm.markMoment(note); showMark = false })
    pendingWaypoint?.let { p -> WaypointSheet(p, onCommit = vm::commitWaypoint) }
    if (showEnd) {
        AlertDialog(
            onDismissRequest = { showEnd = false },
            title = { Text(stringResource(R.string.live_end_title)) },
            text = { Text(stringResource(R.string.live_end_message)) },
            confirmButton = {
                Button(
                    onClick = { showEnd = false; vm.stop() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text(stringResource(R.string.live_end_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showEnd = false }) { Text(stringResource(R.string.live_cancel)) } },
        )
    }

    selected?.let { sel ->
        val context = rememberTrackSeries(snap.track)
        EventDetailSheet(
            event = sel.event, eventId = sel.eventId, label = sel.label, contextSeries = context,
            onLabelChange = vm::setLabel, onDismiss = vm::closeEvent, runStartMonoNs = snap.startedMonoNs,
        )
    }
}

@Composable
private fun tabLabel(t: LiveTab): String = when (t) {
    LiveTab.LIVE -> stringResource(R.string.live_tab_live)
    LiveTab.CELLS -> stringResource(R.string.live_tab_cells)
    LiveTab.WIFI -> stringResource(R.string.live_tab_wifi)
    LiveTab.GNSS -> stringResource(R.string.live_tab_gnss)
}

/** drive → Car, walk → Walk, rail → Train, … */
@Composable
internal fun modeLabel(mode: String?): String = when (mode) {
    "drive" -> stringResource(R.string.live_mode_drive)
    "walk" -> stringResource(R.string.live_mode_walk)
    "rail" -> stringResource(R.string.live_mode_rail)
    "static" -> stringResource(R.string.live_mode_static)
    "simulated" -> stringResource(R.string.live_mode_simulated)
    null -> ""
    else -> stringResource(R.string.live_mode_custom)
}

/** Horizontal chips selecting the track colouring layer. */
@Composable
internal fun LayerChips(layer: MapLayer, onLayer: (MapLayer) -> Unit, modifier: Modifier = Modifier, layers: List<MapLayer> = MapLayer.entries) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        layers.forEach { l ->
            FilterChip(
                selected = l == layer,
                onClick = { onLayer(l) },
                label = { Text(mapLayerTitle(l)) },
                colors = FilterChipDefaults.filterChipColors(containerColor = LocalTracerColors.current.scrim),
            )
        }
    }
}

/** "06:12" under an hour, "1:54:12" above; rounded up so it never reads 00:00 while running. */
internal fun remainingLabel(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    return if (total >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    else String.format(java.util.Locale.ROOT, "%02d:%02d", total / 60, total % 60)
}

/** Floating 28 dp pill: red dot · 00:42:17 · Train · 61.4 km [· 06:12 left] */
@Composable
private fun RunPill(elapsedMs: Long, state: RunState, mode: String, distance: String, remainingMs: Long? = null) {
    val colors = LocalTracerColors.current
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val dot = when (state) {
                RunState.RECORDING -> colors.critical
                RunState.PAUSED -> colors.warning
                else -> colors.noData
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(Fmt.hms(elapsedMs), style = Mono.value)
            Spacer(Modifier.width(8.dp))
            val sub = listOf(mode, distance).filter { it.isNotEmpty() }.joinToString(" · ")
            val text = if (state == RunState.PAUSED) stringResource(R.string.live_state_paused) + " · " + sub else sub
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (remainingMs != null) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        stringResource(R.string.live_protocol_left, remainingLabel(remainingMs)),
                        style = Mono.small,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** "Waypoint N · add label (optional)" — Save / Skip both record the waypoint with its tap time; dismiss = Skip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointSheet(p: PendingWaypoint, onCommit: (String?) -> Unit) {
    var label by rememberSaveable(p.tappedAt.tMonoNs) { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { onCommit(null) }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_waypoint_title, p.number), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(Fmt.timestamp(p.tappedAt.tUtcMs), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Space.m))
            OutlinedTextField(
                value = label, onValueChange = { label = it.take(80) },
                placeholder = { Text(stringResource(R.string.live_waypoint_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.m))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                TextButton(onClick = { onCommit(null) }) { Text(stringResource(R.string.live_waypoint_skip)) }
                Button(onClick = { onCommit(label) }) { Text(stringResource(R.string.live_waypoint_save)) }
            }
        }
    }
}

/** Square tonal Mark (tap = waypoint, long-press = note) · square tonal Pause · wide End-run pill in errorContainer. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveActionRow(state: RunState, enabled: Boolean, onMark: () -> Unit, onMarkLong: () -> Unit, onPauseResume: () -> Unit, onEnd: () -> Unit) {
    val square = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.l, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tonal square like the icon buttons, but with a long-press (FilledTonalIconButton has none).
        val markCd = stringResource(R.string.live_mark)
        val container = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        val content = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Box(
            Modifier
                .size(56.dp)
                .clip(square)
                .background(container)
                .combinedClickable(enabled = enabled, role = Role.Button, onClick = onMark, onLongClick = onMarkLong)
                .semantics { contentDescription = markCd },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides content) { Icon(Icons.Filled.Bookmark, contentDescription = null) }
        }
        val paused = state == RunState.PAUSED
        FilledTonalIconButton(onClick = onPauseResume, enabled = enabled, shape = square, modifier = Modifier.size(56.dp)) {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = stringResource(if (paused) R.string.live_resume else R.string.live_pause),
            )
        }
        Button(
            onClick = onEnd, enabled = enabled,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.weight(1f).height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.live_end))
        }
    }
}

@Composable
private fun CollectorBanner(status: CollectorStatus, onOpenSettings: () -> Unit) {
    val colors = LocalTracerColors.current
    val tint = if (status.health == CollectorHealth.UNAVAILABLE) colors.critical else colors.warning
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(status.id, style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status.message.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            // Only offer "Fix" when there is something the user can change, and send them to the
            // place that changes it (Android permissions / Location / Developer options — not Tracer settings).
            val fix = status.fix
            if (fix != null) {
                val context = LocalContext.current
                TextButton(onClick = { openFix(context, fix, onOpenSettings) }) { Text(stringResource(R.string.live_fix)) }
            }
        }
    }
}

@Composable
private fun MarkMomentDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_mark_title)) },
        text = {
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                placeholder = { Text(stringResource(R.string.live_mark_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(note) }) { Text(stringResource(R.string.live_mark_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.live_cancel)) } },
    )
}

private fun openFix(context: Context, fix: FixAction, onOpenSettings: () -> Unit) {
    val intent = when (fix) {
        FixAction.APP_PERMISSIONS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        FixAction.LOCATION_SETTINGS -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        FixAction.WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
        FixAction.DEVELOPER_OPTIONS -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        FixAction.TRACER_SETTINGS -> { onOpenSettings(); return }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Developer options hidden / OEM without the screen: fall back to the app's system page.
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
