package app.tracer.ui.tests

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.report.Comparison
import app.tracer.core.report.Verdict
import app.tracer.ui.kit.Caption
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.Dot
import app.tracer.ui.kit.K
import app.tracer.ui.kit.Pill
import app.tracer.ui.kit.PillKind
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.SlimBar
import app.tracer.ui.kit.bgOf
import app.tracer.ui.kit.fg
import app.tracer.ui.kit.tc
import app.tracer.ui.theme.TType
import kotlin.math.roundToInt

/** Frame 7 — Tests: comparison card, tests grouped by day and site, recording banner, "+" new test. */
@Composable
fun TestsScreen(
    onOpenTest: (String) -> Unit,
    onNewTest: () -> Unit,
    onOpenLive: () -> Unit,
    viewModel: TestsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Screen {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // blue round "+" top right
            Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Box(
                    Modifier.align(Alignment.CenterEnd).size(44.dp).clip(CircleShape).background(tc.link).clickable(onClick = onNewTest),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = "New test", tint = Color.White, modifier = Modifier.size(26.dp)) }
            }
            Text("Tests", style = TType.largeTitle, color = tc.text, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))

            val rec = ui.recording
            if (rec != null) {
                RecordingCard(rec, onOpenLive)
                Spacer(Modifier.height(K.gap))
            }

            val cmp = ui.comparison
            if (cmp != null) ComparisonCard(cmp)

            if (ui.loaded && ui.sections.isEmpty()) {
                EmptyState(onNewTest)
            }

            ui.sections.forEach { section ->
                Caption(section.caption)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.cardRadius)).background(tc.card)) {
                    section.rows.forEachIndexed { i, row ->
                        key(row.run.runId) { TestRowView(row, viewModel, onOpenTest) }
                        if (i < section.rows.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RecordingCard(rec: RecordingBanner, onOpenLive: () -> Unit) {
    val s = (rec.elapsedMs / 1000).coerceAtLeast(0)
    val clock = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    Card(color = tc.card, onClick = onOpenLive, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (rec.paused) tc.text3 else tc.bad, 9.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tc.text)) { append(if (rec.paused) "Paused" else "Recording") }
                    withStyle(SpanStyle(color = tc.text3)) { append(" · ${rec.label} · $clock") }
                },
                style = TType.bodyRegular.merge(TType.num), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("Open", style = TType.body, color = tc.link, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ComparisonCard(c: Comparison) {
    val v = if (c.improved) Verdict.VERY_GOOD else Verdict.BELOW_AVG
    val fg = tc.fg(v)
    Card(color = tc.bgOf(v), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 18.dp)) {
        Text("${c.newerLabel} vs ${c.olderLabel}", style = TType.sub.copy(fontSize = 16.sp), color = fg)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val before = c.checksBefore
            if (before != null) {
                Text(
                    "${before.roundToInt()}%",
                    style = TType.headline.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.LineThrough).merge(TType.num),
                    color = fg.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                c.checksAfter?.let { "${it.roundToInt()}%" } ?: "—",
                style = TType.largeTitle.copy(fontSize = 40.sp, lineHeight = 44.sp).merge(TType.num), color = fg,
            )
            Spacer(Modifier.width(12.dp))
            Text("checks passed", style = TType.bodyRegular, color = tc.text2, modifier = Modifier.padding(bottom = 5.dp))
        }
        val deltas = c.deltas.take(2)
        if (deltas.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                deltas.forEach { d -> Text(d, style = TType.sub.copy(fontSize = 16.sp).merge(TType.num), color = tc.text2, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun EmptyState(onNewTest: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No tests yet", style = TType.title, color = tc.text)
        Spacer(Modifier.height(8.dp))
        Text("Walk, drive or ride with Tracer to see how your connection holds up.", style = TType.sub, color = tc.text3,
            modifier = Modifier.padding(horizontal = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Pill("New test", onNewTest, Modifier.fillMaxWidth(), kind = PillKind.PRIMARY)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TestRowView(row: TestRow, viewModel: TestsViewModel, onOpenTest: (String) -> Unit) {
    val runId = row.run.runId
    LaunchedEffect(runId) { viewModel.request(runId) }
    var menu by remember(runId) { mutableStateOf(false) }
    val rep = row.report

    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { onOpenTest(runId) }, onLongClick = { menu = true })
                .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, style = TType.headline.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold), color = tc.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(row.time, style = TType.sub.copy(fontSize = 16.sp).merge(TType.num), color = tc.text3, modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                if (rep != null && !row.otherDevice) SlimBar(rep.segments, rep.durationS, Modifier.fillMaxWidth())
                else SlimBar(emptyList(), 0.0, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.detail, style = TType.sub.copy(fontSize = 16.sp).merge(TType.num), color = tc.text3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (row.otherDevice) {
                        Text("View", style = TType.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = tc.link)
                    } else if (rep != null) {
                        val v = rep.overallVerdict
                        Text(v.label, style = TType.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = tc.fg(v))
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tc.text4, modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Delete", color = tc.bad) },
                onClick = { menu = false; viewModel.delete(runId) },
            )
        }
    }
}
