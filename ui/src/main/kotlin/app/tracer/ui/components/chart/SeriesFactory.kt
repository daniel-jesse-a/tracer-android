package app.tracer.ui.components.chart

import app.tracer.core.quality.ConnType
import app.tracer.ui.theme.TracerExtraColors

/** Builders for the design's standard lanes so every screen draws them identically. */
object SeriesFactory {

    /** TYPE band in connection-type colours; None hatched. */
    fun connType(label: String, xs: LongArray, types: Array<ConnType>, colors: TracerExtraColors, noDataText: String? = null): CategoricalSeries =
        CategoricalSeries(
            label = label,
            xs = xs,
            values = Array(types.size) { types[it].name },
            palette = ConnType.entries.associate { it.name to colors.conn(it) },
            names = ConnType.entries.associate { it.name to it.label },
            hatched = ConnType.entries.filter { it.dotted }.map { it.name }.toSet(),
            noDataText = noDataText,
        )

    /** RSRP −140…−44 dBm with the dotted −110 threshold. */
    fun rsrp(label: String, xs: LongArray, ys: FloatArray, colors: TracerExtraColors, noDataText: String? = null) =
        NumericSeries(label, "dBm", xs, ys, -140f, -44f, colors.good, threshold = -110f, noDataText = noDataText)

    fun sinr(label: String, xs: LongArray, ys: FloatArray, colors: TracerExtraColors, noDataText: String? = null) =
        NumericSeries(label, "dB", xs, ys, -10f, 30f, colors.excellent, decimals = 0, threshold = 0f, noDataText = noDataText)

    /** Latency 0…400 ms; failed probes (timeouts) are hatched. */
    fun latency(label: String, xs: LongArray, ys: FloatArray, failed: BooleanArray?, colors: TracerExtraColors, noDataText: String? = null) =
        NumericSeries(label, "ms", xs, ys, 0f, 400f, colors.fair, threshold = 250f, failed = failed, noDataText = noDataText)

    fun loss(label: String, xs: LongArray, ys: FloatArray, colors: TracerExtraColors, noDataText: String? = null) =
        NumericSeries(label, "%", xs, ys, 0f, 100f, colors.bad, noDataText = noDataText)

    fun throughput(label: String, xs: LongArray, ys: FloatArray, colors: TracerExtraColors, noDataText: String? = null) =
        NumericSeries(label, "Mb/s", xs, ys, 0f, 200f, colors.nrSa, decimals = 1, maxGapNs = 180_000_000_000L, noDataText = noDataText)

    fun wifiRssi(label: String, xs: LongArray, ys: FloatArray, colors: TracerExtraColors, noDataText: String? = null) =
        NumericSeries(label, "dBm", xs, ys, -100f, -30f, colors.wifi, threshold = -75f, noDataText = noDataText)

    fun satellites(label: String, xs: LongArray, ys: FloatArray, colors: TracerExtraColors, noDataText: String? = null) =
        NumericSeries(label, "", xs, ys, 0f, 40f, colors.info, threshold = 5f, noDataText = noDataText)
}
