package app.tracer.run

/**
 * Detects that the whole process stopped running while recording (Doze / OEM freezer / cached-app
 * freeze can stop even a foreground service): the service's 1 s tick and the sample writer both
 * call [observe]; when the elapsed-realtime clock (counts deep sleep) jumped more than
 * [thresholdMs] since the previous observation, the silent interval is a stall.
 *
 * Uses the monotonic clock for the jump (a user / NTP wall-clock change is not a stall) and the wall
 * clock only to report the interval. Not recording (paused / stopping) only moves the mark, so a
 * pause never reads as a stall. Not thread-safe: callers serialise (RunService: under sampleLock).
 * Pure Kotlin, unit-tested on the JVM.
 */
class StallDetector(private val thresholdMs: Long = DEFAULT_THRESHOLD_MS) {

    /** A silent interval: wall clock of the last observation before it and of the first after it. */
    data class Stall(val startUtcMs: Long, val endUtcMs: Long, val durationMs: Long)

    private var lastMonoNs = -1L
    private var lastUtcMs = 0L

    /** Forget the previous observation (session start, resume): the next [observe] only sets the mark. */
    fun reset(utcMs: Long, monoNs: Long) {
        lastMonoNs = monoNs
        lastUtcMs = utcMs
    }

    /** Records an observation; returns the stall that just ended, if any. */
    fun observe(utcMs: Long, monoNs: Long, recording: Boolean): Stall? {
        val prevMono = lastMonoNs
        val prevUtc = lastUtcMs
        if (prevMono >= 0 && monoNs < prevMono) return null // out-of-order observer (a sample stamped earlier): keep the newer mark
        lastMonoNs = monoNs
        lastUtcMs = utcMs
        if (!recording || prevMono < 0) return null
        val silentMs = (monoNs - prevMono) / 1_000_000L
        if (silentMs <= thresholdMs) return null
        // Wall clock of the end, derived from the start + monotonic duration if the wall clock moved backwards.
        val end = if (utcMs > prevUtc) utcMs else prevUtc + silentMs
        return Stall(prevUtc, end, silentMs)
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 15_000L
        const val REASON = "stall"
    }
}
