package app.tracer.ui.companion

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.pairing.ui.QrCodeImage
import app.tracer.pairing.ui.QrScanner
import app.tracer.ui.R
import app.tracer.ui.common.GroupedRow
import app.tracer.ui.common.MetricTile
import app.tracer.ui.common.SectionHeader
import app.tracer.ui.common.TileState
import app.tracer.ui.common.formatDateTime
import app.tracer.ui.onboarding.Permissions
import app.tracer.ui.preflight.PreflightViewModel
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(onOpenRun: (String) -> Unit, viewModel: CompanionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val linked by viewModel.linkedRuns.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var scanning by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }
    val invalidMsg = stringResource(R.string.companion_invalid_qr)
    val unit = stringResource(R.string.unit_ms)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_companion)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
        ) {
            // own QR
            SectionHeader(stringResource(R.string.companion_this_device))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
                    val qr = ui.ownQr
                    if (qr != null) {
                        // QR on white with quiet zone so it scans in dark mode too
                        Box(Modifier.size(232.dp).background(Color.White, MaterialTheme.shapes.medium).padding(16.dp)) {
                            QrCodeImage(qr)
                        }
                        Spacer(Modifier.height(Space.m))
                        Text(stringResource(R.string.companion_qr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(stringResource(R.string.companion_qr_after_start), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // scan partner
            SectionHeader(stringResource(R.string.companion_partner))
            val partner = ui.partner
            if (partner == null) {
                Text(stringResource(R.string.companion_start_on_iphone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.m))
            } else {
                GroupedRow(0, 1) {
                    Column(Modifier.weight(1f)) {
                        Text(partner.model.ifBlank { partner.platform }, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(partner.runName, partner.startedAt?.let { formatDateTime(it) }, "${partner.platform} ${partner.osVersion}").joinToString(" · "),
                            style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val l = ui.lastLinked
                        if (l != null) {
                            Text(
                                if (l.result.isSuccess) stringResource(R.string.companion_linked_ok) else stringResource(R.string.companion_linked_failed, l.result.exceptionOrNull()?.message ?: ""),
                                style = Mono.small,
                                color = if (l.result.isSuccess) LocalTracerColors.current.ok else LocalTracerColors.current.critical,
                            )
                        } else if (!ui.runActive) {
                            Text(stringResource(R.string.companion_link_pending), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = viewModel::clearPartner) { Text(stringResource(R.string.action_clear)) }
                }
                Spacer(Modifier.height(Space.m))
            }
            FilledTonalButton(onClick = {
                if (Permissions.camera(context)) scanning = true else cameraLauncher.launch(Manifest.permission.CAMERA)
            }) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.size(Space.s))
                Text(stringResource(R.string.companion_scan))
            }

            // clock
            SectionHeader(stringResource(R.string.companion_clock))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    label = stringResource(R.string.companion_gps_offset),
                    value = ui.ownGpsOffsetMs?.let { PreflightViewModel.signed(it) } ?: "—",
                    unit = unit, quality = null, modifier = Modifier.weight(1f),
                    sub = stringResource(R.string.companion_gps_offset_sub),
                    state = if (ui.ownGpsOffsetMs == null) TileState.STALE else TileState.NORMAL,
                )
                val d = ui.deltaMs
                MetricTile(
                    label = stringResource(R.string.companion_delta),
                    value = d?.let { PreflightViewModel.signed(it) } ?: "—",
                    unit = unit, quality = null, modifier = Modifier.weight(1f),
                    sub = stringResource(R.string.companion_delta_target),
                    state = when {
                        d == null -> TileState.STALE
                        abs(d) >= 50 -> TileState.WARN
                        else -> TileState.NORMAL
                    },
                )
            }

            // linked runs
            SectionHeader(stringResource(R.string.companion_linked_runs))
            if (linked.isEmpty()) {
                Text(stringResource(R.string.companion_no_linked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.rowGap)) {
                    linked.forEachIndexed { i, item ->
                        GroupedRow(i, linked.size, Modifier.clickable { onOpenRun(item.run.runId) }) {
                            Column(Modifier.weight(1f)) {
                                Text(item.run.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${formatDateTime(item.run.startedAt)} · ⇄ ${item.run.linkedRunId?.take(8) ?: stringResource(R.string.companion_imported)}",
                                    style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
    }

    if (scanning) {
        Dialog(onDismissRequest = { scanning = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                QrScanner(onResult = { text ->
                    scanning = false
                    if (!viewModel.onScanned(text)) scope.launch { snackbar.showSnackbar(invalidMsg) }
                })
                IconButton(onClick = { scanning = false }, modifier = Modifier.align(Alignment.TopEnd).padding(Space.l)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
                }
            }
        }
    }
}
