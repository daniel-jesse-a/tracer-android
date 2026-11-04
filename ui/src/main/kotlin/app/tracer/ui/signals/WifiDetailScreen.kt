package app.tracer.ui.signals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.model.WifiConnectionSample
import app.tracer.core.model.WifiScanSample
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.CollectorHealth
import app.tracer.core.runtime.CollectorStatus
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.util.WifiMath
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.ChartLine
import app.tracer.ui.kit.Dot
import app.tracer.ui.kit.Presets
import app.tracer.ui.kit.PresetChart
import app.tracer.ui.kit.Segmented
import app.tracer.ui.kit.SignalBars
import app.tracer.ui.kit.dbm
import app.tracer.ui.kit.fg
import app.tracer.ui.kit.liveXLabels
import app.tracer.ui.kit.tc
import app.tracer.ui.kit.toPts
import app.tracer.ui.panels.cleanSsid
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.TType

private const val BETTER_AP_DB_DETAIL = 8

/** Signals › Wi-Fi: live RSSI, link speed, networks around, channel view, technical. */
@Composable
fun WifiDetailScreen(onBack: () -> Unit) {
    val vm: SignalDetailViewModel = hiltViewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var win by rememberSaveable { mutableIntStateOf(1) }
    val w = snap.wifi
    val rssi = w?.rssiDbm
    val v = Verdict.wifiRssi(rssi?.toDouble())
    val ssid = cleanSsid(w?.ssid)
    val now = liveNowMs(snap)
    val windowS = WINDOWS_S[win]
    val better = betterAp(snap)

    DetailScreen("Wi-Fi", onBack) {
        val freq = w?.frequencyMhz
        val sub = if (w == null) "Not connected · ${snap.scan.size} networks nearby"
        else listOfNotNull(
            freq?.let { "${WifiMath.bandOf(it)} GHz" },
            freq?.let { WifiMath.channelOf(it) }?.let { "channel $it" },
            w.linkSpeedMbps?.let { "$it Mbps link" },
        ).joinToString(" · ").ifEmpty { "Connected" }
        Hero(
            eyebrow = ssid ?: if (w == null) "Wi-Fi" else "Hidden network",
            value = rssi?.toDouble()?.dbm() ?: if (w == null) "Off" else "–",
            unit = if (rssi != null) "dBm" else null, v = v, sub = sub,
            verdictText = if (rssi == null) (if (w == null) "Not connected" else "No data") else v.label,
        )

        if (better != null && rssi != null) {
            Card(color = tc.averageBg) {
                Text("Better access point available", style = TType.headline, color = tc.average)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Another ${ssid ?: "network"} access point is ${better.rssiDbm - rssi} dB stronger " +
                        "(${WifiMath.bandOf(better.frequencyMhz)} GHz, channel ${WifiMath.channelOf(better.frequencyMhz) ?: "–"}). " +
                        "The phone is holding a farther one; turning Wi-Fi off and on usually moves it.",
                    style = TType.sub, color = tc.text2,
                )
            }
        }

        ChartCard("Signal", trailing = rssi?.let { "${it.toDouble().dbm()} dBm" }) {
            WindowPicker(win) { win = it }
            Spacer(Modifier.height(12.dp))
            PresetChart(
                Presets.wifiRssi(tc),
                listOf(ChartLine(snap.history.wifiRssi.toPts(now), tc.wifi, maxGapS = 10.0)),
                xMin = -windowS, xMax = 0.0, currentDot = true, xLabels = liveXLabels(windowS),
                emptyReason = if (w == null) "Not connected to Wi-Fi" else "Waiting for readings",
            )
        }

        ChartCard("Link speed", trailing = w?.linkSpeedMbps?.let { "$it Mbps" }) {
            val pts = snap.history.wifiLinkMbps.toPts(now)
            PresetChart(
                Presets.mbps(tc, pts.maxOfOrNull { it.v }),
                listOf(ChartLine(pts, tc.wifi, maxGapS = 10.0)),
                xMin = -windowS, xMax = 0.0, height = 110.dp, currentDot = true, xLabels = liveXLabels(windowS),
                emptyReason = if (w == null) "Not connected to Wi-Fi" else "This phone doesn't report link speed",
            )
        }

        NetworksAround(snap)
        ChannelView(snap)
        WifiTechnical(snap, w, status["wifi_scan"], now)
    }
}

