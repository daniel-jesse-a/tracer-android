package app.tracer.ui.preflight

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.analysis.Protocol
import app.tracer.core.config.ProbePaths
import app.tracer.core.config.RunSettings
import app.tracer.ui.R
import app.tracer.ui.common.ReadinessItem
import app.tracer.ui.common.SectionHeader
import app.tracer.ui.common.TonalBadge
import app.tracer.ui.common.resolve
import app.tracer.ui.onboarding.openBatteryExemption
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import kotlinx.coroutines.launch

private val MODES = listOf(
    "walk" to R.string.mode_walk,
    "drive" to R.string.mode_car,
    "rail" to R.string.mode_train,
    "custom" to R.string.mode_custom,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreflightScreen(
    onStarted: () -> Unit,
    onFixPermissions: () -> Unit,
    onOpenCompanion: () -> Unit,
    viewModel: PreflightViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val linkOnStart by viewModel.linkOnStart.collectAsStateWithLifecycle()
    val sim by viewModel.simScenario.collectAsStateWithLifecycle()
    val namePreview by viewModel.namePreview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<IntervalField?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val blockedMsg = stringResource(R.string.preflight_blocked)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            val blocked = ui?.hardBlocked != false
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.preflight_start)) },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                containerColor = if (blocked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = {
                    if (blocked) {
                        scope.launch { snackbar.showSnackbar(blockedMsg) }
                    } else {
                        viewModel.start()
                        onStarted()
                    }
                },
            )
        },
    ) { padding ->
        val u = ui
        val s = settings
        if (u == null || s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.l), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.preflight_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                TonalBadge(stringResource(R.string.preflight_ready_badge, u.ready, u.items.size))
            }

            // 2-column readiness grid
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                u.items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        pair.forEach { item ->
                            ReadinessItem(
                                title = item.title.resolve(),
                                sub = item.sub.resolve(),
                                status = item.status,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onFix = item.fix?.let { fix -> { runFix(context, fix, viewModel, onFixPermissions, onOpenCompanion) } },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            SectionHeader(stringResource(R.string.preflight_setup))
            OutlinedTextField(
                value = setup.site,
                onValueChange = viewModel::setSite,
                label = { Text(stringResource(R.string.preflight_site)) },
                placeholder = { Text(stringResource(R.string.preflight_site_placeholder)) },
                supportingText = {
                    val preview = namePreview
                    if (preview != null) Text(preview, style = Mono.small)
                    else Text(stringResource(R.string.preflight_site_hint))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // With a site the service names the run "Site · T# · date"; the free-text name is the fallback.
            if (setup.siteOrNull == null) {
                Spacer(Modifier.height(Space.s))
                OutlinedTextField(
                    value = setup.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.preflight_run_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Space.m))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MODES.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = setup.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = MODES.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.preflight_run_type), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.xs))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RunType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = setup.runType == t,
                        onClick = { viewModel.setRunType(t) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = RunType.entries.size),
                    ) {
                        Text(stringResource(if (t == RunType.FREE) R.string.preflight_run_type_free else R.string.preflight_run_type_protocol), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(Space.m))
            val protocol = setup.protocol
            if (setup.runType == RunType.PROTOCOL) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Protocol.ALL.forEach { p ->
                        ProtocolRow(p, selected = p.id == setup.protocolId) { viewModel.setProtocol(p.id) }
                    }
                }
                Spacer(Modifier.height(Space.m))
                if (protocol != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.preflight_protocol_fixed, PreflightViewModel.durationLabel(protocol.durationS), pathLabel(protocol.paths)),
                            style = Mono.body,
                            modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
                        )
                    }
                    Spacer(Modifier.height(Space.m))
                }
            } else {
                IntervalRow(s) { editing = it }
                Spacer(Modifier.height(Space.m))
            }

            ToggleRow(
                title = stringResource(R.string.preflight_link_iphone),
                sub = u.partner?.let { p ->
                    val dt = u.deltaMs?.let { stringResource(R.string.preflight_delta, PreflightViewModel.signed(it)) } ?: stringResource(R.string.preflight_delta_unknown)
                    "${p.model.ifBlank { p.platform }} · $dt"
                } ?: stringResource(R.string.preflight_link_none),
                checked = linkOnStart && u.partner != null,
                onCheckedChange = { if (u.partner == null) onOpenCompanion() else viewModel.setLinkOnStart(it) },
                onClick = if (u.partner == null) onOpenCompanion else null,
            )
            if (protocol == null) { // protocol settings are fixed (battery saver off)
                Spacer(Modifier.height(Space.rowGap))
                ToggleRow(
                    title = stringResource(R.string.settings_battery_saver),
                    sub = stringResource(R.string.preflight_battery_saver_sub),
                    checked = setup.batterySaver,
                    onCheckedChange = viewModel::setBatterySaver,
                )
            }
            if (viewModel.isDebugBuild) {
                Spacer(Modifier.height(Space.rowGap))
                ToggleRow(
                    title = stringResource(R.string.preflight_simulate),
                    sub = sim ?: "commute",
                    checked = setup.simulate,
                    onCheckedChange = viewModel::setSimulate,
                )
            }
            Spacer(Modifier.height(112.dp)) // clear the FAB
        }
    }

    editing?.let { field ->
        IntervalDialog(
            field = field,
            current = when (field) {
                IntervalField.SAMPLING -> settings?.locationIntervalMs
                IntervalField.PROBES -> settings?.latencyIntervalMs
                IntervalField.WIFI_SCAN -> settings?.wifiScanIntervalMs
            } ?: 1_000L,
            onDismiss = { editing = null },
            onSave = { ms -> viewModel.setInterval(field, ms); editing = null },
        )
    }
}

