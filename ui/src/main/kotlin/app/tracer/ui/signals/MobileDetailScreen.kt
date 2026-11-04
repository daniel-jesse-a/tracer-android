package app.tracer.ui.signals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.cell.CarrierSet
import app.tracer.core.cell.CellNormalizer
import app.tracer.core.model.CellSample
import app.tracer.core.model.EventSample
import app.tracer.core.report.ChartText
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.ui.event.EventJson
import app.tracer.ui.kit.ChartLegend
import app.tracer.ui.kit.ChartLine
import app.tracer.ui.kit.PresetChart
import app.tracer.ui.kit.Presets
import app.tracer.ui.kit.SignalBars
import app.tracer.ui.kit.dbm
import app.tracer.ui.kit.fg
import app.tracer.ui.kit.liveXLabels
import app.tracer.ui.kit.tc
import app.tracer.ui.kit.toPts
import app.tracer.ui.theme.TType
import kotlin.math.roundToInt

/** "5G NSA", "5G", "4G+", "4G", "3G", "2G" from radio.rat. */
internal fun techLabel(rat: String?, nrState: String?): String? = when {
    rat == null -> null
    rat == "NR_SA" || nrState == "sa" -> "5G SA"
    rat == "NR_ADVANCED" || nrState == "nsa_advanced" -> "5G+"
    rat.startsWith("NR") || nrState == "nsa" -> "5G NSA"
    rat == "LTE_CA" || rat == "LTE_ADV_PRO" -> "4G+"
    rat == "LTE" -> "4G"
    rat in setOf("UMTS", "HSPA", "HSPAP", "WCDMA", "TDSCDMA") -> "3G"
    rat == "GSM" -> "2G"
    rat == "NONE" || rat == "UNKNOWN" -> null
    else -> rat
}

private fun ratWord(rat: String): String = when (rat) { "NR" -> "5G"; "LTE" -> "4G"; "WCDMA", "TDSCDMA" -> "3G"; "GSM" -> "2G"; else -> rat }

/** Signals › Mobile data (4G / 5G): live RSRP / NR / SINR / RSRQ, carriers, serving cell, neighbours, cell changes. */
@Composable
fun MobileDetailScreen(onBack: () -> Unit) {
    val vm: SignalDetailViewModel = hiltViewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    var win by rememberSaveable { mutableIntStateOf(1) }
    val now = liveNowMs(snap)
    val windowS = WINDOWS_S[win]
    val anchor = snap.primaryServing
    val sub = anchor?.subscriptionId ?: snap.servingBySub.keys.firstOrNull()
    val serving = sub?.let { snap.servingBySub[it] }.orEmpty()
    val nrLeg = serving.firstOrNull { it.rat == "NR" && it !== anchor }
    val rsrp = anchor?.rsrp
    val v = Verdict.rsrp(rsrp?.toDouble())
    val tech = techLabel(snap.rat, snap.nrState) ?: anchor?.rat?.let(::ratWord)
    val nrSeen = (snap.rat?.startsWith("NR") == true) || (snap.nrState != null && snap.nrState != "none") || serving.any { it.rat == "NR" }
    val plmn = anchor?.let { c -> if (c.mcc != null && c.mnc != null) "${c.mcc}-${c.mnc}" else null }

    DetailScreen("Mobile data", onBack) {
        Hero(
            eyebrow = listOfNotNull(tech ?: "Mobile", plmn).joinToString(" · "),
            value = rsrp?.toDouble()?.dbm() ?: "–",
            unit = if (rsrp != null) "dBm" else null, v = v,
            sub = if (anchor == null) "No mobile network seen. Phone access may not be allowed or there is no SIM."
            else listOfNotNull(
                anchor.band?.let { "Band ${it.removePrefix("B")}" },
                nrLeg?.band?.let { "5G ${it}" },
                anchor.sinr?.let { "SINR ${it.toDouble().dbm()} dB" },
                nrLeg?.rsrp?.let { "5G ${it.toDouble().dbm()} dBm" },
            ).joinToString(" · ").ifEmpty { "Measuring…" },
            verdictText = if (rsrp == null) "No data" else v.label,
        )

        ChartCard("Signal (RSRP)", trailing = rsrp?.let { "${it.toDouble().dbm()} dBm" }) {
            WindowPicker(win) { win = it }
            Spacer(Modifier.height(12.dp))
            val anchorPts = snap.history.rsrp.toPts(now)
            val nrPts = snap.history.nrRsrp.toPts(now)
            PresetChart(
                Presets.rsrp(tc),
                listOf(ChartLine(anchorPts, tc.mobile, maxGapS = 15.0), ChartLine(nrPts, tc.mobile, dashed = true, maxGapS = 15.0)),
                xMin = -windowS, xMax = 0.0, currentDot = true, xLabels = liveXLabels(windowS),
                emptyReason = if (anchor == null) ChartText.MOBILE_NO_READINGS else "Waiting for readings",
            )
            Spacer(Modifier.height(8.dp))
            ChartLegend(buildList {
                add(Triple(if (nrLeg != null || anchor?.rat == "LTE") "4G anchor" else "Serving cell", tc.mobile, false))
                if (nrSeen) add(Triple("5G (NR leg)", tc.mobile, true))
            })
            if (nrSeen && snap.history.nrRsrp.isEmpty() && anchor?.rat != "NR") {
                Spacer(Modifier.height(6.dp))
                Text(ChartText.NR_NOT_REPORTED, style = TType.footnote.copy(fontSize = 13.sp), color = tc.text3)
            }
        }

        ChartCard("Quality (SINR)", trailing = anchor?.sinr?.let { "${it.toDouble().dbm()} dB" }) {
            PresetChart(
                Presets.sinr(tc), listOf(ChartLine(snap.history.sinr.toPts(now), tc.mobile, maxGapS = 15.0)),
                xMin = -windowS, xMax = 0.0, height = 120.dp, currentDot = true, xLabels = liveXLabels(windowS),
                emptyReason = if (anchor == null) ChartText.MOBILE_NO_READINGS else ChartText.SINR_NOT_REPORTED,
            )
        }

        ChartCard("RSRQ", trailing = anchor?.rsrq?.let { "${it.toDouble().dbm()} dB" }) {
            PresetChart(
                Presets.rsrq(tc), listOf(ChartLine(snap.history.rsrq.toPts(now), tc.mobile, maxGapS = 15.0)),
                xMin = -windowS, xMax = 0.0, height = 110.dp, currentDot = true, xLabels = liveXLabels(windowS),
                emptyReason = if (anchor == null) ChartText.MOBILE_NO_READINGS else "This phone doesn't report RSRQ",
            )
        }

        CarriersCard(snap)
        ServingCard(anchor, nrLeg)
        NeighboursCard(snap, sub, anchor)
        CellChangesCard(snap, now)
        MobileTechnical(snap, now)
    }
}