/** Same-SSID access point at least 8 dB stronger than the connected one. */
internal fun betterAp(snap: LiveSnapshot): WifiScanSample? {
    val w = snap.wifi ?: return null
    val rssi = w.rssiDbm ?: return null
    val ssid = cleanSsid(w.ssid) ?: return null
    return snap.scan.filter { cleanSsid(it.ssid) == ssid && it.bssid != w.bssid && it.rssiDbm >= rssi + BETTER_AP_DB_DETAIL }
        .maxByOrNull { it.rssiDbm }
}

internal fun securityOf(caps: String?): String = when {
    caps == null -> "–"
    caps.contains("SAE") -> "WPA3"
    caps.contains("OWE") -> "OWE"
    caps.contains("EAP") -> "Enterprise"
    caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
    caps.contains("WPA") -> "WPA"
    caps.contains("WEP") -> "WEP"
    else -> "Open"
}

internal fun standardLabel(s: String?): String? = when (s) {
    "wifi7" -> "Wi-Fi 7"; "wifi6e" -> "Wi-Fi 6E"; "wifi6" -> "Wi-Fi 6"; "wifi5" -> "Wi-Fi 5"; "wifi4" -> "Wi-Fi 4"
    "legacy" -> "Legacy (a/b/g)"; null, "unknown" -> null; else -> s
}

private fun barsOf(v: Verdict): Int = (v.segments - 1).coerceAtLeast(0)

private data class NetGroup(val name: String, val aps: List<WifiScanSample>, val connected: Boolean) {
    val best: WifiScanSample get() = aps.first()
}

