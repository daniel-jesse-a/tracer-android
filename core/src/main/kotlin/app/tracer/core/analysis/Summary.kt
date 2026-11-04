package app.tracer.core.analysis

import app.tracer.core.config.RunSettings
import app.tracer.core.model.*
import app.tracer.core.schema.SampleCodec
import app.tracer.core.util.Stats
import kotlinx.serialization.json.*

/**
 * Streaming builder for a bundle's `summary.json`. Feed every sample once (any order within a
 * table; tables may come one after another), then [build]. Memory is O(numeric samples kept for
 * percentiles) — a 2 h dual-path run is ~60 k probe values, a few MB at most — never whole rows.
 *
 * All figures are per path ("wifi" / "cellular") because dual-path runs measure both at once.
 */
class SummaryBuilder(private val run: RunRecord) {

    private class Vals { private var a = DoubleArray(256); var n = 0; private set
        fun add(v: Double?) { if (v == null || v.isNaN()) return; if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun list(): List<Double> = a.copyOf(n).toList()
    }

    /** v3: which server answered (remote address, IP version, edge PoP) — counts per value. */
    private class ServerAgg { val remoteIps = sortedMapOf<String, Int>(); val ipVersions = sortedMapOf<String, Int>(); val edgePops = sortedMapOf<String, Int>()
        fun add(p: ProbeSample) {
            p.remoteIp?.let { remoteIps.merge(it, 1, Int::plus) }
            p.ipVersion?.let { ipVersions.merge(it.toString(), 1, Int::plus) }
            p.edgePop?.let { edgePops.merge(it, 1, Int::plus) }
        }
        fun into(o: JsonObjectBuilder) {
            o.putJsonObject("remote_ips") { remoteIps.forEach { (k, v) -> put(k, v) } }
            o.putJsonObject("ip_versions") { ipVersions.forEach { (k, v) -> put(k, v) } }
            o.putJsonObject("edge_pops") { edgePops.forEach { (k, v) -> put(k, v) } }
        }
    }

    private class EndpointAgg { var n = 0; var reachable = 0; var expected = 0; var expectedKnown = 0
        val errors = HashMap<String, Int>(); val servers = ServerAgg() }

    private class PathAgg {
        val endpoints = sortedMapOf<String, EndpointAgg>()
        val dns = Vals(); val connect = Vals(); val tls = Vals(); val ttfb = Vals(); val total = Vals()
        val mbps = Vals(); var throughputBytes = 0L; var throughputN = 0; var throughputFail = 0
        val throughputMs = Vals(); val throughputServers = ServerAgg()
        val gateway = Vals(); var gatewayN = 0; var gatewayOk = 0
        val dnsServer = Vals(); var dnsServerN = 0; var dnsServerOk = 0
    }

    private val paths = sortedMapOf<String, PathAgg>()
    private fun path(p: ProbeSample) = paths.getOrPut(pathOf(p)) { PathAgg() }

    private val wifiRssi = Vals()
    private val rsrp = Vals(); private val sinr = Vals()
    private var lastServingStamp = HashMap<Int, Long>()
    private val eventsByType = sortedMapOf<String, Int>()
    private val eventsBySeverity = sortedMapOf<String, Int>()
    private val eventsByTypeSeverity = sortedMapOf<String, Int>()
    private var roams = 0
    private val roamPenalty = Vals(); private val roamLinkDown = Vals()
    private var roamEvents = 0
    private val pathChanges = ArrayList<Pair<Long, String?>>() // (mono ns, primary)
    private var firstMono = Long.MAX_VALUE; private var lastMono = Long.MIN_VALUE
    private val locByProvider = sortedMapOf<String, Int>(); private val hAcc = Vals()
    private var nrRadioRows = 0; private var radioRows = 0
    private val nrStates = sortedMapOf<String, Int>()
    private var waypoints = 0
    private val rows = sortedMapOf<String, Long>()
    private val coverage = CoverageTracker(run)
    private val movement = MovementTracker()
    private var mockFixes = 0
    private class IntegrityAgg { var n = 0; var ok = 0; var failed = 0; var untested = 0 }
    private val integrityByKind = sortedMapOf<String, IntegrityAgg>()
    private val integrityLatest = sortedMapOf<String, IntegritySample>()

