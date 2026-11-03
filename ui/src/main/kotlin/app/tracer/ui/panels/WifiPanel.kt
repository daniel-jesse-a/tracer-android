package app.tracer.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tracer.core.quality.QualityLevel
import app.tracer.core.quality.QualityScale
import app.tracer.core.runtime.ChannelLoad
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.util.WifiMath
import app.tracer.ui.R
import app.tracer.ui.live.Fmt
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space

internal fun cleanSsid(ssid: String?): String? =
    ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

/** AP count on a channel → quality (more APs = worse). Default congestion threshold is 10. */
private fun congestion(aps: Int): QualityLevel = when {
    aps >= 10 -> QualityLevel.BAD
    aps >= 6 -> QualityLevel.POOR
    aps >= 3 -> QualityLevel.FAIR
    aps >= 2 -> QualityLevel.GOOD
    else -> QualityLevel.EXCELLENT
}

/** Wi-Fi tab: connected AP card, channel congestion per band, scan list. */
@Composable
fun WifiPanel(snap: LiveSnapshot) {
    val wifi = snap.wifi
    val scan = snap.scan
    val byBand = snap.channelLoad.groupBy { it.band }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.rowGap),
    ) {
        item(key = "conn") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                val onC = MaterialTheme.colorScheme.onPrimaryContainer
                Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (wifi == null) {
                        Text(stringResource(R.string.live_wifi_not_connected), style = MaterialTheme.typography.titleMedium, color = onC)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.live_wifi_connected), style = Mono.tiny, color = onC.copy(alpha = 0.75f))
                                Text(cleanSsid(wifi.ssid) ?: stringResource(R.string.live_wifi_hidden), style = MaterialTheme.typography.titleMedium, color = onC,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(wifi.bssid ?: Fmt.DASH, style = Mono.tiny, color = onC.copy(alpha = 0.75f))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.int(wifi.rssiDbm), style = Mono.big, color = onC)
                                Text("dBm · ${QualityScale.wifiRssi(wifi.rssiDbm).label}", style = Mono.tiny, color = onC.copy(alpha = 0.75f))
                            }
                        }
                        val f = wifi.frequencyMhz
                        val parts = listOfNotNull(
                            f?.let { WifiMath.channelOf(it) }?.let { "ch $it" },
                            f?.let { "${WifiMath.bandOf(it)} GHz" },
                            wifi.channelWidth?.let { "$it MHz" },
                            wifi.standard,
                            wifi.linkSpeedMbps?.let { "$it Mb/s" },
                            wifi.security,
                        )
                        Text(parts.joinToString(" · "), style = Mono.small, color = onC)
                    }
                }
            }
        }
        listOf("2.4", "5", "6").forEach { band ->
            val loads = byBand[band].orEmpty()
            if (loads.isNotEmpty()) {
                item(key = "band-$band") { CongestionChart(band, loads) }
            }
        }
        item(key = "scanhead") {
            Row(Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.s)) {
                Text(stringResource(R.string.live_wifi_scan, scan.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (scan.isEmpty()) {
            item(key = "scannone") {
                PanelRow(0, 1) {
                    Text(stringResource(R.string.live_wifi_no_scan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(scan, key = { i, r -> "${r.bssid}:$i" }) { i, r ->
            PanelRow(i, scan.size) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (wifi?.bssid != null && wifi.bssid == r.bssid) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.live_wifi_connected),
                            tint = LocalTracerColors.current.ok, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(cleanSsid(r.ssid) ?: stringResource(R.string.live_wifi_hidden), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.bssid, style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Fmt.int(r.rssiDbm), style = Mono.value)
                }
                Spacer(Modifier.height(6.dp))
                LevelBar(r.rssiDbm, -100, -30, QualityScale.wifiRssi(r.rssiDbm))
                Spacer(Modifier.height(4.dp))
                val parts = listOfNotNull(
                    WifiMath.channelOf(r.frequencyMhz)?.let { "ch $it" },
                    "${WifiMath.bandOf(r.frequencyMhz)} GHz",
                    r.channelWidth?.let { "$it MHz" },
                    r.standard,
                )
                Text(parts.joinToString(" · "), style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(Space.l)) }
    }
}

/** Bars per channel (height = AP count, colour = congestion level, count + channel printed). */
@Composable
private fun CongestionChart(band: String, loads: List<ChannelLoad>) {
    val colors = LocalTracerColors.current
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface
    val sorted = loads.sortedBy { it.channel }
    val maxAps = sorted.maxOf { it.aps }.coerceAtLeast(1)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = Space.s)) {
        Column(Modifier.padding(Space.l)) {
            Text(
                stringResource(R.string.live_wifi_band_load, band, sorted.sumOf { it.aps }),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(Space.s))
            Canvas(Modifier.fillMaxWidth().height(88.dp)) {
                val labelH = 14.dp.toPx()
                val chartH = size.height - 2 * labelH
                val slot = size.width / sorted.size
                val barW = (slot * 0.7f).coerceAtMost(28.dp.toPx())
                sorted.forEachIndexed { i, l ->
                    val h = chartH * l.aps / maxAps
                    val x = i * slot + (slot - barW) / 2
                    val top = labelH + chartH - h
                    drawRect(colors.quality(congestion(l.aps)), Offset(x, top), Size(barW, h))
                    val count = measurer.measure(l.aps.toString(), Mono.tiny.copy(color = valueColor))
                    drawText(count, topLeft = Offset(x + (barW - count.size.width) / 2, top - count.size.height))
                    val ch = measurer.measure(l.channel.toString(), Mono.tiny.copy(color = labelColor))
                    drawText(ch, topLeft = Offset(x + (barW - ch.size.width) / 2, size.height - labelH + 2))
                }
            }
        }
    }
}
