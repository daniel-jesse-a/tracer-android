package app.tracer.ui.components.chart

/**
 * Min/max-per-pixel-column decimation for time series.
 *
 * Splits the time range [start, end] into [columns] equal columns and returns, for each column,
 * the minimum and maximum y of the points that fall in it:
 *   out[2 * c]     = min y in column c
 *   out[2 * c + 1] = max y in column c
 * Columns without data (and NaN ys, which mean "no value") yield NaN for both entries.
 *
 * [xs] must be sorted ascending (samples are ordered by t_mono_ns); [xs] and [ys] have equal size.
 * Points exactly at [end] land in the last column. Runs in O(log n + k) for k points in range,
 * so a 10k+ point series decimates in well under a millisecond.
 */
fun decimateMinMax(xs: LongArray, ys: FloatArray, start: Long, end: Long, columns: Int): FloatArray {
    require(xs.size == ys.size) { "xs and ys must have the same size" }
    if (columns <= 0) return FloatArray(0)
    val out = FloatArray(columns * 2) { Float.NaN }
    if (end <= start || xs.isEmpty()) return out
    val span = (end - start).toDouble()
    var i = lowerBound(xs, start)
    while (i < xs.size) {
        val x = xs[i]
        if (x > end) break
        val y = ys[i]
        if (!y.isNaN()) {
            val c = ((x - start) / span * columns).toInt().coerceIn(0, columns - 1)
            val mi = 2 * c
            val lo = out[mi]
            val hi = out[mi + 1]
            if (lo.isNaN() || y < lo) out[mi] = y
            if (hi.isNaN() || y > hi) out[mi + 1] = y
        }
        i++
    }
    return out
}

/** Index of the first element ≥ [value] in ascending [xs] (xs.size if none). */
fun lowerBound(xs: LongArray, value: Long): Int {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] < value) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Index of the last element ≤ [value] in ascending [xs], or -1. */
fun floorIndex(xs: LongArray, value: Long): Int {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] <= value) lo = mid + 1 else hi = mid
    }
    return lo - 1
}
