package app.tracer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.config.ProbePaths
import app.tracer.core.config.RunSettings
import app.tracer.core.config.Thresholds
import app.tracer.core.sim.Scenarios
import app.tracer.ui.kit.Caption
import app.tracer.ui.kit.K
import app.tracer.ui.kit.ListRow
import app.tracer.ui.kit.RadioDot
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.Segmented
import app.tracer.ui.kit.Toggle
import app.tracer.ui.kit.TopLinks
import app.tracer.ui.kit.tc
import app.tracer.ui.theme.TType

/*
 * Frame 10 — Settings. Light/Dark/Auto, test defaults, data & battery, companions, advanced
 * (engineer view, check interval, test servers) and — with engineer view on — every v1 detailed
 * setting in the same row style.
 */

private class PickerSpec(val title: String, val options: List<String>, val selected: Int, val onPick: (Int) -> Unit)
private class NumberSpec(val title: String, val value: Long, val unit: String, val min: Long, val max: Long, val onSave: (Long) -> Unit)
private class TextSpec(val title: String, val value: String, val secret: Boolean, val onSave: (String) -> Unit)

private val THEMES = listOf("light" to "Light", "dark" to "Dark", "auto" to "Auto")
private val NETWORKS = listOf("dual" to "Wi-Fi + mobile", "wifi" to "Wi-Fi only", "cellular" to "Mobile only")
private val LENGTHS = listOf(5 to "5 min", 10 to "10 min", 15 to "15 min", 0 to "No limit")
private val MOVEMENTS = listOf("walk" to "Walk", "drive" to "Drive", "rail" to "Train", "static" to "Stay still")
private val SPEED_SIZES_MB = listOf(1L, 5L, 10L, 25L, 50L)
private val CHECK_INTERVALS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L)
private val PATHS = listOf(
    ProbePaths.DEFAULT to "Default network", ProbePaths.DUAL to "Dual (Wi-Fi + mobile)",
    ProbePaths.WIFI_ONLY to "Wi-Fi only", ProbePaths.CELLULAR_ONLY to "Mobile only",
)

