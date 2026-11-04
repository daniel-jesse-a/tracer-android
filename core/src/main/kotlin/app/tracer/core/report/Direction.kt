package app.tracer.core.report

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which way is "good" for every metric the app judges, colours, ranks, describes or charts.
 *
 * LOWER is better: response / latency / jitter / loss / DNS / connect / TLS / TTFB, position accuracy
 * (± m), switches, drops, false drops, recording gaps, clock gap.
 * HIGHER is better: Wi-Fi RSSI, RSRP, RSRQ, SINR, speed, online %, checks %, satellites, coverage, GPS fixes.
 *
 * The UI uses [Direction.better] for comparison deltas and [Metric.hint] for chart captions so the
 * rule lives in one place (pinned by DirectionTest).
 */
enum class Metric(val label: String, val unit: String, val higherIsBetter: Boolean) {
    // lower is better
    RESPONSE("Response", "ms", false),
    LATENCY("Latency", "ms", false),
    JITTER("Jitter", "ms", false),
    LOSS("Loss", "%", false),
    DNS("DNS", "ms", false),
    CONNECT("Connect", "ms", false),
    TLS("TLS", "ms", false),
    TTFB("First byte", "ms", false),
    POSITION("Position", "m", false),
    SWITCHES("Switches", "", false),
    DROPS("Drops", "", false),
    FALSE_DROPS("False drops", "", false),
    GAPS("Gaps", "", false),
    CLOCK_GAP("Clock gap", "ms", false),
    // higher is better
    WIFI_RSSI("Wi-Fi signal", "dBm", true),
    RSRP("Mobile signal", "dBm", true),
    RSRQ("RSRQ", "dB", true),
    SINR("SINR", "dB", true),
    SPEED("Speed", "Mbps", true),
    ONLINE("Online", "%", true),
    CHECKS("Checks passed", "%", true),
    SATELLITES("Satellites", "", true),
    COVERAGE("Recorded", "%", true),
    GPS_FIXES("GPS fixes", "", true);

    /** "lower is better" / "higher is better" (chart captions). */
    val hint: String get() = if (higherIsBetter) "higher is better" else "lower is better"
}

/** Outcome of a before → after change for one metric. */
enum class Change { BETTER, WORSE, SAME, UNKNOWN }

object Direction {
    /**
     * true = [after] is an improvement over [before], false = it got worse,
     * null = unchanged (within [tolerance]) or a value is missing.
     */
    fun better(metric: Metric, before: Double?, after: Double?, tolerance: Double = 0.0): Boolean? {
        if (before == null || after == null || before.isNaN() || after.isNaN()) return null
        val d = after - before
        if (abs(d) <= tolerance) return null
        return if (metric.higherIsBetter) d > 0 else d < 0
    }

    fun better(metric: Metric, before: Int?, after: Int?): Boolean? = better(metric, before?.toDouble(), after?.toDouble())

    fun change(metric: Metric, before: Double?, after: Double?, tolerance: Double = 0.0): Change = when {
        before == null || after == null || before.isNaN() || after.isNaN() -> Change.UNKNOWN
        else -> when (better(metric, before, after, tolerance)) { true -> Change.BETTER; false -> Change.WORSE; null -> Change.SAME }
    }

    /** Is [v] on the good side of [threshold] for this metric (e.g. response 120 ms vs "good below 150 ms")? */
    fun isGood(metric: Metric, v: Double, threshold: Double): Boolean = if (metric.higherIsBetter) v >= threshold else v <= threshold
}

/**
 * One comparison line ("Response 268 → 180 ms"). [better] says which way it moved for this metric;
 * the UI colours true green, false orange, null neutral.
 */
data class Delta(val metric: Metric, val before: Double, val after: Double, val text: String) {
    val better: Boolean? get() = Direction.better(metric, before, after)
    val change: Change get() = Direction.change(metric, before, after)

    companion object {
        /** "Response 268 → 180 ms", "False drops 4 → 0", "Online 95% → 99%". */
        fun of(metric: Metric, before: Double, after: Double, label: String = metric.label): Delta {
            fun f(v: Double) = v.roundToInt().toString().replace('-', '−')
            val text = when (metric.unit) {
                "" -> "$label ${f(before)} → ${f(after)}"
                "%" -> "$label ${f(before)}% → ${f(after)}%"
                "m" -> "$label ±${f(before)} → ±${f(after)} m"
                else -> "$label ${f(before)} → ${f(after)} ${metric.unit}"
            }
            return Delta(metric, before, after, text)
        }
    }
}
