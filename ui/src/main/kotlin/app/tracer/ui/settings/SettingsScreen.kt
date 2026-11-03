package app.tracer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.config.ProbePaths
import app.tracer.core.config.RunSettings
import app.tracer.core.sim.Scenarios
import app.tracer.ui.R
import app.tracer.ui.common.SectionHeader
import app.tracer.ui.common.UiText
import app.tracer.ui.common.resolve
import app.tracer.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: (() -> Unit)? = null, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sim by viewModel.simScenario.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val s = settings
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            SettingsForm(
                s = s,
                sim = sim,
                vm = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun SettingsForm(s: RunSettings, sim: String?, vm: SettingsViewModel, modifier: Modifier) {
    val ms = stringResource(R.string.unit_ms)
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_section_intervals))
        NumberField(stringResource(R.string.settings_location_interval), s.locationIntervalMs, ms, 200, 60_000) { v -> vm.update { it.copy(locationIntervalMs = v) } }
        NumberField(stringResource(R.string.settings_cell_interval), s.cellPollIntervalMs, ms, 250, 60_000) { v -> vm.update { it.copy(cellPollIntervalMs = v) } }
        NumberField(stringResource(R.string.settings_wifi_interval), s.wifiScanIntervalMs, ms, 5_000, 600_000) { v -> vm.update { it.copy(wifiScanIntervalMs = v) } }
        NumberField(stringResource(R.string.settings_latency_interval), s.latencyIntervalMs, ms, 500, 60_000) { v -> vm.update { it.copy(latencyIntervalMs = v) } }
        SwitchRow(stringResource(R.string.settings_adaptive_latency), stringResource(R.string.settings_adaptive_latency_desc), s.adaptiveLatency) { v -> vm.update { it.copy(adaptiveLatency = v) } }
        NumberField(stringResource(R.string.settings_throughput_interval), s.throughputIntervalMs, ms, 10_000, 3_600_000) { v -> vm.update { it.copy(throughputIntervalMs = v) } }
        NumberField(stringResource(R.string.settings_throughput_bytes), s.throughputBytes, stringResource(R.string.unit_bytes), 10_000, 100_000_000) { v -> vm.update { it.copy(throughputBytes = v) } }
        NumberField(stringResource(R.string.settings_public_ip_interval), s.publicIpIntervalMs, ms, 30_000, 3_600_000) { v -> vm.update { it.copy(publicIpIntervalMs = v) } }
        NumberField(stringResource(R.string.settings_device_interval), s.deviceIntervalMs, ms, 1_000, 600_000) { v -> vm.update { it.copy(deviceIntervalMs = v) } }

        SectionHeader(stringResource(R.string.settings_section_collection))
        SwitchRow(stringResource(R.string.settings_battery_saver), stringResource(R.string.settings_battery_saver_desc), s.batterySaver) { v -> vm.update { it.copy(batterySaver = v) } }
        SwitchRow(stringResource(R.string.settings_raw_gnss), stringResource(R.string.settings_raw_gnss_desc), s.gnssRawMeasurements) { v -> vm.update { it.copy(gnssRawMeasurements = v) } }
        SwitchRow(stringResource(R.string.settings_fused), stringResource(R.string.settings_fused_desc), s.useFusedComparison) { v -> vm.update { it.copy(useFusedComparison = v) } }
        SwitchRow(stringResource(R.string.settings_rtt), stringResource(R.string.settings_rtt_desc), s.wifiRttEnabled) { v -> vm.update { it.copy(wifiRttEnabled = v) } }

        SectionHeader(stringResource(R.string.settings_section_probing))
        ProbePathsSelector(s.paths) { p -> vm.update { it.copy(probePaths = p, dualPathProbing = p == ProbePaths.DUAL) } }
        NumberField(stringResource(R.string.settings_cellular_budget), s.cellularDataBudgetMb, stringResource(R.string.unit_mb), 10, 100_000) { v -> vm.update { it.copy(cellularDataBudgetMb = v) } }
        SwitchRow(stringResource(R.string.settings_internal_probes), stringResource(R.string.settings_internal_probes_desc), s.internalProbes) { v -> vm.update { it.copy(internalProbes = v) } }

        SectionHeader(stringResource(R.string.settings_section_endpoints))
        EndpointsEditor(s, vm)
        TextSetting(stringResource(R.string.settings_throughput_url), s.throughputUrl) { v -> if (v.isNotBlank()) vm.update { it.copy(throughputUrl = v.trim()) } }
        TextSetting(stringResource(R.string.settings_public_ip_url), s.publicIpUrl) { v -> if (v.isNotBlank()) vm.update { it.copy(publicIpUrl = v.trim()) } }
        TextSetting(stringResource(R.string.settings_captive_url), s.captiveUrl) { v -> if (v.isNotBlank()) vm.update { it.copy(captiveUrl = v.trim()) } }

        SectionHeader(stringResource(R.string.settings_section_services))
        SecretSetting(stringResource(R.string.settings_opencellid_key), s.openCellIdKey.orEmpty()) { v -> vm.update { it.copy(openCellIdKey = v.trim().ifEmpty { null }) } }
        TextSetting(stringResource(R.string.settings_upload_url), s.uploadUrl.orEmpty()) { v -> vm.update { it.copy(uploadUrl = v.trim().ifEmpty { null }) } }

        SectionHeader(stringResource(R.string.settings_section_storage))
        NumberField(stringResource(R.string.settings_retention_days), s.retentionDays.toLong(), stringResource(R.string.unit_days), 1, 3650) { v -> vm.update { it.copy(retentionDays = v.toInt()) } }
        NumberField(stringResource(R.string.settings_min_free_storage), s.minFreeStorageMb, stringResource(R.string.unit_mb), 50, 100_000) { v -> vm.update { it.copy(minFreeStorageMb = v) } }

        SectionHeader(stringResource(R.string.settings_section_thresholds))
        ThresholdsEditor(s, vm)

        if (vm.isDebugBuild) {
            SectionHeader(stringResource(R.string.settings_section_debug))
            ScenarioPicker(sim, vm::setSimScenario)
        }
        Spacer(Modifier.height(32.dp))
    }
}

