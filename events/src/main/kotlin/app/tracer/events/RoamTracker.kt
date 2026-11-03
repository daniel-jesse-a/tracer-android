package app.tracer.events

import app.tracer.core.model.*
import app.tracer.core.schema.SampleCodec
import app.tracer.core.util.Stats
import app.tracer.core.util.TimeWindow
import app.tracer.core.util.WifiMath

/**
 * Turns the Wi-Fi sample stream into one [WifiRoamSample] per roam (BSSID change within an SSID,
 * including a reconnect to a different AP of the same SSID after a disconnect).
 *
 * - link_down_ms: disconnect (Wi-Fi link lost / BSSID null) → re-association on the new BSSID;
 *   0 when the switch was seamless (no disconnect reported between the two BSSIDs).
 * - latency penalty: median Wi-Fi-path RTT (TCP connect) in the [PENALTY_WINDOW_MS] after the roam
 *   minus the median in the [BASELINE_MS] before it. The record is emitted once that window has
 *   passed (or at [finish]), so it arrives ~10 s after the roam.
 * - 802.11k/v/r: from the newest scan that saw the target BSSID (`wifi_scan.features`).
 *
 * Pure and bounded (windows ≤ 60 s), so it runs live in RunService and offline in Replay.
 */
class RoamTracker(private val runId: String) {
    companion object {
        const val PENALTY_WINDOW_MS = 10_000L
        const val BASELINE_MS = 30_000L
    }

    private data class Pending(val stamp: Stamp, val tMs: Long, val ssid: String?, val from: WifiConnectionSample, val to: WifiConnectionSample, val linkDownMs: Long)

    private var current: WifiConnectionSample? = null
    /** last connected AP before a disconnect (for reconnect-to-another-AP roams) */
    private var lastConnected: WifiConnectionSample? = null
    private var disconnectedAtMs: Long? = null
    private val rtt = TimeWindow<Double>(BASELINE_MS + PENALTY_WINDOW_MS + 5_000)
    private val features = HashMap<String, Pair<Long, String?>>() // bssid → (scan time, features)
    private val pending = ArrayDeque<Pending>()
    private var nowMs = 0L

    fun onSample(s: Sample): List<WifiRoamSample> {
        val t = s.stamp.tMonoMs
        if (t > nowMs) nowMs = t
        when (s) {
            is WifiConnectionSample -> onWifi(s, t)
            is NetworkPathSample -> if (s.primaryInterface != "wifi" && s.interfaces?.contains("wifi") != true && current != null) {
                // Wi-Fi left the network list: treat as disconnect
                lastConnected = current; current = null; if (disconnectedAtMs == null) disconnectedAtMs = t
            }
            is ProbeSample -> if (s.probeType == "latency" && s.success && isWifi(s)) (s.connectMs ?: s.totalMs)?.let { rtt.add(t, it) }
            is WifiScanSample -> {
                if (features.size > 2_000) features.entries.removeIf { nowMs - it.value.first > 600_000 } // bound memory on long runs
                features[s.bssid.lowercase()] = t to s.features
            }
            else -> {}
        }
        return drain(force = false)
    }

    fun finish(): List<WifiRoamSample> = drain(force = true)

    private fun isWifi(p: ProbeSample) = p.isCellular == false || SampleCodec.normalizeIface(p.iface, p.isCellular) == "wifi"

    private fun onWifi(w: WifiConnectionSample, t: Long) {
        val bssid = w.bssid
        if (bssid == null) {
            if (current != null) { lastConnected = current; current = null; disconnectedAtMs = t }
            return
        }
        val prev = current ?: lastConnected
        if (prev?.bssid != null && prev.bssid != bssid && (prev.ssid == null || prev.ssid == w.ssid)) {
            val down = disconnectedAtMs?.let { (t - it).coerceAtLeast(0) } ?: 0L
            pending += Pending(w.stamp, t, w.ssid ?: prev.ssid, prev, w, down)
        }
        current = w; lastConnected = null; disconnectedAtMs = null
    }

    private fun drain(force: Boolean): List<WifiRoamSample> {
        val out = mutableListOf<WifiRoamSample>()
        while (pending.isNotEmpty() && (force || nowMs - pending.first().tMs >= PENALTY_WINDOW_MS)) {
            val p = pending.removeFirst()
            val before = rtt.filter { (ts, _) -> ts in (p.tMs - BASELINE_MS) until p.tMs }.map { it.second }
            val after = rtt.filter { (ts, _) -> ts in p.tMs..(p.tMs + PENALTY_WINDOW_MS) }.map { it.second }
            val mb = Stats.median(before); val ma = Stats.median(after)
            val feat = p.to.bssid?.lowercase()?.let { features[it] }?.second
            val flags = feat?.split(',')?.map { it.trim() }?.toSet()
            out += WifiRoamSample(
                runId = runId, stamp = Stamp(p.stamp.tUtcMs, p.stamp.tMonoNs), ssid = p.ssid,
                fromBssid = p.from.bssid, toBssid = p.to.bssid,
                fromBand = p.from.frequencyMhz?.let { WifiMath.bandOf(it) }, toBand = p.to.frequencyMhz?.let { WifiMath.bandOf(it) },
                fromRssiDbm = p.from.rssiDbm, toRssiDbm = p.to.rssiDbm, linkDownMs = p.linkDownMs,
                latencyPenaltyMs = if (mb != null && ma != null) ma - mb else null, rttBeforeMs = mb, rttAfterMs = ma,
                target11k = flags?.contains("11k"), target11v = flags?.contains("11v"), target11r = flags?.contains("11r"),
            )
        }
        return out
    }
}
