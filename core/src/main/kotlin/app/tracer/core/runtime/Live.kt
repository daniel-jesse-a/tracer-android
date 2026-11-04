package app.tracer.core.runtime

import app.tracer.core.model.*
import app.tracer.core.util.Geo
import app.tracer.core.util.Ring
import app.tracer.core.util.Stats
import app.tracer.core.util.WifiMath

data class TrackPoint(val lat: Double, val lon: Double, val tMonoNs: Long, val rsrp: Int?, val sinr: Int?, val latencyMs: Double?, val failed: Boolean, val wifiRssi: Int?, val rat: String?, val usedSats: Int?)

data class SatView(val svid: Int, val constellation: String, val cn0: Double, val elevation: Double, val azimuth: Double, val used: Boolean)

data class ChannelLoad(val band: String, val channel: Int, val aps: Int, val strongestRssi: Int)

/** Everything the Live Run screen, panels and the notification render. Immutable; emitted ~1 Hz. */
data class LiveSnapshot(
    val runId: String? = null,
    val state: RunState = RunState.IDLE,
    val startedMonoNs: Long? = null,
    val elapsedMs: Long = 0,
    val pausedMs: Long = 0,
    /** Filtered display position (PositionFilter); hAccM is the estimate's accuracy. */
    val location: LocationSample? = null,
    /** Latest raw fix as recorded (any provider). */
    val rawLocation: LocationSample? = null,
    val distanceM: Double = 0.0,
    val transport: String? = null,
    val pathStatus: String? = null,
    val rat: String? = null,
    val nrState: String? = null,
    val servingBySub: Map<Int, List<CellSample>> = emptyMap(),
    val neighboursBySub: Map<Int, List<CellSample>> = emptyMap(),
    val wifi: WifiConnectionSample? = null,
    val scan: List<WifiScanSample> = emptyList(),
    val channelLoad: List<ChannelLoad> = emptyList(),
    val satellites: List<SatView> = emptyList(),
    val latencyMs: Double? = null,
    /** RTT on the parallel mobile-data path while Wi-Fi is the default (dual-path probing). */
    val cellularLatencyMs: Double? = null,
    val medianLatencyMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val lastMbps: Double? = null,
    val publicIp: String? = null,
    val asnOrg: String? = null,
    val device: DeviceSample? = null,
    val eventCount: Int = 0,
    val recentEvents: List<EventSample> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val gpsOffsetMs: Long? = null,
    /** last ~10 min of readings for the detail screens' live graphs */
    val history: LiveHistory = LiveHistory(),
    /** latest probe per "path|endpoint" (timing breakdown, server identity) */
    val lastProbes: Map<String, ProbeSample> = emptyMap(),
) {
    val primaryServing: CellSample? get() = servingBySub.values.flatten().maxByOrNull { if (it.rat == "LTE" || it.rat == "NR") 1 else 0 }
    val usedSats: Int get() = satellites.count { it.used }
}

/** One reading for a live graph: run-clock ms (monotonic) and value. */
data class HPoint(val tMonoMs: Long, val v: Double)

/** Rolling series (≤ [LiveAggregator.HISTORY] points each) shown in Wi-Fi / Mobile / Response / Position details. */
data class LiveHistory(
    val wifiRssi: List<HPoint> = emptyList(),
    val wifiLinkMbps: List<HPoint> = emptyList(),
    val rsrp: List<HPoint> = emptyList(),
    val sinr: List<HPoint> = emptyList(),
    val rsrq: List<HPoint> = emptyList(),
    val nrRsrp: List<HPoint> = emptyList(),
    val latencyWifi: List<HPoint> = emptyList(),
    val latencyMobile: List<HPoint> = emptyList(),
    val hAcc: List<HPoint> = emptyList(),
    val mbpsWifi: List<HPoint> = emptyList(),
    val mbpsMobile: List<HPoint> = emptyList(),
)

/**
 * Folds the sample stream into [LiveSnapshot]. Pure and single-threaded: the RunService calls
 * [onSample] from the writer coroutine and publishes [snapshot] to a StateFlow at ~1 Hz.
 * Track is decimated to ≥ [minTrackStepM] metres so 3-hour runs stay < ~10k points.
 */
class LiveAggregator(private val minTrackStepM: Double = 5.0, private val maxTrack: Int = 20_000) {
    companion object { const val HISTORY = 600 }
    var snapshot = LiveSnapshot(); private set