@Composable
private fun CarriersCard(snap: LiveSnapshot) {
    val set = remember(snap.carrierAggregation) { CarrierSet.parse(snap.carrierAggregation) }
    val agg = set.aggregateMhz ?: set.carriers.mapNotNull { it.bandwidthMhz }.takeIf { it.isNotEmpty() }?.sum()
    ChartCard("Carriers", trailing = agg?.let { "${it.roundToInt()} MHz total" }) {
        if (set.carriers.isEmpty()) {
            Text("This phone doesn't report its carriers", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        set.carriers.forEachIndexed { i, c ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ratWord(c.rat)} · " + (c.band ?: "band –"),
                        style = TType.bodyRegular, color = tc.text,
                    )
                    Text(
                        listOfNotNull(
                            if (c.primary) "Primary" else "Secondary",
                            c.arfcn?.let { "ARFCN $it" },
                            c.pci?.let { "PCI $it" },
                        ).joinToString(" · "),
                        style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3,
                    )
                }
                Text(c.bandwidthMhz?.let { "${it.roundToInt()} MHz" } ?: "–", style = TType.callout.merge(TType.num), color = tc.text)
            }
        }
    }
}

@Composable
private fun ServingCard(anchor: CellSample?, nrLeg: CellSample?) {
    ChartCard("Serving cell", trailing = anchor?.rat?.let(::ratWord)) {
        if (anchor == null) {
            Text("No serving cell", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        KV("PCI", anchor.pci?.toString())
        KV("Cell ID", anchor.cellId?.let { "$it (0x${java.lang.Long.toHexString(it).uppercase()})" })
        KV("TAC", anchor.tac?.toString())
        KV(if (anchor.rat == "NR") "NR-ARFCN" else "EARFCN", anchor.earfcnOrArfcn?.toString())
        KV("Band", anchor.band)
        KV("Bandwidth", anchor.bandwidthMhz?.let { "${it.roundToInt()} MHz" })
        val ta = anchor.timingAdvance
        val taM = CellNormalizer.timingAdvanceDistanceM(anchor.rat, ta)
        KV("Timing advance", if (ta == null) null else "$ta" + (taM?.let { " · about ${formatDistance(it)} to the mast" } ?: ""))
        if (nrLeg != null) {
            SubHead("5G leg")
            KV("PCI", nrLeg.pci?.toString())
            KV("NR-ARFCN", nrLeg.earfcnOrArfcn?.toString())
            KV("Band", nrLeg.band)
            KV("SS-RSRP", nrLeg.rsrp?.toDouble()?.dbm()?.let { "$it dBm" })
            KV("SS-SINR", nrLeg.sinr?.toDouble()?.dbm()?.let { "$it dB" })
        }
    }
}

private fun formatDistance(m: Double): String = if (m >= 1000) "%.1f km".format(m / 1000) else "${(m / 10).roundToInt() * 10} m"

@Composable
private fun NeighboursCard(snap: LiveSnapshot, sub: Int?, anchor: CellSample?) {
    val list = sub?.let { snap.neighboursBySub[it] }.orEmpty().sortedByDescending { it.rsrp ?: -200 }
    ChartCard("Neighbour cells", trailing = "${list.size} seen") {
        if (list.isEmpty()) {
            Text("No neighbour cells reported", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        val ref = anchor?.rsrp
        list.take(12).forEachIndexed { i, n ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
            val nv = Verdict.rsrp(n.rsrp?.toDouble())
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                SignalBars((nv.segments - 1).coerceAtLeast(0), tc.fg(nv))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${ratWord(n.rat)} · PCI ${n.pci ?: "–"}", style = TType.bodyRegular, color = tc.text, maxLines = 1)
                    Text(listOfNotNull(n.band, n.earfcnOrArfcn?.let { "ARFCN $it" }, n.rsrq?.let { "RSRQ ${it.toDouble().dbm()}" }).joinToString(" · ").ifEmpty { "–" },
                        style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(n.rsrp?.let { "${it.toDouble().dbm()} dBm" } ?: "–", style = TType.callout.merge(TType.num), color = tc.text)
                    val r = n.rsrp
                    if (ref != null && r != null) {
                        val d = r - ref
                        // a neighbour stronger than the serving cell means a cell change is coming: flagged, not "good"
                        Text(
                            (if (d > 0) "+$d" else d.toDouble().dbm()) + " vs serving",
                            style = TType.footnote.copy(fontSize = 12.sp).merge(TType.num),
                            color = if (d >= 3) tc.belowAvg else tc.text3,
                        )
                    }
                }
            }
        }
    }
}

private val CELL_EVENTS = setOf("handover", "rat_change", "rat_flicker", "neighbour_overtake", "signal_drop")

private fun cellEventText(e: EventSample): String {
    val b = EventJson.flatten(e.beforeJson).toMap()
    val a = EventJson.flatten(e.afterJson).toMap()
    return when (e.eventType) {
        "handover" -> "Cell change: PCI ${b["pci"] ?: "–"} → ${a["pci"] ?: "–"}" +
            (a["band"]?.takeIf { it != "null" }?.let { " · $it" } ?: "")
        "rat_change" -> "Network: ${techLabel(b["rat"], null) ?: b["rat"] ?: "–"} → ${techLabel(a["rat"], null) ?: a["rat"] ?: "–"}"
        "rat_flicker" -> "Network flickered: ${techLabel(a["rat"], null) ?: a["rat"] ?: "–"}"
        "neighbour_overtake" -> "Neighbour cell became stronger"
        "signal_drop" -> "Mobile signal dropped"
        else -> e.eventType.replace('_', ' ')
    }
}

@Composable
private fun CellChangesCard(snap: LiveSnapshot, now: Long) {
    val list = snap.recentEvents.filter { it.eventType in CELL_EVENTS }.take(10)
    ChartCard("Recent cell changes", trailing = if (list.isEmpty()) null else "${list.size}") {
        if (list.isEmpty()) {
            Text("No cell changes yet", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        list.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(cellEventText(e), style = TType.bodyRegular.copy(fontSize = 16.sp), color = tc.text, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(ago(now, e.stamp.tMonoMs), style = TType.footnote.copy(fontSize = 13.sp).merge(TType.num), color = tc.text3)
            }
        }
    }
}

@Composable
private fun MobileTechnical(snap: LiveSnapshot, now: Long) {
    ExpandSection("Technical", sub = "Raw values per SIM, NR state, CQI, server") {
        KV("radio.rat", snap.rat)
        KV("nr_state", snap.nrState)
        KV("Transport", snap.transport)
        val subs = (snap.servingBySub.keys + snap.neighboursBySub.keys).sorted()
        subs.forEachIndexed { i, s ->
            SubHead("SIM ${i + 1} · subscription $s")
            val cells = snap.servingBySub[s].orEmpty()
            if (cells.isEmpty()) Text("No serving cell", style = TType.sub, color = tc.text3)
            cells.forEach { c ->
                Text("${c.rat} · PCI ${c.pci ?: "–"} · ${c.band ?: "–"}", style = TType.label, color = tc.text2, modifier = Modifier.padding(top = 4.dp))
                KV("RSRP / RSRQ", "${c.rsrp ?: "–"} / ${c.rsrq ?: "–"}".replace('-', '−'))
                KV("SINR / CQI", "${c.sinr ?: "–"} / ${c.cqi ?: "–"}".replace('-', '−'))
                KV("RSSI", c.rssi?.toDouble()?.dbm())
                KV("MCC-MNC", if (c.mcc != null) "${c.mcc}-${c.mnc ?: "–"}" else null)
                KV("Cell info age", c.cellinfoAgeMs?.let { "${it} ms" })
            }
            Text("${snap.neighboursBySub[s].orEmpty().size} neighbours", style = TType.footnote, color = tc.text3)
        }
        KV("Carrier JSON", snap.carrierAggregation?.take(160))
        val p = lastProbe(snap, "cellular")
        SubHead("Last mobile check" + (p?.let { " · ${ago(now, it.stamp.tMonoMs)}" } ?: ""))
        if (p == null) {
            Text("No check on mobile data yet", style = TType.sub, color = tc.text3)
        } else {
            ProbeBreakdown(p)
            Spacer(Modifier.height(8.dp))
            ServerIdentity(p)
        }
    }
}

