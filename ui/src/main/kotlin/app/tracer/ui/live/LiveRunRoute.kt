package app.tracer.ui.live

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.model.EventSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.RunState
import app.tracer.core.report.Carrier
import app.tracer.core.report.LiveWords
import app.tracer.core.report.Plain
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.CollectorStatus
import app.tracer.core.runtime.FixAction
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.ui.R
import app.tracer.ui.components.map.MapLayer
import app.tracer.ui.components.map.TrackMap
import app.tracer.ui.components.map.mapLayerTitle
import app.tracer.ui.event.EventDetailSheet
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.Dot
import app.tracer.ui.kit.K
import app.tracer.ui.kit.PillKind
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.SlimBar
import app.tracer.ui.kit.TileRow
import app.tracer.ui.kit.VerdictTile
import app.tracer.ui.kit.tc
import app.tracer.ui.panels.CellsPanel
import app.tracer.ui.panels.GnssPanel
import app.tracer.ui.panels.WifiPanel
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import app.tracer.ui.theme.TType
import kotlin.math.roundToInt

/** Design frame 2: header + progress, plain headline, compact map, 2×2 verdict tiles, "So far", Mark · Pause · Finish. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRunRoute(onRunEnded: (runId: String) -> Unit, onOpenSignals: () -> Unit, onOpenSettings: () -> Unit) {
    val vm: LiveRunViewModel = hiltViewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val mapData by vm.mapData.collectAsStateWithLifecycle()
    val layer by vm.layer.collectAsStateWithLifecycle()
    val banners by vm.banners.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val limitMs by vm.limitMs.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val engineer by vm.engineerView.collectAsStateWithLifecycle()
    val pendingWaypoint by vm.pendingWaypoint.collectAsStateWithLifecycle()
    val ended by rememberUpdatedState(onRunEnded)
    LaunchedEffect(vm) { vm.ended.collect { ended(it) } }

    var showMark by rememberSaveable { mutableStateOf(false) }
    var showEnd by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val active = state == RunState.RECORDING || state == RunState.PAUSED
    val context = LocalContext.current
    val guides = app.tracer.ui.fix.LocalGuides.current

    Screen {
        LiveHeader(run, state, snap.elapsedMs, limitMs)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val wifiRssi = snap.wifi?.rssiDbm
            val (head, sub) = if (timeline.known) {
                LiveWords.headline(timeline.carrier, wifiRssi, timeline.sinceS, timeline.nowS)
            } else {
                (if (state == RunState.PREFLIGHT) "Starting…" else "Checking the network") to "Measuring…"
            }
            Spacer(Modifier.height(14.dp))
            Text(head, style = TType.largeTitle.copy(fontSize = 30.sp, lineHeight = 36.sp), color = tc.text)
            Text(sub, style = TType.sub.copy(fontSize = 17.sp), color = tc.text2, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))

            // ---- compact map
            Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(K.cardRadius)).background(tc.map)) {
                TrackMap(
                    points = mapData.points,
                    layer = layer,
                    modifier = Modifier.fillMaxSize(),
                    follow = true,
                    towers = if (engineer) listOfNotNull(mapData.tower) else emptyList(),
                    taRing = if (engineer) mapData.taRing else null,
                    events = mapData.events,
                    onEventClick = vm::openEventAt,
                    selectedEventId = selected?.event?.stamp?.tMonoNs,
                    showLegend = false,
                )
                val acc = (snap.rawLocation ?: snap.location)?.hAccM
                Box(
                    Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                        .background(Color(0xCC1C1C1E)).padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(if (acc != null) "You · ±${acc.roundToInt()} m" else "You · finding position", style = TType.footnote.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = Color(0xFFF5F5F7))
                }
            }

            // ---- collector problems (Fix routes to the place that changes it)
            if (banners.isNotEmpty()) {
                Spacer(Modifier.height(K.gap))
                Card(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    banners.forEach { b -> BannerLine(b) { fix -> if (!guides.openForCollector(fix)) openFix(context, fix, onOpenSettings) } }
                }
            }

            Spacer(Modifier.height(6.dp))
            Tiles(snap, timeline.carrier, onOpenSignals)

            Spacer(Modifier.height(6.dp))
            SoFar(snap, timeline, onMoment = vm::openEvent)

            if (engineer) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    LinkText("Details") { showDetails = true }
                    LinkText("Signals") { onOpenSignals() }
                    LinkText("Settings") { onOpenSettings() }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // ---- actions
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(K.gap),
        ) {
            ActionPill("Mark", PillKind.SECONDARY, active, Modifier.weight(1f), onClick = vm::tapWaypoint, onLongClick = { showMark = true })
            ActionPill(if (state == RunState.PAUSED) "Resume" else "Pause", PillKind.SECONDARY, active, Modifier.weight(1f),
                onClick = { if (state == RunState.PAUSED) vm.resume() else vm.pause() })
            ActionPill("Finish", PillKind.PRIMARY, active, Modifier.weight(1.15f), onClick = { showEnd = true })
        }
    }

    if (showMark) MarkMomentDialog(onDismiss = { showMark = false }, onConfirm = { note -> vm.markMoment(note); showMark = false })
    pendingWaypoint?.let { p -> WaypointSheet(p, onCommit = vm::commitWaypoint) }
    if (showEnd) {
        val shortRun = snap.elapsedMs < 5 * 60_000L
        AlertDialog(
            onDismissRequest = { showEnd = false },
            containerColor = tc.card,
            title = { Text(if (shortRun) "Finish early?" else "Finish this test?", color = tc.text) },
            text = {
                Text(
                    if (shortRun) "Tests under 5 minutes are marked short and left out of comparisons. Recording stops and the summary opens."
                    else "Recording stops and the summary opens.",
                    color = tc.text2,
                )
            },
            confirmButton = { TextButton(onClick = { showEnd = false; vm.stop() }) { Text(if (shortRun) "Finish anyway" else "Finish", color = tc.link, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { showEnd = false }) { Text("Keep going", color = tc.link) } },
        )
    }
    if (showDetails && engineer) DetailsSheet(vm, snap, onDismiss = { showDetails = false })

    selected?.let { sel ->
        val series = rememberTrackSeries(snap.track)
        EventDetailSheet(
            event = sel.event, eventId = sel.eventId, label = sel.label, contextSeries = series,
            onLabelChange = vm::setLabel, onDismiss = vm::closeEvent, runStartMonoNs = snap.startedMonoNs,
        )
    }
}

/** "● Office · Test 4          01:24 / 10:00" + thin progress line. */
@Composable
private fun LiveHeader(run: RunRecord?, state: RunState, elapsedMs: Long, limitMs: Long?) {
    val title = run?.let { r ->
        val site = r.site
        val n = r.testNumber
        if (site != null && n != null) "$site · Test $n" else r.name
    } ?: "Test"
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(if (state == RunState.RECORDING) tc.bad else tc.text3, 10.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (state == RunState.PAUSED) "$title · Paused" else title,
                style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                Fmt.mmss(elapsedMs) + (limitMs?.let { " / " + Fmt.mmss(it) } ?: ""),
                style = TType.body.merge(TType.num).copy(fontWeight = FontWeight.Normal), color = tc.text3,
            )
        }
        if (limitMs != null && limitMs > 0) {
            Spacer(Modifier.height(10.dp))
            val frac = (elapsedMs.toFloat() / limitMs).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(tc.card2)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(tc.text))
            }
        }
    }
}

