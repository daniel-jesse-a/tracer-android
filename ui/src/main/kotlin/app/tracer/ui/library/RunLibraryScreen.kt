package app.tracer.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.model.RunState
import app.tracer.core.quality.QualityLevel
import app.tracer.core.runtime.RunListItem
import app.tracer.ui.R
import app.tracer.ui.common.CompletenessBadgeRow
import app.tracer.ui.common.completenessBadges
import app.tracer.ui.common.formatBytes
import app.tracer.ui.common.formatClock
import app.tracer.ui.common.formatDuration
import app.tracer.ui.common.formatKm
import app.tracer.ui.common.modeTag
import app.tracer.ui.common.runTitle
import app.tracer.ui.common.asString
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunLibraryScreen(
    onOpenRun: (String) -> Unit,
    onExport: (String) -> Unit,
    onOpenLive: () -> Unit,
    viewModel: RunLibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { msg -> snackbar.showSnackbar(msg.asString(context)) } }
    val shareFailed = stringResource(R.string.library_share_failed)
    LaunchedEffect(Unit) {
        viewModel.shareRequests.collect { req ->
            runCatching { shareFiles(context, req) }.onFailure { snackbar.showSnackbar(shareFailed) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.import(uri)
    }
    val selecting = selection.isNotEmpty()
    LaunchedEffect(selection) { selection.forEach(viewModel::requestExtras) }

    Scaffold(
        topBar = {
            if (selecting) {
                val bytes = selection.sumOf { extras[it]?.approxBytes ?: 0L }
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.library_clear_selection)) }
                    },
                    title = { Text(stringResource(R.string.library_selected, selection.size, formatBytes(bytes))) },
                    actions = {
                        IconButton(onClick = { viewModel.share(selection) }, enabled = busy == null) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
                        IconButton(onClick = { confirmDelete = selection }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_runs)) },
                    actions = {
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = busy == null) {
                            Icon(Icons.Outlined.FolderOpen, stringResource(R.string.library_import))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            busy?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
            SearchField(query, viewModel::setQuery, Modifier.padding(horizontal = Space.l, vertical = Space.s))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                RunFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = {
                            Text(
                                when (f) {
                                    RunFilter.ALL -> stringResource(R.string.filter_all, runs?.size ?: 0)
                                    RunFilter.TRAIN -> stringResource(R.string.filter_train)
                                    RunFilter.LINKED -> stringResource(R.string.filter_linked)
                                    RunFilter.THIS_WEEK -> stringResource(R.string.filter_week)
                                    RunFilter.HAS_DROPS -> stringResource(R.string.filter_drops)
                                },
                            )
                        },
                    )
                }
            }
            when {
                runs == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = Space.l, end = Space.l, top = Space.s, bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    if (runState == RunState.RECORDING || runState == RunState.PAUSED) {
                        item(key = "recording") {
                            RecordingBanner(formatDuration(live.elapsedMs), paused = runState == RunState.PAUSED, onClick = onOpenLive)
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(if (runs.isNullOrEmpty()) R.string.library_empty else R.string.library_no_match),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Space.xl),
                            )
                        }
                    }
                    items(visible, key = { it.run.runId }) { item ->
                        LaunchedEffect(item.run.runId) { viewModel.requestExtras(item.run.runId) }
                        RunCard(
                            item = item,
                            extras = extras[item.run.runId],
                            selecting = selecting,
                            selected = item.run.runId in selection,
                            onClick = { if (selecting) viewModel.toggleSelection(item.run.runId) else onOpenRun(item.run.runId) },
                            onLongClick = { viewModel.toggleSelection(item.run.runId) },
                            onExport = { onExport(item.run.runId) },
                            onDelete = { confirmDelete = setOf(item.run.runId) },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.library_delete_title, ids.size)) },
            text = { Text(stringResource(R.string.library_delete_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(ids); confirmDelete = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** SearchBar-styled input (pill, surfaceContainerHigh) — avoids the churning M3 SearchBar API. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_clear)) } }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun RecordingBanner(elapsed: String, paused: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FiberManualRecord, contentDescription = null, tint = LocalTracerColors.current.critical)
            Spacer(Modifier.width(Space.s))
            Text(elapsed, style = Mono.value)
            Spacer(Modifier.width(Space.s))
            Text(
                stringResource(if (paused) R.string.library_paused_open else R.string.library_recording_open),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunCard(
    item: RunListItem,
    extras: RunExtras?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val r = item.run
    val cs = MaterialTheme.colorScheme
    var menu by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = if (selected) cs.primaryContainer else cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(start = if (selecting) Space.s else Space.l, end = Space.xs, top = Space.m, bottom = Space.m)) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(runTitle(r), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(dateLabel(LocalContext.current, r.startedAt), style = Mono.small, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                val endedAt = r.endedAt
                val duration = if (endedAt != null) formatClock(endedAt - r.startedAt) else stringResource(R.string.library_unfinished)
                val linked = r.linkedRunId != null || item.hasLinkedImport
                val meta = buildString {
                    append(modeTag(r.mode)); append("  "); append(duration)
                    append("  "); append(formatKm(extras?.distanceM))
                    append("  "); append(item.eventCount); append(" ev")
                    r.protocol?.let { append("  "); append(it) }
                    if (r.devicePlatform == "ios") append("  iOS")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meta, style = Mono.small, color = cs.onSurfaceVariant, maxLines = 1)
                    if (linked) {
                        Text(
                            "  ⇄ " + stringResource(if (r.devicePlatform == "ios") R.string.library_linked_android else R.string.library_linked_iphone),
                            style = Mono.small, color = LocalTracerColors.current.linkedRun, maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(Space.s))
                QualityBar(extras?.quality, Modifier.fillMaxWidth().height(4.dp))
                Spacer(Modifier.height(Space.s))
                CompletenessBadgeRow(completenessBadges(r))
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.library_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_export)) }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

/** Thin segmented bar of the run's RSRP quality over time; grey while not yet computed. */
@Composable
private fun QualityBar(levels: List<QualityLevel>?, modifier: Modifier) {
    val tc = LocalTracerColors.current
    val colors = (levels?.takeIf { it.isNotEmpty() } ?: listOf(QualityLevel.NONE)).map { tc.quality(it) }
    Canvas(modifier) {
        val w = size.width / colors.size
        colors.forEachIndexed { i, c ->
            drawRect(c, topLeft = Offset(i * w, 0f), size = Size(w + 0.5f, size.height))
        }
    }
}

private fun dateLabel(context: Context, utcMs: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(utcMs))
    return when {
        DateUtils.isToday(utcMs) -> context.getString(R.string.library_today, time)
        DateUtils.isToday(utcMs + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.library_yesterday, time)
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(utcMs))
    }
}

private fun shareFiles(context: Context, req: ShareRequest) {
    val uris = ArrayList(req.files.map { FileProvider.getUriForFile(context, req.authority, it) })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    send.setType("application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
