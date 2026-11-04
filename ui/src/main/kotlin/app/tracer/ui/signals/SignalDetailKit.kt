package app.tracer.ui.signals

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import app.tracer.core.model.ProbeSample
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.CollectorStatus
import app.tracer.core.runtime.CollectorStatusBoard
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.runtime.RunController
import app.tracer.ui.kit.BarPart
import app.tracer.ui.kit.BreakdownBar
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.Caption
import app.tracer.ui.kit.Meter
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.Segmented
import app.tracer.ui.kit.TopLinks
import app.tracer.ui.kit.bgOf
import app.tracer.ui.kit.fg
import app.tracer.ui.kit.tc
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.TType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/*
 * Shared pieces of the live detail screens (Signals › Wi-Fi / Mobile data / Response / Location).
 * Layering: hero + one graph (consumer) → "More" cards → collapsed "Technical" (engineer, monospace).
 */

/** Live snapshot (~1 Hz) and collector health for the detail screens. */
@HiltViewModel
class SignalDetailViewModel @Inject constructor(
    controller: RunController,
    statusBoard: CollectorStatusBoard,
) : ViewModel() {
    val snapshot: StateFlow<LiveSnapshot> = controller.live
    val status: StateFlow<Map<String, CollectorStatus>> = statusBoard.state
}

/** Monotonic "now" (same clock as HPoint.tMonoMs) from the snapshot, else the system clock. */
internal fun liveNowMs(s: LiveSnapshot): Long {
    val start = s.startedMonoNs
    val fromSnap = if (start != null) start / 1_000_000L + s.elapsedMs + s.pausedMs else null
    val latest = listOf(s.history.wifiRssi, s.history.rsrp, s.history.latencyWifi, s.history.latencyMobile, s.history.hAcc)
        .mapNotNull { it.lastOrNull()?.tMonoMs }.maxOrNull()
    return listOfNotNull(fromSnap, latest).maxOrNull() ?: SystemClock.elapsedRealtime()
}

internal val WINDOWS_S = listOf(120.0, 300.0, 600.0)

@Composable
internal fun DetailScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Screen {
        TopLinks(back = "Signals", onBack = onBack, title = title)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            content()
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** Verdict-tinted hero: eyebrow, big value + unit, 5-segment meter, verdict word and a sub line. */
@Composable
internal fun Hero(eyebrow: String, value: String, unit: String?, v: Verdict, sub: String?, verdictText: String = v.label) {
    Card(color = tc.bgOf(v), padding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)) {
        Text(eyebrow, style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                buildAnnotatedString {
                    append(value)
                    if (unit != null) withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = tc.text2)) { append(" $unit") }
                },
                style = TType.largeTitle.copy(fontSize = 44.sp, lineHeight = 48.sp).merge(TType.num), color = tc.text, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(verdictText, style = TType.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = tc.fg(v), modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Meter(v, Modifier.fillMaxWidth(), height = 6.dp)
        if (sub != null) {
            Spacer(Modifier.height(12.dp))
            Text(sub, style = TType.sub, color = tc.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Card with a title, optional trailing text, then content. */
@Composable
internal fun ChartCard(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TType.headline, color = tc.text, modifier = Modifier.weight(1f), maxLines = 1)
            if (trailing != null) Text(trailing, style = TType.footnote.copy(fontSize = 14.sp).merge(TType.num), color = tc.text3, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun WindowPicker(selected: Int, onSelect: (Int) -> Unit) {
    Segmented(listOf("2 min", "5 min", "10 min"), selected, onSelect, height = 36.dp)
}

/** Collapsible card ("Technical"), closed by default; tap the header to open. */
@Composable
internal fun ExpandSection(title: String, sub: String? = null, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Card(padding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TType.headline, color = tc.text)
                if (sub != null && !open) Text(sub, style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Hide" else "Show",
                tint = tc.text3, modifier = Modifier.size(24.dp))
        }
        if (open) {
            HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), content = content)
        }
    }
}

/** Engineer key/value line (monospace value). */
@Composable
internal fun KV(key: String, value: String?, valueColor: Color = tc.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(key, style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3, modifier = Modifier.width(132.dp), maxLines = 2)
        Text(value ?: "–", style = Mono.body.copy(fontSize = 13.sp), color = valueColor, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/** Sub-heading inside a technical section. */
@Composable
internal fun SubHead(text: String) {
    Caption(text, Modifier.padding(top = 0.dp))
}

/** Small "chip" row: label over value, used for median / jitter / loss. */
@Composable
internal fun StatChips(items: List<Triple<String, String, Color>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (label, value, color) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).padding(0.dp),
            ) {
                Card(color = tc.card2, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(label, style = TType.footnote.copy(fontSize = 13.sp), color = tc.text3, maxLines = 1)
                    Text(value, style = TType.headline.merge(TType.num), color = color, maxLines = 1)
                }
            }
        }
    }
}

/** Latest probe on a path ("wifi" | "cellular") of a type, newest first. */
internal fun lastProbe(s: LiveSnapshot, path: String, type: String = "latency"): ProbeSample? =
    s.lastProbes.filterKeys { it.startsWith("$path|$type|") }.values.maxByOrNull { it.stamp.tMonoNs }

/** DNS / connect / TLS / first byte of one probe. */
@Composable
internal fun ProbeBreakdown(p: ProbeSample) {
    BreakdownBar(
        listOf(
            BarPart("DNS", p.dnsMs, tc.text3),
            BarPart("Connect", p.connectMs, tc.link),
            BarPart("TLS", p.tlsMs, tc.average),
            BarPart("First byte", p.ttfbMs, tc.aboveAvg),
        ),
    )
}

/** Remote server identity lines for a probe. */
@Composable
internal fun ServerIdentity(p: ProbeSample) {
    KV("Endpoint", p.endpoint.removePrefix("https://").removePrefix("http://").substringBefore('/'))
    KV("Total", p.totalMs?.let { "${Math.round(it)} ms" })
    KV("Status", if (p.success) (p.httpStatus?.let { "HTTP $it" } ?: "OK") else (p.error ?: "failed"),
        valueColor = if (p.success) tc.text else tc.bad)
    KV("Remote IP", p.remoteIp)
    KV("IP version", p.ipVersion?.let { "IPv$it" })
    KV("Edge PoP", p.edgePop)
    KV("TLS cert", p.tlsCertSha256?.take(16)?.chunked(4)?.joinToString(":"))
}

/** "3 s ago" for a monotonic stamp. */
internal fun ago(nowMs: Long, tMonoMs: Long): String {
    val s = ((nowMs - tMonoMs) / 1000).coerceAtLeast(0)
    return if (s < 90) "$s s ago" else "${s / 60} min ago"
}
