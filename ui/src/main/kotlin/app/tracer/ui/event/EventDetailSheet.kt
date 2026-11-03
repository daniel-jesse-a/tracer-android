package app.tracer.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tracer.core.model.EventSample
import app.tracer.ui.R
import app.tracer.ui.common.EventIcon
import app.tracer.ui.common.eventTitle
import app.tracer.ui.components.chart.ChartSeries
import app.tracer.ui.components.chart.TimelineChart
import app.tracer.ui.live.Fmt
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import java.util.Locale

private const val CONTEXT_NS = 30_000_000_000L

/**
 * Event detail bottom sheet.
 *
 * @param eventId rowid in the event table; < 0 when the event is not persisted yet (label disabled)
 * @param contextSeries series covering the event; the sheet shows them over ±30 s (RSRP, SINR,
 *   latency, GNSS used — whatever the caller provides)
 * @param runStartMonoNs to show "+mm:ss into the run"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    event: EventSample,
    eventId: Long,
    label: String?,
    contextSeries: List<ChartSeries>,
    onLabelChange: (String) -> Unit,
    onDismiss: () -> Unit,
    runStartMonoNs: Long? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val changes = remember(event) { EventJson.changes(event.beforeJson, event.afterJson) }
    val candidates = remember(event) { EventJson.candidates(event.evidenceJson) }
    val t = event.stamp.tMonoNs

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EventIcon(event.eventType, event.severity)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(eventTitle(event), style = MaterialTheme.typography.titleLarge)
                    Text(eventTypeLabel(event.eventType), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SeverityBadge(event.severity)
            }
            KeyValue(stringResource(R.string.live_detail_time), buildString {
                append(Fmt.timestampMs(event.stamp.tUtcMs))
                runStartMonoNs?.let { append("  (+").append(Fmt.clock((t - it) / 1_000_000)).append(")") }
            })
            KeyValue(
                stringResource(R.string.live_detail_position),
                if (event.lat != null && event.lon != null) String.format(Locale.US, "%.5f, %.5f", event.lat, event.lon) else Fmt.DASH,
            )

            if (changes.isNotEmpty()) {
                SectionTitle(stringResource(R.string.live_detail_before_after))
                changes.forEach { c -> ChangeRow(c) }
            }

            SectionTitle(stringResource(R.string.live_detail_likely_cause))
            val cause = event.likelyCause
            if (cause != null) {
                ConfidenceRow(causeLabel(cause), event.causeConfidence)
            } else {
                Text(stringResource(R.string.live_detail_no_cause), style = MaterialTheme.typography.bodyMedium)
            }

            if (candidates.isNotEmpty()) {
                SectionTitle(stringResource(R.string.live_detail_evidence))
                candidates.forEach { cand ->
                    Card(colors = CardDefaults.cardColors(containerColor = LocalTracerColors.current.tile)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConfidenceRow(causeLabel(cand.cause), cand.confidence)
                            cand.signals.forEach { (k, v) -> KeyValue(k, v) }
                        }
                    }
                }
            }

            if (contextSeries.isNotEmpty()) {
                SectionTitle(stringResource(R.string.live_detail_context))
                TimelineChart(
                    series = contextSeries,
                    startNs = t - CONTEXT_NS,
                    endNs = t + CONTEXT_NS,
                    cursorNs = t,
                    events = listOf(t to event.severity),
                    originNs = runStartMonoNs ?: (t - CONTEXT_NS),
                )
            }

            SectionTitle(stringResource(R.string.live_detail_label))
            var text by rememberSaveable(eventId) { mutableStateOf(label.orEmpty()) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = eventId >= 0,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.live_detail_label_hint)) },
            )
            if (eventId < 0) {
                Text(stringResource(R.string.live_detail_label_pending), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { onLabelChange(text.trim()) }, enabled = eventId >= 0 && text.trim() != label.orEmpty()) {
                Text(stringResource(R.string.live_detail_save_label))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = Mono.small)
    }
}

@Composable
private fun ChangeRow(c: EventJson.Change) {
    val changed = c.before != c.after
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(c.key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(c.before ?: Fmt.DASH, style = Mono.small)
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
            modifier = Modifier.padding(horizontal = 6.dp).size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            c.after ?: Fmt.DASH,
            style = if (changed) Mono.small.copy(fontWeight = FontWeight.Bold) else Mono.small,
            color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConfidenceRow(label: String, confidence: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.live_detail_confidence, Fmt.pct(confidence)), style = Mono.small)
        }
        val p = (confidence ?: 0.0).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp))
    }
}