    fun add(s: Sample) {
        rows.merge(s.table.sqlName, 1L, Long::plus)
        val t = s.stamp.tMonoNs
        if (t < firstMono) firstMono = t
        if (t > lastMono) lastMono = t
        coverage.add(s)
        when (s) {
            is ProbeSample -> addProbe(s)
            is WifiConnectionSample -> wifiRssi.add(s.rssiDbm?.toDouble())
            is CellSample -> if (s.serving) {
                // first serving row per poll (same stamp) = PCell
                val sub = s.subscriptionId ?: 0
                if (lastServingStamp[sub] != t) { lastServingStamp[sub] = t; rsrp.add(s.rsrp?.toDouble()); sinr.add(s.sinr?.toDouble()) }
            }
            is EventSample -> {
                eventsByType.merge(s.eventType, 1, Int::plus)
                eventsBySeverity.merge(s.severity, 1, Int::plus)
                eventsByTypeSeverity.merge("${s.eventType}:${s.severity}", 1, Int::plus)
                if (s.eventType == "wifi_roam") roamEvents++
            }
            is WifiRoamSample -> { roams++; roamPenalty.add(s.latencyPenaltyMs); roamLinkDown.add(s.linkDownMs?.toDouble()) }
            is NetworkPathSample -> pathChanges += t to (s.primaryInterface?.takeIf { s.status == "satisfied" } ?: "none")
            is LocationSample -> { locByProvider.merge(s.provider, 1, Int::plus); hAcc.add(s.hAccM); movement.add(s); if (s.isMock == true) mockFixes++ }
            is IntegritySample -> {
                val a = integrityByKind.getOrPut(s.kind) { IntegrityAgg() }
                a.n++; when (s.ok) { true -> a.ok++; false -> a.failed++; null -> a.untested++ }
                val key = "${s.kind}|${s.path ?: ""}|${s.target}"
                val prev = integrityLatest[key]
                if (prev == null || s.stamp.tMonoNs >= prev.stamp.tMonoNs) integrityLatest[key] = s
            }
            is RadioSample -> { radioRows++; if (s.rat.startsWith("NR")) nrRadioRows++; s.nrState?.let { nrStates.merge(it, 1, Int::plus) } }
            is WaypointSample -> waypoints++
            else -> {}
        }
    }

    private fun pathOf(p: ProbeSample): String =
        when (p.isCellular) { true -> "cellular"; false -> "wifi"; null -> SampleCodec.normalizeIface(p.iface, null) ?: "unknown" }

    private fun addProbe(p: ProbeSample) {
        val a = path(p)
        when (p.probeType) {
            "latency" -> {
                val e = a.endpoints.getOrPut(p.endpoint) { EndpointAgg() }
                e.n++
                if (p.reachable) e.reachable++ else e.errors.merge(p.error ?: "unknown", 1, Int::plus)
                p.expectedResponse?.let { e.expectedKnown++; if (it) e.expected++ }
                    ?: run { if (p.httpStatus == null && p.reachable) { /* v1 rows: unknown */ } }
                if (p.reachable) { a.dns.add(p.dnsMs); a.connect.add(p.connectMs); a.tls.add(p.tlsMs); a.ttfb.add(p.ttfbMs); a.total.add(p.totalMs) }
                e.servers.add(p)
            }
            "throughput" -> {
                a.throughputN++; a.throughputServers.add(p)
                if (p.success) { a.mbps.add(p.mbps); a.throughputBytes += p.bytes ?: 0; a.throughputMs.add(p.totalMs) } else a.throughputFail++
            }
            "gateway" -> { a.gatewayN++; if (p.success) { a.gatewayOk++; a.gateway.add(p.totalMs) } }
            "dns_server" -> { a.dnsServerN++; if (p.success) { a.dnsServerOk++; a.dnsServer.add(p.totalMs) } }
        }
    }

