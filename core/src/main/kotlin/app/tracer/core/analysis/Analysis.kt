package app.tracer.core.analysis

import app.tracer.core.config.RunSettings
import app.tracer.core.model.*
import app.tracer.core.util.Geo
import app.tracer.core.util.Stats
import kotlinx.serialization.json.*

/** Per-collector completeness: observed rows ÷ expected rows over recorded (non-gap) time. */
object Completeness {
    enum class Badge { COMPLETE, PARTIAL, SPARSE, EMPTY }

    data class Result(val ratios: Map<String, Double>, val overall: Double, val gapMs: Long, val badge: Badge) {
        fun toJson(): String = buildJsonObject {
            put("overall", overall); put("gap_ms", gapMs); put("badge", badge.name.lowercase())
            putJsonObject("collectors") { ratios.forEach { (k, v) -> put(k, v) } }
        }.toString()
    }

    /**
     * @param observed keys: location, gnss_epochs, cell_serving_polls, wifi_scans, latency_probes, device, motion, network_path (presence)
     */
    fun compute(durationMs: Long, gapMs: Long, observed: Map<String, Long>, settings: RunSettings, activeSubscriptions: Int = 1): Result {
        val s = settings.effective()
        val activeS = ((durationMs - gapMs).coerceAtLeast(0)) / 1000.0
        if (activeS < 1) return Result(emptyMap(), 0.0, gapMs, Badge.EMPTY)
        val expected = mapOf(
            "location" to activeS * 1000.0 / s.locationIntervalMs,
            "gnss_epochs" to activeS,
            "cell_serving_polls" to activeS * 1000.0 / s.cellPollIntervalMs * activeSubscriptions.coerceAtLeast(1),
            "wifi_scans" to activeS * 1000.0 / s.wifiScanIntervalMs,
            "latency_probes" to activeS * 1000.0 / s.latencyIntervalMs * s.endpoints.size,
            "device" to activeS * 1000.0 / s.deviceIntervalMs,
            "motion" to activeS,
        )
        val ratios = expected.mapValues { (k, exp) -> if (exp < 1) 1.0 else ((observed[k] ?: 0L) / exp).coerceIn(0.0, 1.0) }
            .toMutableMap()
        // network_path is event-driven (callbacks on change), so it is scored by presence, not rate
        observed["network_path"]?.let { ratios["network_path"] = if (it > 0) 1.0 else 0.0 }
        val overall = ratios.values.average()
        val timeShare = activeS * 1000.0 / durationMs
        val score = overall * timeShare
        val badge = when { score >= 0.95 -> Badge.COMPLETE; score >= 0.70 -> Badge.PARTIAL; score > 0 -> Badge.SPARSE; else -> Badge.EMPTY }
        return Result(ratios.mapValues { Math.round(it.value * 1000) / 1000.0 }, Math.round(score * 1000) / 1000.0, gapMs, badge)
    }
}

/** Ranks fixed-length windows of a run by how bad connectivity was. */
object WorstSegments {
    data class Segment(
        val startMonoNs: Long, val endMonoNs: Long, val startUtcMs: Long, val endUtcMs: Long,
        val score: Double, val reasons: List<String>,
        val lat: Double?, val lon: Double?, val distanceM: Double,
        val medianRsrp: Double?, val medianLatencyMs: Double?, val lossRate: Double?, val eventCount: Int,
    )