@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    onOpenCompanions: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPhoneSetup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sim by viewModel.simScenario.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val engineer by viewModel.engineerView.collectAsStateWithLifecycle()
    val networks by viewModel.defaultNetworks.collectAsStateWithLifecycle()
    val lengthMin by viewModel.defaultLengthMin.collectAsStateWithLifecycle()
    val movement by viewModel.defaultMovement.collectAsStateWithLifecycle()
    val linked by viewModel.linkedDevices.collectAsStateWithLifecycle()
    val autoEmail by viewModel.autoEmail.collectAsStateWithLifecycle()
    val recipients by viewModel.emailRecipients.collectAsStateWithLifecycle()
    val emailZip by viewModel.emailZip.collectAsStateWithLifecycle()

    var picker by remember { mutableStateOf<PickerSpec?>(null) }
    var number by remember { mutableStateOf<NumberSpec?>(null) }
    var text by remember { mutableStateOf<TextSpec?>(null) }
    var serversOpen by remember { mutableStateOf(false) }
    var thresholdsOpen by remember { mutableStateOf(false) }

    Screen {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            if (onBack != null) TopLinks(back = "Live", onBack = onBack) else Spacer(Modifier.height(36.dp))
            Text("Settings", style = TType.largeTitle, color = tc.text, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))

            val themeIdx = THEMES.indexOfFirst { it.first == theme }.coerceAtLeast(0)
            Segmented(THEMES.map { it.second }, themeIdx, { i -> viewModel.setThemeMode(THEMES[i].first) })

            val s = settings

            val limits = app.tracer.ui.fix.LocalGuides.current.limits
            Caption("Phone")
            Group {
                ListRow("Phone setup", if (limits.isEmpty()) "All set" else "${limits.size} to fix", onClick = onOpenPhoneSetup,
                    leading = { app.tracer.ui.kit.Dot(if (limits.isEmpty()) tc.veryGood else tc.belowAvg, 10.dp) })
            }

            Caption("Test defaults")
            Group {
                val ni = NETWORKS.indexOfFirst { it.first == networks }.coerceAtLeast(0)
                ListRow("Networks", NETWORKS[ni].second, onClick = {
                    picker = PickerSpec("Networks", NETWORKS.map { it.second }, ni) { viewModel.setDefaultNetworks(NETWORKS[it].first) }
                })
                Sep()
                val li = LENGTHS.indexOfFirst { it.first == lengthMin }
                ListRow("Length", if (li >= 0) LENGTHS[li].second else "$lengthMin min", onClick = {
                    picker = PickerSpec("Length", LENGTHS.map { it.second }, li) { viewModel.setDefaultLengthMin(LENGTHS[it].first) }
                })
                Sep()
                val mi = MOVEMENTS.indexOfFirst { it.first == movement }.coerceAtLeast(0)
                ListRow("Movement", MOVEMENTS[mi].second, onClick = {
                    picker = PickerSpec("Movement", MOVEMENTS.map { it.second }, mi) { viewModel.setDefaultMovement(MOVEMENTS[it].first) }
                })
            }

            Caption("Data & battery")
            Group {
                val bytes = s?.throughputBytes
                ListRow("Speed test size", bytes?.let { mb(it) } ?: "—", onClick = {
                    val cur = s ?: return@ListRow
                    val si = SPEED_SIZES_MB.indexOfFirst { it * 1_000_000L == cur.throughputBytes }
                    picker = PickerSpec("Speed test size", SPEED_SIZES_MB.map { "$it MB" }, si) { i ->
                        viewModel.update { it.copy(throughputBytes = SPEED_SIZES_MB[i] * 1_000_000L) }
                    }
                })
                Sep()
                ListRow("Battery saver", trailing = {
                    Toggle(s?.batterySaver == true) { on -> viewModel.update { it.copy(batterySaver = on) } }
                })
            }

            Caption("Email reports")
            Group {
                ToggleRow("Email after each test", "Opens a ready-to-send email with the report when a test ends", autoEmail) { viewModel.setAutoEmail(it) }
                Sep()
                ListRow("Recipients", if (recipients.size == 1) recipients[0] else "${recipients.size} people", onClick = {
                    text = TextSpec("Recipients (comma separated)", recipients.joinToString(", "), false) { v ->
                        viewModel.setEmailRecipients(v.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.contains('@') })
                    }
                })
                Sep()
                ToggleRow("Attach full data", "ZIP for engineers, when under 17 MB", emailZip) { viewModel.setEmailZip(it) }
            }

            Caption("Companions")
            Group {
                ListRow("Devices", "$linked linked", onClick = onOpenCompanions)
            }

            Caption("Advanced")
            Group {
                ListRow("Engineer view", trailing = { Toggle(engineer) { viewModel.setEngineerView(it) } })
                Sep()
                ListRow("Check intervals", s?.let { ms(it.latencyIntervalMs) } ?: "—", onClick = {
                    val cur = s ?: return@ListRow
                    val ci = CHECK_INTERVALS_MS.indexOf(cur.latencyIntervalMs)
                    picker = PickerSpec("Check intervals", CHECK_INTERVALS_MS.map { ms(it) }, ci) { i ->
                        viewModel.update { it.copy(latencyIntervalMs = CHECK_INTERVALS_MS[i]) }
                    }
                })
                Sep()
                ListRow("Test servers", s?.endpoints?.size?.toString() ?: "—", onClick = { serversOpen = true })
            }

            if (engineer && s != null) {
                EngineerSections(
                    s = s, sim = sim, debug = viewModel.isDebugBuild, vm = viewModel,
                    onPicker = { picker = it }, onNumber = { number = it }, onText = { text = it },
                    onThresholds = { thresholdsOpen = true }, onOpenLibrary = onOpenLibrary,
                )
            }

            Caption("About")
            Group {
                ListRow("Version", viewModel.versionName, chevron = true)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    picker?.let { p -> PickerSheet(p) { picker = null } }
    number?.let { n -> NumberDialog(n) { number = null } }
    text?.let { t -> TextDialog(t) { text = null } }
    val cur = settings
    if (serversOpen && cur != null) ServersSheet(cur, engineer, viewModel) { serversOpen = false }
    if (thresholdsOpen && cur != null) ThresholdsDialog(cur.thresholds, viewModel) { thresholdsOpen = false }
}

