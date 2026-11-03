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

    // location
    var lastLocation: LocationSample? = null; internal set
    internal val stepDistances = Ring<Double>(30)
    val locations = TimeWindow<LocationSample>(120_000)

    // gnss
    val gnssEpochs = TimeWindow<GnssEpoch>(120_000)
    internal var pendingEpochT: Long? = null
    internal val pendingSats = mutableListOf<GnssSatelliteSample>()

    // cellular: key = "sub:rat"
    val serving = mutableMapOf<String, CellSample>()
    val servingHistory = mutableMapOf<String, TimeWindow<CellSample>>()
    val handoverTimes = TimeWindow<Long>(300_000)
    val cellChangeTimes = TimeWindow<String>(300_000)
    internal val neighbours = mutableMapOf<String, NeighbourState>()
    val overtakes = TimeWindow<String>(120_000)
    val radio = mutableMapOf<String, RadioSample>()
    val ratChanges = TimeWindow<Pair<String, String>>(120_000)

    // wifi
    var wifi: WifiConnectionSample? = null; internal set
    val wifiHistory = TimeWindow<WifiConnectionSample>(60_000)
    var lastScan: List<WifiScanSample> = emptyList(); internal set
    internal var pendingScanId: Long? = null
    internal val pendingScan = mutableListOf<WifiScanSample>()

    // network path
    var path: NetworkPathSample? = null; internal set
    val pathHistory = TimeWindow<NetworkPathSample>(120_000)

    // probes
    internal val latencyOk = Ring<Double>(60)
    internal val dnsOk = Ring<Double>(60)
    val latencyProbes = TimeWindow<ProbeSample>(120_000)
    internal val throughput = mutableListOf<Double>()
    var consecutiveFailures = 0; internal set
    var lastPublicIp: ProbeSample? = null; internal set

    // drop state
    var dropped = false; internal set
    var dropStartMs: Long = 0; internal set
    var dropReason: String? = null; internal set

    // gps state
    var gpsDegraded = false; internal set
    internal var gpsLastBadMs = 0L

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

    fun medianLatency(): Double? = Stats.median(latencyOk.toList())
    fun medianDns(): Double? = Stats.median(dnsOk.toList())
    fun throughputMedian(): Double? = Stats.median(throughput)

    fun lastLatLon(): Pair<Double, Double>? = lastLocation?.let { it.lat to it.lon }

    internal fun recordStep(l: LocationSample): Double? {
        val prev = lastLocation ?: return null
        val d = Geo.haversineM(prev.lat, prev.lon, l.lat, l.lon)
        return d
    }

    companion object {
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
