package app.tracer.ui.companion

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.report.Verdict
import app.tracer.pairing.ui.QrCodeImage
import app.tracer.pairing.ui.QrScanner
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.IconWell
import app.tracer.ui.kit.K
import app.tracer.ui.kit.ListRow
import app.tracer.ui.kit.MiniMeter
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.TitleBlock
import app.tracer.ui.kit.Toggle
import app.tracer.ui.kit.TopLinks
import app.tracer.ui.kit.bgOf
import app.tracer.ui.kit.fg
import app.tracer.ui.kit.tc
import app.tracer.ui.theme.TType

/** Frame 8 — Companions: linked phones with clock gap, add-a-device QR / scan, start on all companions. */
@Composable
fun CompanionsScreen(
    onBack: () -> Unit,
    onOpenRun: (String) -> Unit,
    viewModel: CompanionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val startAll by viewModel.startCompanions.collectAsStateWithLifecycle()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) scanning = true }
    val startScan: () -> Unit = {
        scanError = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scanning = true
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Screen {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopLinks(back = "Settings", onBack = onBack)
            TitleBlock(
                eyebrow = null, title = "Companions",
                trailing = if (ui.rows.isNotEmpty()) {
                    { Text("${ui.liveCount} of ${ui.rows.size} live", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3, modifier = Modifier.padding(bottom = 6.dp)) }
                } else null,
            )

            if (ui.rows.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.cardRadius)).background(tc.card)) {
                    ui.rows.forEachIndexed { i, row ->
                        DeviceRow(row, onOpenRun)
                        if (i < ui.rows.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
                    }
                }
                Text(
                    "Clock gap to this phone · all tests line up on GPS time",
                    style = TType.sub, color = tc.text3, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 18.dp),
                )
            } else {
                Text(
                    "No companions yet. Run Tracer on a second phone and scan its code to compare both tests on the same timeline.",
                    style = TType.sub, color = tc.text3, modifier = Modifier.padding(start = 4.dp, bottom = 18.dp),
                )
            }

            AddDeviceCard(ui, onScan = startScan, onClear = viewModel::clearPartner)
            val err = scanError ?: ui.linkError
            if (err != null) {
                Text(err, style = TType.sub, color = tc.bad, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            }

            Spacer(Modifier.height(28.dp))
            Card(padding = PaddingValues(0.dp)) {
                ListRow("Start tests on all companions", trailing = { Toggle(startAll) { viewModel.setStartCompanions(it) } })
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (scanning) {
        Dialog(onDismissRequest = { scanning = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                QrScanner(onResult = { text ->
                    scanning = false
                    if (!viewModel.onScanned(text)) scanError = "That is not a Tracer pairing code."
                })
                IconButton(onClick = { scanning = false }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(row: CompanionRow, onOpenRun: (String) -> Unit) {
    val gap = row.gapMs
    val v = if (row.live) Verdict.link(gap?.toDouble()) else Verdict.NONE
    val (tint, well) = when {
        !row.live -> tc.text3 to tc.card2
        v == Verdict.NONE -> tc.veryGood to tc.veryGoodBg
        else -> tc.fg(v) to tc.bgOf(v)
    }
    val open = row.openRunId
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .then(if (open != null) Modifier.clickable { onOpenRun(open) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconWell(if (row.platform == "ios") Icons.Outlined.PhoneIphone else Icons.Outlined.PhoneAndroid, tint, well)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.status, style = TType.sub, color = tc.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (row.live && gap != null) "$gap ms" else "—",
                style = TType.body.copy(fontWeight = FontWeight.Bold).merge(TType.num), color = tc.text,
            )
            Spacer(Modifier.height(6.dp))
            MiniMeter(v)
        }
    }
}

@Composable
private fun AddDeviceCard(ui: CompanionsUi, onScan: () -> Unit, onClear: () -> Unit) {
    Card(onClick = onScan, padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val qr = ui.ownQr
            if (qr != null) {
                // QR on white with a quiet zone so it scans in dark mode too
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp)) {
                    QrCodeImage(qr, Modifier.fillMaxSize())
                }
            } else {
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(12.dp)).background(tc.card2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = tc.text2, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Add a device", style = TType.headline.copy(fontSize = 19.sp), color = tc.text)
                Spacer(Modifier.height(4.dp))
                Text("iPhone or Android", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3)
                Text(
                    if (qr != null) "Scan with Tracer" else "Tap to scan · your code appears once a test starts",
                    style = TType.sub.copy(fontSize = 16.sp), color = tc.text3,
                )
                if (ui.pending) {
                    Text("Clear scanned phone", style = TType.label, color = tc.link,
                        modifier = Modifier.padding(top = 8.dp).clickable(onClick = onClear))
                }
            }
        }
    }
}