private val PATH_OPTIONS = listOf(
    ProbePaths.DEFAULT to R.string.settings_paths_default,
    ProbePaths.DUAL to R.string.settings_paths_dual,
    ProbePaths.WIFI_ONLY to R.string.settings_paths_wifi,
    ProbePaths.CELLULAR_ONLY to R.string.settings_paths_cellular,
)

/** Default | Dual | Wi-Fi only | Cellular only → RunSettings.probePaths, with a one-line explanation of the choice. */
@Composable
private fun ProbePathsSelector(current: ProbePaths, onSelect: (ProbePaths) -> Unit) {
    Column {
        Text(stringResource(R.string.settings_probe_paths), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PATH_OPTIONS.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = current == p,
                    onClick = { onSelect(p) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = PATH_OPTIONS.size),
                    icon = {},
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                when (current) {
                    ProbePaths.DEFAULT -> R.string.settings_paths_default_desc
                    ProbePaths.DUAL -> R.string.settings_paths_dual_desc
                    ProbePaths.WIFI_ONLY -> R.string.settings_paths_wifi_desc
                    ProbePaths.CELLULAR_ONLY -> R.string.settings_paths_cellular_desc
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NumberField(label: String, value: Long, unit: String, min: Long, max: Long, onValue: (Long) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    val parsed = text.toLongOrNull()
    val valid = parsed != null && parsed in min..max
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(12)
            val p = text.toLongOrNull()
            if (p != null && p in min..max) onValue(p)
        },
        label = { Text(label) },
        suffix = { Text(unit, style = Mono.small) },
        textStyle = Mono.value,
        isError = !valid,
        supportingText = if (valid) null else { { Text(stringResource(R.string.settings_range, min, max)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextSetting(label: String, value: String, onValue: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretSetting(label: String, value: String, onValue: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value) }
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) R.string.settings_hide_secret else R.string.settings_show_secret),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EndpointsEditor(s: RunSettings, vm: SettingsViewModel) {
    s.endpoints.forEach { e ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.label, style = MaterialTheme.typography.bodyLarge)
                Text(e.url, style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.removeEndpoint(e.url) }, enabled = s.endpoints.size > 1) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_endpoint_remove, e.label))
            }
        }
        HorizontalDivider()
    }
    var url by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    val err = error
    OutlinedTextField(
        value = label, onValueChange = { label = it },
        label = { Text(stringResource(R.string.settings_endpoint_label)) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = url, onValueChange = { url = it; error = null },
        label = { Text(stringResource(R.string.settings_endpoint_url)) },
        singleLine = true, isError = err != null,
        supportingText = if (err != null) { { Text(err.resolve()) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = {
        error = vm.addEndpoint(url, label)
        if (error == null) { url = ""; label = "" }
    }) { Text(stringResource(R.string.settings_endpoint_add)) }
}

@Composable
private fun ThresholdsEditor(s: RunSettings, vm: SettingsViewModel) {
    var text by rememberSaveable { mutableStateOf(SettingsViewModel.prettyThresholds(s.thresholds)) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var applied by remember { mutableStateOf(false) }
    val err = error
    Text(stringResource(R.string.settings_thresholds_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = null; applied = false },
        textStyle = Mono.small,
        isError = err != null,
        supportingText = when {
            err != null -> { { Text(err.resolve()) } }
            applied -> { { Text(stringResource(R.string.settings_thresholds_applied)) } }
            else -> null
        },
        minLines = 8,
        maxLines = 20,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            error = vm.applyThresholds(text)
            applied = error == null
        }) { Text(stringResource(R.string.settings_thresholds_apply)) }
        TextButton(onClick = {
            vm.resetThresholds()
            text = SettingsViewModel.prettyThresholds(app.tracer.core.config.Thresholds())
            error = null
            applied = true
        }) { Text(stringResource(R.string.settings_thresholds_reset)) }
    }
}

@Composable
private fun ScenarioPicker(current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(stringResource(R.string.settings_sim_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(current ?: stringResource(R.string.settings_sim_off), style = Mono.small)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.settings_sim_off)) }, onClick = { onPick(null); open = false })
            Scenarios.names.forEach { name ->
                DropdownMenuItem(text = { Text(name, style = Mono.small) }, onClick = { onPick(name); open = false })
            }
        }
    }
}
