package app.tracer.events

import app.tracer.core.cell.CellNormalizer
import app.tracer.core.model.*
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
    const val WIFI_ROAM_TRANSITION = "wifi_roam_transition"
    /** gateway RTT spiked with the internet RTT → delay is on the local (Wi-Fi) link */
    const val WIFI_LINK_DELAY = "wifi_link_delay"
    /** gateway RTT normal while internet RTT spiked → delay is upstream of the AP/router */
    const val UPSTREAM_DELAY = "upstream_delay"
    const val CAPTIVE = "captive_portal"
    const val DNS = "dns_issue"
    const val GATEWAY = "carrier_gateway_change"
    /** first DNS query lost while the radio woke; the answer came on the resolver's retry timer (~2 s / ~5 s) */
    const val DNS_RETRY_TIMER = "dns_retry_timer"
}

data class Candidate(val cause: String, val confidence: Double, val signals: JsonObject)

data class Diagnosis(val best: Candidate?, val candidates: List<Candidate>) {
    fun evidenceJson(snapshot: JsonObject, windowMs: Long = CauseEngine.NEAR_MS): String = evidence(snapshot, windowMs).toString()

    fun evidence(snapshot: JsonObject, windowMs: Long = CauseEngine.NEAR_MS): JsonObject = buildJsonObject {
        put("window_ms", windowMs)
        put("window_s", windowMs / 1000.0)
        putJsonArray("candidates") {
            candidates.sortedByDescending { it.confidence }.forEach { c ->
                addJsonObject { put("cause", c.cause); put("confidence", c.confidence); put("signals", c.signals) }
            }
        }
        put("snapshot", snapshot)
    }
}

/**
 * Heuristic cause attribution, evaluated at the event time T once T+2 s has been observed.
 *
 * Signal rules (v2):
 *  - Periodic signals (serving cell rows, GNSS epochs, latency/internal probes) use the **median of
 *    samples within T ± 2 s** — never a minimum over a long window.
 *  - State reported only on change (Wi-Fi link, network path, Android validation flags, radio RAT,
 *    Wi-Fi scans) uses the **last report at or before T + 2 s** (that value is the state at T).
 *  - Explicit precursors keep their own documented windows because they are *events*, not signal
 *    levels: neighbour overtake (≤ 15 s before), recent roam (≤ 10 s before), handover rate (60 s),
 *    and the tunnel baseline (T−45…T−15 s) the collapse is measured against.
 *  - Domain: cellular-RF events (signal_drop, handover, rat_change, neighbour_overtake) and events on
 *    the cellular path never receive Wi-Fi causes; Wi-Fi events never receive cellular-RF causes.
 *  - captive_portal requires Android to report the network unvalidated or captive (path reason
 *    captive_portal/not_validated, or x_android_network validated=0 / captive_portal=1) near T.
 */
class CauseEngine {
    companion object {
        const val MIN_CONFIDENCE = 0.4
        /** Default of thresholds.cause_lookback_s (2 s); the engine uses [Context.nearMs]. */
        const val NEAR_MS = 2_000L
        private fun r2(x: Double) = Math.round(x * 100) / 100.0
        private fun med(xs: List<Double>) = Stats.median(xs)
    }

