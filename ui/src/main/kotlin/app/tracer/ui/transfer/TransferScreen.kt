package app.tracer.ui.transfer

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.ui.R
import app.tracer.ui.common.GroupedRow
import app.tracer.ui.common.SectionHeader
import app.tracer.ui.common.formatBytes
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import java.io.File

/** Export a run to a bundle (progress + manifest summary), share it, optionally upload over HTTPS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(runId: String, onBack: () -> Unit, viewModel: TransferViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.run?.name ?: stringResource(R.string.transfer_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
        ) {
            Text(runId, style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader(stringResource(R.string.transfer_bundle))
            val progress = ui.progress
            val file = ui.file
            val error = ui.error
            when {
                progress != null -> {
                    Text(stringResource(R.string.transfer_exporting, (progress * 100).toInt()), style = Mono.small)
                    Spacer(Modifier.height(Space.s))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                error != null -> {
                    Text(stringResource(R.string.transfer_failed, error), color = LocalTracerColors.current.critical)
                    Spacer(Modifier.height(Space.s))
                    OutlinedButton(onClick = viewModel::export) { Text(stringResource(R.string.action_retry)) }
                }
                file != null -> {
                    Text("${file.name} · ${formatBytes(file.length())}", style = Mono.small)
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Button(onClick = { share(context, file, viewModel.authority) }) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(Space.s))
                            Text(stringResource(R.string.action_share))
                        }
                        if (ui.uploadUrl != null) {
                            FilledTonalButton(onClick = viewModel::upload, enabled = !ui.uploading) {
                                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                                Spacer(Modifier.width(Space.s))
                                Text(stringResource(if (ui.uploading) R.string.transfer_uploading else R.string.transfer_upload))
                            }
                        }
                    }
                    ui.uploadStatus?.let { st ->
                        val ok = st in 200..299
                        Text(
                            if (st < 0) stringResource(R.string.transfer_upload_io) else stringResource(R.string.transfer_upload_status, st),
                            style = Mono.small,
                            color = if (ok) LocalTracerColors.current.ok else LocalTracerColors.current.critical,
                            modifier = Modifier.padding(top = Space.s),
                        )
                    }
                    if (ui.manifest.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.transfer_manifest, ui.manifest.size, ui.manifest.sumOf { it.rows ?: 0L }))
                        Column(verticalArrangement = Arrangement.spacedBy(Space.rowGap)) {
                            ui.manifest.forEachIndexed { i, f ->
                                GroupedRow(i, ui.manifest.size) {
                                    Text(f.path, style = Mono.small, modifier = Modifier.weight(1f))
                                    Text(f.rows?.let { stringResource(R.string.transfer_rows, it) } ?: "", style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(Space.m))
                                    Text(formatBytes(f.bytes), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

private fun share(context: android.content.Context, file: File, authority: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
