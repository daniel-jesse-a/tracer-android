package app.tracer.ui.replay

import app.tracer.core.model.CellSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Sample
import app.tracer.core.model.WaypointSample
import app.tracer.core.model.WifiRoamSample
import app.tracer.core.model.WifiConnectionSample
import app.tracer.core.quality.ConnType
import app.tracer.core.util.Stats
import app.tracer.ui.components.map.EventMarker
import app.tracer.ui.components.map.MapPoint
import app.tracer.ui.components.map.TrackBuilder

/** x/y arrays for one numeric lane (NaN = no value). */
class NumData(val xs: LongArray, val ys: FloatArray, val failed: BooleanArray? = null) {
    val isEmpty: Boolean get() = xs.isEmpty()
    companion object { val EMPTY = NumData(LongArray(0), FloatArray(0)) }
}

/** x/category arrays for one categorical lane. */
class CatData(val xs: LongArray, val values: Array<ConnType>) {
    companion object { val EMPTY = CatData(LongArray(0), emptyArray()) }
}

/** Everything Replay draws; built off the main thread. */
class ReplayData(
    val run: RunRecord,
    val startNs: Long,
    val endNs: Long,
    val points: List<MapPoint>,
    val overlay: List<MapPoint>,
    val type: CatData,
    val rat: CatData,
    val rsrp: NumData,
    val sinr: NumData,
    val latency: NumData,
    val loss: NumData,
    val throughput: NumData,
    val wifi: NumData,
    val gnssUsed: NumData,
    val linkedLatency: NumData?,
    val linkedRsrp: NumData?,
    /** rowid → event, ascending time */
    val events: List<Pair<Long, EventSample>>,
    val labels: Map<Long, String>,
    val markers: List<EventMarker>,
    val linkedName: String?,
    /** user waypoints, ascending seq */
    val waypoints: List<WaypointSample> = emptyList(),
    /** Wi-Fi roams (wifi_roam rows), ascending time */
    val roams: List<WifiRoamSample> = emptyList(),
) {
    /** Map pins for [waypoints] with a position (ids are negative: −seq, never clash with event rowids). */
    val waypointMarkers: List<EventMarker> get() = waypoints.mapNotNull { w ->
        val la = w.lat; val lo = w.lon
        if (la != null && lo != null) EventMarker(ReplayBuilder.waypointMarkerId(w.seq), la, lo, ReplayBuilder.WAYPOINT_SEVERITY, w.label ?: "#${w.seq}") else null
    }
}

/** Pure helpers that turn sample lists into lanes. */
object ReplayBuilder {

    /** EventMarker.severity used for waypoint pins (drawn in the info colour by both map backends). */
    const val WAYPOINT_SEVERITY = "waypoint"

    fun waypointMarkerId(seq: Int): Long = -seq.toLong().coerceAtLeast(1L)

    /** seq of a waypoint pin id, or null for an event rowid. */
    fun waypointSeqOf(markerId: Long): Int? = if (markerId < 0) (-markerId).toInt() else null

    /** "aa:bb:cc:dd:ee:ff" → "ee:ff" (enough to tell APs of one SSID apart). */
    fun bssidTail(bssid: String?): String = bssid?.takeLast(5) ?: "—"

    /** TYPE: ConnType.of(rat, primaryInterface) at every change of either stream. */
    fun connType(radio: List<RadioSample>, paths: List<NetworkPathSample>): CatData {
        val merged = (radio.map { it.stamp.tMonoNs to it } + paths.map { it.stamp.tMonoNs to it }).sortedBy { it.first }
        if (merged.isEmpty()) return CatData.EMPTY
        var rat: String? = null
        var iface: String? = null
        val xs = ArrayList<Long>(merged.size)
        val vs = ArrayList<ConnType>(merged.size)
        merged.forEach { (t, s) ->
            when (s) {
                is RadioSample -> rat = s.rat
                is NetworkPathSample -> iface = if (s.status == "satisfied") s.primaryInterface else "none"
                else -> {}
            }
            val ct = if (iface == "none") ConnType.NONE else ConnType.of(rat, iface)
            xs += t; vs += ct
        }
        return CatData(xs.toLongArray(), vs.toTypedArray())
    }

    fun rat(radio: List<RadioSample>): CatData {
        val r = radio.sortedBy { it.stamp.tMonoNs }
        return CatData(LongArray(r.size) { r[it].stamp.tMonoNs }, Array(r.size) { ConnType.of(r[it].rat) })
    }

