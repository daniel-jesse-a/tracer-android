package app.tracer.core.util

import kotlin.math.*

object Geo {
    private const val R = 6_371_008.8

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    /** Destination point given start, bearing (deg) and distance (m) — used for TA rings. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val d = distM / R
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }
}

object Stats {
    fun median(xs: Collection<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun percentile(xs: Collection<Double>, p: Double): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val idx = (p / 100.0 * (s.size - 1)).coerceIn(0.0, (s.size - 1).toDouble())
        val lo = floor(idx).toInt(); val hi = ceil(idx).toInt()
        return s[lo] + (s[hi] - s[lo]) * (idx - lo)
    }

    /** Mean absolute difference of consecutive samples (RFC 3550-style jitter, unsmoothed). */
    fun jitter(xs: List<Double>): Double? =
        if (xs.size < 2) null else xs.zipWithNext { a, b -> abs(b - a) }.average()
}

/** Fixed-capacity ring buffer, newest last. */
class Ring<T>(private val capacity: Int) : Iterable<T> {
    private val buf = ArrayDeque<T>(capacity)
    fun add(x: T) { if (buf.size == capacity) buf.removeFirst(); buf.addLast(x) }
    val size get() = buf.size
    fun last(): T? = buf.lastOrNull()
    fun clear() = buf.clear()
    fun toList(): List<T> = buf.toList()
    override fun iterator(): Iterator<T> = buf.iterator()
}

/** Time-windowed buffer keyed by monotonic ms; drops entries older than [windowMs]. */
class TimeWindow<T>(private val windowMs: Long) : Iterable<Pair<Long, T>> {
    private val buf = ArrayDeque<Pair<Long, T>>()
    fun add(tMs: Long, x: T) { buf.addLast(tMs to x); trim(tMs) }
    fun trim(nowMs: Long) { while (buf.isNotEmpty() && nowMs - buf.first().first > windowMs) buf.removeFirst() }
    fun since(nowMs: Long, ms: Long): List<T> = buf.filter { nowMs - it.first <= ms }.map { it.second }
    fun values(): List<T> = buf.map { it.second }
    fun last(): T? = buf.lastOrNull()?.second
    /** Values with timestamps in [fromMs, toMs]. */
    fun between(fromMs: Long, toMs: Long): List<T> = buf.filter { it.first in fromMs..toMs }.map { it.second }
    /** Newest value at or before [tMs] (state reported on change is valid until the next report). */
    fun lastAtOrBefore(tMs: Long): T? = buf.lastOrNull { it.first <= tMs }?.second
    fun lastAtOrBeforeWithTime(tMs: Long): Pair<Long, T>? = buf.lastOrNull { it.first <= tMs }
    val size get() = buf.size
    override fun iterator(): Iterator<Pair<Long, T>> = buf.iterator()
}

object WifiMath {
    fun channelOf(freqMhz: Int): Int? = when (freqMhz) {
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5
        in 5160..5885 -> (freqMhz - 5000) / 5
        in 5955..7115 -> (freqMhz - 5950) / 5
        else -> null
    }

    /** "2.4" | "5" | "6" | "60" */
    fun bandOf(freqMhz: Int): String = when (freqMhz) {
        in 2400..2500 -> "2.4"
        in 4900..5900 -> "5"
        in 5925..7125 -> "6"
        in 57000..71000 -> "60"
        else -> "?"
    }

    /** android.net.wifi.ScanResult.WIFI_STANDARD_* → canonical string. */
    fun standardOf(androidStandard: Int, freqMhz: Int? = null): String = when (androidStandard) {
        1 -> "legacy"
        4 -> "wifi4"
        5 -> "wifi5"
        6 -> if (freqMhz != null && bandOf(freqMhz) == "6") "wifi6e" else "wifi6"
        7 -> "wigig"
        8 -> "wifi7"
        else -> "unknown"
    }

    /** ScanResult.CHANNEL_WIDTH_* → MHz text. */
    fun widthOf(androidWidth: Int): String? = when (androidWidth) {
        0 -> "20"; 1 -> "40"; 2 -> "80"; 3 -> "160"; 4 -> "80+80"; 5 -> "320"; else -> null
    }

    /** Count of APs per (band, primary channel). */
    fun congestion(freqs: List<Int>): Map<Pair<String, Int>, Int> =
        freqs.mapNotNull { f -> channelOf(f)?.let { bandOf(f) to it } }.groupingBy { it }.eachCount()
}