    fun rank(
        locations: List<LocationSample>, serving: List<CellSample>, probes: List<ProbeSample>, events: List<EventSample>,
        windowS: Int = 30, topN: Int = 10,
    ): List<Segment> {
        val all = (locations + serving + probes + events)
        if (all.isEmpty()) return emptyList()
        val t0 = all.minOf { it.stamp.tMonoNs }
        val w = windowS * 1_000_000_000L
        fun bucket(s: Sample) = (s.stamp.tMonoNs - t0) / w
        val locB = locations.groupBy(::bucket); val cellB = serving.groupBy(::bucket)
        val probeB = probes.filter { it.probeType == "latency" }.groupBy(::bucket); val evB = events.groupBy(::bucket)
        val buckets = (locB.keys + cellB.keys + probeB.keys + evB.keys).toSortedSet()
        val segs = buckets.map { b ->
            val ls = locB[b].orEmpty().sortedBy { it.stamp.tMonoNs }
            val cs = cellB[b].orEmpty(); val ps = probeB[b].orEmpty(); val es = evB[b].orEmpty()
            val rsrp = Stats.median(cs.mapNotNull { it.rsrp?.toDouble() })
            val lat = Stats.median(ps.filter { it.success }.mapNotNull { it.totalMs })
            val loss = if (ps.isEmpty()) null else ps.count { !it.success }.toDouble() / ps.size
            val reasons = mutableListOf<String>()
            var score = 0.0
            rsrp?.let { if (it < -100) { score += (-100 - it) * 1.5; reasons += "weak_rsrp" } }
            if (cs.isEmpty() && ls.isNotEmpty()) { score += 20.0; reasons += "no_cell_data" }
            lat?.let { if (it > 100) { score += (it - 100) / 10.0; reasons += "high_latency" } }
            loss?.let { if (it > 0) { score += it * 60.0; reasons += "packet_loss" } }
            es.forEach { e -> score += when (e.severity) { "critical" -> 25.0; "warning" -> 8.0; else -> 2.0 } }
            if (es.isNotEmpty()) reasons += es.map { it.eventType }.distinct()
            val dist = ls.zipWithNext { a, c -> Geo.haversineM(a.lat, a.lon, c.lat, c.lon) }.sum()
            val mid = ls.getOrNull(ls.size / 2)
            val members = ls + cs + ps + es
            Segment(
                startMonoNs = t0 + b * w, endMonoNs = t0 + (b + 1) * w,
                startUtcMs = members.minOf { it.stamp.tUtcMs }, endUtcMs = members.maxOf { it.stamp.tUtcMs },
                score = score, reasons = reasons, lat = mid?.lat, lon = mid?.lon, distanceM = dist,
                medianRsrp = rsrp, medianLatencyMs = lat, lossRate = loss, eventCount = es.size,
            )
        }
        return segs.filter { it.score > 0 }.sortedByDescending { it.score }.take(topN)
    }
}

/** End-of-run summary numbers. */
data class RunSummary(
    val durationMs: Long, val distanceM: Double, val eventsByType: Map<String, Int>,
    val medianRsrp: Double?, val p10Rsrp: Double?, val medianLatencyMs: Double?, val p95LatencyMs: Double?,
    val lossRate: Double?, val medianMbps: Double?, val handovers: Int, val ratShare: Map<String, Double>,
) {
    companion object {
        fun of(locations: List<LocationSample>, serving: List<CellSample>, probes: List<ProbeSample>, radio: List<RadioSample>, events: List<EventSample>, durationMs: Long): RunSummary {
            val l = locations.sortedBy { it.stamp.tMonoNs }
            val dist = l.zipWithNext { a, b -> Geo.haversineM(a.lat, a.lon, b.lat, b.lon) }.filter { it < 500 }.sum()
            val rs = serving.mapNotNull { it.rsrp?.toDouble() }
            val lat = probes.filter { it.probeType == "latency" }
            val ok = lat.filter { it.success }.mapNotNull { it.totalMs }
            val tp = probes.filter { it.probeType == "throughput" && it.success }.mapNotNull { it.mbps }
            val ratCounts = radio.groupingBy { it.rat }.eachCount()
            val total = ratCounts.values.sum().coerceAtLeast(1)
            return RunSummary(
                durationMs, dist, events.groupingBy { it.eventType }.eachCount(),
                Stats.median(rs), Stats.percentile(rs, 10.0), Stats.median(ok), Stats.percentile(ok, 95.0),
                if (lat.isEmpty()) null else lat.count { !it.success }.toDouble() / lat.size, Stats.median(tp),
                events.count { it.eventType == "handover" }, ratCounts.mapValues { it.value.toDouble() / total },
            )
        }
    }
}