    private val latencyWindow = Ring<Pair<Double?, Boolean>>(30)
    private val recentEvents = Ring<EventSample>(50)
    private val track = ArrayList<TrackPoint>()
    private val positionFilter = app.tracer.core.geo.PositionFilter()
    private class Series { val r = Ring<HPoint>(HISTORY); fun add(t: Long, v: Double?): Boolean { if (v == null || v.isNaN()) return false; r.add(HPoint(t, v)); return true }
        fun list() = r.toList() }
    private val hWifi = Series(); private val hLink = Series(); private val hRsrp = Series(); private val hSinr = Series(); private val hRsrq = Series()
    private val hNr = Series(); private val hLatW = Series(); private val hLatM = Series(); private val hAcc = Series()
    private val hMbW = Series(); private val hMbM = Series()
    private var lastHistServingNs = Long.MIN_VALUE
    /** latest motion-recognition verdict (null = unknown) */
    private var still: Boolean? = null
    private var pendingSats = mutableListOf<SatView>()
    private var pendingSatStamp = -1L
    private var pendingScanId = -1L
    private var pendingScan = mutableListOf<WifiScanSample>()
    private val pendingServing = mutableMapOf<Int, MutableList<CellSample>>()
    private val pendingNeigh = mutableMapOf<Int, MutableList<CellSample>>()
    private val pollStamp = mutableMapOf<Int, Long>()

    fun reset(runId: String?, state: RunState, startedMonoNs: Long?) {
        snapshot = LiveSnapshot(runId = runId, state = state, startedMonoNs = startedMonoNs)
        latencyWindow.clear(); recentEvents.clear(); track.clear(); pendingSats.clear(); pendingScan.clear()
        pendingServing.clear(); pendingNeigh.clear(); pollStamp.clear()
        positionFilter.reset(); still = null
        listOf(hWifi, hLink, hRsrp, hSinr, hRsrq, hNr, hLatW, hLatM, hAcc, hMbW, hMbM).forEach { it.r.clear() }
        lastHistServingNs = Long.MIN_VALUE
    }

    fun setState(state: RunState) { snapshot = snapshot.copy(state = state) }
    fun tick(nowMonoNs: Long, pausedMs: Long, gpsOffsetMs: Long?) {
        val start = snapshot.startedMonoNs ?: return
        snapshot = snapshot.copy(elapsedMs = (nowMonoNs - start) / 1_000_000 - pausedMs, pausedMs = pausedMs, gpsOffsetMs = gpsOffsetMs)
    }

