package app.tracer.events

import app.tracer.core.config.Thresholds
import app.tracer.core.model.*
import app.tracer.core.util.Stats
import app.tracer.core.util.TimeWindow
import kotlinx.serialization.json.*

object EventType {
    const val HANDOVER = "handover"
    const val RAT_CHANGE = "rat_change"
    const val WIFI_ROAM = "wifi_roam"
    const val WIFI_CELL_SWITCH = "wifi_cell_switch"
    const val DROP = "drop"
    const val RECONNECT = "reconnect"
    const val CAPTIVE_PORTAL = "captive_portal"
    const val IP_ASN_CHANGE = "ip_asn_change"
    const val GPS_DEGRADED = "gps_degraded"
    const val LATENCY_SPIKE = "latency_spike"
    const val DNS_SPIKE = "dns_spike"
    const val THROUGHPUT_DROP = "throughput_drop"
    const val SIGNAL_DROP = "signal_drop"
    const val NEIGHBOUR_OVERTAKE = "neighbour_overtake"

    val all = listOf(HANDOVER, RAT_CHANGE, WIFI_ROAM, WIFI_CELL_SWITCH, DROP, RECONNECT, CAPTIVE_PORTAL, IP_ASN_CHANGE,
        GPS_DEGRADED, LATENCY_SPIKE, DNS_SPIKE, THROUGHPUT_DROP, SIGNAL_DROP, NEIGHBOUR_OVERTAKE)
}

object Severity { const val INFO = "info"; const val WARNING = "warning"; const val CRITICAL = "critical" }

/**
 * Streaming, deterministic event detector. Feed samples in (approximately) monotonic order;
 * returns zero or more [EventSample]s per input. The same engine runs live (inside RunService)
 * and offline (Replay → "re-run rules with new thresholds"), so results are identical.
 */
class EventEngine(private val runId: String, thresholds: Thresholds = Thresholds(), private val causes: CauseEngine = CauseEngine()) {

    val ctx = Context(thresholds)
    private val th get() = ctx.th

    fun onSample(s: Sample): List<EventSample> {
        val t = s.stamp.tMonoMs
        if (t > ctx.nowMs) ctx.nowMs = t
        val out = mutableListOf<EventSample>()
        if (s !is GnssSatelliteSample) flushGnss(s.stamp, out, force = false)
        if (s !is WifiScanSample) flushScan()
        when (s) {
            is LocationSample -> onLocation(s, out)
            is GnssSatelliteSample -> onGnss(s, out)
            is CellSample -> if (s.serving) onServing(s, out) else onNeighbour(s, out)
            is RadioSample -> onRadio(s, out)
            is WifiConnectionSample -> onWifi(s, out)
            is WifiScanSample -> onScan(s)
            is NetworkPathSample -> onPath(s, out)
            is ProbeSample -> onProbe(s, out)
            else -> {}
        }
        return out
    }

    /** Flush pending epoch/scan at end of a replay. */
    fun finish(): List<EventSample> {
        val out = mutableListOf<EventSample>()
        flushGnss(Stamp(0, Long.MAX_VALUE), out, force = true)
        flushScan()
        return out
    }

    fun replay(samples: Sequence<Sample>): List<EventSample> {
        val out = mutableListOf<EventSample>()
        samples.forEach { out += onSample(it) }
        out += finish()
        return out
    }

    // ------------------------------------------------------------------ emit

    private fun spaced(key: String): Boolean {
        val last = ctx.lastEmit[key]
        return last == null || ctx.nowMs - last >= (th.minEventSpacingS * 1000).toLong()
    }

    private fun emit(
        out: MutableList<EventSample>, stamp: Stamp, type: String, severity: String,
        before: JsonObject?, after: JsonObject?, diagnose: Boolean = true, spacingKey: String? = null, extra: JsonObject = JsonObject(emptyMap()),
    ) {
        if (spacingKey != null) { if (!spaced(spacingKey)) return; ctx.lastEmit[spacingKey] = ctx.nowMs }
        val dx = if (diagnose) causes.diagnose(type, ctx, extra) else null
        val ll = ctx.lastLatLon()
        out += EventSample(
            runId = runId, stamp = Stamp(stamp.tUtcMs, stamp.tMonoNs), eventType = type, severity = severity,
            beforeJson = before?.toString(), afterJson = after?.toString(), lat = ll?.first, lon = ll?.second,
            likelyCause = dx?.best?.cause, causeConfidence = dx?.best?.confidence,
            evidenceJson = dx?.evidenceJson(snapshot()),
        )
    }