/** Wi-Fi · Mobile data · Response · Position. */
@Composable
private fun Tiles(snap: LiveSnapshot, carrier: Carrier, onOpenSignals: () -> Unit) {
    val wifi = snap.wifi
    val rssi = wifi?.rssiDbm
    val wifiV = Verdict.wifiRssi(rssi?.toDouble())
    val serving = snap.primaryServing
    val rsrp = serving?.rsrp
    val mobileV = Verdict.rsrp(rsrp?.toDouble())
    val tech = (snap.rat ?: serving?.rat)?.let { Plain.ratPlain(it) }
    val rtt = snap.latencyMs
    val acc = (snap.rawLocation ?: snap.location)?.hAccM
    TileRow {
        VerdictTile(
            "Wi-Fi", if (rssi != null) Fmt.int(rssi) else "Off", wifiV, Modifier.weight(1f),
            unit = if (rssi != null) "dBm" else null, onClick = onOpenSignals,
            verdictText = if (rssi == null) (if (wifi == null) "Not connected" else "No data") else wifiV.label,
        )
        VerdictTile(
            "Mobile data",
            when {
                tech == null && serving == null -> "No signal"
                else -> "${tech ?: "–"} · ${if (carrier == Carrier.MOBILE) "in use" else "ready"}"
            },
            mobileV, Modifier.weight(1f), onClick = onOpenSignals,
        )
    }
    TileRow {
        VerdictTile("Response", Fmt.d0(rtt), Verdict.response(rtt), Modifier.weight(1f), unit = if (rtt != null) "ms" else null, onClick = onOpenSignals)
        VerdictTile("Position", if (acc != null) "±${acc.roundToInt()}" else Fmt.DASH, Verdict.position(acc), Modifier.weight(1f),
            unit = if (acc != null) "m" else null, onClick = onOpenSignals)
    }
}

