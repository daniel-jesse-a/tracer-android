package app.tracer.core.sim

import app.tracer.core.model.*
import app.tracer.core.util.Geo

/**
 * Deterministic 1 Hz synthetic timelines. Used by rule unit tests and by the app's debug
 * "Simulate run" mode (which paces these samples into the real writer + event engine).
 */
class SecondBuilder internal constructor(
    val runId: String, val t: Int, private val baseUtc: Long, private val baseMono: Long, private val gpsOffsetMs: Long,
    val pos: Pair<Double, Double>, val speedMps: Double, val courseDeg: Double,
) {
    internal val out = mutableListOf<Sample>()
    private var subMs = 0L

    /** Each emitted row is spaced 1 ms after the previous one within the second, preserving order. */
    fun stamp(gps: Boolean = false): Stamp {
        val utc = baseUtc + t * 1000L + subMs
        val mono = baseMono + (t * 1000L + subMs) * 1_000_000L
        subMs++
        return Stamp(utc, mono, if (gps) utc + gpsOffsetMs else null)
    }

    fun location(hAcc: Double = 4.0, jumpM: Double = 0.0) {
        val (lat, lon) = if (jumpM > 0) Geo.destination(pos.first, pos.second, courseDeg + 90, jumpM) else pos
        val st = stamp(gps = true)
        out += LocationSample(runId, st, lat, lon, 12.0, hAcc, 6.0, speedMps, 0.5, courseDeg, 5.0, null, st.gpsTimeMs, "gps")
    }

    fun gnss(used: Int, cn0: Double, visible: Int = maxOf(used + 4, 8)) {
        val st = stamp()
        repeat(visible) { i ->
            out += GnssSatelliteSample(runId, st, i + 1, if (i % 3 == 0) "GALILEO" else "GPS", cn0 - (i % 4) * 2, 20.0 + i * 5 % 70, (i * 37 % 360).toDouble(), i < used, 1_575_420_000.0)
        }
    }

    fun radio(rat: String, sub: String = "1", nrState: String? = null) { out += RadioSample(runId, stamp(), sub, rat, nrState) }

    fun serving(cellId: Long, pci: Int, rsrp: Int, sinr: Int = 12, rat: String = "LTE", ta: Int? = 5, earfcn: Int = 1650, sub: Int = 1, ageMs: Long = 200) {
        out += CellSample(runId, stamp(), true, rat, "424", "02", 1001, cellId, pci, earfcn, if (rat == "NR") "n78" else "B3", 20.0,
            rsrp, -10, -60, sinr, 10, ta, ageMs, sub)
    }

    fun neighbour(pci: Int, rsrp: Int, rat: String = "LTE", earfcn: Int = 1650, sub: Int = 1) {
        out += CellSample(runId, stamp(), false, rat, null, null, null, null, pci, earfcn, null, null, rsrp, -12, null, null, null, null, 200, sub)
    }

    fun path(primary: String? = "cellular", satisfied: Boolean = true, reason: String? = null) {
        out += NetworkPathSample(runId, stamp(), if (satisfied) "satisfied" else if (reason == "captive_portal") "requires_connection" else "unsatisfied",
            primary, primary, primary == "cellular", false, "10.0.0.2", null, if (satisfied) "good" else "unknown", if (satisfied) null else reason)
    }

    fun latency(ms: Double?, dnsMs: Double = 8.0, endpoint: String = "https://1.1.1.1/cdn-cgi/trace", cellular: Boolean = true, error: String? = null) {
        out += if (ms == null) ProbeSample(runId, stamp(), "latency", endpoint, false, error ?: "timeout", iface = if (cellular) "rmnet0" else "wlan0", isCellular = cellular, totalMs = 3000.0)
        // ms = round-trip time: TCP connect ≈ 1 RTT, TLS 1.3 ≈ 1 RTT, TTFB ≈ 1 RTT
        else ProbeSample(runId, stamp(), "latency", endpoint, true, null, dnsMs, ms, ms, ms, 3 * ms + dnsMs, 0, null, if (cellular) "rmnet0" else "wlan0", cellular)
    }

    fun throughput(mbps: Double, cellular: Boolean = true) {
        out += ProbeSample(runId, stamp(), "throughput", "https://speed.cloudflare.com/__down?bytes=1000000", true, null, 8.0, 20.0, 25.0, 60.0,
            1_000_000.0 * 8 / (mbps * 1000), 1_000_000, mbps, if (cellular) "rmnet0" else "wlan0", cellular)
    }

    fun publicIp(ip: String, asn: String, org: String, cellular: Boolean = true) {
        out += ProbeSample(runId, stamp(), "public_ip", "https://ipinfo.io/json", true, isCellular = cellular, publicIp = ip, asn = asn, asnOrg = org)
    }

    fun wifi(ssid: String, bssid: String, rssi: Int, freq: Int = 5180) {
        out += WifiConnectionSample(runId, stamp(), ssid, bssid, "WPA3", rssi, 433, freq, "80", "wifi6")
    }

    fun scan(scanId: Long, aps: List<Triple<String, String, Int>>, freq: Int = 5180) {
        val st = stamp()
        aps.forEach { (ssid, bssid, rssi) -> out += WifiScanSample(runId, st, scanId, ssid, bssid, rssi, freq, "80", "wifi6", "[WPA3-SAE-CCMP][ESS]") }
    }
}

