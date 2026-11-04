package app.tracer.events

import app.tracer.core.cell.CellNormalizer
import app.tracer.core.config.Thresholds
import app.tracer.core.model.*
import app.tracer.core.util.Geo
import app.tracer.core.util.Ring
import app.tracer.core.util.Stats
import app.tracer.core.util.TimeWindow

/** One finalised GNSS status epoch. */
data class GnssEpoch(val tMs: Long, val used: Int, val visible: Int, val meanTopCn0: Double?)

/** Rolling state shared by the rule engine and the cause engine. All times are monotonic ms. */
class Context(val th: Thresholds) {
    var nowMs: Long = 0
        internal set

    /** Cause-evidence window (± ms around T) and diagnosis delay; = thresholds.cause_lookback_s. */
    val nearMs: Long get() = th.causeWindowMs

    // ---------- resume / wake ----------
    /** Newest time any data sample arrived (events, labels and gap rows excluded). */
    internal var lastSampleMs: Long? = null
    /** Start of the current resume window (end of a gap ≥ [RESUME_GAP_MS]); null = none yet. */
    var resumeAtMs: Long? = null; internal set
    var resumeUntilMs: Long = Long.MIN_VALUE; internal set
    /** How long the gap before the current resume window was. */
    var resumeGapMs: Long = 0; internal set
    /** True while [tMs] lies in the [RESUME_WINDOW_MS] after a gap: wake artefacts, not network problems. */
    fun inResume(tMs: Long): Boolean { val a = resumeAtMs ?: return false; return tMs in a..resumeUntilMs }

    // ---------- RAT debounce ----------
    internal class PendingRat(val stamp: Stamp, val tMs: Long, val from: RadioSample, val to: RadioSample)
    internal val pendingRat = mutableMapOf<String, PendingRat>()

    // location
    var lastLocation: LocationSample? = null; internal set
    internal val stepDistances = Ring<Double>(30)
    val locations = TimeWindow<LocationSample>(120_000)

    /** h_acc of every raw GPS fix in the last 60 s, good and bad (the GPS baseline is never frozen). */
    val gpsHAcc = TimeWindow<Double>(60_000)
    fun gpsHAccBaseline(): Double? = Stats.median(gpsHAcc.values())

    // gnss
    val gnssEpochs = TimeWindow<GnssEpoch>(120_000)
    internal var pendingEpochT: Long? = null
    internal val pendingSats = mutableListOf<GnssSatelliteSample>()

    // cellular: key = "sub:rat"
    val serving = mutableMapOf<String, CellSample>()
    internal val servingPollStamp = mutableMapOf<String, Long>()
    val servingHistory = mutableMapOf<String, TimeWindow<CellSample>>()
    internal fun servingHistoryOf(key: String) = servingHistory.getOrPut(key) { TimeWindow(120_000) }
    val handoverTimes = TimeWindow<Long>(300_000)
    val cellChangeTimes = TimeWindow<String>(300_000)
    internal val neighbours = mutableMapOf<String, NeighbourState>()
    val overtakes = TimeWindow<String>(120_000)
    val radio = mutableMapOf<String, RadioSample>()
    val ratChanges = TimeWindow<Pair<String, String>>(120_000)

    // wifi
    var wifi: WifiConnectionSample? = null; internal set
    val wifiHistory = TimeWindow<WifiConnectionSample>(180_000)
    /** every completed scan, for "latest scan at or before T" lookups */
    val scanHistory = TimeWindow<List<WifiScanSample>>(180_000)
    val radioHistory = TimeWindow<RadioSample>(180_000)
    /** Android network validation / captive flags (x_android_network) */
    val androidNet = TimeWindow<AndroidNetworkSample>(180_000)
    /** gateway / dns_server probes */
    val internalProbes = TimeWindow<ProbeSample>(180_000)
    val roamTimes = TimeWindow<String>(120_000)
    var lastScan: List<WifiScanSample> = emptyList(); internal set
    internal var pendingScanId: Long? = null
    internal val pendingScan = mutableListOf<WifiScanSample>()