    fun serving(serving: List<CellSample>): Pair<NumData, NumData> {
        val p = TrackBuilder.primaryServing(serving)
        val xs = LongArray(p.size) { p[it].stamp.tMonoNs }
        return NumData(xs, FloatArray(p.size) { p[it].rsrp?.toFloat() ?: Float.NaN }) to
            NumData(xs, FloatArray(p.size) { p[it].sinr?.toFloat() ?: Float.NaN })
    }

    fun latency(probes: List<ProbeSample>): NumData {
        val l = probes.filter { it.probeType == "latency" }.sortedBy { it.stamp.tMonoNs }
        return NumData(
            LongArray(l.size) { l[it].stamp.tMonoNs },
            FloatArray(l.size) { if (l[it].success) l[it].totalMs?.toFloat() ?: Float.NaN else Float.NaN },
            BooleanArray(l.size) { !l[it].success },
        )
    }

    /** Rolling loss % over the last [window] latency probes. */
    fun rollingLoss(probes: List<ProbeSample>, window: Int = 20): NumData {
        val l = probes.filter { it.probeType == "latency" }.sortedBy { it.stamp.tMonoNs }
        val ys = FloatArray(l.size)
        var fails = 0
        for (i in l.indices) {
            if (!l[i].success) fails++
            if (i >= window && !l[i - window].success) fails--
            val n = minOf(i + 1, window)
            ys[i] = 100f * fails / n
        }
        return NumData(LongArray(l.size) { l[it].stamp.tMonoNs }, ys)
    }

    fun throughput(probes: List<ProbeSample>): NumData {
        val t = probes.filter { it.probeType == "throughput" && it.success && it.mbps != null }.sortedBy { it.stamp.tMonoNs }
        return NumData(LongArray(t.size) { t[it].stamp.tMonoNs }, FloatArray(t.size) { t[it].mbps!!.toFloat() })
    }

    fun wifi(w: List<WifiConnectionSample>): NumData {
        val s = w.sortedBy { it.stamp.tMonoNs }
        return NumData(LongArray(s.size) { s[it].stamp.tMonoNs }, FloatArray(s.size) { s[it].rssiDbm?.toFloat() ?: Float.NaN })
    }

    fun gnss(e: TrackBuilder.GnssEpochs): NumData = NumData(e.t, FloatArray(e.used.size) { e.used[it].toFloat() })

    /** Median (t_mono_ms − t_utc_ms) over a run's samples: maps aligned wall-clock times onto this run's mono axis. */
    fun monoMinusUtcMs(samples: List<Sample>): Long? =
        Stats.median(samples.map { (it.stamp.tMonoMs - it.stamp.tUtcMs).toDouble() })?.toLong()

    fun utcToMonoNs(utcMs: Long, monoMinusUtcMs: Long): Long = (utcMs + monoMinusUtcMs) * 1_000_000L

    /** Linked-run locations (already aligned onto this run's wall clock) → overlay points. */
    fun overlay(aligned: List<LocationSample>, k: Long): List<MapPoint> =
        aligned.sortedBy { it.stamp.tUtcMs }.map { MapPoint(it.lat, it.lon, utcToMonoNs(it.stamp.tUtcMs, k), hAccM = it.hAccM) }

    fun linkedLatency(aligned: List<ProbeSample>, k: Long): NumData {
        val l = aligned.filter { it.probeType == "latency" }.sortedBy { it.stamp.tUtcMs }
        return NumData(
            LongArray(l.size) { utcToMonoNs(l[it].stamp.tUtcMs, k) },
            FloatArray(l.size) { if (l[it].success) l[it].totalMs?.toFloat() ?: Float.NaN else Float.NaN },
            BooleanArray(l.size) { !l[it].success },
        )
    }

    fun linkedRsrp(aligned: List<CellSample>, k: Long): NumData {
        val p = TrackBuilder.primaryServing(aligned)
        return NumData(LongArray(p.size) { utcToMonoNs(p[it].stamp.tUtcMs, k) }, FloatArray(p.size) { p[it].rsrp?.toFloat() ?: Float.NaN })
    }

    /** Stable merge by t_mono_ns (GNSS epochs keep their within-epoch order). */
    fun mergeByTime(lists: List<List<Sample>>): List<Sample> = lists.flatten().sortedBy { it.stamp.tMonoNs }

    /** Previous / next event strictly before / after [t] (1 ms tolerance so stepping moves on). */
    fun prevEvent(events: List<Pair<Long, EventSample>>, t: Long): Pair<Long, EventSample>? =
        events.lastOrNull { it.second.stamp.tMonoNs < t - 1_000_000L }

    fun nextEvent(events: List<Pair<Long, EventSample>>, t: Long): Pair<Long, EventSample>? =
        events.firstOrNull { it.second.stamp.tMonoNs > t + 1_000_000L }
}
