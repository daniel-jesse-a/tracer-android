package app.tracer.ui.signals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.SatView
import app.tracer.ui.kit.Card
import app.tracer.ui.kit.ChartLine
import app.tracer.ui.kit.PresetChart
import app.tracer.ui.kit.Presets
import app.tracer.ui.kit.liveXLabels
import app.tracer.ui.kit.tc
import app.tracer.ui.kit.toPts
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.TColors
import app.tracer.ui.theme.TType
import java.util.Locale
import kotlin.math.roundToInt

private fun providerLabel(p: String?): String? = when (p) {
    "gps" -> "GPS"
    "fused" -> "Fused (GPS + Wi-Fi + cell)"
    "network" -> "Wi-Fi and cell towers"
    null -> null
    else -> p
}

/** C/N0 dB-Hz: higher is better. */
private fun cn0Color(c: TColors, v: Double): Color = when {
    v >= 40 -> c.veryGood
    v >= 33 -> c.aboveAvg
    v >= 25 -> c.average
    v >= 18 -> c.belowAvg
    else -> c.bad
}

private fun constellationName(c: String): String = when (c) {
    "GPS" -> "GPS"; "GLONASS" -> "GLONASS"; "GALILEO" -> "Galileo"; "BEIDOU" -> "BeiDou"; "QZSS" -> "QZSS"
    "SBAS" -> "SBAS"; "IRNSS" -> "NavIC"; else -> c.lowercase().replaceFirstChar { it.uppercase() }
}

/** Signals › Location: accuracy graph (lower is better), provider, satellites by constellation, technical. */
@Composable
fun LocationDetailScreen(onBack: () -> Unit) {
    val vm: SignalDetailViewModel = hiltViewModel()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    var win by rememberSaveable { mutableIntStateOf(1) }
    val now = liveNowMs(snap)
    val windowS = WINDOWS_S[win]
    val loc = snap.rawLocation ?: snap.location
    val acc = loc?.hAccM
    val v = Verdict.position(acc)
    val used = snap.usedSats

    DetailScreen("Location", onBack) {
        Hero(
            eyebrow = "Position accuracy" + (providerLabel(loc?.provider)?.let { " · $it" } ?: ""),
            value = acc?.let { "±${it.roundToInt()}" } ?: "–", unit = if (acc != null) "m" else null, v = v,
            sub = if (loc == null) "Finding your position · $used satellites used"
            else "Smaller is better · $used of ${snap.satellites.size} satellites used",
            verdictText = if (acc == null) "No data" else v.label,
        )

        ChartCard("Accuracy", trailing = acc?.let { "±${it.roundToInt()} m" }) {
            WindowPicker(win) { win = it }
            Spacer(Modifier.height(12.dp))
            val pts = snap.history.hAcc.toPts(now)
            PresetChart(
                Presets.accuracy(tc, pts.filter { it.tS >= -windowS }.maxOfOrNull { it.v }),
                listOf(ChartLine(pts, tc.link, maxGapS = 15.0)),
                xMin = -windowS, xMax = 0.0, currentDot = true, xLabels = liveXLabels(windowS),
                emptyReason = "Waiting for a position",
            )
        }

        if (loc?.isMock == true) {
            Card(color = tc.badBg) {
                Text("Location is simulated", style = TType.headline, color = tc.bad)
                Text("Another app is providing a mock location. Positions in this test are not real.", style = TType.sub, color = tc.text2)
            }
        }

        ChartCard("Movement") {
            KV("Source", providerLabel(loc?.provider))
            KV("Speed", loc?.speedMps?.let { "%.1f km/h".format(Locale.US, it * 3.6) })
            KV("Altitude", loc?.altM?.let { "${it.roundToInt()} m" })
            KV("Heading", loc?.courseDeg?.let { "${it.roundToInt()}°" })
            KV("Distance so far", if (snap.distanceM >= 1000) "%.2f km".format(Locale.US, snap.distanceM / 1000) else "${snap.distanceM.roundToInt()} m")
        }

        SatellitesCard(snap.satellites)

        ExpandSection("Technical", sub = "Raw fix, filtered fix, clocks") {
            SubHead("Raw fix")
            val raw = snap.rawLocation
            KV("Provider", raw?.provider)
            KV("Lat, lon", raw?.let { "%.6f, %.6f".format(Locale.US, it.lat, it.lon) })
            KV("Horizontal ±", raw?.hAccM?.let { "%.1f m".format(Locale.US, it) })
            KV("Vertical ±", raw?.vAccM?.let { "%.1f m".format(Locale.US, it) })
            KV("Speed ±", raw?.speedAcc?.let { "%.2f m/s".format(Locale.US, it) })
            KV("Mock location", when (raw?.isMock) { true -> "Yes"; false -> "No"; null -> "Not reported" },
                valueColor = if (raw?.isMock == true) tc.bad else tc.text)
            KV("Fix age", raw?.let { ago(now, it.stamp.tMonoMs) })
            SubHead("Shown position (filtered)")
            val f = snap.location
            KV("Lat, lon", f?.let { "%.6f, %.6f".format(Locale.US, it.lat, it.lon) })
            KV("Estimate ±", f?.hAccM?.let { "%.1f m".format(Locale.US, it) })
            KV("GPS − phone clock", snap.gpsOffsetMs?.let { "$it ms".replace('-', '−') })
        }
    }
}

@Composable
private fun SatellitesCard(sats: List<SatView>) {
    val groups = sats.groupBy { it.constellation }.toList().sortedByDescending { (_, l) -> l.count { it.used } * 100 + l.size }
    ChartCard("Satellites", trailing = "${sats.count { it.used }} used · ${sats.size} seen") {
        if (sats.isEmpty()) {
            Text("No satellites in view. Indoors the phone uses Wi-Fi and cell towers instead.", style = TType.sub, color = tc.text3)
            return@ChartCard
        }
        Text("Bar = signal strength (C/N0, higher is better). Filled dot = used for the position.", style = TType.footnote.copy(fontSize = 13.sp), color = tc.text3)
        groups.forEach { (con, list) ->
            Text(
                "${constellationName(con)} · ${list.count { it.used }} of ${list.size} used",
                style = TType.label, color = tc.text2, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            list.sortedByDescending { it.cn0 }.take(12).forEach { s -> SatRow(s) }
        }
    }
}

@Composable
private fun SatRow(s: SatView) {
    val col = cn0Color(tc, s.cn0)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(if (s.used) col else tc.card2),
        )
        Spacer(Modifier.width(8.dp))
        Text("${s.svid}", style = Mono.small, color = tc.text2, modifier = Modifier.width(34.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(tc.card2)) {
            Box(Modifier.fillMaxWidth((s.cn0 / 50.0).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(col))
        }
        Spacer(Modifier.width(8.dp))
        Text("${s.cn0.roundToInt()}", style = Mono.small, color = tc.text, modifier = Modifier.width(26.dp))
        Text("${s.elevation.roundToInt()}°", style = Mono.tiny, color = tc.text3, modifier = Modifier.width(30.dp))
    }
}

