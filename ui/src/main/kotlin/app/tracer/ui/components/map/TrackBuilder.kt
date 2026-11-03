package app.tracer.ui.components.map

import app.tracer.core.model.CellSample
import app.tracer.core.model.GnssSatelliteSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.WifiConnectionSample

/** Builds coloured map tracks from persisted samples (Summary, Replay, Worst segments). */
object TrackBuilder {

    private const val STALE_NS = 10_000_000_000L
    private const val THROUGHPUT_STALE_NS = 180_000_000_000L

    /** GNSS epochs: (t_mono_ns, used-in-fix count), one per distinct timestamp, ascending. */
    data class GnssEpochs(val t: LongArray, val used: IntArray)

    fun gnssEpochs(sats: List<GnssSatelliteSample>): GnssEpochs {
        val sorted = sats.sortedBy { it.stamp.tMonoNs }
        val ts = ArrayList<Long>()
        val used = ArrayList<Int>()
        var curT = Long.MIN_VALUE
        var curUsed = 0
        for (s in sorted) {
            val t = s.stamp.tMonoNs
            if (t != curT) {
                if (curT != Long.MIN_VALUE) { ts += curT; used += curUsed }
                curT = t; curUsed = 0
            }
            if (s.usedInFix) curUsed++
        }
        if (curT != Long.MIN_VALUE) { ts += curT; used += curUsed }
        return GnssEpochs(ts.toLongArray(), used.toIntArray())
    }

    /**
     * One serving cell per poll for the dominant subscription, preferring LTE/NR (the anchor that
     * carries RSRP/SINR), ascending by time.
     */
    fun primaryServing(serving: List<CellSample>): List<CellSample> {
        if (serving.isEmpty()) return emptyList()
        val mainSub = serving.groupingBy { it.subscriptionId }.eachCount().maxByOrNull { it.value }?.key
        return serving.asSequence()
            .filter { it.serving && it.subscriptionId == mainSub }
            .groupBy { it.stamp.tMonoNs }
            .map { (_, cells) -> cells.firstOrNull { it.rat == "LTE" } ?: cells.firstOrNull { it.rat == "NR" } ?: cells.first() }
            .sortedBy { it.stamp.tMonoNs }
    }

    /** Walks every stream once (merge-style) and attaches the latest non-stale value to each fix. */
    fun build(
        locations: List<LocationSample>,
        serving: List<CellSample> = emptyList(),
        probes: List<ProbeSample> = emptyList(),
        wifi: List<WifiConnectionSample> = emptyList(),
        radio: List<RadioSample> = emptyList(),
        gnss: GnssEpochs? = null,
        paths: List<NetworkPathSample> = emptyList(),
    ): List<MapPoint> {
        val locs = locations.sortedBy { it.stamp.tMonoNs }
        val cells = primaryServing(serving)
        val lat = probes.filter { it.probeType == "latency" }.sortedBy { it.stamp.tMonoNs }
        val wf = wifi.sortedBy { it.stamp.tMonoNs }
        val rd = radio.sortedBy { it.stamp.tMonoNs }
        val tp = probes.filter { it.probeType == "throughput" && it.success }.sortedBy { it.stamp.tMonoNs }
        val np = paths.sortedBy { it.stamp.tMonoNs }
        var ci = 0; var pi = 0; var wi = 0; var ri = 0; var gi = 0; var ti = 0; var ni = 0
        val out = ArrayList<MapPoint>(locs.size)
        for (l in locs) {
            val t = l.stamp.tMonoNs
            while (ci < cells.size && cells[ci].stamp.tMonoNs <= t) ci++
            while (pi < lat.size && lat[pi].stamp.tMonoNs <= t) pi++
            while (wi < wf.size && wf[wi].stamp.tMonoNs <= t) wi++
            while (ri < rd.size && rd[ri].stamp.tMonoNs <= t) ri++
            while (ti < tp.size && tp[ti].stamp.tMonoNs <= t) ti++
            while (ni < np.size && np[ni].stamp.tMonoNs <= t) ni++
            if (gnss != null) while (gi < gnss.t.size && gnss.t[gi] <= t) gi++
            val cell = cells.getOrNull(ci - 1)?.takeIf { t - it.stamp.tMonoNs <= STALE_NS }
            val probe = lat.getOrNull(pi - 1)?.takeIf { t - it.stamp.tMonoNs <= STALE_NS }
            val w = wf.getOrNull(wi - 1)?.takeIf { t - it.stamp.tMonoNs <= STALE_NS }
            val r = rd.getOrNull(ri - 1)
            val used = if (gnss != null && gi > 0 && t - gnss.t[gi - 1] <= STALE_NS) gnss.used[gi - 1] else null
            out += MapPoint(
                lat = l.lat, lon = l.lon, tMonoNs = t,
                rsrp = cell?.rsrp, sinr = cell?.sinr,
                latencyMs = probe?.takeIf { it.success }?.totalMs, failed = probe != null && !probe.success,
                wifiRssi = w?.rssiDbm, rat = r?.rat ?: cell?.rat, usedSats = used,
                iface = np.getOrNull(ni - 1)?.primaryInterface,
                mbps = tp.getOrNull(ti - 1)?.takeIf { t - it.stamp.tMonoNs <= THROUGHPUT_STALE_NS }?.mbps,
                hAccM = l.hAccM,
            )
        }
        return out
    }
}