    // network path
    var path: NetworkPathSample? = null; internal set
    val pathHistory = TimeWindow<NetworkPathSample>(120_000)

    // probes — state is kept per path ("wifi" / "cellular"): with dual-path probing both run at once
    class PathState {
        internal val latencyOk = Ring<Double>(60)
        internal val dnsOk = Ring<Double>(60)
        internal val throughput = mutableListOf<Double>()
        /** v3: RTT window per endpoint: endpoints differ (and are often bimodal), so each has its own baseline */
        internal val latencyByEndpoint = mutableMapOf<String, Ring<Double>>()
        /** last time a probe on this path did a real DNS lookup (dns_ms > 1) */
        internal var lastDnsLookupMs: Long? = null
        /** Rolling medians/baselines restart after a gap (the first probes after a wake are not the baseline). */
        internal fun resetBaselines() { latencyOk.clear(); dnsOk.clear(); latencyByEndpoint.clear() }
        var consecutiveFailures = 0; internal set
        internal val failingEndpoints = mutableSetOf<String>()
        var dropped = false; internal set
        var dropStartMs: Long = 0; internal set
        var dropReason: String? = null; internal set
        fun medianLatency(): Double? = Stats.median(latencyOk.toList())
        fun medianDns(): Double? = Stats.median(dnsOk.toList())
        fun throughputMedian(): Double? = Stats.median(throughput)
    }
    val paths = mutableMapOf<String, PathState>()
    fun pathState(path: String) = paths.getOrPut(path) { PathState() }
    val latencyProbes = TimeWindow<ProbeSample>(120_000)
    internal val endpointsSeen = mutableSetOf<String>()
    val distinctEndpoints: Int get() = endpointsSeen.size.coerceAtLeast(1)
    var lastPublicIp: ProbeSample? = null; internal set
    /** Path of a probe row: its own is_cellular flag, else the current default transport. */
    fun pathOf(p: ProbeSample): String = when (p.isCellular) { true -> "cellular"; false -> "wifi"; null -> if (onWifi()) "wifi" else "cellular" }
    /** Path carrying the default network now. */
    fun defaultPath(): String = if (onWifi()) "wifi" else "cellular"

    // gps state
    var gpsDegraded = false; internal set
    internal var gpsLastBadMs = 0L
    internal var gpsDegradedSinceMs = 0L
    /** a gps_degraded event was actually emitted for the current episode (so one gps_recovered follows) */
    internal var gpsDegradedEmitted = false
    internal var firstSampleMs: Long? = null
    internal var lastGpsFixMs: Long? = null
    internal var noFixReported = false
    // last non-"none" primary interface (wifi ↔ cell switches often pass through a no-network instant)
    internal var lastPrimary: String? = null

    internal val lastEmit = mutableMapOf<String, Long>()

    class NeighbourState(var rsrp: Int, var lastSeenMs: Long, var overtakeSinceMs: Long? = null, var emitted: Boolean = false, var servingIdAtStart: String? = null)

    // ---------- derived helpers used by causes ----------

    fun primaryInterface(): String? = path?.primaryInterface
    fun onWifi(): Boolean = primaryInterface() == "wifi"

    /** Most relevant serving cell: the latest-updated serving sample across subscriptions (LTE anchor preferred over NR in NSA). */
    fun primaryServing(): CellSample? =
        serving.values.filter { nowMs - it.stamp.tMonoMs < 15_000 }
            .sortedWith(compareByDescending<CellSample> { it.rat == "LTE" || it.rat == "NR" }.thenByDescending { it.stamp.tMonoNs })
            .firstOrNull()

    fun servingWithin(ms: Long): List<CellSample> = servingHistory.values.flatMap { it.since(nowMs, ms) }