class SyntheticRun(
    val runId: String = "00000000-0000-4000-8000-000000000001",
    val startUtcMs: Long = 1_760_000_000_000L,
    val startMonoNs: Long = 5_000_000_000_000L,
    val gpsOffsetMs: Long = 350,
    val origin: Pair<Double, Double> = 25.2048 to 55.2708,
) {
    fun build(seconds: Int, speedMps: (Int) -> Double = { 15.0 }, courseDeg: Double = 45.0, f: SecondBuilder.() -> Unit): List<Sample> {
        val out = mutableListOf<Sample>()
        var pos = origin
        for (t in 0 until seconds) {
            val v = speedMps(t)
            val b = SecondBuilder(runId, t, startUtcMs, startMonoNs, gpsOffsetMs, pos, v, courseDeg)
            b.f()
            out += b.out
            pos = Geo.destination(pos.first, pos.second, courseDeg, v)
        }
        return out
    }
}

/** Named scenarios. Each exhibits one cause so rule tests can assert it. */
object Scenarios {
    val names = listOf("tunnel", "cell_edge_drop", "late_handover", "congestion", "interference", "sticky_wifi",
        "handover_churn", "throughput_drop", "gps_degraded", "captive_portal", "dns_issue", "carrier_gateway_change", "commute")

    fun byName(name: String, run: SyntheticRun = SyntheticRun()): List<Sample> = when (name) {
        "tunnel" -> tunnel(run)
        "cell_edge_drop" -> cellEdgeDrop(run)
        "late_handover" -> lateHandover(run)
        "congestion" -> congestion(run)
        "interference" -> interference(run)
        "sticky_wifi" -> stickyWifi(run)
        "handover_churn" -> handoverChurn(run)
        "throughput_drop" -> throughputDrop(run)
        "gps_degraded" -> gpsDegraded(run)
        "captive_portal" -> captivePortal(run)
        "dns_issue" -> dnsIssue(run)
        "carrier_gateway_change" -> carrierGatewayChange(run)
        "commute" -> commute(run)
        else -> error("unknown scenario $name")
    }

    private fun SecondBuilder.baseline(rsrp: Int = -85, sinr: Int = 15, latency: Double? = 30.0 + (t % 5), used: Int = 14, cn0: Double = 40.0, cellId: Long = 1001, pci: Int = 101) {
        location(); gnss(used, cn0); radio("LTE"); serving(cellId, pci, rsrp, sinr); neighbour(202, rsrp - 12); path("cellular")
        if (t % 2 == 0) latency(latency)
    }

    fun tunnel(r: SyntheticRun) = r.build(120) {
        when (t) {
            in 0 until 60 -> baseline()
            in 60 until 85 -> {
                location(hAcc = 45.0); gnss(used = 1, cn0 = 22.0, visible = 3)
                radio(if (t < 64) "LTE" else "UMTS")
                serving(1001, 101, rsrp = -119, sinr = -2, ta = 6)
                path("cellular", satisfied = t < 66, reason = "not_validated")
                if (t % 2 == 0) latency(if (t < 63) 220.0 else null)
            }
            else -> baseline()
        }
    }

    fun cellEdgeDrop(r: SyntheticRun) = r.build(90) {
        when {
            t < 20 -> baseline(rsrp = -98, sinr = 6)
            t < 50 -> { location(); gnss(13, 39.0); radio("LTE"); serving(1001, 101, rsrp = -98 - (t - 20) * 2 / 3 - 2, sinr = 2, ta = 32); path(); if (t % 2 == 0) latency(90.0) }
            t < 60 -> { location(); gnss(13, 39.0); radio("LTE"); serving(1001, 101, rsrp = -121, sinr = -4, ta = 33); path(satisfied = t < 52, reason = "not_validated"); if (t % 2 == 0) latency(null) }
            else -> baseline(rsrp = -96, cellId = 1002, pci = 102)
        }
    }

    fun lateHandover(r: SyntheticRun) = r.build(80) {
        when {
            t < 10 -> baseline(rsrp = -95)
            t < 40 -> {
                location(); gnss(14, 40.0); radio("LTE")
                serving(1001, 101, rsrp = -95 - (t - 10) / 2, sinr = 3)
                neighbour(202, rsrp = -100 + (t - 10))   // overtakes by ≥6 dB from t≈16
                path(satisfied = t < 34, reason = "not_validated"); if (t % 2 == 0) latency(if (t < 32) 60.0 else null)
            }
            else -> { location(); gnss(14, 40.0); radio("LTE"); serving(2002, 202, -80, 16); neighbour(101, -105); path(); if (t % 2 == 0) latency(32.0) }
        }
    }

