package app.tracer.ui.components.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A lane of [TimelineChart]. Arrays are treated as immutable once handed to the chart (they are
 * remember keys by identity); build new instances for new data.
 */
@Immutable
sealed class ChartSeries {
    /** Short caps label shown in the gutter: TYPE, RSRP, LAT … */
    abstract val label: String
    /** t_mono_ns, ascending. */
    abstract val xs: LongArray
    /** A value older than this (ns) is "no data" at the cursor and breaks the line. */
    abstract val maxGapNs: Long
    /** Shown greyed in the lane when it has nothing to draw, e.g. "no Wi-Fi data". */
    abstract val noDataText: String?
    abstract val hasData: Boolean
}

@Immutable
class NumericSeries(
    override val label: String,
    val unit: String,
    override val xs: LongArray,
    /** NaN = no value at that sample (e.g. failed probe). */
    val ys: FloatArray,
    val yMin: Float,
    val yMax: Float,
    val color: Color,
    val decimals: Int = 0,
    override val maxGapNs: Long = 30_000_000_000L,
    /** Dotted horizontal threshold (e.g. RSRP −110). */
    val threshold: Float? = null,
    /** Samples that failed (e.g. probe timeout) → hatched band. Same size as [xs] or null. */
    val failed: BooleanArray? = null,
    override val noDataText: String? = null,
    /**
     * Direction of "good" (see core/report/Direction.kt). With a [threshold], the bad side of it is
     * tinted: below for higher-is-better lanes (RSRP, SINR, Wi-Fi), above for lower-is-better lanes
     * (latency, loss), so a rising latency line reads as worse at a glance.
     */
    val higherIsBetter: Boolean = true,
) : ChartSeries() {
    override val hasData: Boolean = ys.any { !it.isNaN() } || (failed?.any { it } ?: false)

    fun format(v: Float): String {
        val s = if (decimals == 0) v.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", v)
        return s.replace('-', '−')
    }

    /** Value in effect at [t], or null if none / stale. */
    fun valueAt(t: Long): Float? {
        val i = floorIndex(xs, t)
        if (i < 0 || t - xs[i] > maxGapNs) return null
        return ys[i].takeIf { !it.isNaN() }
    }

    fun failedAt(t: Long): Boolean {
        val f = failed ?: return false
        val i = floorIndex(xs, t)
        return i >= 0 && t - xs[i] <= maxGapNs && f[i]
    }
}

@Immutable
class CategoricalSeries(
    override val label: String,
    override val xs: LongArray,
    /** Category per sample (same size as [xs]). */
    val values: Array<String>,
    val palette: Map<String, Color>,
    /** Optional display names; falls back to the raw category. */
    val names: Map<String, String> = emptyMap(),
    /** Categories drawn hatched (e.g. connection type None) so they never rely on hue. */
    val hatched: Set<String> = emptySet(),
    val fallback: Color = Color(0xFF5A5A60),
    override val maxGapNs: Long = 60_000_000_000L,
    override val noDataText: String? = null,
) : ChartSeries() {
    override val hasData: Boolean = xs.isNotEmpty()

    fun valueAt(t: Long): String? {
        val i = floorIndex(xs, t)
        if (i < 0 || t - xs[i] > maxGapNs) return null
        return values[i]
    }

    fun colorOf(v: String): Color = palette[v] ?: fallback
    fun nameOf(v: String): String = names[v] ?: v
}

/** Helpers to build arrays from nullable values. */
object SeriesArrays {
    inline fun <T> longs(items: List<T>, t: (T) -> Long): LongArray = LongArray(items.size) { t(items[it]) }
    inline fun <T> floats(items: List<T>, v: (T) -> Number?): FloatArray =
        FloatArray(items.size) { v(items[it])?.toFloat() ?: Float.NaN }
}