    fun minServingRsrp(ms: Long): Int? = servingWithin(ms).mapNotNull { it.rsrp }.minOrNull()
    fun maxServingRsrp(ms: Long): Int? = servingWithin(ms).mapNotNull { it.rsrp }.maxOrNull()
    fun minServingSinr(ms: Long): Int? = servingWithin(ms).mapNotNull { it.sinr }.minOrNull()
    fun maxTimingAdvanceM(ms: Long): Double? = servingWithin(ms).mapNotNull { CellNormalizer.timingAdvanceDistanceM(it.rat, it.timingAdvance) }.maxOrNull()
    fun maxTimingAdvance(ms: Long): Int? = servingWithin(ms).filter { it.rat == "LTE" }.mapNotNull { it.timingAdvance }.maxOrNull()

    fun epochsBetween(fromAgoMs: Long, toAgoMs: Long): List<GnssEpoch> =
        gnssEpochs.values().filter { nowMs - it.tMs in toAgoMs..fromAgoMs }

    fun speedMps(): Double? = lastLocation?.speedMps

    fun medianLatency(): Double? = pathState(defaultPath()).medianLatency()

    fun lastLatLon(): Pair<Double, Double>? = lastLocation?.let { it.lat to it.lon }

    internal fun recordStep(l: LocationSample): Double? {
        val prev = lastLocation ?: return null
        val d = Geo.haversineM(prev.lat, prev.lon, l.lat, l.lon)
        return d
    }

    /**
     * How long the radio of [serviceId] has stayed below [ratBefore]'s rank around [tMs]: from the first
     * lower-rank report after the last report at ≥ that rank (at or before [tMs]) until the first
     * report back at ≥ that rank, or [horizonMs]. 0 when the radio at [tMs] is not lower.
     */
    fun downgradeHeldMs(tMs: Long, ratBefore: String, serviceId: String?, horizonMs: Long): Long {
        val rb = ratRank(ratBefore)
        val hist = radioHistory.filter { serviceId == null || it.second.serviceId == serviceId }
        val atT = hist.lastOrNull { it.first <= tMs } ?: return 0
        if (ratRank(atT.second.rat) >= rb) return 0
        var start: Long? = null
        for ((ts, r) in hist) {
            if (ts > tMs) break
            if (ratRank(r.rat) >= rb) start = null else if (start == null) start = ts
        }
        val end = hist.firstOrNull { it.first > tMs && it.first <= horizonMs && ratRank(it.second.rat) >= rb }?.first ?: horizonMs
        return (end - (start ?: tMs)).coerceAtLeast(0)
    }

    companion object {
        /** A data silence or x_gap at least this long starts a resume window. */
        const val RESUME_GAP_MS = 10_000L
        const val RESUME_WINDOW_MS = 30_000L
        /** A RAT change that reverts within this is a flicker, not a change. */
        const val RAT_FLICKER_MS = 2_000L
        /** A RAT downgrade counts toward tunnel_underpass only when held longer than this. */
        const val RAT_SUSTAIN_MS = 5_000L
        /** DNS retransmission timers (first query lost while the radio woke): ~2 s and ~5 s. */
        fun isDnsRetryTimer(dnsMs: Double): Boolean = dnsMs in 1_900.0..2_200.0 || dnsMs in 4_900.0..5_200.0
        const val DNS_IDLE_MS = 20_000L

        fun ratRank(rat: String?): Int = when (rat) {
            null, "NONE", "UNKNOWN" -> 0
            "GSM", "GPRS", "EDGE" -> 1
            "UMTS", "HSPA", "HSPAP", "TDSCDMA" -> 3
            "LTE", "LTE_CA", "LTE_ADV_PRO", "IWLAN" -> 4
            "NR_NSA", "NR_NSA_MMWAVE" -> 5
            "NR_ADVANCED", "NR_SA" -> 6
            else -> 2
        }
    }
}
