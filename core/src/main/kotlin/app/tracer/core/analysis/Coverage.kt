package app.tracer.core.analysis

import app.tracer.core.model.*
import app.tracer.core.util.Geo
import app.tracer.core.util.Stats
import kotlinx.serialization.json.*
import kotlin.math.hypot

/** One recording gap on the run's monotonic timeline (ms). */
data class GapInterval(val startMs: Long, val endMs: Long, val reason: String) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Honest recording coverage of a run.
 *
 * Gaps are the union of
 *  - `x_gap` rows (any reason except `pause`; the service also writes `stall` rows from its heartbeat), and
 *  - detected stalls: any stretch while recording (pauses excluded) longer than [CoverageTracker.STALL_MS]
 *    in which no sample of any table arrived, even when no `x_gap` row exists (the iOS export said
 *    `gaps: 0` while the watchdog logged 26 suspensions).
 * Overlapping intervals are merged, so a heartbeat `stall` row and the silence it describes count once.
 *
 * `coverage_pct` = captured ÷ (duration − paused) × 100.
 */
data class Coverage(
    val startMonoMs: Long,
    val durationMs: Long,
    val pausedMs: Long,
    val pauses: Int,
    val gaps: List<GapInterval>,
    val stallThresholdMs: Long,
) {
    val gapMs: Long get() = gaps.sumOf { it.durationMs }
    val capturedMs: Long get() = (durationMs - pausedMs - gapMs).coerceAtLeast(0)
    val recordingMs: Long get() = (durationMs - pausedMs).coerceAtLeast(0)
    val coveragePct: Double? get() = if (recordingMs > 0) Math.round(1000.0 * capturedMs / recordingMs) / 10.0 else null
    val gapCount: Int get() = gaps.size
    val longestGapMs: Long get() = gaps.maxOfOrNull { it.durationMs } ?: 0
    /** reason → (count, total ms) */
    val byReason: Map<String, Pair<Int, Long>> get() =
        gaps.groupBy { it.reason }.toSortedMap().mapValues { (_, l) -> l.size to l.sumOf { it.durationMs } }

    fun toJson(maxListed: Int = 100): JsonObject = buildJsonObject {
        put("duration_s", s1(durationMs)); put("paused_s", s1(pausedMs)); put("pauses", pauses)
        put("captured_s", s1(capturedMs)); put("coverage_pct", coveragePct)
        put("stall_threshold_s", stallThresholdMs / 1000.0)
        putJsonObject("gaps") {
            put("count", gapCount); put("total_s", s1(gapMs)); put("longest_s", s1(longestGapMs))
            putJsonObject("by_reason") {
                byReason.forEach { (r, v) -> putJsonObject(r) { put("count", v.first); put("total_s", s1(v.second)) } }
            }
            putJsonArray("list") {
                gaps.take(maxListed).forEach { g ->
                    addJsonObject { put("t_run_s", s1(g.startMs - startMonoMs)); put("duration_s", s1(g.durationMs)); put("reason", g.reason) }
                }
            }
            if (gaps.size > maxListed) put("list_truncated", true)
        }
    }

    private fun s1(ms: Long) = Math.round(ms / 100.0) / 10.0
}

/**
 * Streaming builder for [Coverage]. Feed every sample once (any table order); memory is two longs
 * per second of run (first/last sample time in that second), so silences are measured exactly.
 */
class CoverageTracker(private val run: RunRecord, private val stallMs: Long = STALL_MS) {
    companion object {
        /** No sample of any table for longer than this while recording = a stall gap. */
        const val STALL_MS = 15_000L
        /** Tables whose rows are not evidence that recording was alive at their timestamp. */
        private val IGNORED = setOf(Table.X_GAP, Table.X_EVENT_LABEL, Table.EVENT)
    }

    private val runStartMs: Long? = run.tMonoNs.takeIf { it > 0 }?.let { it / 1_000_000L }
    private var baseMs: Long? = runStartMs
    private var firstMs = Long.MAX_VALUE
    private var lastMs = Long.MIN_VALUE
    private var bucketMin = LongArray(0)
    private var bucketMax = LongArray(0)
    /** samples seen before [baseMs] was known (no run start mono): replayed once it is */
    private val early = ArrayList<Long>()
    private val gapRows = ArrayList<GapInterval>()