/** "So far" card: switches · hops, carrier bar, latest moments. */
@Composable
private fun SoFar(snap: LiveSnapshot, timeline: LiveTimeline, onMoment: (EventSample) -> Unit) {
    val switches = timeline.segments.zipWithNext().count { (a, b) -> a.carrier != b.carrier }
    val hops = snap.recentEvents.count { it.eventType == "wifi_roam" }
    val start = snap.startedMonoNs
    val moments = snap.recentEvents.mapNotNull { e -> Plain.title(e)?.let { e to it } }.take(3)
    Card(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("So far", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3, modifier = Modifier.weight(1f))
            Text(
                if (switches == 0 && hops == 0) "No switches yet"
                else "$switches ${if (switches == 1) "switch" else "switches"} · $hops Wi-Fi ${if (hops == 1) "hop" else "hops"}",
                style = TType.sub.copy(fontSize = 16.sp), color = tc.text3,
            )
        }
        Spacer(Modifier.height(12.dp))
        SlimBar(timeline.segments, timeline.nowS, height = 8.dp)
        if (moments.isNotEmpty()) Spacer(Modifier.height(8.dp))
        moments.forEach { (e, title) ->
            val at = start?.let { (e.stamp.tMonoNs - it) / 1_000_000L }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onMoment(e) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(at?.let { Fmt.mmss(it) } ?: Fmt.DASH, style = TType.sub.copy(fontSize = 16.sp).merge(TType.num), color = tc.text3, modifier = Modifier.width(56.dp))
                Text(title, style = TType.bodyRegular, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "● Wi-Fi scans throttled …  Fix" — compact, no stripe. */
@Composable
private fun BannerLine(status: CollectorStatus, onFix: (FixAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(tc.average, 8.dp)
        Spacer(Modifier.width(10.dp))
        Text(status.message.orEmpty(), style = TType.sub, color = tc.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // Only offer "Fix" when there is something the user can change.
        val fix = status.fix
        if (fix != null) {
            Spacer(Modifier.width(8.dp))
            LinkText("Fix") { onFix(fix) }
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text, style = TType.label.copy(fontSize = 16.sp), color = tc.link,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

/** Kit-style pill with an optional long-press (Mark: tap = waypoint, hold = note). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionPill(
    text: String, kind: PillKind, enabled: Boolean, modifier: Modifier,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, height: Dp = 56.dp,
) {
    val bg = if (kind == PillKind.PRIMARY) tc.primaryBtn else tc.card
    val fg = if (kind == PillKind.PRIMARY) tc.onPrimaryBtn else tc.text
    Box(
        modifier.height(height).clip(RoundedCornerShape(K.pillRadius)).background(if (enabled) bg else tc.card2)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp), color = if (enabled) fg else tc.text3) }
}

/** Engineer view: the v1 tabbed panels (overview · cells · Wi-Fi · GNSS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(vm: LiveRunViewModel, snap: LiveSnapshot, onDismiss: () -> Unit) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val selectedSub by vm.selectedSub.collectAsStateWithLifecycle()
    val layer by vm.layer.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        LayerChips(layer, vm::setLayer, Modifier.padding(horizontal = Space.m, vertical = Space.xs))
        val tabs = LiveTab.entries
        PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
            tabs.forEach { t -> Tab(selected = tab == t, onClick = { vm.setTab(t) }, text = { Text(tabLabel(t)) }) }
        }
        Box(Modifier.fillMaxWidth().height(560.dp)) {
            when (tab) {
                LiveTab.LIVE -> LiveOverview(snap, stats, onEventClick = vm::openEvent)
                LiveTab.CELLS -> CellsPanel(snap, stats, selectedSub, vm::selectSub)
                LiveTab.WIFI -> WifiPanel(snap)
                LiveTab.GNSS -> GnssPanel(snap)
            }
        }
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