@Composable
private fun EngineerSections(
    s: RunSettings, sim: String?, debug: Boolean, vm: SettingsViewModel,
    onPicker: (PickerSpec) -> Unit, onNumber: (NumberSpec) -> Unit, onText: (TextSpec) -> Unit,
    onThresholds: () -> Unit, onOpenLibrary: () -> Unit,
) {
    Caption("Engineer")
    Group { ListRow("All runs", sub = "Run library, import, replay, export", onClick = onOpenLibrary) }

    Caption("Engineer · Intervals")
    Group {
        MsRow("Location", s.locationIntervalMs, 200, 60_000, onNumber) { v -> vm.update { it.copy(locationIntervalMs = v) } }
        Sep()
        MsRow("Cell poll", s.cellPollIntervalMs, 250, 60_000, onNumber) { v -> vm.update { it.copy(cellPollIntervalMs = v) } }
        Sep()
        MsRow("Wi-Fi scan", s.wifiScanIntervalMs, 5_000, 600_000, onNumber) { v -> vm.update { it.copy(wifiScanIntervalMs = v) } }
        Sep()
        MsRow("Latency probe", s.latencyIntervalMs, 500, 60_000, onNumber) { v -> vm.update { it.copy(latencyIntervalMs = v) } }
        Sep()
        ToggleRow("Adaptive latency probing", "Probe faster while the link is degraded", s.adaptiveLatency) { v -> vm.update { it.copy(adaptiveLatency = v) } }
        Sep()
        MsRow("Throughput test", s.throughputIntervalMs, 10_000, 3_600_000, onNumber) { v -> vm.update { it.copy(throughputIntervalMs = v) } }
        Sep()
        ListRow("Throughput download size", "${s.throughputBytes} B", onClick = {
            onNumber(NumberSpec("Throughput download size", s.throughputBytes, "bytes", 10_000, 100_000_000) { v -> vm.update { it.copy(throughputBytes = v) } })
        })
        Sep()
        MsRow("Public IP check", s.publicIpIntervalMs, 30_000, 3_600_000, onNumber) { v -> vm.update { it.copy(publicIpIntervalMs = v) } }
        Sep()
        MsRow("Device (battery, thermal)", s.deviceIntervalMs, 1_000, 600_000, onNumber) { v -> vm.update { it.copy(deviceIntervalMs = v) } }
    }

    Caption("Engineer · Collection")
    Group {
        ToggleRow("Raw GNSS measurements", "Logs pseudoranges and clock (large)", s.gnssRawMeasurements) { v -> vm.update { it.copy(gnssRawMeasurements = v) } }
        Sep()
        ToggleRow("Fused location comparison", "Also log the fused provider next to raw GPS", s.useFusedComparison) { v -> vm.update { it.copy(useFusedComparison = v) } }
        Sep()
        ToggleRow("Wi-Fi RTT ranging", "802.11mc/az distance to access points that support it", s.wifiRttEnabled) { v -> vm.update { it.copy(wifiRttEnabled = v) } }
    }

    Caption("Engineer · Probing")
    Group {
        val pi = PATHS.indexOfFirst { it.first == s.paths }
        ListRow("Probe paths", if (pi >= 0) PATHS[pi].second else s.paths.key, sub = pathDesc(s.paths), onClick = {
            onPicker(PickerSpec("Probe paths", PATHS.map { it.second }, pi) { i ->
                val p = PATHS[i].first
                vm.update { it.copy(probePaths = p, dualPathProbing = p == ProbePaths.DUAL) }
            })
        })
        Sep()
        ListRow("Mobile-data budget per run", "${s.cellularDataBudgetMb} MB", onClick = {
            onNumber(NumberSpec("Mobile-data budget per run", s.cellularDataBudgetMb, "MB", 10, 100_000) { v -> vm.update { it.copy(cellularDataBudgetMb = v) } })
        })
        Sep()
        ToggleRow("Internal probes", "Ping the default gateway and query each DHCP DNS server", s.internalProbes) { v -> vm.update { it.copy(internalProbes = v) } }
    }

    Caption("Engineer · Endpoints")
    Group {
        UrlRow("Throughput URL", "{bytes} = size", s.throughputUrl, onText) { v -> if (v.isNotBlank()) vm.update { it.copy(throughputUrl = v.trim()) } }
        Sep()
        UrlRow("Public IP URL", null, s.publicIpUrl, onText) { v -> if (v.isNotBlank()) vm.update { it.copy(publicIpUrl = v.trim()) } }
        Sep()
        UrlRow("Captive portal check URL", null, s.captiveUrl, onText) { v -> if (v.isNotBlank()) vm.update { it.copy(captiveUrl = v.trim()) } }
    }

    Caption("Engineer · Services")
    Group {
        val key = s.openCellIdKey
        ListRow("OpenCelliD API key", if (key.isNullOrBlank()) "Not set" else "Set", onClick = {
            onText(TextSpec("OpenCelliD API key", key.orEmpty(), secret = true) { v -> vm.update { it.copy(openCellIdKey = v.trim().ifEmpty { null }) } })
        })
        Sep()
        UrlRow("Upload URL", "Optional, HTTPS", s.uploadUrl.orEmpty(), onText) { v -> vm.update { it.copy(uploadUrl = v.trim().ifEmpty { null }) } }
    }

    Caption("Engineer · Storage")
    Group {
        ListRow("Keep runs for", "${s.retentionDays} days", onClick = {
            onNumber(NumberSpec("Keep runs for", s.retentionDays.toLong(), "days", 1, 3650) { v -> vm.update { it.copy(retentionDays = v.toInt()) } })
        })
        Sep()
        ListRow("Stop below free storage", "${s.minFreeStorageMb} MB", onClick = {
            onNumber(NumberSpec("Stop below free storage", s.minFreeStorageMb, "MB", 50, 100_000) { v -> vm.update { it.copy(minFreeStorageMb = v) } })
        })
    }

    Caption("Engineer · Event thresholds")
    Group {
        ListRow("Event thresholds", sub = "Same snake_case JSON as the iOS app's event_thresholds.json", onClick = onThresholds)
    }

    if (debug) {
        Caption("Engineer · Debug")
        Group {
            val names = listOf<String?>(null) + Scenarios.names
            val si = names.indexOf(sim)
            ListRow("Simulated scenario", sim ?: "Off (commute)", onClick = {
                onPicker(PickerSpec("Simulated scenario", names.map { it ?: "Off (commute)" }, si) { i -> vm.setSimScenario(names[i]) })
            })
        }
    }
}