    /** Values a cause can read at event time T. */
    /** @param transition for roam / Wi-Fi→cell events the relevant Wi-Fi state is the one *left* (last report before T) */
    class At(val ctx: Context, val t: Long, transition: Boolean = false) {
        private val lo = t - ctx.nearMs
        private val hi = t + ctx.nearMs
        val serving: List<CellSample> = ctx.servingHistory.values.flatMap { it.between(lo, hi) }.filter { (it.cellinfoAgeMs ?: 0) <= ctx.th.cellStaleMs }
        val rsrp: Double? = med(serving.mapNotNull { it.rsrp?.toDouble() })
        val sinr: Double? = med(serving.mapNotNull { it.sinr?.toDouble() })
        val ta: Double? = med(serving.filter { it.rat == "LTE" }.mapNotNull { it.timingAdvance?.toDouble() })
        val taM: Double? = med(serving.mapNotNull { CellNormalizer.timingAdvanceDistanceM(it.rat, it.timingAdvance) })
        val epochs = ctx.gnssEpochs.between(lo, hi)
        val used: Double? = med(epochs.map { it.used.toDouble() })
        val cn0: Double? = med(epochs.mapNotNull { it.meanTopCn0 })
        val wifi: WifiConnectionSample? = ctx.wifiHistory.lastAtOrBefore(if (transition) t - 1 else hi)?.takeIf { it.bssid != null }
        val path: NetworkPathSample? = ctx.pathHistory.lastAtOrBefore(hi)
        val radio: RadioSample? = ctx.radioHistory.lastAtOrBefore(hi)
        val scanWithTime = ctx.scanHistory.lastAtOrBeforeWithTime(hi)
        /** scans are periodic (30 s): accept the newest one up to 30 s old */
        val scan: List<WifiScanSample> = scanWithTime?.takeIf { t - it.first <= 30_000 }?.second ?: emptyList()
        val net: List<AndroidNetworkSample> = ctx.androidNet.between(lo, hi).ifEmpty { listOfNotNull(ctx.androidNet.lastAtOrBefore(hi)) }
        val probes: List<ProbeSample> = ctx.latencyProbes.between(lo, hi)
        val internal: List<ProbeSample> = ctx.internalProbes.between(lo, hi)
        val speed: Double? = ctx.locations.lastAtOrBefore(hi)?.speedMps
        fun probesOn(path: String) = probes.filter { ctx.pathOf(it) == path }
    }