    private fun r1(x: Double?) = x?.let { Math.round(it * 10) / 10.0 }
    private fun r3(x: Double?) = x?.let { Math.round(it * 1000) / 1000.0 }
    private fun dist(v: Vals) = buildJsonObject {
        val l = v.list(); put("n", l.size); put("median", r1(Stats.median(l))); put("p95", r1(Stats.percentile(l, 95.0)))
    }

    fun build(settings: RunSettings?): JsonObject = buildJsonObject {
        val durMs = run.endedAt?.let { it - run.startedAt } ?: if (lastMono > firstMono) (lastMono - firstMono) / 1_000_000 else 0
        put("summary_version", 2)
        put("run_id", run.runId); put("name", run.name); put("site", run.site); put("test_number", run.testNumber)
        put("protocol", run.protocol); put("mode", run.mode); put("device_model", run.deviceModel); put("os_version", run.osVersion)
        put("app_version", run.appVersion); put("time_zone", run.timeZone); put("started_at_utc_ms", run.startedAt); put("duration_s", durMs / 1000)
        settings?.let {
            put("method_fingerprint", run.methodFingerprint
                ?: run.settingsJson?.let { j -> Method.fingerprintOfRecorded(j, run.protocol) } ?: Method.fingerprint(it, run.protocol))
            put("method", Method.canonical(it, run.protocol))
        }
        val cov = coverage.build()
        put("captured_s", Math.round(cov.capturedMs / 100.0) / 10.0)
        put("coverage_pct", cov.coveragePct)
        put("gaps", cov.gapCount)
        put("coverage", cov.toJson())
        putJsonObject("event_rules") {
            put("cause_window_s", (settings?.thresholds ?: app.tracer.core.config.Thresholds()).causeWindowMs / 1000.0)
            put("stall_threshold_s", CoverageTracker.STALL_MS / 1000.0)
        }

        putJsonObject("paths") {
            for ((name, a) in paths) putJsonObject(name) {
                val n = a.endpoints.values.sumOf { it.n }; val reach = a.endpoints.values.sumOf { it.reachable }
                val expKnown = a.endpoints.values.sumOf { it.expectedKnown }; val exp = a.endpoints.values.sumOf { it.expected }
                put("latency_probes", n)
                put("reachable_rate", if (n > 0) r3(reach.toDouble() / n) else null)
                put("expected_response_rate", if (expKnown > 0) r3(exp.toDouble() / expKnown) else null)
                putJsonObject("latency_ms") {
                    put("total", dist(a.total)); put("dns", dist(a.dns)); put("connect", dist(a.connect))
                    put("tls", dist(a.tls)); put("ttfb", dist(a.ttfb))
                }
                putJsonObject("endpoints") {
                    for ((url, e) in a.endpoints) putJsonObject(url) {
                        put("n", e.n); put("reachable_rate", r3(e.reachable.toDouble() / e.n))
                        put("expected_response_rate", if (e.expectedKnown > 0) r3(e.expected.toDouble() / e.expectedKnown) else null)
                        putJsonObject("errors") { e.errors.toSortedMap().forEach { (k, v) -> put(k, v) } }
                        e.servers.into(this)
                    }
                }
                putJsonObject("throughput_mbps") {
                    val l = a.mbps.list(); put("n", a.throughputN); put("failed", a.throughputFail)
                    put("median", r1(Stats.median(l))); put("max", r1(l.maxOrNull())); put("bytes", a.throughputBytes)
                    // actual test duration (time-boxed by throughput_max_ms)
                    put("duration_ms_median", r1(Stats.median(a.throughputMs.list())))
                    a.throughputServers.into(this)
                }
                putJsonObject("internal") {
                    // gateway RTT = the Wi-Fi/radio link; connect − gateway ≈ upstream
                    put("gateway_probes", a.gatewayN); put("gateway_reachable_rate", if (a.gatewayN > 0) r3(a.gatewayOk.toDouble() / a.gatewayN) else null)
                    put("gateway_rtt_ms", dist(a.gateway))
                    put("dns_server_probes", a.dnsServerN); put("dns_server_reachable_rate", if (a.dnsServerN > 0) r3(a.dnsServerOk.toDouble() / a.dnsServerN) else null)
                    put("dns_server_rtt_ms", dist(a.dnsServer))
                    val link = Stats.median(a.gateway.list()); val conn = Stats.median(a.connect.list())
                    put("link_rtt_median_ms", r1(link))
                    put("upstream_rtt_median_ms", if (link != null && conn != null) r1((conn - link).coerceAtLeast(0.0)) else null)
                }
            }
        }

        putJsonObject("radio") {
            val w = wifiRssi.list(); put("wifi_rssi_median_dbm", r1(Stats.median(w))); put("wifi_rssi_worst_dbm", w.minOrNull()?.toInt())
            put("rsrp_median_dbm", r1(Stats.median(rsrp.list()))); put("sinr_median_db", r1(Stats.median(sinr.list())))
            put("nr_share", if (radioRows > 0) r3(nrRadioRows.toDouble() / radioRows) else null)
            putJsonObject("nr_state_rows") { nrStates.forEach { (k, v) -> put(k, v) } }
        }

        putJsonObject("events") {
            put("total", eventsByType.values.sum())
            putJsonObject("by_type") { eventsByType.forEach { (k, v) -> put(k, v) } }
            putJsonObject("by_severity") { eventsBySeverity.forEach { (k, v) -> put(k, v) } }
            putJsonObject("by_type_severity") { eventsByTypeSeverity.forEach { (k, v) -> put(k, v) } }
        }

        putJsonObject("wifi_mobility") {
            put("roams", if (roams > 0) roams else roamEvents)
            put("roam_latency_penalty_median_ms", r1(Stats.median(roamPenalty.list())))
            put("roam_link_down_median_ms", r1(Stats.median(roamLinkDown.list())))
            val fb = cellularFallback()
            put("cellular_fallback_s", r1(fb.first / 1000.0)); put("cellular_fallback_episodes", fb.second)
        }

        putJsonObject("position") {
            putJsonObject("fixes_by_provider") { locByProvider.forEach { (k, v) -> put(k, v) } }
            put("h_acc_median_m", r1(Stats.median(hAcc.list())))
            put("waypoints", waypoints)
            put("mock_fixes", mockFixes)
        }
        put("movement", movement.build().toJson())
        putJsonObject("integrity") {
            put("checks", integrityByKind.values.sumOf { it.n })
            put("ok", integrityByKind.values.sumOf { it.ok }); put("failed", integrityByKind.values.sumOf { it.failed })
            put("untested", integrityByKind.values.sumOf { it.untested })
            putJsonObject("by_kind") { integrityByKind.forEach { (k, a) -> putJsonObject(k) { put("n", a.n); put("ok", a.ok); put("failed", a.failed); put("untested", a.untested) } } }
            putJsonArray("latest") {
                integrityLatest.values.forEach { i -> addJsonObject {
                    put("kind", i.kind); put("path", i.path); put("target", i.target); put("ok", i.ok); put("value", i.value)
                    put("t_run_s", if (run.tMonoNs > 0) Math.round((i.stamp.tMonoNs - run.tMonoNs) / 1e8) / 10.0 else null)
                } }
            }
        }
        putJsonObject("rows") { rows.forEach { (k, v) -> put(k, v) } }
    }

    /** Time the default network was cellular *after* Wi-Fi had been the default (i.e. fallback), and episode count. */
    private fun cellularFallback(): Pair<Long, Int> {
        val ch = pathChanges.sortedBy { it.first }
        var seenWifi = false; var since: Long? = null; var totalNs = 0L; var episodes = 0
        for ((t, primary) in ch) {
            if (since != null && primary != "cellular") { totalNs += t - since; since = null }
            if (primary == "wifi") seenWifi = true
            if (primary == "cellular" && seenWifi && since == null) { since = t; episodes++ }
        }
        if (since != null && lastMono > since) totalNs += lastMono - since
        return totalNs / 1_000_000 to episodes
    }
}