@Composable
private fun NetworksAround(snap: LiveSnapshot) {
    val groups = remember(snap.scan, snap.wifi?.bssid) {
        snap.scan.groupBy { cleanSsid(it.ssid) ?: "Hidden network" }
            .map { (name, l) -> NetGroup(name, l.sortedByDescending { it.rssiDbm }, l.any { it.bssid == snap.wifi?.bssid }) }
            .sortedWith(compareByDescending<NetGroup> { it.connected }.thenByDescending { it.best.rssiDbm })
            .take(30)
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    ChartCard("Networks around", trailing = "${snap.scan.size} access points") {
        if (groups.isEmpty()) {
            Text("No scan results yet. Android limits apps to a few Wi-Fi scans every 2 minutes.", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        groups.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = tc.sep)
            val b = g.best
            val bv = Verdict.wifiRssi(b.rssiDbm.toDouble())
            Row(
                Modifier.fillMaxWidth().clickable { expanded = if (expanded == g.name) null else g.name }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalBars(barsOf(bv), tc.fg(bv))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, style = TType.bodyRegular, color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (g.connected) {
                            Spacer(Modifier.width(8.dp))
                            Text("Connected", style = TType.footnote.copy(fontWeight = FontWeight.SemiBold), color = tc.link)
                        }
                    }
                    Text(
                        listOfNotNull(
                            "${WifiMath.bandOf(b.frequencyMhz)} GHz",
                            WifiMath.channelOf(b.frequencyMhz)?.let { "ch $it" },
                            b.channelWidth?.let { "$it MHz" },
                            securityOf(b.capabilities),
                            if (g.aps.size > 1) "${g.aps.size} APs" else null,
                        ).joinToString(" · "),
                        style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3, maxLines = 1,
                    )
                }
                Text("${b.rssiDbm.toDouble().dbm()} dBm", style = TType.callout.merge(TType.num), color = tc.text)
            }
            if (expanded == g.name) {
                g.aps.forEach { ap ->
                    val flags = ap.features?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    Row(Modifier.fillMaxWidth().padding(start = 30.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ap.bssid == snap.wifi?.bssid) { Dot(tc.link, 7.dp); Spacer(Modifier.width(6.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(ap.bssid, style = Mono.small, color = tc.text2)
                            Text(
                                listOfNotNull(
                                    "${ap.frequencyMhz} MHz",
                                    WifiMath.channelOf(ap.frequencyMhz)?.let { "ch $it" },
                                    standardLabel(ap.standard),
                                    if (flags.isNotEmpty()) flags.joinToString(" ") { "802.$it" } else "no 11k/v/r",
                                ).joinToString(" · "),
                                style = Mono.tiny, color = tc.text3,
                            )
                        }
                        Text(ap.rssiDbm.toDouble().dbm(), style = Mono.small, color = tc.fg(Verdict.wifiRssi(ap.rssiDbm.toDouble())))
                    }
                }
            }
        }
    }
}

/** WiFiman-style channel graph: each AP is a hill on its channel, height = RSSI; the connected one in blue. */
@Composable
private fun ChannelView(snap: LiveSnapshot) {
    val bands = remember(snap.scan) { listOf("2.4", "5", "6").filter { b -> snap.scan.any { WifiMath.bandOf(it.frequencyMhz) == b } } }
    if (bands.isEmpty()) return
    val connectedBand = snap.wifi?.frequencyMhz?.let { WifiMath.bandOf(it) }
    var sel by rememberSaveable { mutableIntStateOf(-1) }
    val idx = if (sel in bands.indices) sel else (connectedBand?.let { bands.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
    val band = bands[idx]
    val aps = snap.scan.filter { WifiMath.bandOf(it.frequencyMhz) == band && WifiMath.channelOf(it.frequencyMhz) != null }
    ChartCard("Channels", trailing = "${aps.size} on $band GHz") {
        if (bands.size > 1) {
            Segmented(bands.map { "$it GHz" }, idx, { sel = it }, height = 36.dp)
            Spacer(Modifier.height(12.dp))
        }
        ChannelChart(aps, band, snap.wifi)
        Spacer(Modifier.height(6.dp))
        val load = snap.channelLoad.filter { it.band == band }.sortedByDescending { it.aps }.take(3)
        if (load.isNotEmpty()) {
            Text("Busiest: " + load.joinToString(" · ") { "ch ${it.channel} (${it.aps})" }, style = TType.footnote.copy(fontSize = 13.sp), color = tc.text3)
        }
    }
}

private fun widthChannels(width: String?, band: String): Double {
    val mhz = width?.substringBefore('+')?.toDoubleOrNull() ?: 20.0
    // channel numbers step 5 MHz in every band; drawn centred on the primary channel (no centre frequency in scans)
    return if (band == "2.4") maxOf(mhz, 20.0) / 5.0 / 2.0 else mhz / 5.0 / 2.0
}

@Composable
private fun ChannelChart(aps: List<WifiScanSample>, band: String, connected: WifiConnectionSample?) {
    val c = tc
    val measurer = rememberTextMeasurer()
    val chans = aps.mapNotNull { WifiMath.channelOf(it.frequencyMhz) }
    val (lo, hi) = when (band) {
        "2.4" -> -1.0 to 15.0
        else -> {
            val minC = (chans.minOrNull() ?: 36).toDouble(); val maxC = (chans.maxOrNull() ?: 64).toDouble()
            val pad = 10.0
            (minC - pad) to maxOf(maxC + pad, minC + 40.0)
        }
    }
    val ticks: List<Int> = when (band) {
        "2.4" -> (1..13).toList()
        else -> chans.distinct().sorted().let { cs -> if (cs.size <= 8) cs else cs.filterIndexed { i, _ -> i % ((cs.size + 7) / 8) == 0 } }
    }
    val labelled = aps.sortedByDescending { it.rssiDbm }.take(5).map { it.bssid }.toSet()
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val axisH = 16.dp.toPx()
        val plotH = size.height - axisH
        val top = -30.0; val bottom = -95.0
        fun x(ch: Double) = ((ch - lo) / (hi - lo) * size.width).toFloat()
        fun y(dbm: Int) = ((top - dbm.toDouble().coerceIn(bottom, top)) / (top - bottom) * plotH).toFloat()
        drawLine(c.sep, Offset(0f, plotH), Offset(size.width, plotH), strokeWidth = 1.dp.toPx())
        val ordered = aps.sortedWith(compareBy<WifiScanSample> { it.bssid == connected?.bssid }.thenBy { it.rssiDbm })
        for (ap in ordered) {
            val ch = WifiMath.channelOf(ap.frequencyMhz)?.toDouble() ?: continue
            val half = widthChannels(ap.channelWidth, band)
            val x0 = x(ch - half); val x1 = x(ch + half)
            val peak = y(ap.rssiDbm)
            val ctrl = (peak - 0.25f * plotH) / 0.75f
            val p = Path().apply {
                moveTo(x0, plotH)
                cubicTo(x0 + (x1 - x0) * 0.1f, ctrl, x1 - (x1 - x0) * 0.1f, ctrl, x1, plotH)
            }
            val isConn = ap.bssid == connected?.bssid
            val col = if (isConn) c.wifi else c.text3
            drawPath(p, col.copy(alpha = if (isConn) 0.25f else 0.07f))
            drawPath(p, col, style = Stroke(if (isConn) 2.5.dp.toPx() else 1.2.dp.toPx()))
            if (isConn || ap.bssid in labelled) {
                val name = cleanSsid(ap.ssid) ?: "Hidden"
                val m = measurer.measure(name.take(14), TType.tick.copy(fontSize = 10.sp, color = if (isConn) c.wifi else c.text2))
                val tx = (x(ch) - m.size.width / 2f).coerceIn(0f, (size.width - m.size.width).coerceAtLeast(0f))
                drawText(m, topLeft = Offset(tx, (peak - m.size.height - 2f).coerceAtLeast(0f)))
            }
        }
        for (t in ticks) {
            val m = measurer.measure(t.toString(), TType.tick.copy(fontSize = 10.sp, color = c.text3))
            drawText(m, topLeft = Offset(x(t.toDouble()) - m.size.width / 2f, plotH + 3.dp.toPx()))
        }
    }
}

@Composable
private fun WifiTechnical(snap: LiveSnapshot, w: WifiConnectionSample?, scanStatus: CollectorStatus?, now: Long) {
    ExpandSection("Technical", sub = "BSSID, frequency, security, server") {
        SubHead("Connection")
        KV("BSSID", w?.bssid)
        KV("Frequency", w?.frequencyMhz?.let { "$it MHz" })
        KV("Channel", w?.frequencyMhz?.let { WifiMath.channelOf(it) }?.toString())
        KV("Standard", standardLabel(w?.standard))
        KV("Channel width", w?.channelWidth?.let { "$it MHz" })
        KV("Security", w?.security)
        KV("Link speed", w?.linkSpeedMbps?.let { "$it Mbps" })
        KV("RSSI", w?.rssiDbm?.toDouble()?.dbm()?.let { "$it dBm" })
        SubHead("Scanning")
        val lastScan = snap.scan.maxOfOrNull { it.stamp.tMonoMs }
        KV("Access points", "${snap.scan.size}")
        KV("Last scan", lastScan?.let { ago(now, it) })
        KV(
            "Scan state",
            when (scanStatus?.health) {
                null -> "–"
                CollectorHealth.OK -> "OK"
                CollectorHealth.DEGRADED -> scanStatus?.message ?: "Throttled"
                CollectorHealth.UNAVAILABLE -> scanStatus?.message ?: "Unavailable"
                CollectorHealth.DISABLED -> "Off in settings"
                CollectorHealth.STOPPED -> "Stopped"
            },
            valueColor = if (scanStatus?.health == CollectorHealth.DEGRADED || scanStatus?.health == CollectorHealth.UNAVAILABLE) tc.belowAvg else tc.text,
        )
        SubHead("Internet")
        KV("Public IP", snap.publicIp)
        KV("Network (ASN)", snap.asnOrg)
        val p = lastProbe(snap, "wifi")
        SubHead("Last Wi-Fi check" + (p?.let { " · ${ago(now, it.stamp.tMonoMs)}" } ?: ""))
        if (p == null) {
            Text("No check on Wi-Fi yet", style = TType.sub, color = tc.text3)
        } else {
            ProbeBreakdown(p)
            Spacer(Modifier.height(8.dp))
            ServerIdentity(p)
        }
    }
}
