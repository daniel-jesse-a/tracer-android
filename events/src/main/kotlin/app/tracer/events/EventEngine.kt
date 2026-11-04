package app.tracer.events

import app.tracer.core.config.Thresholds
import app.tracer.core.model.*
import app.tracer.core.util.Ring
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
    /** v3: a RAT change that reverted within 2 s (5G→LTE→5G), reported once as info */
    const val RAT_FLICKER = "rat_flicker"
    /** v3: end of a gps_degraded episode */
    const val GPS_RECOVERED = "gps_recovered"

    val all = listOf(HANDOVER, RAT_CHANGE, WIFI_ROAM, WIFI_CELL_SWITCH, DROP, RECONNECT, CAPTIVE_PORTAL, IP_ASN_CHANGE,
        GPS_DEGRADED, LATENCY_SPIKE, DNS_SPIKE, THROUGHPUT_DROP, SIGNAL_DROP, NEIGHBOUR_OVERTAKE, RAT_FLICKER, GPS_RECOVERED)

    /** Event types that are wake artefacts inside a resume window: info severity + evidence after_resume. */
    val RESUME_SENSITIVE = setOf(LATENCY_SPIKE, DNS_SPIKE, RAT_CHANGE, RAT_FLICKER)
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
        // data silence ≥ 10 s (process frozen / doze / suspended): what follows is a resume, not the network
        if (s !is GapSample && s !is EventSample && s !is EventLabelSample) {
            val last = ctx.lastSampleMs
            if (last != null && t - last >= Context.RESUME_GAP_MS) startResume(t, t - last)
            if (last == null || t > last) ctx.lastSampleMs = t
        }
        if (t > ctx.nowMs) ctx.nowMs = t
        if (ctx.firstSampleMs == null) ctx.firstSampleMs = t
        val out = mutableListOf<EventSample>()
        flushRat(out, force = false)
        checkNoFix(s.stamp, out)
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
            is AndroidNetworkSample -> onAndroidNet(s, out)
            is GapSample -> onGap(s)
            else -> {}
        }
        drainPending(out, force = false)
        return out
    }

    // ------------------------------------------------------------------ resume windows

    /** An x_gap row ≥ 10 s (any reason): the 30 s after its end are a resume window. */
    private fun onGap(g: GapSample) {
        val dur = g.endUtcMs - g.startUtcMs
        if (dur < Context.RESUME_GAP_MS) return
        // gap end on the monotonic timeline, via the row's own clock pair
        val endMono = g.stamp.tMonoMs - (g.stamp.tUtcMs - g.endUtcMs)
        if (endMono + Context.RESUME_WINDOW_MS < ctx.nowMs) return // too old to matter
        val cur = ctx.resumeAtMs
        if (cur != null && kotlin.math.abs(cur - endMono) <= 5_000) {
            // the same gap as the silence already detected: keep its baselines, just make sure the window covers it
            ctx.resumeUntilMs = maxOf(ctx.resumeUntilMs, endMono + Context.RESUME_WINDOW_MS)
            ctx.resumeGapMs = maxOf(ctx.resumeGapMs, dur)
        } else startResume(endMono, dur)
    }

    private fun startResume(atMs: Long, gapMs: Long) {
        ctx.resumeAtMs = atMs
        ctx.resumeUntilMs = atMs + Context.RESUME_WINDOW_MS
        ctx.resumeGapMs = gapMs
        // rolling medians / baselines restart: the pre-gap baseline no longer describes the network,
        // and the first post-wake samples must not become it either
        ctx.paths.values.forEach { it.resetBaselines() }
        ctx.servingHistory.clear()
        ctx.stepDistances.clear()
    }

    /** Flush pending epoch/scan at end of a replay. */
    fun finish(): List<EventSample> {
        val out = mutableListOf<EventSample>()
        flushGnss(Stamp(0, Long.MAX_VALUE), out, force = true)
        flushScan()
        flushRat(out, force = true)
        drainPending(out, force = true)
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

    /**
     * Events are detected immediately but *diagnosed* once the stream has reached T + 2 s, so cause
     * attribution can use signals on both sides of the event (T ± 2 s). Output is therefore ≤ ~2 s late.
     */
    private class Pending(val tMs: Long, val stamp: Stamp, val type: String, val severity: String, val before: JsonObject?,
                          val after: JsonObject?, val diagnose: Boolean, val extra: JsonObject, val lat: Double?, val lon: Double?,
                          val delayMs: Long, val afterResume: Boolean)

    private val pending = ArrayList<Pending>()

    @Suppress("UNUSED_PARAMETER")
    private fun emit(
        out: MutableList<EventSample>, stamp: Stamp, type: String, severity: String,
        before: JsonObject?, after: JsonObject?, diagnose: Boolean = true, spacingKey: String? = null, extra: JsonObject = JsonObject(emptyMap()),
        delayMs: Long = ctx.nearMs,
    ): Boolean {
        if (spacingKey != null) { if (!spaced(spacingKey)) return false; ctx.lastEmit[spacingKey] = ctx.nowMs }
        val ll = ctx.lastLatLon()
        val afterResume = type in EventType.RESUME_SENSITIVE && ctx.inResume(stamp.tMonoMs)
        val sev = if (afterResume) Severity.INFO else severity
        val ex = if (afterResume) JsonObject(extra + ("after_resume" to JsonPrimitive(true))) else extra
        pending += Pending(stamp.tMonoMs, Stamp(stamp.tUtcMs, stamp.tMonoNs), type, sev, before, after, diagnose, ex, ll?.first, ll?.second,
            maxOf(delayMs, ctx.nearMs), afterResume)
        return true
    }

    private fun drainPending(out: MutableList<EventSample>, force: Boolean) {
        if (pending.isEmpty()) return
        val ready = pending.filter { force || ctx.nowMs - it.tMs >= it.delayMs }.sortedBy { it.tMs }
        if (ready.isEmpty()) return
        pending.removeAll(ready.toSet())
        for (p in ready) {
            val dx = if (p.diagnose) causes.diagnose(p.type, ctx, p.tMs, p.extra) else null
            // every event records the evidence window actually used (window_s) and whether it followed a resume
            val evidence = JsonObject(
                (dx?.evidence(causes.snapshot(ctx, p.tMs), ctx.nearMs) ?: buildJsonObject { put("window_s", ctx.nearMs / 1000.0) }) +
                    buildJsonObject {
                        if (p.afterResume) { put("after_resume", true); put("resume_gap_s", ctx.resumeGapMs / 1000.0) }
                        p.extra["dns_retry_timer"]?.let { put("dns_retry_timer", it) }
                    },
            )
            out += EventSample(
                runId = runId, stamp = p.stamp, eventType = p.type, severity = p.severity,
                beforeJson = p.before?.toString(), afterJson = p.after?.toString(), lat = p.lat, lon = p.lon,
                likelyCause = dx?.best?.cause, causeConfidence = dx?.best?.confidence,
                evidenceJson = evidence.toString(),
            )
        }
    }

    private fun cellJson(c: CellSample) = buildJsonObject {
        put("rat", c.rat); put("cell_id", c.cellId); put("pci", c.pci); put("earfcn_or_arfcn", c.earfcnOrArfcn); put("band", c.band)
        put("rsrp", c.rsrp); put("rsrq", c.rsrq); put("sinr", c.sinr); put("timing_advance", c.timingAdvance); put("subscription_id", c.subscriptionId)
    }

    private fun wifiJson(w: WifiConnectionSample) = buildJsonObject {
        put("ssid", w.ssid); put("bssid", w.bssid); put("rssi_dbm", w.rssiDbm); put("frequency_mhz", w.frequencyMhz); put("standard", w.standard)
    }

    // ------------------------------------------------------------------ location / GNSS

    /** No raw GNSS fix for gps_no_fix_s since start / last fix → gps_degraded(no_fix), once per outage. */
    private fun checkNoFix(stamp: Stamp, out: MutableList<EventSample>) {
        // a recording gap is not "no fix": count from the resume
        val since = maxOf(ctx.lastGpsFixMs ?: ctx.firstSampleMs ?: return, ctx.resumeAtMs ?: Long.MIN_VALUE)
        if (!ctx.noFixReported && ctx.nowMs - since >= (th.gpsNoFixS * 1000).toLong()) {
            ctx.noFixReported = true
            ctx.gpsLastBadMs = ctx.nowMs
            if (!ctx.gpsDegraded) {
                enterGpsDegraded(stamp, out, buildJsonObject {
                    put("reason", "no_fix"); put("seconds_without_fix", (ctx.nowMs - since) / 1000)
                    put("fallback_provider", ctx.lastLocation?.provider)
                })
            }
        }
    }

    private fun onLocation(l: LocationSample, out: MutableList<EventSample>) {
        if (l.provider != "gps") {
            // Fused/network fixes only position events when raw GPS is absent (indoors); they never
            // drive GNSS-quality rules.
            val lastGps = ctx.lastGpsFixMs
            if (lastGps == null || ctx.nowMs - lastGps > 5_000) { ctx.lastLocation = l; ctx.locations.add(ctx.nowMs, l) }
            return
        }
        ctx.lastGpsFixMs = ctx.nowMs
        ctx.noFixReported = false
        val step = ctx.recordStep(l)
        val medianStep = Stats.median(ctx.stepDistances.toList())
        val jump = step != null && medianStep != null && ctx.stepDistances.size >= 5 &&
            step > th.gpsJumpFactor * medianStep && step > th.gpsJumpMinM
        step?.let { if (!jump) ctx.stepDistances.add(it) }
        val prevLoc = ctx.lastLocation
        if (!jump) { ctx.lastLocation = l; ctx.locations.add(ctx.nowMs, l) }
        l.hAccM?.let { ctx.gpsHAcc.add(ctx.nowMs, it) }
        val badAcc = (l.hAccM ?: 0.0) > th.gpsHAccM
        evaluateGps(l.stamp, out, badAcc || jump, buildJsonObject {
            put("reason", if (badAcc) "accuracy" else if (jump) "jump" else null)
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

    /**
     * GPS state machine: one gps_degraded on entering the degraded state, one gps_recovered (info)
     * after gps_recover_s without a bad fix/epoch. Nothing re-fires while it stays degraded, however
     * long (iOS logged repeated events for 86 min at ~5.7 km accuracy). The baseline reported is the
     * median h_acc of *all* raw GPS fixes in the last 60 s, so it follows the real state instead of
     * freezing at the last good value.
     */
    private fun evaluateGps(stamp: Stamp, out: MutableList<EventSample>, bad: Boolean, detail: JsonObject) {
        if (bad) {
            ctx.gpsLastBadMs = ctx.nowMs
            if (!ctx.gpsDegraded) enterGpsDegraded(stamp, out, detail)
        } else if (ctx.gpsDegraded && ctx.nowMs - ctx.gpsLastBadMs >= (th.gpsRecoverS * 1000).toLong()) {
            ctx.gpsDegraded = false
            if (ctx.gpsDegradedEmitted) {
                ctx.gpsDegradedEmitted = false
                emit(out, stamp, EventType.GPS_RECOVERED, Severity.INFO, null, buildJsonObject {
                    put("degraded_s", (ctx.nowMs - ctx.gpsDegradedSinceMs) / 1000)
                    put("h_acc_m", ctx.lastLocation?.hAccM); put("baseline_h_acc_m", ctx.gpsHAccBaseline())
                }, diagnose = false)
            }
        }
    }

    private fun enterGpsDegraded(stamp: Stamp, out: MutableList<EventSample>, detail: JsonObject) {
        ctx.gpsDegraded = true
        ctx.gpsDegradedSinceMs = ctx.nowMs
        val withBaseline = JsonObject(detail + buildJsonObject {
            put("baseline_h_acc_m", ctx.gpsHAccBaseline()); put("threshold_h_acc_m", th.gpsHAccM)
        })
        ctx.gpsDegradedEmitted = emit(out, stamp, EventType.GPS_DEGRADED, Severity.WARNING, null, withBaseline,
            diagnose = false, spacingKey = EventType.GPS_DEGRADED)
    }

    // ------------------------------------------------------------------ cellular

    private fun cellKey(c: CellSample) = "${c.subscriptionId ?: 0}:${c.rat}"

    private fun onServing(c: CellSample, out: MutableList<EventSample>) {
        if ((c.cellinfoAgeMs ?: 0) > th.cellStaleMs) return // cached CellInfo, not evidence of anything
        val key = cellKey(c)
        // Only the first serving row of a poll (same stamp) is the PCell; the collector emits it first.
        // Further "serving" rows in that poll (CA SCells, modem quirks) must not look like handovers.
        if (ctx.servingPollStamp[key] == c.stamp.tMonoNs) return
        ctx.servingPollStamp[key] = c.stamp.tMonoNs
        val prev = ctx.serving[key]
        val hist = ctx.servingHistoryOf(key)

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
        val rsrpNow = c.rsrp
        if (rsrpNow != null && maxRecent != null && maxRecent - rsrpNow > th.signalDropDb) {
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

    /**
     * RAT changes are debounced: a change is held for [Context.RAT_FLICKER_MS]; if the radio returns to
     * the previous RAT within that time the pair is one info `rat_flicker`. A change that stands becomes
     * `rat_change`; downgrades are diagnosed only once [Context.RAT_SUSTAIN_MS] has been observed so
     * tunnel_underpass can require a sustained downgrade.
     */
    private fun onRadio(r: RadioSample, out: MutableList<EventSample>) {
        val prev = ctx.radio[r.serviceId]
        ctx.radio[r.serviceId] = r
        ctx.radioHistory.add(ctx.nowMs, r)
        if (prev == null || prev.rat == r.rat) return
        val pend = ctx.pendingRat.remove(r.serviceId)
        if (pend != null) {
            if (r.rat == pend.from.rat && ctx.nowMs - pend.tMs <= Context.RAT_FLICKER_MS) {
                emit(out, pend.stamp, EventType.RAT_FLICKER, Severity.INFO,
                    buildJsonObject { put("rat", pend.from.rat); put("nr_state", pend.from.nrState); put("service_id", pend.from.serviceId) },
                    buildJsonObject { put("rat", pend.to.rat); put("nr_state", pend.to.nrState); put("reverted_after_ms", ctx.nowMs - pend.tMs) },
                    diagnose = false, spacingKey = "${EventType.RAT_FLICKER}:${r.serviceId}")
                return
            }
            emitRatChange(pend, out) // a further change: the held one stood
        }
        ctx.pendingRat[r.serviceId] = Context.PendingRat(r.stamp, ctx.nowMs, prev, r)
    }

    private fun flushRat(out: MutableList<EventSample>, force: Boolean) {
        if (ctx.pendingRat.isEmpty()) return
        val due = ctx.pendingRat.filter { (_, p) -> force || ctx.nowMs - p.tMs > Context.RAT_FLICKER_MS }
        due.forEach { (k, p) -> ctx.pendingRat.remove(k); emitRatChange(p, out) }
    }

    private fun emitRatChange(p: Context.PendingRat, out: MutableList<EventSample>) {
        val prev = p.from; val r = p.to
        ctx.ratChanges.add(p.tMs, prev.rat to r.rat)
        val down = Context.ratRank(r.rat) < Context.ratRank(prev.rat)
        emit(out, p.stamp, EventType.RAT_CHANGE, if (down) Severity.WARNING else Severity.INFO,
            buildJsonObject { put("rat", prev.rat); put("nr_state", prev.nrState); put("service_id", prev.serviceId) },
            buildJsonObject { put("rat", r.rat); put("nr_state", r.nrState); put("service_id", r.serviceId); put("downgrade", down) },
            diagnose = down, delayMs = if (down) Context.RAT_SUSTAIN_MS + 500 else ctx.nearMs)
    }

    // ------------------------------------------------------------------ Wi-Fi

    private fun onWifi(w: WifiConnectionSample, out: MutableList<EventSample>) {
        val prev = ctx.wifi
        if (prev?.bssid != null && w.bssid != null && prev.bssid != w.bssid && prev.ssid == w.ssid) {
            emit(out, w.stamp, EventType.WIFI_ROAM, Severity.INFO, wifiJson(prev), wifiJson(w))
            ctx.roamTimes.add(ctx.nowMs, w.bssid ?: "")
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
        ctx.scanHistory.add(ctx.pendingScan.first().stamp.tMonoMs, ctx.lastScan)
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
        val primary = p.primaryInterface?.takeIf { it != "none" }
        val lastPrimary = ctx.lastPrimary
        if (primary != null) ctx.lastPrimary = primary
        if (prev == null) return
        // wifi ↔ cellular, also when the switch passes through an "unsatisfied / none" instant
        if (primary != null && lastPrimary != null && primary != lastPrimary && setOf(lastPrimary, primary) == setOf("wifi", "cellular")) {
            emit(out, p.stamp, EventType.WIFI_CELL_SWITCH, Severity.INFO,
                buildJsonObject { put("primary_interface", lastPrimary) }, pathJson(p), extra = pathExtra(lastPrimary))
        }
        if (p.unsatisfiedReason == "captive_portal" && prev.unsatisfiedReason != "captive_portal") {
            emit(out, p.stamp, EventType.CAPTIVE_PORTAL, Severity.WARNING, pathJson(prev), pathJson(p))
            return
        }
        val path = lastPrimary ?: ctx.defaultPath()
        val ps = ctx.pathState(path)
        if (prev.status == "satisfied" && p.status != "satisfied" && !ps.dropped) {
            startDrop(out, p.stamp, path, "path_${p.unsatisfiedReason ?: p.status}", pathJson(prev), pathJson(p))
        } else if (p.status == "satisfied" && primary != null && primary != path) {
            // default network moved to another transport: the old path's outage is not "ongoing" for the user
            val old = ctx.pathState(path)
            if (old.dropped) endDrop(out, p.stamp, path, old, primary)
        }
    }

    /** Android's validation / captive flags (x_android_network): the only source of captive_portal events besides path. */
    private fun onAndroidNet(n: AndroidNetworkSample, out: MutableList<EventSample>) {
        val prev = ctx.androidNet.last()
        ctx.androidNet.add(ctx.nowMs, n)
        if (n.captivePortal == true && prev?.captivePortal != true && ctx.path?.unsatisfiedReason != "captive_portal") {
            val path = SampleCodecIface.pathOf(n)
            emit(out, n.stamp, EventType.CAPTIVE_PORTAL, Severity.WARNING, null,
                buildJsonObject { put("android_captive_flag", true); put("validated", n.validated); put("path", path) },
                spacingKey = EventType.CAPTIVE_PORTAL, extra = pathExtra(path))
        }
    }

    private fun pathExtra(path: String) = buildJsonObject { put("path", path) }

    private fun startDrop(out: MutableList<EventSample>, stamp: Stamp, path: String, reason: String, before: JsonObject?, after: JsonObject?) {
        val ps = ctx.pathState(path)
        ps.dropped = true; ps.dropStartMs = ctx.nowMs; ps.dropReason = reason
        // If another transport is carrying the default network, the user stayed online: that is a
        // path outage with failover (warning), not a connectivity drop (critical).
        val failover = ctx.defaultPath().takeIf { it != path && ctx.path?.status == "satisfied" }
        val extraFields = mutableMapOf<String, JsonElement>("trigger" to JsonPrimitive(reason), "path" to JsonPrimitive(path))
        failover?.let { extraFields["failover"] = JsonPrimitive(it) }
        emit(out, stamp, EventType.DROP, if (failover != null) Severity.WARNING else Severity.CRITICAL, before,
            JsonObject((after ?: JsonObject(emptyMap())) + extraFields),
            extra = pathExtra(path))
    }

    private fun endDrop(out: MutableList<EventSample>, stamp: Stamp, path: String, ps: Context.PathState, via: String?) {
        val outage = ctx.nowMs - ps.dropStartMs
        ps.dropped = false
        emit(out, stamp, EventType.RECONNECT, Severity.INFO,
            buildJsonObject { put("trigger", ps.dropReason); put("path", path) },
            buildJsonObject { put("outage_ms", outage); put("interface", via) }, diagnose = false)
        ps.dropReason = null
        ps.consecutiveFailures = 0; ps.failingEndpoints.clear()
    }

    private fun onProbe(p: ProbeSample, out: MutableList<EventSample>) {
        val path = ctx.pathOf(p)
        val ps = ctx.pathState(path)
        when (p.probeType) {
            "latency" -> onLatency(p, path, ps, out)
            "throughput" -> {
                val mbps = p.mbps
                if (p.success && mbps != null) {
                    val median = ps.throughputMedian()
                    if (median != null && ps.throughput.size >= th.throughputMinSamples && mbps < median * (1 - th.throughputDropFraction)) {
                        emit(out, p.stamp, EventType.THROUGHPUT_DROP, Severity.WARNING,
                            buildJsonObject { put("median_mbps", median); put("path", path) }, buildJsonObject { put("mbps", mbps); put("interface", p.iface) },
                            spacingKey = "${EventType.THROUGHPUT_DROP}:$path", extra = pathExtra(path))
                    }
                    ps.throughput.add(mbps)
                }
            }
            "public_ip" -> if (p.success && p.publicIp != null) {
                val prev = ctx.lastPublicIp
                ctx.lastPublicIp = p
                if (prev?.publicIp != null && prev.isCellular == p.isCellular &&
                    (prev.publicIp != p.publicIp || (prev.asn != null && p.asn != null && prev.asn != p.asn))) {
                    val asnChanged = prev.asn != null && p.asn != null && prev.asn != p.asn
                    emit(out, p.stamp, EventType.IP_ASN_CHANGE, if (asnChanged) Severity.WARNING else Severity.INFO,
                        buildJsonObject { put("public_ip", prev.publicIp); put("asn", prev.asn); put("asn_org", prev.asnOrg) },
                        buildJsonObject { put("public_ip", p.publicIp); put("asn", p.asn); put("asn_org", p.asnOrg) },
                        extra = buildJsonObject { put("asn_changed", asnChanged); put("is_cellular", p.isCellular); put("path", path) })
                }
            }
            // The captive check is evidence only; captive_portal events come from Android's own flags.
            "captive" -> {}
            "gateway", "dns_server" -> ctx.internalProbes.add(ctx.nowMs, p)
        }
    }

    /** RTT proxy: TCP connect time (one round trip). Falls back to the whole call when phases are missing. */
    private fun rttOf(p: ProbeSample): Double? = p.connectMs?.takeIf { it > 0 } ?: p.totalMs

    private fun onLatency(p: ProbeSample, path: String, ps: Context.PathState, out: MutableList<EventSample>) {
        ctx.latencyProbes.add(ctx.nowMs, p)
        ctx.endpointsSeen += p.endpoint
        if (!p.success) {
            ps.consecutiveFailures++
            ps.failingEndpoints += p.endpoint
            // A drop needs consecutive failures across ≥ 2 endpoints — one broken endpoint is not an outage.
            val endpointsNeeded = minOf(2, ctx.distinctEndpoints)
            if (ps.consecutiveFailures >= th.dropConsecutiveFailures && ps.failingEndpoints.size >= endpointsNeeded && !ps.dropped) {
                startDrop(out, p.stamp, path, "probe_failures", buildJsonObject { put("median_rtt_ms", ps.medianLatency()) },
                    buildJsonObject {
                        put("consecutive_failures", ps.consecutiveFailures); put("error", p.error); put("endpoint", p.endpoint)
                        put("endpoints_failing", ps.failingEndpoints.size)
                    })
            }
            return
        }
        ps.consecutiveFailures = 0
        ps.failingEndpoints.clear()
        val pathUp = path != ctx.defaultPath() || (ctx.path?.status != "unsatisfied" && ctx.path?.status != "requires_connection")
        if (ps.dropped && pathUp) endDrop(out, p.stamp, path, ps, p.iface)
        val rtt = rttOf(p) ?: return
        val median = ps.medianLatency()
        // Baseline per (path, endpoint), bimodal-aware: endpoints often answer from two groups
        // (cached edge vs origin, 5G vs LTE). A spike is outside both: above max(p90 × 1.5, p90 + latency_min_ms)
        // of that endpoint's own window, after ≥ 10 samples.
        val win = ps.latencyByEndpoint.getOrPut(p.endpoint) { Ring(maxOf(th.latencyWindow, 10)) }
        val p90 = if (win.size >= LATENCY_MIN_SAMPLES) Stats.percentile(win.toList(), 90.0) else null
        val limit = p90?.let { maxOf(it * 1.5, it + th.latencyMinMs) }
        win.add(rtt)
        if (limit != null && rtt > limit) {
            emit(out, p.stamp, EventType.LATENCY_SPIKE, Severity.WARNING,
                buildJsonObject {
                    put("median_rtt_ms", median); put("path", path)
                    put("endpoint_p90_ms", p90); put("limit_ms", limit); put("endpoint_samples", win.size - 1)
                },
                buildJsonObject {
                    put("rtt_ms", rtt); put("total_ms", p.totalMs); put("dns_ms", p.dnsMs); put("connect_ms", p.connectMs)
                    put("tls_ms", p.tlsMs); put("ttfb_ms", p.ttfbMs); put("endpoint", p.endpoint)
                },
                spacingKey = "${EventType.LATENCY_SPIKE}:$path", extra = pathExtra(path))
        }
        p.dnsMs?.let { dns ->
            // first real lookup after ≥ 20 s without one on this path (radio idle): ~2 s / ~5 s answers are
            // the resolver's retransmission timer after the first query was lost while the radio woke
            val lastLookup = ps.lastDnsLookupMs
            val idleMs = if (lastLookup != null) ctx.nowMs - lastLookup else ctx.firstSampleMs?.let { ctx.nowMs - it }
            if (dns > 1.0) ps.lastDnsLookupMs = ctx.nowMs
            val retryTimer = dns > 1.0 && idleMs != null && idleMs >= Context.DNS_IDLE_MS && Context.isDnsRetryTimer(dns)
            val md = ps.medianDns()
            if (md != null && ps.dnsOk.size >= 5 && dns > th.dnsFactor * md && dns > th.dnsMinMs) {
                val extra = if (retryTimer) JsonObject(pathExtra(path) + buildJsonObject {
                    put("dns_retry_timer", true); put("dns_ms", dns); put("dns_idle_ms", idleMs)
                }) else pathExtra(path)
                emit(out, p.stamp, EventType.DNS_SPIKE, if (retryTimer) Severity.INFO else Severity.WARNING,
                    buildJsonObject { put("median_dns_ms", md); put("path", path) }, buildJsonObject { put("dns_ms", dns); put("endpoint", p.endpoint) },
                    spacingKey = "${EventType.DNS_SPIKE}:$path", extra = extra)
            }
            // retry-timer answers say nothing about the resolver, and cached answers (≤ 1 ms) were no
            // lookup at all: keep both out of the baseline
            if (!retryTimer && dns > 1.0) ps.dnsOk.add(dns)
        }
        ps.latencyOk.add(rtt)
        if (ps.latencyOk.size > th.latencyWindow) {
            val keep = ps.latencyOk.toList().takeLast(th.latencyWindow); ps.latencyOk.clear(); keep.forEach { ps.latencyOk.add(it) }
        }
    }
}

private const val LATENCY_MIN_SAMPLES = 10

private object SampleCodecIface {
    fun pathOf(n: AndroidNetworkSample): String =
        if (n.transports?.contains("cellular", ignoreCase = true) == true || n.iface == "cellular") "cellular" else "wifi"
}