    fun congestion(r: SyntheticRun) = r.build(80) {
        when (t) {
            in 40 until 50 -> { location(); gnss(14, 41.0); radio("LTE"); serving(1001, 101, -78, 20); path(); if (t % 2 == 0) latency(420.0 + t) }
            else -> baseline(rsrp = -78, sinr = 20)
        }
    }

    fun interference(r: SyntheticRun) = r.build(80) {
        when (t) {
            in 40 until 52 -> { location(); gnss(14, 41.0); radio("LTE"); serving(1001, 101, -84, -4); path(); if (t % 2 == 0) latency(380.0) }
            else -> baseline(rsrp = -84, sinr = 14)
        }
    }

    fun stickyWifi(r: SyntheticRun) = r.build(70, speedMps = { 1.2 }) {
        location(); gnss(10, 35.0); radio("LTE"); serving(1001, 101, -90, 10)
        path("wifi")
        if (t < 40) wifi("Office", "aa:aa:aa:aa:aa:01", rssi = -70 - minOf(t, 12)) else wifi("Office", "aa:aa:aa:aa:aa:02", -58)
        if (t % 10 == 5) scan(t.toLong(), listOf(Triple("Office", "aa:aa:aa:aa:aa:01", -80), Triple("Office", "aa:aa:aa:aa:aa:02", -60)) +
            (3..12).map { Triple("Neighbour$it", "bb:bb:bb:bb:bb:%02d".format(it), -75) })
        if (t % 2 == 0) latency(if (t in 30 until 40) 300.0 else 25.0, cellular = false)
    }

    fun handoverChurn(r: SyntheticRun) = r.build(90, speedMps = { 33.0 }) {
        val idx = t / 12
        baseline(rsrp = -92, cellId = 1000L + idx, pci = 100 + idx)
    }

    fun throughputDrop(r: SyntheticRun) = r.build(400) {
        baseline(rsrp = -82)
        if (t % 60 == 10) throughput(if (t < 300) 48.0 + (t % 7) else 9.0)
    }

    fun gpsDegraded(r: SyntheticRun) = r.build(60) {
        when (t) {
            in 30 until 40 -> { location(hAcc = 38.0, jumpM = if (t == 31) 400.0 else 0.0); gnss(4, 28.0); radio("LTE"); serving(1001, 101, -86); path(); if (t % 2 == 0) latency(35.0) }
            else -> baseline()
        }
    }

    fun captivePortal(r: SyntheticRun) = r.build(60, speedMps = { 0.0 }) {
        location(); gnss(12, 38.0); radio("LTE"); serving(1001, 101, -88)
        if (t < 30) { path("cellular"); if (t % 2 == 0) latency(35.0) }
        else { wifi("Airport_Free", "cc:cc:cc:cc:cc:01", -55, 2437); path("wifi", satisfied = false, reason = "captive_portal"); if (t % 2 == 0) latency(null, cellular = false, error = "http_302") }
    }

    fun dnsIssue(r: SyntheticRun) = r.build(70) {
        when (t) {
            in 40 until 50 -> { location(); gnss(14, 40.0); radio("LTE"); serving(1001, 101, -80, 18); path(); if (t % 2 == 0) latency(40.0, dnsMs = 900.0) }
            else -> baseline(rsrp = -80, sinr = 18)
        }
    }

    fun carrierGatewayChange(r: SyntheticRun) = r.build(80) {
        baseline(rsrp = -84)
        if (t == 5) publicIp("94.200.1.10", "AS5384", "Emirates Telecommunications")
        if (t == 50) publicIp("185.93.4.2", "AS15802", "Emirates Integrated Telecommunications")
    }

    /** Long mixed run for the debug simulator: concatenation of several scenarios. */
    fun commute(r: SyntheticRun): List<Sample> {
        val parts = listOf("congestion", "late_handover", "tunnel", "handover_churn", "cell_edge_drop")
        val out = mutableListOf<Sample>()
        var utc = r.startUtcMs; var mono = r.startMonoNs; var origin = r.origin
        for (p in parts) {
            val seg = byName(p, SyntheticRun(r.runId, utc, mono, r.gpsOffsetMs, origin))
            out += seg
            val lastT = seg.maxOf { it.stamp.tMonoNs }
            mono = lastT + 1_000_000_000L
            utc = seg.maxOf { it.stamp.tUtcMs } + 1000
            (seg.lastOrNull { it is LocationSample } as? LocationSample)?.let { origin = it.lat to it.lon }
        }
        return out
    }
}