private fun runFix(context: Context, fix: FixAction, vm: PreflightViewModel, onPermissions: () -> Unit, onCompanion: () -> Unit) {
    fun open(action: String) = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    when (fix) {
        FixAction.PERMISSIONS -> onPermissions()
        FixAction.LOCATION_SETTINGS -> open(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        FixAction.BATTERY -> openBatteryExemption(context)
        FixAction.DEV_OPTIONS -> open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        FixAction.RETRY_ENDPOINTS -> vm.checkEndpoints()
        FixAction.COMPANION -> onCompanion()
        FixAction.STORAGE -> open(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    }
}

@Composable
internal fun pathLabel(p: ProbePaths): String = stringResource(
    when (p) {
        ProbePaths.DEFAULT -> R.string.paths_default
        ProbePaths.DUAL -> R.string.paths_dual
        ProbePaths.WIFI_ONLY -> R.string.paths_wifi_only
        ProbePaths.CELLULAR_ONLY -> R.string.paths_cellular_only
    },
)

/** One selectable protocol: radio · title · duration (mono) · description. */
@Composable
private fun ProtocolRow(p: Protocol, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(horizontal = Space.s, vertical = Space.s), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(Space.s))
            Column(Modifier.weight(1f).padding(top = Space.s, end = Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(PreflightViewModel.durationLabel(p.durationS), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Tonal 3-cell row: Sampling · Probes · Wi-Fi scan; tap a cell to edit. */
@Composable
private fun IntervalRow(s: RunSettings, onEdit: (IntervalField) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            IntervalCell(stringResource(R.string.preflight_sampling), stringResource(R.string.preflight_hz, PreflightViewModel.rateHz(s.locationIntervalMs)), Modifier.weight(1f)) { onEdit(IntervalField.SAMPLING) }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IntervalCell(stringResource(R.string.preflight_probes), stringResource(R.string.preflight_every_s, PreflightViewModel.seconds(s.latencyIntervalMs)), Modifier.weight(1f)) { onEdit(IntervalField.PROBES) }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IntervalCell(stringResource(R.string.preflight_wifi_scan), stringResource(R.string.preflight_every_s, PreflightViewModel.seconds(s.wifiScanIntervalMs)), Modifier.weight(1f)) { onEdit(IntervalField.WIFI_SCAN) }
        }
    }
}

@Composable
private fun IntervalCell(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(horizontal = Space.m, vertical = Space.m)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = Mono.value)
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onClick: (() -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(sub, style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun IntervalDialog(field: IntervalField, current: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val range = when (field) {
        IntervalField.SAMPLING -> 200L..60_000L
        IntervalField.PROBES -> 500L..60_000L
        IntervalField.WIFI_SCAN -> 5_000L..600_000L
    }
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val value = text.toLongOrNull()
    val valid = value != null && value in range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (field) {
                        IntervalField.SAMPLING -> R.string.preflight_sampling
                        IntervalField.PROBES -> R.string.preflight_probes
                        IntervalField.WIFI_SCAN -> R.string.preflight_wifi_scan
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { t -> text = t.filter { it.isDigit() }.take(7) },
                label = { Text(stringResource(R.string.preflight_interval_ms)) },
                suffix = { Text(stringResource(R.string.unit_ms), style = Mono.small) },
                textStyle = Mono.value,
                isError = !valid,
                supportingText = { Text(stringResource(R.string.settings_range, range.first, range.last)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
