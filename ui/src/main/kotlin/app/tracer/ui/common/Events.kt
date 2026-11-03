package app.tracer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.GpsOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NetworkCell
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.NetworkWifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tracer.core.model.EventSample
import app.tracer.core.quality.ConnType
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.severityColor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/*
 * Shared event vocabulary (design: "Event icon set · 24 px grid, 2 px stroke, colour = severity").
 * eventType strings are app.tracer.events.EventType constants; "annotation"/"manual_mark" = Manual mark.
 */

private fun iconFor(eventType: String): ImageVector = when (eventType) {
    "handover" -> Icons.Outlined.SwapHoriz
    "rat_change" -> Icons.Outlined.NetworkCell
    "wifi_roam" -> Icons.Outlined.NetworkWifi
    "drop" -> Icons.Outlined.Block
    "reconnect" -> Icons.Outlined.Autorenew
    "captive_portal" -> Icons.Outlined.Web
    "ip_asn_change" -> Icons.Outlined.Public
    "gps_degraded" -> Icons.Outlined.GpsOff
    "latency_spike" -> Icons.AutoMirrored.Outlined.ShowChart
    "dns_spike" -> Icons.Outlined.Dns
    "annotation", "manual_mark" -> Icons.Outlined.Bookmark
    "signal_drop" -> Icons.AutoMirrored.Outlined.TrendingDown
    "neighbour_overtake" -> Icons.AutoMirrored.Outlined.CompareArrows
    "throughput_drop" -> Icons.Outlined.Speed
    else -> Icons.Outlined.Info
}

/** 24 dp event glyph tinted by severity (Reconnect uses the ok colour). Decorative: titles carry meaning. */
@Composable
fun EventIcon(eventType: String, severity: String, modifier: Modifier = Modifier) {
    val c = LocalTracerColors.current
    val tint = if (eventType == "reconnect") c.ok else severityColor(severity, c)
    if (eventType == "wifi_cell_switch") {
        Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
            }
        }
    } else {
        Icon(iconFor(eventType), contentDescription = null, tint = tint, modifier = modifier.size(24.dp))
    }
}

// ---------------------------------------------------------------- text helpers (English, data labels)

private fun obj(json: String?): JsonObject? = json?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
private fun JsonObject?.num(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
private fun JsonObject?.child(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun dbm(v: Double?): String = formatDbm(v?.roundToInt())
private fun ms(v: Double?): String = formatMs(v)
private fun ratLabel(rat: String?): String = when {
    rat == null -> NO_VALUE
    ConnType.of(rat) == ConnType.NONE -> rat
    else -> ConnType.of(rat).label
}
private fun host(url: String?): String? = url?.substringAfter("://")?.substringBefore('/')

/** "Handover PCI 87 → 214", "Drop · no service", "Latency spike · 420 ms" … */
fun eventTitle(e: EventSample): String {
    val b = obj(e.beforeJson)
    val a = obj(e.afterJson)
    return when (e.eventType) {
        "handover" -> "Handover PCI ${b.str("pci") ?: NO_VALUE} → ${a.str("pci") ?: NO_VALUE}"
        "rat_change" -> "RAT change · ${ratLabel(a.str("rat"))}"
        "wifi_roam" -> "Wi-Fi roam · ${a.str("ssid") ?: b.str("ssid") ?: NO_VALUE}"
        "wifi_cell_switch" -> "Wi-Fi ↔ cell · ${a.str("primary_interface") ?: "none"}"
        "drop" -> "Drop · no service"
        "reconnect" -> "Reconnect · ${a.str("interface") ?: NO_VALUE}"
        "captive_portal" -> "Captive portal"
        "ip_asn_change" -> "IP / ASN change"
        "gps_degraded" -> "GPS degraded"
        "latency_spike" -> "Latency spike · ${ms(a.num("total_ms"))} ms"
        "dns_spike" -> "DNS spike · ${ms(a.num("dns_ms"))} ms"
        "throughput_drop" -> "Throughput drop · ${a.num("mbps")?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: NO_VALUE} Mb/s"
        "signal_drop" -> "Signal drop · RSRP ${dbm(a.num("rsrp"))}"
        "neighbour_overtake" -> "Neighbour overtake PCI ${a.str("pci") ?: NO_VALUE}"
        "annotation", "manual_mark" -> "Manual mark"
        else -> e.eventType.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Mono sub-line: "RSRP −109 → −94 · likely cell edge". */
fun eventSubtitle(e: EventSample): String {
    val b = obj(e.beforeJson)
    val a = obj(e.afterJson)
    val parts = mutableListOf<String>()
    when (e.eventType) {
        "handover", "neighbour_overtake" -> {
            parts += "RSRP ${dbm(b.num("rsrp"))} → ${dbm(a.num("rsrp"))}"
            a.str("band")?.let { parts += it }
        }
        "rat_change" -> parts += "${ratLabel(b.str("rat"))} → ${ratLabel(a.str("rat"))}"
        "wifi_roam" -> parts += "RSSI ${dbm(b.num("rssi_dbm"))} → ${dbm(a.num("rssi_dbm"))}"
        "wifi_cell_switch" -> parts += "${b.str("primary_interface") ?: "none"} → ${a.str("primary_interface") ?: "none"}"
        "drop" -> {
            b.str("trigger")?.let { parts += it.replace('_', ' ') }
            a.num("consecutive_failures")?.let { parts += "${it.roundToInt()} failures" }
            a.str("error")?.let { parts += it }
        }
        "reconnect" -> a.num("outage_ms")?.let { parts += String.format(java.util.Locale.ROOT, "%.1f s outage", it / 1000) }
        "captive_portal" -> (a.str("error") ?: a.str("unsatisfied_reason"))?.let { parts += it }
        "ip_asn_change" -> parts += "${b.str("asn_org") ?: b.str("public_ip") ?: NO_VALUE} → ${a.str("asn_org") ?: a.str("public_ip") ?: NO_VALUE}"
        "latency_spike" -> {
            parts += "median ${ms(b.num("median_ms"))} ms"
            host(a.str("endpoint"))?.let { parts += it }
        }
        "dns_spike" -> parts += "median ${ms(b.num("median_dns_ms"))} ms"
        "throughput_drop" -> parts += "median ${b.num("median_mbps")?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: NO_VALUE} Mb/s"
        "signal_drop" -> parts += "from ${dbm(b.num("rsrp"))} · PCI ${a.str("pci") ?: NO_VALUE}"
        "gps_degraded" -> {
            a.num("h_acc_m")?.let { parts += "±${it.roundToInt()} m" }
            a.num("used")?.let { parts += "${it.roundToInt()} sats" }
        }
        else -> Unit
    }
    // evidence snapshot fallback for context
    if (parts.isEmpty()) obj(e.evidenceJson).child("serving")?.num("rsrp")?.let { parts += "RSRP ${dbm(it)}" }
    e.likelyCause?.let { parts += "likely ${it.replace('_', ' ')}" }
    return parts.joinToString(" · ").ifEmpty { e.severity }
}
