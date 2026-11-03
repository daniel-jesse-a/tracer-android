package app.tracer.events

import app.tracer.core.util.Stats
import kotlinx.serialization.json.*

/** Cause identifiers — shared vocabulary with iOS. */
object Cause {
    const val TUNNEL = "tunnel_underpass"
    const val CELL_EDGE = "cell_edge"
    const val LATE_HANDOVER = "late_failed_handover"
    const val HANDOVER_CHURN = "handover_churn"
    const val CONGESTION = "congestion_backhaul"
    const val INTERFERENCE = "interference"
    const val WIFI_WEAK = "wifi_weak_signal"
    const val STICKY_CLIENT = "sticky_client"
    const val CHANNEL_CONGESTION = "channel_congestion"
    const val WIFI_UPLINK = "wifi_uplink"
    const val CAPTIVE = "captive_portal"
    const val DNS = "dns_issue"
    const val GATEWAY = "carrier_gateway_change"
}

data class Candidate(val cause: String, val confidence: Double, val signals: JsonObject)

data class Diagnosis(val best: Candidate?, val candidates: List<Candidate>) {
    fun evidenceJson(snapshot: JsonObject): String = buildJsonObject {
        putJsonArray("candidates") {
            candidates.sortedByDescending { it.confidence }.forEach { c ->
                addJsonObject { put("cause", c.cause); put("confidence", c.confidence); put("signals", c.signals) }
            }
        }
        put("snapshot", snapshot)
    }.toString()
}

/**
 * Heuristic cause attribution. Each heuristic returns a candidate with a confidence in [0,1] and
 * the signals it used; the highest-confidence candidate ≥ [MIN_CONFIDENCE] becomes likely_cause.
 * All windows look back from the event (causes never need future data, so live == replay).
 */
class CauseEngine {
    companion object {
        const val MIN_CONFIDENCE = 0.4
        private fun r2(x: Double) = Math.round(x * 100) / 100.0
    }

