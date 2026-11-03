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
    val location: LocationSample? = null,
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
) {
    val primaryServing: CellSample? get() = servingBySub.values.flatten().maxByOrNull { if (it.rat == "LTE" || it.rat == "NR") 1 else 0 }
    val usedSats: Int get() = satellites.count { it.used }
}

/**
 * Folds the sample stream into [LiveSnapshot]. Pure and single-threaded: the RunService calls
 * [onSample] from the writer coroutine and publishes [snapshot] to a StateFlow at ~1 Hz.
 * Track is decimated to ≥ [minTrackStepM] metres so 3-hour runs stay < ~10k points.
 */
class LiveAggregator(private val minTrackStepM: Double = 5.0, private val maxTrack: Int = 20_000) {
    var snapshot = LiveSnapshot(); private set

    private val latencyWindow = Ring<Pair<Double?, Boolean>>(30)
    private val recentEvents = Ring<EventSample>(50)
    private val track = ArrayList<TrackPoint>()
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
                val prev = sn.location
                val step = prev?.let { Geo.haversineM(it.lat, it.lon, s.lat, s.lon) } ?: 0.0
                sn = sn.copy(location = s, distanceM = sn.distanceM + if (step < 500) step else 0.0)
                val last = track.lastOrNull()
                if (last == null || Geo.haversineM(last.lat, last.lon, s.lat, s.lon) >= minTrackStepM) {
                    val serving = sn.primaryServing
                    val lat = latencyWindow.last()
                    track += TrackPoint(s.lat, s.lon, s.stamp.tMonoNs, serving?.rsrp, serving?.sinr, lat?.first, lat?.second == false, sn.wifi?.rssiDbm, sn.rat, sn.usedSats)
                    if (track.size > maxTrack) { // halve resolution, keep endpoints
                        val keep = track.filterIndexed { i, _ -> i % 2 == 0 || i == track.lastIndex }
                        track.clear(); track.addAll(keep)
                    }
                    sn = sn.copy(track = track.toList())
                }
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
                sn = sn.copy(
                    servingBySub = sn.servingBySub + (sub to pendingServing[sub].orEmpty().toList().ifEmpty { sn.servingBySub[sub].orEmpty() }),
                    neighboursBySub = sn.neighboursBySub + (sub to pendingNeigh[sub].orEmpty().sortedByDescending { it.rsrp ?: -200 }),
                )
            }
            is RadioSample -> sn = sn.copy(rat = s.rat, nrState = s.nrState)
            is NetworkPathSample -> sn = sn.copy(transport = s.primaryInterface, pathStatus = s.status)
            is WifiConnectionSample -> sn = sn.copy(wifi = if (s.bssid == null && s.ssid == null) null else s)
            is WifiScanSample -> {
                if (s.scanId != pendingScanId) {
                    if (pendingScan.isNotEmpty()) sn = sn.withScan(pendingScan)
                    pendingScan = mutableListOf(); pendingScanId = s.scanId
                }
                pendingScan += s
                sn = sn.withScan(pendingScan)
            }
            is ProbeSample -> when (s.probeType) {
                "latency" -> {
                    latencyWindow.add(s.totalMs.takeIf { s.success } to s.success)
                    val all = latencyWindow.toList()
                    val ok = all.filter { it.second }.mapNotNull { it.first }
                    sn = sn.copy(
                        latencyMs = s.totalMs.takeIf { s.success }, medianLatencyMs = Stats.median(ok), jitterMs = Stats.jitter(ok),
                        lossPct = 100.0 * all.count { !it.second } / all.size,
                    )
                }
                "throughput" -> if (s.success) sn = sn.copy(lastMbps = s.mbps)
                "public_ip" -> if (s.success) sn = sn.copy(publicIp = s.publicIp, asnOrg = s.asnOrg)
                else -> {}
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