    fun diagnose(eventType: String, ctx: Context, tMs: Long = ctx.nowMs, extra: JsonObject = JsonObject(emptyMap())): Diagnosis {
        val th = ctx.th
        val near = ctx.nearMs
        val afterResume = extra["after_resume"]?.jsonPrimitive?.booleanOrNull == true
        val at = At(ctx, tMs, transition = eventType == EventType.WIFI_ROAM || eventType == EventType.WIFI_CELL_SWITCH)
        val c = mutableListOf<Candidate>()

        val rfEvent = eventType in setOf(EventType.SIGNAL_DROP, EventType.HANDOVER, EventType.RAT_CHANGE, EventType.NEIGHBOUR_OVERTAKE)
        val wifiEvent = eventType == EventType.WIFI_ROAM || eventType == EventType.WIFI_CELL_SWITCH
        val path = extra["path"]?.jsonPrimitive?.contentOrNull
            ?: if (rfEvent) "cellular" else if (wifiEvent) "wifi" else if (at.path?.primaryInterface == "wifi") "wifi" else "cellular"
        val cellularDomain = !wifiEvent && (rfEvent || path == "cellular")
        val wifiDomain = !rfEvent && (wifiEvent || path == "wifi")
        val connectivity = eventType in setOf(EventType.DROP, EventType.SIGNAL_DROP, EventType.LATENCY_SPIKE, EventType.THROUGHPUT_DROP,
            EventType.RAT_CHANGE, EventType.DNS_SPIKE, EventType.WIFI_ROAM, EventType.WIFI_CELL_SWITCH, EventType.CAPTIVE_PORTAL)
        val perfEvent = eventType == EventType.LATENCY_SPIKE || eventType == EventType.THROUGHPUT_DROP || eventType == EventType.DROP

        // ================= cellular RF causes =================
        if (cellularDomain && connectivity) {
            // tunnel / underpass: GNSS collapse at T vs. a baseline 15–45 s earlier, plus radio degradation at T
            val base = ctx.gnssEpochs.between(tMs - 45_000, tMs - 15_000)
            val usedBefore = med(base.map { it.used.toDouble() })
            val cn0Before = med(base.mapNotNull { it.meanTopCn0 })
            val rsrpBefore = med(ctx.servingHistory.values.flatMap { it.between(tMs - 45_000, tMs - 15_000) }.mapNotNull { it.rsrp?.toDouble() })
            val ratBefore = ctx.radioHistory.lastAtOrBefore(tMs - 15_000)?.rat
            // a RAT downgrade only counts when it is sustained (> 5 s): 5G→LTE→5G flickers are not tunnels
            val heldMs = if (ratBefore != null) ctx.downgradeHeldMs(tMs, ratBefore, at.radio?.serviceId, ctx.nowMs) else 0L
            val ratDown = heldMs > Context.RAT_SUSTAIN_MS
            val gnssCollapse = usedBefore != null && at.used != null && at.used <= th.tunnelMaxUsedSats && at.used <= usedBefore * 0.5
            val cn0Collapse = cn0Before != null && at.cn0 != null && cn0Before - at.cn0 >= th.tunnelCn0DropDb
            val rsrpDrop = rsrpBefore != null && at.rsrp != null && rsrpBefore - at.rsrp >= th.signalDropDb
            // RAT changes need all three (sustained downgrade + signal drop + GNSS loss); other events
            // need GNSS loss + (signal drop or sustained downgrade). Never right after a resume.
            val tunnel = !afterResume && gnssCollapse &&
                if (eventType == EventType.RAT_CHANGE) ratDown && rsrpDrop else (rsrpDrop || ratDown)
            if (tunnel) {
                var conf = 0.65
                if (cn0Collapse) conf += 0.15
                if (rsrpDrop && ratDown) conf += 0.05
                c += Candidate(Cause.TUNNEL, r2(conf), buildJsonObject {
                    put("used_sats_baseline", usedBefore); put("used_sats_at_t", at.used); put("cn0_baseline", cn0Before); put("cn0_at_t", at.cn0)
                    put("rsrp_baseline", rsrpBefore); put("rsrp_at_t", at.rsrp); put("rat_baseline", ratBefore); put("rat_at_t", at.radio?.rat)
                    put("rat_downgrade_held_ms", heldMs)
                })
            }
            // cell edge: weak signal or far from the site *at T*
            val weak = at.rsrp != null && at.rsrp < th.cellEdgeRsrp
            val far = at.ta != null && at.ta >= th.cellEdgeTimingAdvance
            if (weak || far) {
                c += Candidate(Cause.CELL_EDGE, r2((if (weak) 0.55 else 0.35) + (if (weak && far) 0.2 else 0.0)), buildJsonObject {
                    put("rsrp_at_t", at.rsrp); put("timing_advance_at_t", at.ta); put("ta_distance_m", at.taM?.let { Math.round(it).toDouble() })
                })
            }
            // interference: strong signal, poor SINR at T
            if (at.rsrp != null && at.rsrp > th.goodRsrp && at.sinr != null && at.sinr < th.interferenceSinr) {
                c += Candidate(Cause.INTERFERENCE, 0.75, buildJsonObject { put("rsrp_at_t", at.rsrp); put("sinr_at_t", at.sinr) })
            }
        }
        if (cellularDomain && (eventType == EventType.DROP || eventType == EventType.SIGNAL_DROP || eventType == EventType.LATENCY_SPIKE)) {
            // precursor event: an overtake ≤ 15 s before and no handover since
            val ot = ctx.overtakes.between(tMs - 15_000, tMs)
            val hoSince = ctx.handoverTimes.between(tMs - 15_000, tMs + near).size
            if (ot.isNotEmpty() && hoSince == 0) {
                c += Candidate(Cause.LATE_HANDOVER, if (eventType == EventType.DROP) 0.8 else 0.65, buildJsonObject {
                    putJsonArray("overtaking_neighbours") { ot.distinct().forEach { add(it) } }; put("handovers_since", hoSince)
                })
            }
        }
        if (cellularDomain && (eventType == EventType.HANDOVER || eventType == EventType.DROP || eventType == EventType.LATENCY_SPIKE)) {
            val n = ctx.handoverTimes.between(tMs - (th.churnWindowS * 1000).toLong(), tMs).size
            if (n >= th.churnHandovers && (at.speed ?: 0.0) >= th.churnMinSpeedMps) {
                c += Candidate(Cause.HANDOVER_CHURN, r2((0.5 + 0.08 * (n - th.churnHandovers)).coerceAtMost(0.85)), buildJsonObject {
                    put("handovers_in_window", n); put("window_s", th.churnWindowS); put("speed_mps", at.speed)
                })
            }
        }
        if (cellularDomain && perfEvent && at.rsrp != null) {
            val noChange = ctx.cellChangeTimes.between(tMs - 30_000, tMs + near).isEmpty()
            if (at.rsrp > th.goodRsrp && (at.sinr == null || at.sinr > th.goodSinr) && noChange) {
                c += Candidate(Cause.CONGESTION, if (eventType == EventType.DROP) 0.5 else 0.7, buildJsonObject {
                    put("rsrp_at_t", at.rsrp); put("sinr_at_t", at.sinr); put("cell_changes_30s", 0)
                })
            }
        }

        // ================= Wi-Fi causes (never for cellular-domain events) =================
        if (wifiDomain) {
            val w = at.wifi
            val rssi = w?.rssiDbm
            if (rssi != null && rssi < th.wifiWeakRssi) c += Candidate(Cause.WIFI_WEAK, 0.6, buildJsonObject { put("rssi_at_t", rssi) })
            if (w?.ssid != null && rssi != null && rssi < th.wifiWeakRssi) {
                val better = at.scan.filter { it.ssid == w.ssid && it.bssid != w.bssid }.maxByOrNull { it.rssiDbm }
                if (better != null && better.rssiDbm - rssi >= th.stickyDeltaDb) {
                    c += Candidate(Cause.STICKY_CLIENT, 0.8, buildJsonObject {
                        put("connected_rssi_at_t", rssi); put("better_bssid", better.bssid); put("better_rssi", better.rssiDbm)
                    })
                }
            }
            w?.frequencyMhz?.let { f ->
                val same = at.scan.count { it.frequencyMhz == f }
                if (same >= th.channelCongestionAps) c += Candidate(Cause.CHANNEL_CONGESTION, 0.5, buildJsonObject { put("aps_on_channel", same); put("frequency_mhz", f) })
            }
            if (eventType != EventType.WIFI_ROAM) {
                val roams = ctx.roamTimes.between(tMs - 10_000, tMs + near)
                if (roams.isNotEmpty()) c += Candidate(Cause.WIFI_ROAM_TRANSITION, 0.65, buildJsonObject { put("roams_10s", roams.size); put("security", w?.security) })
            }
            if (perfEvent && path == "wifi") {
                // split link vs upstream with the gateway probe at T
                val gw = at.internal.filter { it.probeType == "gateway" && ctx.pathOf(it) == "wifi" }
                val gwNow = med(gw.filter { it.success }.mapNotNull { it.totalMs })
                val gwBase = med(ctx.internalProbes.between(tMs - 60_000, tMs - near).filter { it.probeType == "gateway" && it.success && ctx.pathOf(it) == "wifi" }.mapNotNull { it.totalMs })
                val gwLost = gw.isNotEmpty() && gw.none { it.success }
                if (gwNow != null && gwBase != null) {
                    if (gwNow > 3 * gwBase && gwNow > 50) {
                        c += Candidate(Cause.WIFI_LINK_DELAY, 0.8, buildJsonObject { put("gateway_rtt_at_t", gwNow); put("gateway_rtt_baseline", gwBase) })
                    } else {
                        c += Candidate(Cause.UPSTREAM_DELAY, 0.65, buildJsonObject { put("gateway_rtt_at_t", gwNow); put("gateway_rtt_baseline", gwBase) })
                    }
                } else if (gwLost) {
                    c += Candidate(Cause.WIFI_LINK_DELAY, 0.7, buildJsonObject { put("gateway_reachable_at_t", false) })
                } else if (rssi != null && rssi > th.wifiGoodRssi && at.path?.status == "satisfied") {
                    c += Candidate(Cause.WIFI_UPLINK, 0.6, buildJsonObject { put("rssi_at_t", rssi); put("path_status", "satisfied") })
                }
            }
        }

        // ================= captive portal: only with Android's own flag =================
        if (!rfEvent && (eventType == EventType.DROP || eventType == EventType.CAPTIVE_PORTAL || eventType == EventType.LATENCY_SPIKE)) {
            val captiveFlag = at.net.any { it.captivePortal == true } || at.path?.unsatisfiedReason == "captive_portal"
            val unvalidated = at.net.any { it.validated == false } || at.path?.unsatisfiedReason == "not_validated"
            // unvalidated alone is usually "no internet"; it is a portal only if probes also got
            // reachable-but-unexpected answers (an intercepting page)
            val intercepted = at.probesOn(path).any { it.reachable && it.expectedResponse == false }
            if (captiveFlag || (unvalidated && intercepted)) {
                c += Candidate(Cause.CAPTIVE, if (captiveFlag) 0.9 else 0.7, buildJsonObject {
                    put("android_captive_flag", captiveFlag); put("android_unvalidated", unvalidated); put("unexpected_responses", intercepted)
                })
            }
        }

        // ================= DNS =================
        if (eventType == EventType.DNS_SPIKE && extra["dns_retry_timer"]?.jsonPrimitive?.booleanOrNull == true) {
            c += Candidate(Cause.DNS_RETRY_TIMER, 0.85, buildJsonObject {
                put("dns_ms", extra["dns_ms"] ?: JsonNull); put("idle_before_ms", extra["dns_idle_ms"] ?: JsonNull)
                put("after_resume", afterResume)
            })
        }
        if (eventType == EventType.DNS_SPIKE || eventType == EventType.LATENCY_SPIKE || eventType == EventType.DROP) {
            val near = at.probesOn(path)
            val dnsErrors = near.count { it.error == "dns" }
            val share = med(near.mapNotNull { p -> val d = p.dnsMs; val tot = p.totalMs; if (d != null && tot != null && tot > 0) d / tot else null })
            val dnsServerFail = at.internal.any { it.probeType == "dns_server" && !it.success && ctx.pathOf(it) == path }
            if (eventType == EventType.DNS_SPIKE || (share ?: 0.0) > 0.5 || dnsErrors >= 2 || dnsServerFail) {
                c += Candidate(Cause.DNS, if (eventType == EventType.DNS_SPIKE || (share ?: 0.0) > 0.5 || dnsServerFail) 0.8 else 0.65, buildJsonObject {
                    put("dns_share_of_total_at_t", share?.let { r2(it) }); put("dns_errors_at_t", dnsErrors); put("dhcp_dns_server_failed", dnsServerFail)
                })
            }
        }

        if (eventType == EventType.IP_ASN_CHANGE) {
            val asnChanged = extra["asn_changed"]?.jsonPrimitive?.booleanOrNull == true
            val onCell = extra["is_cellular"]?.jsonPrimitive?.booleanOrNull ?: (path == "cellular")
            c += Candidate(Cause.GATEWAY, if (asnChanged && onCell) 0.75 else 0.5, buildJsonObject { put("asn_changed", asnChanged); put("is_cellular", onCell) })
        }

        val sorted = c.sortedByDescending { it.confidence }
        return Diagnosis(sorted.firstOrNull { it.confidence >= MIN_CONFIDENCE }, sorted)
    }

