package app.tracer.ui.signals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.report.Plain
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.util.WifiMath
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.Screen
import app.tracer.ui.kit.TopLinks
import app.tracer.ui.kit.bgOf
import app.tracer.ui.kit.dbm
import app.tracer.ui.kit.fg
import app.tracer.ui.kit.tc
import app.tracer.ui.live.LiveRunViewModel
import app.tracer.ui.panels.cleanSsid
import app.tracer.ui.theme.TType
import kotlin.math.roundToInt

/**
 * Design frame 3: mobile data, Wi-Fi, response and location, each on a 5-colour scale with a marker
 * (scale always runs worse → better, left → right). Tapping a card opens its live detail screen.
 */
@Composable
fun SignalsScreen(
    onBack: () -> Unit,
    onOpenWifi: () -> Unit = {},
    onOpenMobile: () -> Unit = {},
    onOpenResponse: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
) {
    val vm: LiveRunViewModel = hiltViewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    Screen {
        TopLinks(back = "Live", onBack = onBack, title = "Signals")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(0.dp))
            val m = mobile(snap)
            SignalCard(m, Icons.Outlined.SignalCellularAlt, tc.mobile, onOpenMobile)
            val w = wifi(snap)
            SignalCard(w, Icons.Outlined.Wifi, tc.wifi, onOpenWifi)
            val r = response(snap)
            SignalCard(r, Icons.Outlined.Schedule, tc.text, onOpenResponse)
            val l = location(snap)
            SignalCard(l, Icons.Outlined.LocationOn, tc.text, onOpenLocation)
            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class SignalUi(
    val title: String,
    val value: String,
    val verdict: Verdict,
    /** marker position 0..1 on the bad → very good scale; null = no marker */
    val position: Float?,
    val low: String,
    val high: String,
    val footer: String,
)

/**
 * Piecewise scale: [edges] has 6 values (worst end, 4 verdict boundaries, best end) so each verdict
 * owns one fifth of the bar and the marker always sits in its verdict's colour.
 */
private fun scale(v: Double?, edges: DoubleArray): Float? {
    if (v == null || v.isNaN()) return null
    val ascending = edges.last() > edges.first()
    for (i in 0 until 5) {
        val a = edges[i]; val b = edges[i + 1]
        val inside = if (ascending) v < b || i == 4 else v > b || i == 4
        if (inside) {
            val f = ((v - a) / (b - a)).coerceIn(0.0, 1.0)
            return ((i + f) / 5.0).toFloat()
        }
    }
    return 1f
}

private val RSRP_EDGES = doubleArrayOf(-125.0, -110.0, -100.0, -90.0, -80.0, -65.0)
private val WIFI_EDGES = doubleArrayOf(-95.0, -80.0, -72.0, -65.0, -55.0, -35.0)
private val POSITION_EDGES = doubleArrayOf(100.0, 50.0, 20.0, 8.0, 5.0, 1.0)
/** Response ms: lower is better, so the scale runs 1000 ms (left, bad) → 0 ms (right, very good). */
private val RESPONSE_EDGES = doubleArrayOf(1000.0, 600.0, 300.0, 150.0, 50.0, 0.0)

private fun response(snap: LiveSnapshot): SignalUi {
    val rtt = snap.latencyMs
    val footer = listOfNotNull(
        snap.medianLatencyMs?.let { "Median ${it.roundToInt()} ms" },
        snap.jitterMs?.let { "jitter ${it.roundToInt()} ms" },
        snap.lossPct?.let { "loss ${it.roundToInt()}%" },
    ).joinToString(" · ").ifEmpty { "Waiting for the first check" }
    return SignalUi(
        title = "Response",
        value = if (rtt != null) "${rtt.roundToInt()} ms" else "No reading",
        verdict = Verdict.response(rtt),
        position = scale(rtt, RESPONSE_EDGES),
        low = "Slow", high = "Fast", footer = footer,
    )
}

private fun mobile(snap: LiveSnapshot): SignalUi {
    val serving = snap.primaryServing
    val rsrp = serving?.rsrp
    val tech = (snap.rat ?: serving?.rat)?.let { Plain.ratPlain(it) }
    val allCells = snap.servingBySub.values.flatten() + snap.neighboursBySub.values.flatten()
    val nrState = snap.nrState
    val nrSeen = (snap.rat?.startsWith("NR") == true) || (nrState != null && nrState != "none") || allCells.any { it.rat == "NR" }
    val band = serving?.band?.let { b -> "Band " + b.removePrefix("B") }
    val sinr = serving?.sinr?.let { "SINR ${it.toDouble().dbm()} dB" }
    val footer = listOfNotNull(band, sinr, if (nrSeen && tech != "5G") "5G seen" else null).joinToString(" · ")
        .ifEmpty { if (serving == null) "No mobile network seen yet" else "Measuring…" }
    return SignalUi(
        title = "Mobile data",
        value = when {
            rsrp != null -> "${tech ?: "Mobile"} · ${rsrp.toDouble().dbm()} dBm"
            tech != null -> "$tech · no reading"
            else -> "No signal"
        },
        verdict = Verdict.rsrp(rsrp?.toDouble()),
        position = scale(rsrp?.toDouble(), RSRP_EDGES),
        low = "Weak", high = "Strong", footer = footer,
    )
}

private fun wifi(snap: LiveSnapshot): SignalUi {
    val w = snap.wifi
    val rssi = w?.rssiDbm
    val ssid = cleanSsid(w?.ssid)
    val bssid = w?.bssid
    // a stronger access point of the same network in the latest scan → the phone is holding a far AP
    val better = if (ssid != null && rssi != null) {
        snap.scan.firstOrNull { s -> cleanSsid(s.ssid) == ssid && s.bssid != bssid && s.rssiDbm >= rssi + BETTER_AP_DB }
    } else null
    val freq = w?.frequencyMhz
    val bandText = freq?.let { f ->
        val ch = WifiMath.channelOf(f)
        "${WifiMath.bandOf(f)} GHz" + (ch?.let { " · channel $it" } ?: "")
    }
    val footer = when {
        w == null -> "Not connected · ${snap.scan.size} networks nearby"
        better != null -> "${ssid ?: "This network"} · a closer access point is available"
        else -> listOfNotNull(ssid, bandText).joinToString(" · ").ifEmpty { "Connected" }
    }
    return SignalUi(
        title = "Wi-Fi",
        value = if (rssi != null) "${rssi.toDouble().dbm()} dBm" else if (w == null) "Not connected" else "No reading",
        verdict = Verdict.wifiRssi(rssi?.toDouble()),
        position = scale(rssi?.toDouble(), WIFI_EDGES),
        low = "Weak", high = "Strong", footer = footer,
    )
}

private fun location(snap: LiveSnapshot): SignalUi {
    val loc = snap.rawLocation ?: snap.location
    val acc = loc?.hAccM
    val used = snap.usedSats
    val gps = loc?.provider == "gps" && used > 0
    val footer = if (loc == null) "Finding your position · $used satellites" else listOf(
        if (gps) "Outdoors" else "Indoors",
        "$used ${if (used == 1) "satellite" else "satellites"}",
        if (gps) "using GPS" else "using Wi-Fi",
    ).joinToString(" · ")
    return SignalUi(
        title = "Location",
        value = if (acc != null) "±${acc.roundToInt()} m · ${if (gps) "GPS" else "no GPS"}" else "No position yet",
        verdict = Verdict.position(acc),
        position = scale(acc, POSITION_EDGES),
        low = "Rough", high = "Precise", footer = footer,
    )
}

private const val BETTER_AP_DB = 8

@Composable
private fun SignalCard(s: SignalUi, icon: ImageVector, iconTint: Color, onClick: () -> Unit) {
    Card(color = tc.bgOf(s.verdict), padding = PaddingValues(horizontal = 18.dp, vertical = 18.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(s.title, style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text2, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(50)).background(tc.bg.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text(s.verdict.label, style = TType.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = tc.fg(s.verdict))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(s.value, style = TType.tileValue.merge(TType.num), color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        ScaleBar(s.position)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(s.low, style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3, modifier = Modifier.weight(1f))
            Text(s.high, style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(thickness = 0.5.dp, color = tc.meterOff)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.footer, style = TType.sub, color = tc.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("Details", style = TType.label, color = tc.link)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = tc.link, modifier = Modifier.size(20.dp))
        }
    }
}

/** Five verdict-coloured segments (bad → very good) with a white marker at [position]. */
@Composable
private fun ScaleBar(position: Float?) {
    val colors = listOf(tc.bad, tc.belowAvg, tc.average, tc.aboveAvg, tc.veryGood)
    val marker = 18.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(marker)) {
        Row(Modifier.fillMaxWidth().height(9.dp).align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            colors.forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(c)) }
        }
        if (position != null) {
            val x = (maxWidth - marker) * position.coerceIn(0f, 1f)
            Box(
                Modifier.offset(x = x).size(marker).clip(CircleShape).background(Color.White)
                    .border(2.5.dp, Color(0xFF1C1C1E), CircleShape),
            )
        }
    }
}