    fun add(s: Sample) {
        if (s is GapSample) { addGap(s); return }
        if (s.table in IGNORED) return
        addTime(s.stamp.tMonoNs / 1_000_000L)
    }

    fun addTime(monoMs: Long) {
        if (monoMs <= 0) return
        if (monoMs < firstMs) firstMs = monoMs
        if (monoMs > lastMs) lastMs = monoMs
        val base = baseMs
        if (base == null) { early += monoMs; if (early.size > 4096) settleBase(); return }
        if (monoMs < base) return
        val i = ((monoMs - base) / 1000L).toInt()
        if (i >= bucketMin.size) grow(i + 1)
        if (bucketMin[i] == 0L || monoMs < bucketMin[i]) bucketMin[i] = monoMs
        if (monoMs > bucketMax[i]) bucketMax[i] = monoMs
    }

    private fun settleBase() {
        if (baseMs != null) return
        baseMs = early.minOrNull() ?: return
        val e = early.toList(); early.clear(); e.forEach { addTime(it) }
    }

    private fun grow(n: Int) {
        val size = maxOf(n, bucketMin.size * 2, 64)
        bucketMin = bucketMin.copyOf(size); bucketMax = bucketMax.copyOf(size)
    }

    /** x_gap times are wall clock; mapped onto the monotonic timeline with the row's own clock pair. */
    fun addGap(g: GapSample) {
        if (g.endUtcMs <= g.startUtcMs) return
        val offset = when {
            g.stamp.tMonoNs > 0 -> g.stamp.tMonoMs - g.stamp.tUtcMs
            runStartMs != null -> runStartMs - run.tUtcMs
            else -> 0L
        }
        gapRows += GapInterval(g.startUtcMs + offset, g.endUtcMs + offset, g.reason.ifBlank { "unknown" })
    }

    fun build(): Coverage {
        settleBase()
        val start = runStartMs ?: firstMs.takeIf { it != Long.MAX_VALUE } ?: 0L
        val end = run.endedAt?.let { start + (it - run.startedAt) }?.takeIf { it > start }
            ?: lastMs.takeIf { it > start } ?: start
        fun clamp(g: GapInterval) = GapInterval(g.startMs.coerceIn(start, end), g.endMs.coerceIn(start, end), g.reason)

        val pauses = merge(gapRows.filter { it.reason == "pause" }.map(::clamp).filter { it.durationMs > 0 })
        val rows = gapRows.filter { it.reason != "pause" }.map(::clamp).flatMap { subtract(it, pauses) }.filter { it.durationMs > 0 }

        // silences between consecutive samples (and at both ends of the run)
        val silences = ArrayList<GapInterval>()
        var prev = start
        val base = baseMs
        if (base != null) for (i in bucketMin.indices) {
            if (bucketMin[i] == 0L) continue
            val lo = bucketMin[i]; val hi = bucketMax[i]
            if (lo > end) break
            if (lo - prev > stallMs) silences += GapInterval(prev, lo, "stall")
            if (hi > prev) prev = hi
        }
        if (end - prev > stallMs) silences += GapInterval(prev, end, "stall")
        val stalls = silences.flatMap { subtract(it, pauses) }.filter { it.durationMs > stallMs }

        // union of rows and stalls; a merged gap takes the reason of the x_gap row overlapping it most
        val all = (rows + stalls).sortedBy { it.startMs }
        val merged = ArrayList<GapInterval>()
        for (g in all) {
            val last = merged.lastOrNull()
            if (last != null && g.startMs <= last.endMs) merged[merged.size - 1] = last.copy(endMs = maxOf(last.endMs, g.endMs))
            else merged += g.copy(reason = "stall")
        }
        val gaps = merged.map { m ->
            val best = rows.map { it to (minOf(it.endMs, m.endMs) - maxOf(it.startMs, m.startMs)) }
                .filter { it.second > 0 }.maxByOrNull { it.second }?.first
            m.copy(reason = best?.reason ?: "stall")
        }
        return Coverage(start, end - start, pauses.sumOf { it.durationMs }, pauses.size, gaps, stallMs)
    }