    private fun snapshot(): JsonObject = buildJsonObject {
        ctx.primaryServing()?.let { put("serving", cellJson(it)) }
        ctx.radio.values.lastOrNull()?.let { put("rat", it.rat) }
        ctx.wifi?.let { put("wifi", wifiJson(it)) }
        ctx.path?.let { put("path_status", it.status); put("primary_interface", it.primaryInterface) }
        ctx.gnssEpochs.last()?.let { put("gnss_used", it.used); put("gnss_mean_top_cn0", it.meanTopCn0) }
        ctx.lastLocation?.let { put("speed_mps", it.speedMps); put("h_acc_m", it.hAccM) }
        put("median_latency_ms", ctx.medianLatency())
        put("consecutive_failures", ctx.consecutiveFailures)
    }

    private fun cellJson(c: CellSample) = buildJsonObject {
        put("rat", c.rat); put("cell_id", c.cellId); put("pci", c.pci); put("earfcn_or_arfcn", c.earfcnOrArfcn); put("band", c.band)
        put("rsrp", c.rsrp); put("rsrq", c.rsrq); put("sinr", c.sinr); put("timing_advance", c.timingAdvance); put("subscription_id", c.subscriptionId)
    }

    private fun wifiJson(w: WifiConnectionSample) = buildJsonObject {
        put("ssid", w.ssid); put("bssid", w.bssid); put("rssi_dbm", w.rssiDbm); put("frequency_mhz", w.frequencyMhz); put("standard", w.standard)
    }

    // ------------------------------------------------------------------ location / GNSS

    private fun onLocation(l: LocationSample, out: MutableList<EventSample>) {
        val step = ctx.recordStep(l)
        val medianStep = Stats.median(ctx.stepDistances.toList())
        val jump = step != null && medianStep != null && ctx.stepDistances.size >= 5 &&
            step > th.gpsJumpFactor * medianStep && step > th.gpsJumpMinM
        step?.let { if (!jump) ctx.stepDistances.add(it) }
        val prevLoc = ctx.lastLocation
        if (!jump) { ctx.lastLocation = l; ctx.locations.add(ctx.nowMs, l) }
        val badAcc = (l.hAccM ?: 0.0) > th.gpsHAccM
        evaluateGps(l.stamp, out, badAcc || jump, buildJsonObject {
            put("h_acc_m", l.hAccM); put("jump_m", if (jump) step else null); put("median_step_m", medianStep)
            put("prev_lat", prevLoc?.lat); put("prev_lon", prevLoc?.lon)
        })
    }

    private fun onGnss(s: GnssSatelliteSample, out: MutableList<EventSample>) {
        val pt = ctx.pendingEpochT
        if (pt != null && pt != s.stamp.tMonoNs) flushGnss(s.stamp, out, force = true)
        ctx.pendingEpochT = s.stamp.tMonoNs
        ctx.pendingSats += s
    }

    private fun flushGnss(now: Stamp, out: MutableList<EventSample>, force: Boolean) {
        val pt = ctx.pendingEpochT ?: return
        // Satellites of one GnssStatus callback share a stamp. Samples from other collectors may
        // interleave with them in the merged stream, so only close the epoch once the stream has
        // clearly moved on (≥200 ms later) or a satellite with a different stamp arrives.
        if (!force && now.tMonoNs - pt < 200_000_000L) return
        val sats = ctx.pendingSats.toList()
        ctx.pendingSats.clear(); ctx.pendingEpochT = null
        if (sats.isEmpty()) return
        val used = sats.count { it.usedInFix }
        val top = sats.map { it.cn0DbHz }.sortedDescending().take(4)
        val epochMs = pt / 1_000_000L
        ctx.gnssEpochs.add(epochMs, GnssEpoch(epochMs, used, sats.size, if (top.isEmpty()) null else top.average()))
        if (now.tMonoNs == Long.MAX_VALUE) return
        evaluateGps(sats.first().stamp, out, used < th.gpsMinUsedSats, buildJsonObject { put("used_in_fix", used); put("visible", sats.size) })
    }