// ------------------------------------------------------------------------------------- rows

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.cardRadius)).background(tc.card), content = content)
}

@Composable
private fun Sep() = HorizontalDivider(thickness = 0.5.dp, color = tc.sep)

@Composable
private fun ToggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(title, sub = sub, trailing = { Spacer(Modifier.width(8.dp)); Toggle(checked, onChange) })
}

@Composable
private fun MsRow(title: String, value: Long, min: Long, max: Long, onNumber: (NumberSpec) -> Unit, onSave: (Long) -> Unit) {
    ListRow(title, ms(value), onClick = { onNumber(NumberSpec(title, value, "ms", min, max, onSave)) })
}

@Composable
private fun UrlRow(title: String, hint: String?, value: String, onText: (TextSpec) -> Unit, onSave: (String) -> Unit) {
    ListRow(title, sub = value.ifBlank { hint ?: "Not set" }, onClick = { onText(TextSpec(title, value, secret = false, onSave = onSave)) })
}

private fun ms(v: Long): String = if (v >= 1_000 && v % 1_000 == 0L) {
    val s = v / 1_000
    if (s >= 60 && s % 60 == 0L) "${s / 60} min" else "$s s"
} else "$v ms"

private fun mb(bytes: Long): String =
    if (bytes % 1_000_000L == 0L) "${bytes / 1_000_000L} MB" else "%.1f MB".format(bytes / 1_000_000.0)

private fun pathDesc(p: ProbePaths): String = when (p) {
    ProbePaths.DEFAULT -> "Probes run on the default network only."
    ProbePaths.DUAL -> "Default network plus mobile data in parallel while on Wi-Fi."
    ProbePaths.WIFI_ONLY -> "Wi-Fi only — probes never fall back to mobile data."
    ProbePaths.CELLULAR_ONLY -> "Mobile data only, even while Wi-Fi is connected."
}

// ------------------------------------------------------------------------------------- pickers & dialogs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(spec: PickerSpec, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = tc.card2) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = K.screenPad).padding(bottom = 16.dp)) {
            Text(spec.title, style = TType.headline, color = tc.text, modifier = Modifier.padding(bottom = 10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.cardRadius)).background(tc.card)) {
                spec.options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { spec.onPick(i); onDismiss() }.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = TType.bodyRegular, color = tc.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RadioDot(i == spec.selected)
                    }
                    if (i < spec.options.lastIndex) Sep()
                }
            }
        }
    }
}

