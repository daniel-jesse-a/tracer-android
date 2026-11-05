package app.tracer.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.ui.kit.*
import app.tracer.ui.summary.ReportGate
import app.tracer.ui.theme.TType

/** Design v2 frame 9 — Share. */
@Composable
fun ShareScreen(runId: String, onCancel: () -> Unit) {
    val vm: ShareViewModel = hiltViewModel()
    LaunchedEffect(runId) { vm.bind(runId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pending by vm.pendingShare.collectAsStateWithLifecycle()
    val pendingEmail by vm.pendingEmail.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.saveToTree(uri)
    }

    LaunchedEffect(pendingEmail) {
        val intent = pendingEmail ?: return@LaunchedEffect
        try { context.startActivity(intent); vm.shareLaunched() }
        catch (e: ActivityNotFoundException) { vm.shareFailed("No email app is set up on this phone") }
    }

    LaunchedEffect(pending) {
        val files = pending ?: return@LaunchedEffect
        if (files.isEmpty()) { vm.shareLaunched(); return@LaunchedEffect }
        try {
            val uris = ArrayList(files.map { FileProvider.getUriForFile(context, vm.authority, it) })
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { type = mimeOf(files[0]); putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            }
            send.putExtra(Intent.EXTRA_SUBJECT, files[0].nameWithoutExtension)
            val clip = ClipData.newRawUri(files[0].name, uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            send.clipData = clip
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share test").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            vm.shareLaunched()
        } catch (e: ActivityNotFoundException) {
            vm.shareFailed("No app can receive these files")
        } catch (e: IllegalArgumentException) {
            vm.shareFailed("Couldn't share: ${e.message}")
        }
    }

    Screen {
        TopLinks(back = "Cancel", onBack = onCancel, title = "Share", backIsCancel = true)
        ReportGate(state) { _, r ->
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                TitleBlock(eyebrow = r.title, title = "Send the report")
                ReportItem()
                Caption("Send to")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap)) {
                    DestCard(Icons.Outlined.Download, "Download", ui.dest == ShareDest.DOWNLOAD, true, Modifier.weight(1f)) { vm.setDest(ShareDest.DOWNLOAD) }
                    DestCard(Icons.Outlined.Email, "Email", ui.dest == ShareDest.EMAIL, ui.recipients.isNotEmpty(), Modifier.weight(1f)) { vm.setDest(ShareDest.EMAIL) }
                    DestCard(Icons.Outlined.IosShare, "Share…", ui.dest == ShareDest.SHARE, true, Modifier.weight(1f)) { vm.setDest(ShareDest.SHARE) }
                }
                Spacer(Modifier.height(K.gap))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap)) {
                    DestCard(Icons.Outlined.Folder, "Files", ui.dest == ShareDest.FILES, true, Modifier.weight(1f)) { vm.setDest(ShareDest.FILES) }
                    DestCard(Icons.Outlined.Dns, "Server", ui.dest == ShareDest.SERVER, ui.uploadUrl != null, Modifier.weight(1f)) { vm.setDest(ShareDest.SERVER) }
                    Spacer(Modifier.weight(1f))
                }
                val hint = when (ui.dest) {
                    ShareDest.DOWNLOAD -> "Saves to Downloads/Tracer on this phone."
                    ShareDest.EMAIL -> "To ${ui.recipients.joinToString()} · change in Settings › Email reports."
                    else -> null
                }
                if (hint != null) Text(hint, style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                if (ui.uploadUrl == null) {
                    Text("Server: set an upload URL in Settings to send tests to a server.", style = TType.footnote.copy(fontSize = 14.sp),
                        color = tc.text3, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
            // ---- progress / result
            val status = ui.status
            val result = ui.result
            if (ui.busy && status != null) {
                Text(status, style = TType.sub, color = tc.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val p = ui.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = tc.link, trackColor = tc.card2)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = tc.link, trackColor = tc.card2)
                Spacer(Modifier.height(12.dp))
                Pill("Cancel", onClick = vm::cancel, modifier = Modifier.fillMaxWidth(), kind = PillKind.SECONDARY_BLUE)
                Spacer(Modifier.height(12.dp))
            } else if (result != null) {
                Text(result, style = TType.sub, color = if (ui.failed) tc.bad else tc.veryGood, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            }
            val verb = when (ui.dest) {
                ShareDest.DOWNLOAD -> "Download"; ShareDest.EMAIL -> "Email"
                ShareDest.SHARE -> "Share"; ShareDest.FILES -> "Save"; ShareDest.SERVER -> "Send"
            }
            Pill(
                "$verb report",
                onClick = { if (ui.dest == ShareDest.FILES) pickFolder.launch(null) else vm.send() },
                modifier = Modifier.fillMaxWidth(), kind = PillKind.BLUE, enabled = !ui.busy,
            )
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** The one thing that is sent: the Tracer report v1 PDF. The raw run stays on the phone. */
@Composable
private fun ReportItem() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconWell(Icons.Outlined.Description, tint = tc.link, bg = tc.linkBg)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Network test report", style = TType.headline.copy(fontSize = 19.sp), color = tc.text)
                Text("PDF, A4 landscape. Raw data stays on this phone.", style = TType.sub.copy(fontSize = 16.sp), color = tc.text3)
            }
        }
    }
}

@Composable
private fun DestCard(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    SelectCard(selected && enabled, { if (enabled) onClick() }, modifier.height(84.dp).alpha(if (enabled) 1f else 0.4f)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (selected && enabled) tc.link else tc.text, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = TType.label.copy(fontSize = 16.sp), color = tc.text)
        }
    }
}