    private fun evaluateGps(stamp: Stamp, out: MutableList<EventSample>, bad: Boolean, detail: JsonObject) {
        if (bad) {
            ctx.gpsLastBadMs = ctx.nowMs
            if (!ctx.gpsDegraded) {
                ctx.gpsDegraded = true
                emit(out, stamp, EventType.GPS_DEGRADED, Severity.WARNING, null, detail, diagnose = false, spacingKey = EventType.GPS_DEGRADED)
            }
        } else if (ctx.gpsDegraded && ctx.nowMs - ctx.gpsLastBadMs >= (th.gpsRecoverS * 1000).toLong()) {
            ctx.gpsDegraded = false
        }
    }

    // ------------------------------------------------------------------ cellular

    private fun cellKey(c: CellSample) = "${c.subscriptionId ?: 0}:${c.rat}"

    private fun onServing(c: CellSample, out: MutableList<EventSample>) {
        if ((c.cellinfoAgeMs ?: 0) > th.cellStaleMs) return // cached CellInfo, not evidence of anything
        val key = cellKey(c)
        val prev = ctx.serving[key]
        val hist = ctx.servingHistory.getOrPut(key) { TimeWindow(120_000) }

        if (prev != null && prev.identity != c.identity && (c.cellId != null || c.pci != null)) {
            ctx.handoverTimes.add(ctx.nowMs, ctx.nowMs)
            ctx.cellChangeTimes.add(ctx.nowMs, key)
            ctx.neighbours.keys.filter { it.startsWith("$key|") }.forEach { ctx.neighbours.remove(it) }
            emit(out, c.stamp, EventType.HANDOVER, Severity.INFO, cellJson(prev), cellJson(c), diagnose = true)
        }

        // signal_drop: current vs. max within window, same cell or not
        val windowMs = (th.signalDropWindowS * 1000).toLong()
        val maxRecent = hist.since(ctx.nowMs, windowMs).mapNotNull { it.rsrp }.maxOrNull()
        ctx.serving[key] = c
        hist.add(ctx.nowMs, c)
        if (c.rsrp != null && maxRecent != null && maxRecent - c.rsrp > th.signalDropDb) {
            emit(out, c.stamp, EventType.SIGNAL_DROP, Severity.WARNING,
                buildJsonObject { put("rsrp", maxRecent) }, cellJson(c), spacingKey = "${EventType.SIGNAL_DROP}:$key")
        }
    }

    private fun onNeighbour(n: CellSample, out: MutableList<EventSample>) {
        if ((n.cellinfoAgeMs ?: 0) > th.cellStaleMs) return
        val key = cellKey(n)
        val serving = ctx.serving[key] ?: return
        val sr = serving.rsrp ?: return
        val nr = n.rsrp ?: return
        val nKey = "$key|${n.pci}@${n.earfcnOrArfcn}"
        val st = ctx.neighbours.getOrPut(nKey) { Context.NeighbourState(nr, ctx.nowMs) }
        st.rsrp = nr; st.lastSeenMs = ctx.nowMs
        if (nr - sr >= th.overtakeDb) {
            if (st.overtakeSinceMs == null || st.servingIdAtStart != serving.identity) {
                st.overtakeSinceMs = ctx.nowMs; st.servingIdAtStart = serving.identity; st.emitted = false
            } else if (!st.emitted && ctx.nowMs - st.overtakeSinceMs!! >= (th.overtakeHoldS * 1000).toLong()) {
                st.emitted = true
                ctx.overtakes.add(ctx.nowMs, "${n.pci}@${n.earfcnOrArfcn}")
                emit(out, n.stamp, EventType.NEIGHBOUR_OVERTAKE, Severity.WARNING, cellJson(serving), cellJson(n), diagnose = false)
            }
        } else {
            st.overtakeSinceMs = null; st.emitted = false
        }
    }

