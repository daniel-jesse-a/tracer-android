package app.tracer.collectors

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Folds raw accelerometer and barometer readings into one aggregate per window (the motion
 * collector drains it once per second). Pure and single-threaded: callers feed it from one thread.
 *
 * Accelerometer values are reduced to the absolute deviation of |a| from standard gravity
 * (m/s²), i.e. ≈0 at rest. Relative altitude is the barometric altitude difference against the
 * first pressure reading seen by this instance.
 */
class MotionAggregator {

    /** One drained window. Fields are null when the corresponding sensor gave no data. */
    data class Aggregate(
        val accelMean: Double?,
        val accelMax: Double?,
        val accelCount: Int,
        val pressureKpa: Double?,
        val relAltM: Double?,
    )

    private var sum = 0.0
    private var max = Double.NEGATIVE_INFINITY
    private var count = 0
    private var latestHpa: Double? = null
    private var firstHpa: Double? = null
    /** Rolling window (~2.5 s at 10 Hz) — median rejects squeeze/door/handling spikes. */
    private val recentHpa = ArrayDeque<Double>()
    private val baselineHpa = ArrayList<Double>()
    private var pressureSinceDrain = false

    fun addAccel(x: Double, y: Double, z: Double) = addAccelDeviation(accelDeviation(x, y, z))

    fun addAccelDeviation(value: Double) {
        if (value.isNaN()) return
        sum += value
        if (value > max) max = value
        count++
    }

    fun addPressureHpa(hPa: Double) {
        if (hPa.isNaN() || hPa <= 0.0) return
        recentHpa.addLast(hPa)
        while (recentHpa.size > PRESSURE_WINDOW) recentHpa.removeFirst()
        if (firstHpa == null) {
            baselineHpa += hPa
            if (baselineHpa.size >= BASELINE_SAMPLES) firstHpa = median(baselineHpa)
        }
        latestHpa = median(recentHpa)
        pressureSinceDrain = true
    }

    /** Returns the aggregate since the previous drain, or null when nothing arrived. Resets accel stats. */
    fun drain(): Aggregate? {
        if (count == 0 && !pressureSinceDrain) return null
        val p = latestHpa
        val p0 = firstHpa
        val agg = Aggregate(
            accelMean = if (count > 0) sum / count else null,
            accelMax = if (count > 0) max else null,
            accelCount = count,
            pressureKpa = p?.let { it / 10.0 },
            relAltM = if (p != null && p0 != null) altitudeM(p) - altitudeM(p0) else null,
        )
        sum = 0.0; max = Double.NEGATIVE_INFINITY; count = 0; pressureSinceDrain = false
        return agg
    }

    companion object {
        private const val PRESSURE_WINDOW = 25
        private const val BASELINE_SAMPLES = 10
        private fun median(xs: Collection<Double>): Double {
            val s = xs.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }

        /** SensorManager.GRAVITY_EARTH */
        const val GRAVITY_EARTH = 9.80665
        /** SensorManager.PRESSURE_STANDARD_ATMOSPHERE (hPa) */
        const val PRESSURE_STANDARD_ATMOSPHERE = 1013.25

        fun accelDeviation(x: Double, y: Double, z: Double): Double = abs(sqrt(x * x + y * y + z * z) - GRAVITY_EARTH)

        /** Same formula as SensorManager.getAltitude(p0, p). */
        fun altitudeM(hPa: Double, p0: Double = PRESSURE_STANDARD_ATMOSPHERE): Double =
            44330.0 * (1.0 - (hPa / p0).pow(1.0 / 5.255))
    }
}
