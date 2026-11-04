package app.tracer.ui.panels

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tracer.core.cell.CellNormalizer
import app.tracer.core.model.CellSample
import app.tracer.core.quality.QualityScale
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.ui.R
import app.tracer.ui.live.Fmt
import app.tracer.ui.live.LiveStats
import app.tracer.ui.live.neighbourKey
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space

private const val HO_LIKELY_DB = 2
private const val HO_LIKELY_MS = 1_000L

/**
 * Cells tab: header (elapsed · SIM · PLMN · poll rate), SIM switcher when dual, Serving card in
 * primaryContainer, neighbours as grouped rows with RSRP bars; overtaking rows in tertiaryContainer.
 */
@Composable
fun CellsPanel(snap: LiveSnapshot, stats: LiveStats, selectedSub: Int?, onSelectSub: (Int) -> Unit) {
    val subs = (snap.servingBySub.keys + snap.neighboursBySub.keys).sorted()
    val sub = selectedSub?.takeIf { it in subs } ?: snap.primaryServing?.subscriptionId?.takeIf { it in subs } ?: subs.firstOrNull()
    val serving = sub?.let { snap.servingBySub[it] }.orEmpty()
    val neighbours = sub?.let { snap.neighboursBySub[it] }.orEmpty()
    val anchor = serving.firstOrNull { it.rat == "LTE" } ?: serving.firstOrNull()
    val simIndex = sub?.let { subs.indexOf(it) + 1 } ?: 1
    val plmn = anchor?.let { c -> if (c.mcc != null && c.mnc != null) "${c.mcc}-${c.mnc}" else null }
    val hz = sub?.let { stats.pollHz[it] }
    val colors = LocalTracerColors.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.rowGap),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.critical))
                Spacer(Modifier.width(8.dp))
                Text(Fmt.hms(snap.elapsedMs), style = Mono.body)
                Spacer(Modifier.weight(1f))
                val parts = listOfNotNull(
                    stringResource(R.string.live_cells_sim, simIndex),
                    plmn,
                    hz?.let { stringResource(R.string.live_cells_hz, Fmt.d0(it)) },
                )
                Text(parts.joinToString(" · "), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (subs.size > 1) {
            item(key = "sims") {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    subs.forEachIndexed { i, s ->
                        FilterChip(selected = s == sub, onClick = { onSelectSub(s) }, label = { Text(stringResource(R.string.live_cells_sim, i + 1)) })
                    }
                }
            }
        }
        item(key = "serving") {
            if (anchor == null) {
                PanelRow(0, 1) { Text(stringResource(R.string.live_cells_no_serving), style = MaterialTheme.typography.bodyMedium) }
            } else {
                ServingCard(anchor, serving.filter { it !== anchor })
            }
        }
        item(key = "nhead") {
            Row(Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_cells_neighbours, neighbours.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.live_cells_sorted), style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (neighbours.isEmpty()) {
            item(key = "nnone") {
                PanelRow(0, 1) {
                    Text(stringResource(R.string.live_cells_no_neighbours), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(neighbours, key = { i, n -> "${n.rat}:${n.pci}@${n.earfcnOrArfcn}:$i" }) { i, n ->
            val ot = sub?.let { stats.overtakes[neighbourKey(it, n)] }
            NeighbourRow(n, i, neighbours.size, ot?.deltaDb, ot?.durationMs)
        }
        item(key = "bottom") { Spacer(Modifier.height(Space.l)) }
    }
}

@Composable
private fun BigValue(label: String, value: String, unit: String, modifier: Modifier) {
    val onC = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier) {
        Text(label, style = Mono.tiny, color = onC.copy(alpha = 0.75f))
        Text(value, style = Mono.big, color = onC)
        Text(unit, style = Mono.tiny, color = onC.copy(alpha = 0.75f))
    }
}

@Composable
private fun SmallPair(label: String, value: String, modifier: Modifier) {
    val onC = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier) {
        Text(label, style = Mono.tiny, color = onC.copy(alpha = 0.75f))
        Text(value, style = Mono.small, color = onC)
    }
}

@Composable
private fun ServingCard(anchor: CellSample, others: List<CellSample>) {
    val onC = MaterialTheme.colorScheme.onPrimaryContainer
    val hasNr = others.any { it.rat == "NR" }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = if (anchor.rat == "LTE" && hasNr) stringResource(R.string.live_cells_serving_anchor)
                else stringResource(R.string.live_cells_serving_rat, anchor.rat)
                Text(title, style = MaterialTheme.typography.titleMedium, color = onC, modifier = Modifier.weight(1f))
                Text("PCI ${Fmt.int(anchor.pci)}", style = Mono.small, color = onC)
            }
            Row {
                BigValue("RSRP", Fmt.int(anchor.rsrp), "dBm", Modifier.weight(1f))
                BigValue("RSRQ", Fmt.int(anchor.rsrq), "dB", Modifier.weight(1f))
                BigValue("SINR", Fmt.int(anchor.sinr), "dB", Modifier.weight(1f))
            }
            Text(
                "${QualityScale.rsrp(anchor.rsrp).label} · ${stringResource(R.string.live_cells_sinr_q, QualityScale.sinr(anchor.sinr).label)}",
                style = Mono.tiny, color = onC.copy(alpha = 0.75f),
            )
            Row {
                SmallPair(anchor.band ?: Fmt.DASH, Fmt.int(anchor.earfcnOrArfcn), Modifier.weight(1f))
                SmallPair("CID", Fmt.hex(anchor.cellId), Modifier.weight(1.3f))
                SmallPair("TAC", Fmt.int(anchor.tac), Modifier.weight(1f))
                val taM = CellNormalizer.timingAdvanceDistanceM(anchor.rat, anchor.timingAdvance)
                SmallPair("TA ${Fmt.int(anchor.timingAdvance)}", taM?.let { "${Fmt.d0(it)} m" } ?: Fmt.DASH, Modifier.weight(1f))
            }
            if (others.isNotEmpty()) {
                HorizontalDivider(color = onC.copy(alpha = 0.2f))
                others.forEach { c ->
                    val leg = if (c.rat == "NR") stringResource(R.string.live_cells_nr_leg) else stringResource(R.string.live_cells_scell, c.rat)
                    Text(
                        "$leg · ${c.band ?: Fmt.DASH} · ${Fmt.int(c.earfcnOrArfcn)} · PCI ${Fmt.int(c.pci)} · " +
                            "${if (c.rat == "NR") "SS-RSRP" else "RSRP"} ${Fmt.int(c.rsrp)} · SINR ${Fmt.int(c.sinr)}",
                        style = Mono.small, color = onC,
                    )
                }
            }
        }
    }
}