    private fun onRadio(r: RadioSample, out: MutableList<EventSample>) {
        val prev = ctx.radio[r.serviceId]
        ctx.radio[r.serviceId] = r
        if (prev != null && prev.rat != r.rat) {
            ctx.ratChanges.add(ctx.nowMs, prev.rat to r.rat)
            val down = Context.ratRank(r.rat) < Context.ratRank(prev.rat)
            emit(out, r.stamp, EventType.RAT_CHANGE, if (down) Severity.WARNING else Severity.INFO,
                buildJsonObject { put("rat", prev.rat); put("nr_state", prev.nrState); put("service_id", prev.serviceId) },
                buildJsonObject { put("rat", r.rat); put("nr_state", r.nrState); put("service_id", r.serviceId); put("downgrade", down) },
                diagnose = down)
        }
    }

    // ------------------------------------------------------------------ Wi-Fi

    private fun onWifi(w: WifiConnectionSample, out: MutableList<EventSample>) {
        val prev = ctx.wifi
        if (prev?.bssid != null && w.bssid != null && prev.bssid != w.bssid && prev.ssid == w.ssid) {
            emit(out, w.stamp, EventType.WIFI_ROAM, Severity.INFO, wifiJson(prev), wifiJson(w))
        }
        ctx.wifi = w
        ctx.wifiHistory.add(ctx.nowMs, w)
    }

    private fun onScan(s: WifiScanSample) {
        if (ctx.pendingScanId != null && ctx.pendingScanId != s.scanId) flushScan()
        ctx.pendingScanId = s.scanId
        ctx.pendingScan += s
    }

    private fun flushScan() {
        if (ctx.pendingScan.isEmpty()) return
        ctx.lastScan = ctx.pendingScan.toList()
        ctx.pendingScan.clear(); ctx.pendingScanId = null
    }

    // ------------------------------------------------------------------ path + probes

    private fun pathJson(p: NetworkPathSample) = buildJsonObject {
        put("status", p.status); put("primary_interface", p.primaryInterface); put("interfaces", p.interfaces); put("unsatisfied_reason", p.unsatisfiedReason)
    }

    private fun onPath(p: NetworkPathSample, out: MutableList<EventSample>) {
        val prev = ctx.path
        ctx.path = p
        ctx.pathHistory.add(ctx.nowMs, p)
        if (prev == null) return
        val prevPrimary = prev.primaryInterface
        if (prevPrimary != p.primaryInterface && setOf(prevPrimary, p.primaryInterface) == setOf("wifi", "cellular")) {
            emit(out, p.stamp, EventType.WIFI_CELL_SWITCH, Severity.INFO, pathJson(prev), pathJson(p))
        }
        if (p.unsatisfiedReason == "captive_portal" && prev.unsatisfiedReason != "captive_portal") {
            emit(out, p.stamp, EventType.CAPTIVE_PORTAL, Severity.WARNING, pathJson(prev), pathJson(p))
            return
        }
        if (prev.status == "satisfied" && p.status != "satisfied" && !ctx.dropped) {
            startDrop(out, p.stamp, "path_${p.unsatisfiedReason ?: p.status}", pathJson(prev), pathJson(p))
        }
    }

    private fun startDrop(out: MutableList<EventSample>, stamp: Stamp, reason: String, before: JsonObject?, after: JsonObject?) {
        ctx.dropped = true; ctx.dropStartMs = ctx.nowMs; ctx.dropReason = reason
        emit(out, stamp, EventType.DROP, Severity.CRITICAL, before,
            JsonObject((after ?: JsonObject(emptyMap())) + mapOf("trigger" to JsonPrimitive(reason))))
    }

