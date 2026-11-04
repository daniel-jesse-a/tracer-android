package app.tracer.ui.kit

import app.tracer.core.report.Metric
import app.tracer.ui.theme.TColors

/*
 * Y ranges, bands and reference lines per metric, shared by every chart so a metric looks the same
 * everywhere. Band edges follow the Verdict scale (core/report/Report.kt):
 *  - higher is better (RSSI, RSRP, SINR, RSRQ): good band on top, weak band at the bottom;
 *  - lower is better (response, position ±m): good band at the BOTTOM in green with a labelled
 *    reference line, the slow / rough region shaded at the TOP, caption "lower is better".
 */
data class ChartPreset(val yMin: Double, val yMax: Double, val bands: List<ChartBand>, val ref: ChartRef? = null, val caption: String? = null)

object Presets {
    fun wifiRssi(c: TColors) = ChartPreset(
        -95.0, -40.0,
        listOf(
            ChartBand(-65.0, -40.0, c.veryGoodBg, "Strong", c.veryGood),
            ChartBand(-80.0, -65.0, c.averageBg, "OK", c.average),
            ChartBand(-95.0, -80.0, c.badBg, "Weak", c.bad),
        ),
        caption = Metric.WIFI_RSSI.hint,
    )

    fun rsrp(c: TColors) = ChartPreset(
        -130.0, -70.0,
        listOf(
            ChartBand(-90.0, -70.0, c.veryGoodBg, "Strong", c.veryGood),
            ChartBand(-110.0, -90.0, c.averageBg, "OK", c.average),
            ChartBand(-130.0, -110.0, c.badBg, "Weak", c.bad),
        ),
        caption = Metric.RSRP.hint,
    )

    fun sinr(c: TColors) = ChartPreset(
        -10.0, 30.0,
        listOf(
            ChartBand(13.0, 30.0, c.veryGoodBg, "Clean", c.veryGood),
            ChartBand(0.0, 13.0, c.averageBg, "OK", c.average),
            ChartBand(-10.0, 0.0, c.badBg, "Noisy", c.bad),
        ),
        caption = Metric.SINR.hint,
    )

    fun rsrq(c: TColors) = ChartPreset(
        -22.0, -3.0,
        listOf(
            ChartBand(-10.0, -3.0, c.veryGoodBg, "Good", c.veryGood),
            ChartBand(-15.0, -10.0, c.averageBg, "OK", c.average),
            ChartBand(-22.0, -15.0, c.badBg, "Poor", c.bad),
        ),
        caption = Metric.RSRQ.hint,
    )

    /** Response / latency ms: good below 150 ms (bottom, green), slow above 300 ms (top, shaded). */
    fun response(c: TColors, maxSeen: Double?) : ChartPreset {
        val top = maxOf(400.0, (maxSeen ?: 0.0) * 1.15)
        return ChartPreset(
            0.0, top,
            listOf(
                ChartBand(0.0, 150.0, c.veryGoodBg),
                ChartBand(300.0, top, c.badBg, "Slow", c.bad),
            ),
            ref = ChartRef(150.0, c.veryGood, "Good below 150 ms"),
            caption = Metric.RESPONSE.hint,
        )
    }

    /** Position accuracy ±m: precise below 8 m (bottom, green), rough above 20 m (top, shaded). */
    fun accuracy(c: TColors, maxSeen: Double?): ChartPreset {
        val top = maxOf(40.0, (maxSeen ?: 0.0) * 1.15)
        return ChartPreset(
            0.0, top,
            listOf(
                ChartBand(0.0, 8.0, c.veryGoodBg),
                ChartBand(20.0, top, c.badBg, "Rough", c.bad),
            ),
            ref = ChartRef(8.0, c.veryGood, "Good below 8 m"),
            caption = Metric.POSITION.hint,
        )
    }

    /** Mbps (speed tests, Wi-Fi link speed): higher is better, good above 25 Mbps. */
    fun mbps(c: TColors, maxSeen: Double?): ChartPreset {
        val top = maxOf(50.0, (maxSeen ?: 0.0) * 1.15)
        return ChartPreset(
            0.0, top,
            listOf(ChartBand(0.0, 2.0, c.badBg, null)),
            ref = ChartRef(25.0, c.veryGood, "Good above 25 Mbps"),
            caption = Metric.SPEED.hint,
        )
    }
}

/** [LineChart] with a preset's range, bands, reference and caption. */
@androidx.compose.runtime.Composable
fun PresetChart(
    preset: ChartPreset,
    lines: List<ChartLine>,
    xMin: Double,
    xMax: Double,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    height: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(150f),
    markers: List<Double> = emptyList(),
    cursorX: Double? = null,
    rings: List<ChartRing> = emptyList(),
    emptyReason: String? = null,
    currentDot: Boolean = false,
    xLabels: List<Pair<Double, String>> = emptyList(),
    yTicks: List<Pair<Double, String>> = emptyList(),
    showCaption: Boolean = true,
) = LineChart(
    lines = lines, yMin = preset.yMin, yMax = preset.yMax, xMin = xMin, xMax = xMax, modifier = modifier, height = height,
    bands = preset.bands, ref = preset.ref, markers = markers, cursorX = cursorX, rings = rings, emptyReason = emptyReason,
    currentDot = currentDot, xLabels = xLabels, yTicks = yTicks, caption = if (showCaption) preset.caption else null,
)