    private fun merge(l: List<GapInterval>): List<GapInterval> {
        val out = ArrayList<GapInterval>()
        for (g in l.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && g.startMs <= last.endMs) out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, g.endMs)) else out += g
        }
        return out
    }

    /** [g] minus every interval of [cuts] (sorted, disjoint). */
    private fun subtract(g: GapInterval, cuts: List<GapInterval>): List<GapInterval> {
        var pieces = listOf(g)
        for (c in cuts) pieces = pieces.flatMap { p ->
            if (c.endMs <= p.startMs || c.startMs >= p.endMs) listOf(p)
            else listOfNotNull(
                p.copy(endMs = c.startMs).takeIf { it.durationMs > 0 },
                p.copy(startMs = c.endMs).takeIf { it.durationMs > 0 },
            )
        }
        return pieces
    }
}

/** Observed movement from location fixes: trimmed bounding box (m), path length (m), stationary flag. */
data class Movement(
    /** east-west × north-south extent in metres (1st–99th percentile of fixes when ≥ 100 fixes) */
    val bboxM: Pair<Double, Double>?,
    val pathM: Double,
    val fixesUsed: Int,
) {
    val diagonalM: Double? get() = bboxM?.let { hypot(it.first, it.second) }
    /** null = no usable fixes */
    val stationary: Boolean? get() = diagonalM?.let { it < STATIONARY_DIAGONAL_M }

    fun toJson(): JsonObject = buildJsonObject {
        put("bbox_m", bboxM?.let { b -> buildJsonArray { add(r(b.first)); add(r(b.second)) } } ?: JsonNull)
        put("diagonal_m", diagonalM?.let { r(it) }); put("path_m", r(pathM)); put("stationary", stationary)
        put("fixes_used", fixesUsed); put("max_h_acc_m", MovementTracker.MAX_H_ACC_M)
    }

    private fun r(x: Double) = Math.round(x * 10) / 10.0

    companion object { const val STATIONARY_DIAGONAL_M = 150.0 }
}

/**
 * Streaming [Movement]. Fixes with h_acc > 50 m (or unknown) are skipped; GPS fixes are used when
 * there are ≥ 10 of them, otherwise all providers. Path length moves an anchor only once the fix is
 * farther than max(10 m, h_acc(anchor) + h_acc(fix)) from it, so standing still with jittery fixes adds ~0 m.
 */
class MovementTracker {
    companion object { const val MAX_H_ACC_M = 50.0 }

    private class Agg {
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>()
        var anchor: LocationSample? = null; var pathM = 0.0
        fun add(l: LocationSample) {
            lats += l.lat; lons += l.lon
            val a = anchor
            if (a == null) { anchor = l; return }
            val d = Geo.haversineM(a.lat, a.lon, l.lat, l.lon)
            // moved only when the separation exceeds both fixes' uncertainty
            if (d > maxOf(10.0, (a.hAccM ?: 0.0) + (l.hAccM ?: 0.0))) { pathM += d; anchor = l }
        }
        fun movement(): Movement {
            if (lats.size < 2) return Movement(null, 0.0, lats.size)
            val trim = lats.size >= 100
            fun lo(xs: List<Double>) = if (trim) Stats.percentile(xs, 1.0)!! else xs.min()
            fun hi(xs: List<Double>) = if (trim) Stats.percentile(xs, 99.0)!! else xs.max()
            val la0 = lo(lats); val la1 = hi(lats); val lo0 = lo(lons); val lo1 = hi(lons)
            val mid = (la0 + la1) / 2
            return Movement(Geo.haversineM(mid, lo0, mid, lo1) to Geo.haversineM(la0, lo0, la1, lo0), pathM, lats.size)
        }
    }

    private val gps = Agg(); private val any = Agg()

    fun add(l: LocationSample) {
        val acc = l.hAccM ?: return
        if (acc > MAX_H_ACC_M || l.isMock == true) return
        any.add(l)
        if (l.provider == "gps") gps.add(l)
    }

    fun build(): Movement = if (gps.lats.size >= 10) gps.movement() else any.movement()
}