    private fun onProbe(p: ProbeSample, out: MutableList<EventSample>) {
        when (p.probeType) {
            "latency" -> onLatency(p, out)
            "throughput" -> if (p.success && p.mbps != null) {
                val median = ctx.throughputMedian()
                if (median != null && ctx.throughput.size >= th.throughputMinSamples && p.mbps < median * (1 - th.throughputDropFraction)) {
                    emit(out, p.stamp, EventType.THROUGHPUT_DROP, Severity.WARNING,
                        buildJsonObject { put("median_mbps", median) }, buildJsonObject { put("mbps", p.mbps); put("interface", p.iface) },
                        spacingKey = EventType.THROUGHPUT_DROP)
                }
                ctx.throughput += p.mbps
            }
            "public_ip" -> if (p.success && p.publicIp != null) {
                val prev = ctx.lastPublicIp
                ctx.lastPublicIp = p
                if (prev?.publicIp != null && (prev.publicIp != p.publicIp || prev.asn != p.asn)) {
                    val asnChanged = prev.asn != p.asn
                    emit(out, p.stamp, EventType.IP_ASN_CHANGE, if (asnChanged) Severity.WARNING else Severity.INFO,
                        buildJsonObject { put("public_ip", prev.publicIp); put("asn", prev.asn); put("asn_org", prev.asnOrg) },
                        buildJsonObject { put("public_ip", p.publicIp); put("asn", p.asn); put("asn_org", p.asnOrg) },
                        extra = buildJsonObject { put("asn_changed", asnChanged); put("is_cellular", p.isCellular) })
                }
            }
            "captive" -> if (!p.success && p.error?.startsWith("http_") == true && p.error != "http_204") {
                emit(out, p.stamp, EventType.CAPTIVE_PORTAL, Severity.WARNING, null, buildJsonObject { put("error", p.error) }, spacingKey = EventType.CAPTIVE_PORTAL)
            }
        }
    }

    private fun onLatency(p: ProbeSample, out: MutableList<EventSample>) {
        ctx.latencyProbes.add(ctx.nowMs, p)
        if (!p.success) {
            ctx.consecutiveFailures++
            if (ctx.consecutiveFailures >= th.dropConsecutiveFailures && !ctx.dropped) {
                startDrop(out, p.stamp, "probe_failures", buildJsonObject { put("median_latency_ms", ctx.medianLatency()) },
                    buildJsonObject { put("consecutive_failures", ctx.consecutiveFailures); put("error", p.error); put("endpoint", p.endpoint) })
            }
            return
        }
        ctx.consecutiveFailures = 0
        if (ctx.dropped && ctx.path?.status != "unsatisfied" && ctx.path?.status != "requires_connection") {
            val outage = ctx.nowMs - ctx.dropStartMs
            ctx.dropped = false
            emit(out, p.stamp, EventType.RECONNECT, Severity.INFO,
                buildJsonObject { put("trigger", ctx.dropReason) },
                buildJsonObject { put("outage_ms", outage); put("interface", p.iface); put("total_ms", p.totalMs) }, diagnose = false)
            ctx.dropReason = null
        }
        val total = p.totalMs ?: return
        val median = ctx.medianLatency()
        if (median != null && ctx.latencyOk.size >= 5 && total > th.latencyFactor * median && total > th.latencyMinMs) {
            emit(out, p.stamp, EventType.LATENCY_SPIKE, Severity.WARNING,
                buildJsonObject { put("median_ms", median) },
                buildJsonObject { put("total_ms", total); put("dns_ms", p.dnsMs); put("connect_ms", p.connectMs); put("tls_ms", p.tlsMs); put("ttfb_ms", p.ttfbMs); put("endpoint", p.endpoint) },
                spacingKey = EventType.LATENCY_SPIKE)
        }
        p.dnsMs?.let { dns ->
            val md = ctx.medianDns()
            if (md != null && ctx.dnsOk.size >= 5 && dns > th.dnsFactor * md && dns > th.dnsMinMs) {
                emit(out, p.stamp, EventType.DNS_SPIKE, Severity.WARNING,
                    buildJsonObject { put("median_dns_ms", md) }, buildJsonObject { put("dns_ms", dns); put("endpoint", p.endpoint) },
                    spacingKey = EventType.DNS_SPIKE)
            }
            ctx.dnsOk.add(dns)
        }
        // window bounded by thresholds.latencyWindow
        ctx.latencyOk.add(total)
        while (ctx.latencyOk.size > th.latencyWindow) { /* Ring caps at 60; enforce configured window */
            val keep = ctx.latencyOk.toList().takeLast(th.latencyWindow); ctx.latencyOk.clear(); keep.forEach { ctx.latencyOk.add(it) }
        }
    }
}
