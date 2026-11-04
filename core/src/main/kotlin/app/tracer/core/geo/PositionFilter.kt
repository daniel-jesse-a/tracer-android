package app.tracer.core.geo

import app.tracer.core.model.LocationSample
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Display position for the map: one stable estimate from the mixed gps / fused / network fixes.
 *
 * Why: indoors the fused and network providers return fixes that wander 10–60 m around the true
 * spot, and raw GPS (when it has a fix) disagrees with both. Following every fix makes the dot and
 * the track jump around while the phone sits on a desk.
 *
 *  1. Provider choice: raw GPS while it is fresh (≤ 5 s) and ≤ 30 m; otherwise fused; network-only
 *     fixes only when nothing better arrived for 10 s.
 *  2. Outlier rejection: accuracy worse than max(50 m, 3 × the recent median accuracy), or a jump
 *     implying > 60 m/s, is ignored.
 *  3. Kalman-style blend in local metres. Measurement variance = accuracy². Process noise follows
 *     the evidence of movement: still (motion says still, or GPS speed < 0.5 m/s with good speed
 *     accuracy) → almost none, so new fixes only refine the spot; moving → speed-driven.
 *  4. Stationary hold: while still, a fix that lies inside the combined accuracy circle does not move
 *     the dot more than a fraction of a metre.
 *
 * Raw fixes are still recorded unchanged; this only feeds the map, the live distance and the track.
 */
class PositionFilter {
    private var lat = 0.0
    private var lon = 0.0
    /** estimate variance, m² */
    private var p = 0.0
    private var lastNs = Long.MIN_VALUE
    private var have = false
    private var lastGpsNs = Long.MIN_VALUE
    private var lastGoodNs = Long.MIN_VALUE
    private val accs = ArrayDeque<Double>()
    private var lastSpeed: Double? = null
    private var far = 0
    private var n = 0

    /** Last emitted estimate. */
    var current: LocationSample? = null
        private set

    /**
     * @param still true when motion recognition says the phone is still, false when moving, null unknown
     * @return the filtered position (copy of [l] with smoothed lat/lon and the estimate accuracy), or null when the fix is ignored
     */
    fun add(l: LocationSample, still: Boolean? = null): LocationSample? {
        val t = l.stamp.tMonoNs
        val acc = (l.hAccM ?: 30.0).coerceIn(1.0, 5_000.0)
        val isGps = l.provider == "gps"

        // 1. provider choice
        if (isGps && acc <= 30.0) lastGpsNs = t
        val gpsFresh = lastGpsNs != Long.MIN_VALUE && t - lastGpsNs <= 5_000_000_000L
        if (!isGps && gpsFresh) return null
        if (l.provider == "network" && lastGoodNs != Long.MIN_VALUE && t - lastGoodNs <= 10_000_000_000L) return null

        // 2. outliers
        val med = median(accs)
        if (med != null && acc > max(50.0, 3 * med)) return null
        accs.addLast(acc); if (accs.size > 20) accs.removeFirst()
        if (l.provider != "network") lastGoodNs = t

        if (!have) {
            lat = l.lat; lon = l.lon; p = acc * acc; lastNs = t; have = true
            return emit(l, acc)
        }
        val dt = ((t - lastNs) / 1e9).coerceIn(0.0, 60.0)
        val (dx, dy) = metres(l.lat, l.lon)
        val dist = sqrt(dx * dx + dy * dy)
        if (dt > 0 && dist / dt > 60.0 && dist > 3 * acc) return null

        // 3. movement evidence → process noise (m²/s)
        val speed = l.speedMps?.takeIf { s -> l.speedAcc == null || l.speedAcc < s }
        lastSpeed = speed
        val moving = when {
            still == true -> false
            speed != null && speed >= 0.7 -> true
            speed != null && speed < 0.5 && (l.speedAcc ?: 1.0) < 0.5 -> false
            still == false -> true
            // no evidence: moving only after 3 fixes in a row clearly outside both uncertainty circles
            else -> run { far = if (dist > 2 * (sqrt(p) + acc)) far + 1 else 0; far >= 3 }
        }
        if (still == true && dist > 4 * (sqrt(p) + acc)) far++ else if (still == true) far = 0
        val q = if (moving) max(speed ?: 1.4, 1.0).let { it * it } else 0.02
        p += q * dt

        // 4. stationary hold: inside the combined circle → refine slowly
        val r = acc * acc
        var k = p / (p + r)
        // still: plain averaging (k ≈ 1/n) while the estimate settles, then a slow cap so one wild fix can't drag it
        if (!moving) { n++; if (n > 30) k = min(k, if (dist < sqrt(p) + acc) 0.02 else 0.05) } else n = 0
        // motion says still but fixes keep landing far away (it is wrong, e.g. a smooth car ride): follow
        if (still == true && far >= 5) k = p / (p + r)
        val (nLat, nLon) = offset(dx * k, dy * k)
        lat = nLat; lon = nLon
        p = (1 - k) * p
        p = max(p, 1.0)
        lastNs = t
        return emit(l, sqrt(p))
    }

    fun reset() { far = 0; n = 0; have = false; accs.clear(); lastGpsNs = Long.MIN_VALUE; lastGoodNs = Long.MIN_VALUE; current = null }

    private fun emit(l: LocationSample, accM: Double): LocationSample =
        l.copy(lat = lat, lon = lon, hAccM = Math.round(accM * 10) / 10.0).also { current = it }

    private fun metres(la: Double, lo: Double): Pair<Double, Double> {
        val dy = (la - lat) * M_PER_DEG
        val dx = (lo - lon) * M_PER_DEG * cos(Math.toRadians(lat))
        return dx to dy
    }

    private fun offset(dx: Double, dy: Double): Pair<Double, Double> =
        (lat + dy / M_PER_DEG) to (lon + dx / (M_PER_DEG * cos(Math.toRadians(lat))))

    private fun median(xs: Collection<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private companion object { const val M_PER_DEG = 111_320.0 }
}