    fun onSample(s: Sample) {
        var sn = snapshot
        when (s) {
            is LocationSample -> {
                // One stable display position from the mixed providers (see PositionFilter);
                // raw fixes are stored untouched by the writer.
                if (hAcc.add(s.stamp.tMonoMs, s.hAccM)) sn = sn.copy(history = sn.history.copy(hAcc = hAcc.list()))
                val f = positionFilter.add(s, still) ?: run { snapshot = sn; return }
                val prev = sn.location
                val step = prev?.let { Geo.haversineM(it.lat, it.lon, f.lat, f.lon) } ?: 0.0
                sn = sn.copy(location = f, rawLocation = s, distanceM = sn.distanceM + if (step < 500) step else 0.0)
                val last = track.lastOrNull()
                if (last == null || Geo.haversineM(last.lat, last.lon, f.lat, f.lon) >= minTrackStepM) {
                    val serving = sn.primaryServing
                    val lat = latencyWindow.last()
                    track += TrackPoint(f.lat, f.lon, f.stamp.tMonoNs, serving?.rsrp, serving?.sinr, lat?.first, lat?.second == false, sn.wifi?.rssiDbm, sn.rat, sn.usedSats)
                    if (track.size > maxTrack) { // halve resolution, keep endpoints
                        val keep = track.filterIndexed { i, _ -> i % 2 == 0 || i == track.lastIndex }
                        track.clear(); track.addAll(keep)
                    }
                    sn = sn.copy(track = track.toList())
                }
            }
            is MotionSample -> {
                still = when (s.activity) { "still", "tilting" -> true; null, "unknown" -> still; else -> false }
            }
            is GnssSatelliteSample -> {
                if (s.stamp.tMonoNs != pendingSatStamp) {
                    if (pendingSats.isNotEmpty()) sn = sn.copy(satellites = pendingSats.toList())
                    pendingSats = mutableListOf(); pendingSatStamp = s.stamp.tMonoNs
                }
                pendingSats += SatView(s.svid, s.constellation, s.cn0DbHz, s.elevation, s.azimuth, s.usedInFix)
                if (sn.satellites.isEmpty()) sn = sn.copy(satellites = pendingSats.toList())
            }
            is CellSample -> {
                val sub = s.subscriptionId ?: 0
                // a new poll for this subscription starts when the stamp moves on by > 300 ms
                val ps = pollStamp[sub]
                if (ps == null || s.stamp.tMonoNs - ps > 300_000_000L) {
                    pollStamp[sub] = s.stamp.tMonoNs
                    pendingServing[sub] = mutableListOf(); pendingNeigh[sub] = mutableListOf()
                }
                if (s.serving) pendingServing.getOrPut(sub) { mutableListOf() } += s else pendingNeigh.getOrPut(sub) { mutableListOf() } += s
                if (s.serving) {
                    var h = sn.history
                    if (s.stamp.tMonoNs != lastHistServingNs && (s.rat == "LTE" || s.rat == "NR" || s.rat == "WCDMA" || s.rat == "GSM")) {
                        lastHistServingNs = s.stamp.tMonoNs
                        val t = s.stamp.tMonoMs
                        if (hRsrp.add(t, s.rsrp?.toDouble())) h = h.copy(rsrp = hRsrp.list())
                        if (hSinr.add(t, s.sinr?.toDouble())) h = h.copy(sinr = hSinr.list())
                        if (hRsrq.add(t, s.rsrq?.toDouble())) h = h.copy(rsrq = hRsrq.list())
                    }
                    if (s.rat == "NR" && hNr.add(s.stamp.tMonoMs, s.rsrp?.toDouble())) h = h.copy(nrRsrp = hNr.list())
                    if (h !== sn.history) sn = sn.copy(history = h)
                }
                sn = sn.copy(
                    servingBySub = sn.servingBySub + (sub to pendingServing[sub].orEmpty().toList().ifEmpty { sn.servingBySub[sub].orEmpty() }),
                    neighboursBySub = sn.neighboursBySub + (sub to pendingNeigh[sub].orEmpty().sortedByDescending { it.rsrp ?: -200 }),
                )
            }
            is RadioSample -> sn = sn.copy(rat = s.rat, nrState = s.nrState)
            is NetworkPathSample -> sn = sn.copy(transport = s.primaryInterface, pathStatus = s.status)
            is WifiConnectionSample -> {
                sn = sn.copy(wifi = if (s.bssid == null && s.ssid == null) null else s)
                var h = sn.history
                if (hWifi.add(s.stamp.tMonoMs, s.rssiDbm?.toDouble())) h = h.copy(wifiRssi = hWifi.list())
                if (hLink.add(s.stamp.tMonoMs, s.linkSpeedMbps?.toDouble())) h = h.copy(wifiLinkMbps = hLink.list())
                if (h !== sn.history) sn = sn.copy(history = h)
            }
            is WifiScanSample -> {
                if (s.scanId != pendingScanId) {
                    if (pendingScan.isNotEmpty()) sn = sn.withScan(pendingScan)
                    pendingScan = mutableListOf(); pendingScanId = s.scanId
                }
                pendingScan += s
                sn = sn.withScan(pendingScan)
            }
            is ProbeSample -> {
              val cell = s.isCellular == true || s.iface == "cellular"
              sn = sn.copy(lastProbes = sn.lastProbes + ("${if (cell) "cellular" else "wifi"}|${s.probeType}|${s.endpoint}" to s))
              when (s.probeType) {
                "latency" -> {
                    val rttH = (s.connectMs?.takeIf { it > 0 } ?: s.totalMs).takeIf { s.success }
                    val ser = if (cell) hLatM else hLatW
                    if (ser.add(s.stamp.tMonoMs, rttH)) sn = sn.copy(history = if (cell) sn.history.copy(latencyMobile = ser.list()) else sn.history.copy(latencyWifi = ser.list()))
                    // Tiles show the default path only (dual-path probing also measures cellular while on Wi-Fi),
                    // and RTT (TCP connect) rather than the whole fresh-HTTPS handshake.
                    val onDefault = s.isCellular == null || sn.transport == null || s.isCellular == (sn.transport == "cellular")
                    val rtt = s.connectMs?.takeIf { it > 0 } ?: s.totalMs
                    if (onDefault) {
                        latencyWindow.add(rtt.takeIf { s.success } to s.success)
                        val all = latencyWindow.toList()
                        val ok = all.filter { it.second }.mapNotNull { it.first }
                        sn = sn.copy(
                            latencyMs = rtt.takeIf { s.success }, medianLatencyMs = Stats.median(ok), jitterMs = Stats.jitter(ok),
                            lossPct = 100.0 * all.count { !it.second } / all.size,
                        )
                    } else if (s.success) {
                        sn = sn.copy(cellularLatencyMs = rtt)
                    }
                }
                "throughput" -> if (s.success) {
                    sn = sn.copy(lastMbps = s.mbps)
                    val ser = if (cell) hMbM else hMbW
                    if (ser.add(s.stamp.tMonoMs, s.mbps)) sn = sn.copy(history = if (cell) sn.history.copy(mbpsMobile = ser.list()) else sn.history.copy(mbpsWifi = ser.list()))
                }
                "public_ip" -> if (s.success) sn = sn.copy(publicIp = s.publicIp, asnOrg = s.asnOrg)
                else -> {}
              }
            }
            is DeviceSample -> sn = sn.copy(device = s)
            is EventSample -> { recentEvents.add(s); sn = sn.copy(eventCount = sn.eventCount + 1, recentEvents = recentEvents.toList().asReversed()) }
            else -> {}
        }
        snapshot = sn
    }

    private fun LiveSnapshot.withScan(scan: List<WifiScanSample>): LiveSnapshot {
        val load = scan.mapNotNull { r -> WifiMath.channelOf(r.frequencyMhz)?.let { Triple(WifiMath.bandOf(r.frequencyMhz), it, r.rssiDbm) } }
            .groupBy { it.first to it.second }
            .map { (k, v) -> ChannelLoad(k.first, k.second, v.size, v.maxOf { it.third }) }
            .sortedWith(compareBy({ it.band }, { it.channel }))
        return copy(scan = scan.sortedByDescending { it.rssiDbm }, channelLoad = load)
    }
}