    /** Evidence snapshot at T (same ±2 s / last-report rules as the causes). */
    fun snapshot(ctx: Context, tMs: Long): JsonObject {
        val at = At(ctx, tMs)
        return buildJsonObject {
            put("t_mono_ms", tMs)
            at.serving.lastOrNull()?.let { s ->
                putJsonObject("serving") { put("rat", s.rat); put("cell_id", s.cellId); put("pci", s.pci); put("band", s.band); put("earfcn_or_arfcn", s.earfcnOrArfcn) }
            }
            put("rsrp_at_t", at.rsrp); put("sinr_at_t", at.sinr); put("rat", at.radio?.rat); put("nr_state", at.radio?.nrState)
            at.wifi?.let { w -> putJsonObject("wifi") { put("ssid", w.ssid); put("bssid", w.bssid); put("rssi_dbm", w.rssiDbm); put("frequency_mhz", w.frequencyMhz) } }
            put("path_status", at.path?.status); put("primary_interface", at.path?.primaryInterface)
            put("gnss_used_at_t", at.used); put("gnss_cn0_at_t", at.cn0); put("speed_mps", at.speed)
            put("rtt_at_t_ms", med(at.probes.filter { it.success }.mapNotNull { it.connectMs ?: it.totalMs }))
            put("gateway_rtt_at_t_ms", med(at.internal.filter { it.probeType == "gateway" && it.success }.mapNotNull { it.totalMs }))
        }
    }
}