@Composable
private fun NumberDialog(spec: NumberSpec, onDismiss: () -> Unit) {
    var raw by remember(spec) { mutableStateOf(spec.value.toString()) }
    val parsed = raw.toLongOrNull()
    val valid = parsed != null && parsed in spec.min..spec.max
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tc.card,
        title = { Text(spec.title, style = TType.headline, color = tc.text) },
        text = {
            OutlinedTextField(
                value = raw,
                onValueChange = { v -> raw = v.filter { it.isDigit() }.take(12) },
                suffix = { Text(spec.unit) },
                isError = !valid,
                supportingText = { Text("${spec.min} – ${spec.max}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (parsed != null && valid) { spec.onSave(parsed); onDismiss() } }, enabled = valid) { Text("Save", color = tc.link) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = tc.link) } },
    )
}

@Composable
private fun TextDialog(spec: TextSpec, onDismiss: () -> Unit) {
    var raw by remember(spec) { mutableStateOf(spec.value) }
    var visible by remember(spec) { mutableStateOf(!spec.secret) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tc.card,
        title = { Text(spec.title, style = TType.headline, color = tc.text) },
        text = {
            Column {
                OutlinedTextField(
                    value = raw, onValueChange = { raw = it }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (spec.secret) KeyboardType.Password else KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (spec.secret) {
                    Text(if (visible) "Hide key" else "Show key", style = TType.label, color = tc.link,
                        modifier = Modifier.padding(top = 10.dp).clickable { visible = !visible })
                }
            }
        },
        confirmButton = { TextButton(onClick = { spec.onSave(raw); onDismiss() }) { Text("Save", color = tc.link) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = tc.link) } },
    )
}

@Composable
private fun ThresholdsDialog(current: Thresholds, vm: SettingsViewModel, onDismiss: () -> Unit) {
    var raw by remember { mutableStateOf(SettingsViewModel.prettyThresholds(current)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tc.card,
        title = { Text("Event thresholds", style = TType.headline, color = tc.text) },
        text = {
            Column {
                OutlinedTextField(
                    value = raw, onValueChange = { raw = it; error = null },
                    textStyle = TType.footnote.copy(fontFamily = FontFamily.Monospace, color = tc.text),
                    isError = error != null,
                    supportingText = if (error != null) { { Text(error.orEmpty()) } } else null,
                    minLines = 8, maxLines = 16,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Reset to defaults", style = TType.label, color = tc.link,
                    modifier = Modifier.padding(top = 10.dp).clickable {
                        vm.resetThresholds(); raw = SettingsViewModel.prettyThresholds(Thresholds()); error = null
                    })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = vm.applyThresholds(raw)
                error = e
                if (e == null) onDismiss()
            }) { Text("Apply", color = tc.link) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = tc.link) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersSheet(s: RunSettings, editable: Boolean, vm: SettingsViewModel, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = tc.card2) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = K.screenPad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Test servers", style = TType.headline, color = tc.text)
            Text("Every check asks each of these servers for a tiny response.", style = TType.sub, color = tc.text3)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.cardRadius)).background(tc.card)) {
                s.endpoints.forEachIndexed { i, e ->
                    ListRow(
                        e.label, sub = e.url,
                        trailing = if (editable && s.endpoints.size > 1) {
                            { Text("Remove", style = TType.label, color = tc.bad, modifier = Modifier.padding(start = 8.dp).clickable { vm.removeEndpoint(e.url) }) }
                        } else null,
                    )
                    if (i < s.endpoints.lastIndex) Sep()
                }
            }
            if (editable) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val err = error
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null }, label = { Text("URL (https://…)") }, singleLine = true,
                    isError = err != null, supportingText = if (err != null) { { Text(err) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                Text("Add server", style = TType.body.copy(fontSize = 17.sp), color = tc.link,
                    modifier = Modifier.padding(vertical = 6.dp).clickable {
                        val e = vm.addEndpoint(url, label)
                        error = e
                        if (e == null) { url = ""; label = "" }
                    })
            }
        }
    }
}