@Composable
private fun NeighbourRow(n: CellSample, index: Int, count: Int, overtakeDb: Int?, overtakeMs: Long?) {
    val overtaking = overtakeDb != null
    val container = if (overtaking) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val onC = if (overtaking) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
    val dim = if (overtaking) onC.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
    PanelRow(index, count, color = container) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PCI ${Fmt.int(n.pci)}", style = Mono.body.copy(fontWeight = FontWeight.Medium), color = onC)
            Spacer(Modifier.width(Space.m))
            Text("${n.band ?: n.rat} · ${Fmt.int(n.earfcnOrArfcn)}", style = Mono.small, color = dim)
            Spacer(Modifier.weight(1f))
            Text("RSRQ ${Fmt.int(n.rsrq)}", style = Mono.small, color = dim)
            Spacer(Modifier.width(Space.m))
            Text(Fmt.int(n.rsrp), style = Mono.value, color = onC)
        }
        Spacer(Modifier.height(6.dp))
        LevelBar(n.rsrp, -140, -44, QualityScale.rsrp(n.rsrp))
        if (overtaking) {
            Spacer(Modifier.height(6.dp))
            val likely = overtakeDb!! >= HO_LIKELY_DB && (overtakeMs ?: 0L) >= HO_LIKELY_MS
            val text = stringResource(R.string.live_cells_overtaking, Fmt.signedDb(overtakeDb), Fmt.d1((overtakeMs ?: 0L) / 1000.0)) +
                if (likely) stringResource(R.string.live_cells_ho_likely) else ""
            Text("▲ $text", style = Mono.small, color = onC)
        }
    }
}