    fun diagnose(eventType: String, ctx: Context, extra: JsonObject = JsonObject(emptyMap())): Diagnosis {
        val th = ctx.th
        val lb = (th.causeLookbackS * 1000).toLong()
        val c = mutableListOf<Candidate>()

        val isConnectivity = eventType in setOf(EventType.DROP, EventType.SIGNAL_DROP, EventType.LATENCY_SPIKE, EventType.THROUGHPUT_DROP,
            EventType.RAT_CHANGE, EventType.DNS_SPIKE, EventType.WIFI_ROAM, EventType.WIFI_CELL_SWITCH, EventType.CAPTIVE_PORTAL)
        val cellular = !ctx.onWifi()
        val serving = ctx.primaryServing()

        // ---- tunnel / underpass: GNSS collapse + radio degradation, simultaneously ----
        if (isConnectivity) {
            val before = ctx.epochsBetween(lb + 30_000, lb)
            val recent = ctx.epochsBetween(lb, 0)
            val usedBefore = Stats.median(before.map { it.used.toDouble() })
            val usedNow = recent.minOfOrNull { it.used }
            val cn0Before = Stats.median(before.mapNotNull { it.meanTopCn0 })
            val cn0Now = recent.mapNotNull { it.meanTopCn0 }.minOrNull()
            val rsrpBefore = ctx.servingHistory.values.flatMap { it.values() }.filter { ctx.nowMs - it.stamp.tMonoMs in lb..(lb + 30_000) }.mapNotNull { it.rsrp?.toDouble() }.let(Stats::median)
            val rsrpNow = ctx.minServingRsrp(lb)?.toDouble()
            val ratDown = ctx.ratChanges.since(ctx.nowMs, lb).any { (from, to) -> Context.ratRank(to) < Context.ratRank(from) }
            val gnssCollapse = usedBefore != null && usedNow != null && (usedNow <= th.tunnelMaxUsedSats && usedNow <= usedBefore * 0.5)
            val cn0Collapse = cn0Before != null && cn0Now != null && cn0Before - cn0Now >= th.tunnelCn0DropDb
            val rsrpDrop = rsrpBefore != null && rsrpNow != null && rsrpBefore - rsrpNow >= th.signalDropDb
            if (gnssCollapse && (rsrpDrop || ratDown)) {
                var conf = 0.65
                if (cn0Collapse) conf += 0.15
                if (rsrpDrop && ratDown) conf += 0.05
                c += Candidate(Cause.TUNNEL, r2(conf), buildJsonObject {
                    put("used_sats_before", usedBefore); put("used_sats_min", usedNow); put("cn0_before", cn0Before); put("cn0_min", cn0Now)
                    put("rsrp_before", rsrpBefore); put("rsrp_min", rsrpNow); put("rat_downgrade", ratDown)
                })
            }
        }

        // ---- cell edge ----
        if (isConnectivity && cellular) {
            val minRsrp = ctx.minServingRsrp(lb)
            val maxTa = ctx.maxTimingAdvance(lb)
            val weak = minRsrp != null && minRsrp < th.cellEdgeRsrp
            val far = maxTa != null && maxTa >= th.cellEdgeTimingAdvance
            if (weak || far) {
                val conf = (if (weak) 0.55 else 0.35) + (if (weak && far) 0.2 else 0.0)
                c += Candidate(Cause.CELL_EDGE, r2(conf), buildJsonObject {
                    put("min_rsrp", minRsrp); put("max_timing_advance", maxTa); put("ta_distance_m", ctx.maxTimingAdvanceM(lb)?.let { Math.round(it).toDouble() })
                })
            }
        }

        // ---- late / failed handover: overtake preceded the event and no handover followed ----
        if (eventType == EventType.DROP || eventType == EventType.SIGNAL_DROP || eventType == EventType.LATENCY_SPIKE) {
            val ot = ctx.overtakes.since(ctx.nowMs, lb + 5_000)
            val hoSince = ctx.handoverTimes.since(ctx.nowMs, lb).size
            if (ot.isNotEmpty() && hoSince == 0) {
                c += Candidate(Cause.LATE_HANDOVER, if (eventType == EventType.DROP) 0.8 else 0.65, buildJsonObject {
                    putJsonArray("overtaking_neighbours") { ot.distinct().forEach { add(it) } }; put("handovers_since", hoSince)
                })
            }
        }

        // ---- handover churn at speed ----
        if (eventType == EventType.HANDOVER || eventType == EventType.DROP || eventType == EventType.LATENCY_SPIKE) {
            val n = ctx.handoverTimes.since(ctx.nowMs, (th.churnWindowS * 1000).toLong()).size
            val v = ctx.speedMps()
            if (n >= th.churnHandovers && (v ?: 0.0) >= th.churnMinSpeedMps) {
                c += Candidate(Cause.HANDOVER_CHURN, r2((0.5 + 0.08 * (n - th.churnHandovers)).coerceAtMost(0.85)), buildJsonObject {
                    put("handovers_in_window", n); put("window_s", th.churnWindowS); put("speed_mps", v)
                })
            }
        }

        // ---- interference: strong signal, poor SINR ----
        if (isConnectivity && cellular && serving != null) {
            val minSinr = ctx.minServingSinr(lb)
            val rsrp = serving.rsrp
            if (rsrp != null && rsrp > th.goodRsrp && minSinr != null && minSinr < th.interferenceSinr) {
                c += Candidate(Cause.INTERFERENCE, 0.75, buildJsonObject { put("rsrp", rsrp); put("min_sinr", minSinr) })
            }
        }

        // ---- congestion / backhaul: latency or throughput bad while radio is good and stable ----
        if ((eventType == EventType.LATENCY_SPIKE || eventType == EventType.THROUGHPUT_DROP || eventType == EventType.DROP) && cellular && serving != null) {
            val minRsrp = ctx.minServingRsrp(lb)
            val minSinr = ctx.minServingSinr(lb)
            val noChange = ctx.cellChangeTimes.since(ctx.nowMs, 30_000).isEmpty()
            if (minRsrp != null && minRsrp > th.goodRsrp && (minSinr == null || minSinr > th.goodSinr) && noChange) {
                c += Candidate(Cause.CONGESTION, if (eventType == EventType.DROP) 0.5 else 0.7, buildJsonObject {
                    put("min_rsrp", minRsrp); put("min_sinr", minSinr); put("cell_changes_30s", 0)
                })
            }
        }

        // ---- Wi-Fi ----
        if (ctx.onWifi() || eventType == EventType.WIFI_ROAM || eventType == EventType.WIFI_CELL_SWITCH) {
            val hist = ctx.wifiHistory.since(ctx.nowMs, lb)
            val minRssi = hist.mapNotNull { it.rssiDbm }.minOrNull()
            val cur = ctx.wifi
            if (minRssi != null && minRssi < th.wifiWeakRssi) {
                c += Candidate(Cause.WIFI_WEAK, 0.6, buildJsonObject { put("min_rssi", minRssi) })
            }
            if (cur?.ssid != null && cur.rssiDbm != null && hist.any { (it.rssiDbm ?: 0) < th.wifiWeakRssi }) {
                val connRssi = hist.filter { it.bssid == hist.firstOrNull()?.bssid }.mapNotNull { it.rssiDbm }.minOrNull() ?: cur.rssiDbm
                val better = ctx.lastScan.filter { it.ssid == cur.ssid && it.bssid != (hist.firstOrNull()?.bssid ?: cur.bssid) }.maxByOrNull { it.rssiDbm }
                if (better != null && better.rssiDbm - connRssi >= th.stickyDeltaDb) {
                    c += Candidate(Cause.STICKY_CLIENT, 0.8, buildJsonObject {
                        put("connected_rssi", connRssi); put("better_bssid", better.bssid); put("better_rssi", better.rssiDbm)
                    })
                }
            }
            cur?.frequencyMhz?.let { f ->
                val sameChannel = ctx.lastScan.count { it.frequencyMhz == f }
                if (sameChannel >= th.channelCongestionAps) {
                    c += Candidate(Cause.CHANNEL_CONGESTION, 0.5, buildJsonObject { put("aps_on_channel", sameChannel); put("frequency_mhz", f) })
                }
            }
            if (ctx.onWifi() && (eventType == EventType.LATENCY_SPIKE || eventType == EventType.DROP || eventType == EventType.THROUGHPUT_DROP)) {
                val good = hist.isNotEmpty() && hist.all { (it.rssiDbm ?: -100) > th.wifiGoodRssi }
                if (good && ctx.path?.status == "satisfied") {
                    c += Candidate(Cause.WIFI_UPLINK, 0.6, buildJsonObject { put("min_rssi", minRssi); put("path_status", "satisfied") })
                }
            }
        }

        // ---- captive portal ----
        val captiveSeen = ctx.pathHistory.since(ctx.nowMs, lb + 30_000).any { it.unsatisfiedReason == "captive_portal" } ||
            ctx.latencyProbes.since(ctx.nowMs, lb).any { it.error?.startsWith("http_3") == true }
        if (captiveSeen && (eventType == EventType.DROP || eventType == EventType.CAPTIVE_PORTAL || eventType == EventType.LATENCY_SPIKE)) {
            c += Candidate(Cause.CAPTIVE, 0.9, buildJsonObject { put("path_reason", "captive_portal") })
        }

        // ---- DNS ----
        if (eventType == EventType.DNS_SPIKE || eventType == EventType.LATENCY_SPIKE || eventType == EventType.DROP) {
            val recent = ctx.latencyProbes.since(ctx.nowMs, lb)
            val dnsErrors = recent.count { it.error == "dns" }
            val last = recent.lastOrNull()
            val dnsShare = if (last?.dnsMs != null && last.totalMs != null && last.totalMs > 0) last.dnsMs / last.totalMs else 0.0
            if (eventType == EventType.DNS_SPIKE || dnsShare > 0.5 || dnsErrors >= 2) {
                c += Candidate(Cause.DNS, if (eventType == EventType.DNS_SPIKE || dnsShare > 0.5) 0.8 else 0.65, buildJsonObject {
                    put("dns_share_of_total", r2(dnsShare)); put("dns_errors", dnsErrors); put("dns_ms", last?.dnsMs)
                })
            }
        }

        // ---- carrier gateway change ----
        if (eventType == EventType.IP_ASN_CHANGE) {
            val asnChanged = extra["asn_changed"]?.jsonPrimitive?.booleanOrNull == true
            val onCell = extra["is_cellular"]?.jsonPrimitive?.booleanOrNull ?: cellular
            c += Candidate(Cause.GATEWAY, if (asnChanged && onCell) 0.75 else 0.5, buildJsonObject {
                put("asn_changed", asnChanged); put("is_cellular", onCell)
            })
        }

        val sorted = c.sortedByDescending { it.confidence }
        return Diagnosis(sorted.firstOrNull { it.confidence >= MIN_CONFIDENCE }, sorted)
    }
}
