package app.tracer.core.report

import app.tracer.core.analysis.CoverageTracker
import app.tracer.core.analysis.MovementTracker
import app.tracer.core.model.*
import app.tracer.core.util.Stats
import kotlinx.serialization.json.*
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Plain-language test report (design v2: "Tracer — iOS & Android", consumer screens).
 *
 * Everything the Tests list, Test summary, Diagnostics (Simple/Detailed), Moment detail, the
 * Test-N-vs-Test-M card and the PDF report show is computed here, in one streaming pass over a
 * run's tables, so a 2-hour run never needs its tables in memory. The result is small (series are
 * downsampled to ≤ [MAX_POINTS]) and round-trips through JSON so the UI can cache it per run.
 */

/** Five-step verdict. Meter = [segments] lit of 5, in the verdict colour. */
enum class Verdict(val label: String, val segments: Int) {
    BAD("Bad", 1), BELOW_AVG("Below avg", 2), AVERAGE("Average", 3), ABOVE_AVG("Above avg", 4), VERY_GOOD("Very good", 5),
    NONE("No data", 0);

    fun down(): Verdict = if (this == NONE || this == BAD) this else entries[ordinal - 1]

    companion object {
        private fun hi(v: Double?, vg: Double, aa: Double, av: Double, ba: Double): Verdict = when {
            v == null || v.isNaN() -> NONE
            v >= vg -> VERY_GOOD; v >= aa -> ABOVE_AVG; v >= av -> AVERAGE; v >= ba -> BELOW_AVG; else -> BAD
        }
        private fun lo(v: Double?, vg: Double, aa: Double, av: Double, ba: Double): Verdict = when {
            v == null || v.isNaN() -> NONE
            v <= vg -> VERY_GOOD; v <= aa -> ABOVE_AVG; v <= av -> AVERAGE; v <= ba -> BELOW_AVG; else -> BAD
        }
        /** Wi-Fi RSSI dBm: > −55 / −65 / −72 / −80. */
        fun wifiRssi(dbm: Double?) = hi(dbm, -55.0, -65.0, -72.0, -80.0)
        /** Mobile RSRP dBm: > −80 / −90 / −100 / −110. */
        fun rsrp(dbm: Double?) = hi(dbm, -80.0, -90.0, -100.0, -110.0)
        /** SINR dB: ≥ 20 / 13 / 5 / 0. */
        fun sinr(db: Double?) = hi(db, 20.0, 13.0, 5.0, 0.0)
        /** Response (probe round trip: TCP connect, else total) ms: ≤ 50 / 150 / 300 / 600. */
        fun response(ms: Double?) = lo(ms, 50.0, 150.0, 300.0, 600.0)
        /** Download Mbps: ≥ 100 / 25 / 10 / 2. */
        fun speed(mbps: Double?) = hi(mbps, 100.0, 25.0, 10.0, 2.0)
        /** Position accuracy m: ≤ 5 / 8 / 20 / 50. */
        fun position(m: Double?) = lo(m, 5.0, 8.0, 20.0, 50.0)
        /** Online share %: ≥ 99.5 / 98 / 95 / 90. */
        fun online(pct: Double?) = hi(pct, 99.5, 98.0, 95.0, 90.0)
        /** Checks passed %: ≥ 98 / 90 / 75 / 40. */
        fun checks(pct: Double?) = hi(pct, 98.0, 90.0, 75.0, 40.0)
        /** Network switches (path changes + AP changes): 0 / 1 / ≤3 / ≤6. */
        fun stability(switches: Int?) = lo(switches?.toDouble(), 0.0, 1.0, 3.0, 6.0)
        /** Clock gap / link RTT to a companion ms: ≤ 20 / 50 / 100 / 250. */
        fun link(ms: Double?) = lo(ms, 20.0, 50.0, 100.0, 250.0)
    }
}

/** Which network carried traffic. */
enum class Carrier { WIFI, MOBILE, NONE;
    val label get() = when (this) { WIFI -> "Wi-Fi"; MOBILE -> "Mobile"; NONE -> "Offline" }
    companion object {
        fun of(primary: String?, status: String?): Carrier = when {
            status != null && status != "satisfied" -> NONE
            primary == "wifi" || primary == "ethernet" -> WIFI
            primary == "cellular" -> MOBILE
            else -> NONE
        }
    }
}

/** One stretch of the network timeline, seconds from start. */
data class Segment(val fromS: Double, val toS: Double, val carrier: Carrier)

/** A point on a chart, seconds from start. */
data class Pt(val tS: Double, val v: Double)

data class SpeedTest(val tS: Double, val carrier: Carrier, val mbps: Double)

/** Plain-language moment for the summary list and Moment detail. */
data class Moment(
    val tS: Double,
    /** event_type of the source event, or "path" for a derived path change */
    val kind: String,
    val title: String,
    val severity: String,
    val eventMonoNs: Long,
    /** how long the condition lasted (drop → reconnect, switch → switch back), if known */
    val lastedS: Double? = null,
    val stayedOnline: Boolean = true,
    val before: Carrier? = null,
    val after: Carrier? = null,
    val causeKey: String? = null,
    val causeTitle: String? = null,
    val causeDetail: String? = null,
    /** rowid of the source event (null for derived moments) */
    val eventId: Long? = null,
    /** user feedback label ("yes" or "no:<corrected cause>") */
    val feedback: String? = null,
    /** v3: the source event fell in the 30 s after a recording gap (wake artefact); not counted in the headline */
    val afterResume: Boolean = false,
)

/** Latest integrity check per kind + target + path (DNS vs DoH, TLS certificate, first hop). */
data class IntegrityResult(val kind: String, val path: String?, val target: String, val ok: Boolean?, val value: String?)

/** Moment feedback ("Is this right? Yes / No, change") stored in x_event_label. */
object Feedback {
    const val YES = "yes"
    fun no(correctedCause: String?) = if (correctedCause.isNullOrBlank()) "no" else "no:$correctedCause"
    fun isNo(label: String) = label == "no" || label.startsWith("no:") || label == "false" || label == "wrong"
    fun corrected(label: String?) = label?.takeIf { it.startsWith("no:") }?.removePrefix("no:")
    /** Causes offered after "No, change". */
    val CHOICES = listOf("sticky_client", "wifi_weak_signal", "channel_congestion", "wifi_uplink", "captive_portal",
        "dns_issue", "cell_edge", "congestion_backhaul", "tunnel_underpass", "device_or_app", "not_a_problem")
}

data class TestReport(
    val runId: String,
    val title: String,
    val durationS: Double,
    val onlinePct: Double?,
    /** seconds carried by mobile data while Wi-Fi was the expected network */
    val mobileCoveredS: Double,
    val offlineS: Double,
    val switches: Int,
    val pathSwitches: Int,
    val apChanges: Int,
    val wifiSpeedMedian: Double?, val wifiSpeedMax: Double?,
    val mobileSpeedMedian: Double?, val mobileSpeedMax: Double?,
    /** worst per-path median response (the "≤ 136 ms" headline) */
    val responseMs: Double?,
    val responseWifiMs: Double?, val responseMobileMs: Double?,
    val responseMobileMinMs: Double?, val responseMobileMaxMs: Double?,
    val wifiRssiMedian: Double?, val wifiRssiWorst: Double?,
    val rsrpMedian: Double?, val sinrMedian: Double?,
    val mobileTech: String?, val mobileBand: String?, val nrSeen: Boolean,
    val positionAccM: Double?, val positionSource: String?, val positions: Int, val gpsFixes: Int, val satellitesMax: Int?,
    val checksPassedPct: Double?, val checks: Int, val falseDrops: Int,
    val onSchedulePct: Double?,
    val movement: Map<String, Double>,
    val segments: List<Segment>,
    val moments: List<Moment>,
    val wifiRssi: List<Pt>,
    val newApAtS: List<Double>,
    val responseWifi: List<Pt>, val responseMobile: List<Pt>,
    val speedTests: List<SpeedTest>,
    val speedTestSeconds: Int?,
    val methodFingerprint: String?,
    val deviceModel: String,
    val appVersion: String,
    val startedAt: Long,
    // ---- v3 (ReportJson v2)
    /** any Wi-Fi segment or Wi-Fi probe; false → the UI shows "Not tested" instead of a verdict */
    val wifiTested: Boolean = true,
    /** any mobile-data segment or cellular probe */
    val mobileTested: Boolean = true,
    /** observed track extent, east-west × north-south metres (fixes with h_acc > 50 m skipped); null = no usable fixes */
    val bboxM: Pair<Double, Double>? = null,
    /** observed path length, m (anchor-based, jitter-free) */
    val pathM: Double? = null,
    /** bbox diagonal < 150 m; null = no usable fixes */
    val stationary: Boolean? = null,
    /** "Stayed within 110 × 75 m" when the mode was walk/drive/rail but the phone did not really move */
    val movementNote: String? = null,
    /** recorded seconds: duration − pauses − gaps (x_gap rows + stalls > 15 s, merged) */
    val capturedS: Double? = null,
    /** captured ÷ (duration − paused) × 100 */
    val coveragePct: Double? = null,
    val gaps: Int = 0,
    val longestGapS: Double = 0.0,
    /** edge PoPs that served Wi-Fi / mobile probes, most used first: "DXB ×120" */
    val edgesWifi: List<String> = emptyList(),
    val edgesMobile: List<String> = emptyList(),
    val integrity: List<IntegrityResult> = emptyList(),
) {
    /** < 5 min: too short to compare against other tests */
    val shortTest: Boolean get() = durationS < SHORT_TEST_S

    val onlineVerdict get() = Verdict.online(onlinePct)
    val wifiVerdict get() = Verdict.wifiRssi(wifiRssiMedian)
    val mobileVerdict get() = Verdict.rsrp(rsrpMedian)
    val responseVerdict get() = Verdict.response(responseMs)
    val wifiSpeedVerdict get() = Verdict.speed(wifiSpeedMedian ?: wifiSpeedMax)
    val mobileSpeedVerdict get() = Verdict.speed(mobileSpeedMedian ?: mobileSpeedMax)
    val stabilityVerdict get() = Verdict.stability(switches)
    val positionVerdict get() = Verdict.position(positionAccM)

    /** Test quality: checks passed, one step down when probes ran far off schedule. */
    val qualityVerdict: Verdict get() {
        val v = Verdict.checks(checksPassedPct)
        return if (onSchedulePct != null && onSchedulePct < 40.0) v.down() else v
    }

    /** Tests-list verdict: the online verdict, unless the test itself was unreliable. */
    val overallVerdict: Verdict get() {
        val q = qualityVerdict
        return if (q == Verdict.BAD || q == Verdict.BELOW_AVG) q else onlineVerdict
    }

    /** "Stayed online" / "Mostly online" / "Offline 2 times". */
    val headline: String get() {
        val offlineMoments = moments.count { !it.stayedOnline && !it.afterResume }
        return when {
            onlinePct == null -> "No connection data"
            offlineMoments == 0 && onlinePct >= 99.5 -> "Stayed online"
            offlineMoments <= 1 && onlinePct >= 95 -> "Mostly online"
            else -> "Offline ${max(offlineMoments, 1)} ${if (offlineMoments == 1) "time" else "times"}"
        }
    }

    /** "Wi-Fi dipped. Mobile data covered 95 s." */
    val subline: String get() = buildList {
        // only claims about networks that were actually tested
        val wifiDipped = wifiTested && (moments.any { it.kind == "drop" && it.before == Carrier.WIFI && !it.afterResume } ||
            (wifiRssiWorst != null && wifiRssiWorst <= -80))
        if (wifiDipped) add("Wi-Fi dipped.")
        if (wifiTested && mobileCoveredS >= 1) add("Mobile data covered ${fmtDuration(mobileCoveredS)}.")
        if (offlineS >= 1) add("Offline for ${fmtDuration(offlineS)}.")
        if (isEmpty()) add(when {
            !wifiTested && !mobileTested -> "No network was tested."
            !wifiTested -> "Mobile data only. Wi-Fi not tested."
            segments.isNotEmpty() && segments.all { it.carrier == Carrier.WIFI } -> "Wi-Fi held the whole test."
            else -> "No problems found."
        })
        val lost = (durationS - (capturedS ?: durationS)).coerceAtLeast(0.0)
        if (gaps > 0 && coveragePct != null && coveragePct < 95.0) add("Recording had gaps: ${fmtDuration(lost)} not captured.")
    }.joinToString(" ")

    companion object {
        const val SHORT_TEST_S = 300.0
        /** Comparison.pick ignores tests with less coverage than this. */
        const val MIN_COMPARABLE_COVERAGE_PCT = 80.0
    }
}

fun fmtDuration(s: Double): String {
    val t = s.roundToInt()
    return if (t < 120) "$t s" else if (t < 3600) "${t / 60} min ${t % 60} s".replace(" 0 s", "") else "${t / 3600} h ${(t % 3600) / 60} min"
}

/** m:ss (or h:mm:ss) clock for a seconds offset. */
fun fmtClock(s: Double): String {
    val t = s.roundToInt().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** "Office · Test 3" (falls back to the run name). */
fun testTitle(run: RunRecord): String =
    if (run.site != null && run.testNumber != null) "${run.site} · Test ${run.testNumber}" else run.name

// ----------------------------------------------------------------------------- moments

object Plain {
    /** Moment title for an event; null = not shown as a moment. */
    fun title(e: EventSample): String? {
        val a = obj(e.afterJson); val b = obj(e.beforeJson)
        return when (e.eventType) {
            "wifi_roam" -> "Changed access point"
            "wifi_cell_switch" -> when (a.str("primary_interface")) {
                "cellular" -> "Switched to mobile data"
                "wifi" -> "Back on Wi-Fi"
                else -> "Lost the network"
            }
            "drop" -> when (a.str("path") ?: b.str("path") ?: obj(e.evidenceJson).str("path")) {
                "wifi" -> "Wi-Fi dropped"
                "cellular" -> "Mobile data dropped"
                else -> "Connection dropped"
            }
            "reconnect" -> when (a.str("interface")) { "wifi" -> "Back on Wi-Fi"; "cellular" -> "Back on mobile data"; else -> "Reconnected" }
            "gps_degraded" -> if ((a.num("used_sats") ?: a.num("satellites_used") ?: 0.0) <= 0.0) "No GPS indoors" else "Position got rough"
            "captive_portal" -> "Wi-Fi wants a sign-in"
            "latency_spike" -> "Slow responses"
            "dns_spike" -> "Slow name lookups"
            "throughput_drop" -> "Speed dropped"
            "signal_drop" -> "Mobile signal dropped"
            "rat_change" -> "Mobile network changed to ${ratPlain(a.str("rat"))}"
            // a RAT change that reverted within 2 s, and the end of a GPS episode: kept in the event table, not moments
            "rat_flicker" -> null
            "gps_recovered" -> null
            "handover" -> null
            "neighbour_overtake" -> null
            "ip_asn_change" -> "Internet route changed"
            "annotation", "manual_mark" -> "Marked"
            else -> null
        }
    }

    fun ratPlain(rat: String?): String = when {
        rat == null -> "none"
        rat == "NR_SA" -> "5G"
        rat.startsWith("NR") -> "5G"
        rat.startsWith("LTE") -> "4G"
        rat in setOf("UMTS", "HSPA", "HSPAP", "WCDMA", "TDSCDMA") -> "3G"
        rat == "GSM" -> "2G"
        else -> rat.lowercase()
    }

    /** Plain cause title + engineer detail. */
    fun cause(e: EventSample): Pair<String, String>? {
        val key = e.likelyCause ?: return null
        val ev = obj(e.evidenceJson)
        val sig = ev["signals"] as? JsonObject ?: ev
        val parts = mutableListOf(key.replace('_', ' '))
        sig.num("rssi_at_t")?.let { parts += "RSSI ${minus(it)} dBm" }
        sig.num("connected_rssi_at_t")?.let { parts += "RSSI ${minus(it)} dBm" }
        sig.num("rsrp_at_t")?.let { parts += "RSRP ${minus(it)} dBm" }
        sig.num("gateway_rtt_at_t_ms")?.let { parts += "gateway ${it.roundToInt()} ms" }
        if (sig["better_bssid"] != null || sig["better_rssi"] != null || key == "sticky_client") parts += "stronger AP in scan"
        val title = causeTitle(key)
        return title to parts.distinct().joinToString(" · ")
    }

    fun causeTitle(key: String): String = when (key) {
        "sticky_client" -> "Held a far access point"
        "wifi_weak_signal" -> "Wi-Fi too weak here"
        "channel_congestion" -> "Busy Wi-Fi channel"
        "interference" -> "Radio interference"
        "wifi_link_delay" -> "Slow Wi-Fi link"
        "wifi_uplink", "upstream_delay" -> "Slow internet behind the Wi-Fi"
        "wifi_roam_transition" -> "Moving between access points"
        "captive_portal" -> "Network needs a sign-in"
        "not_validated" -> "Network has no internet"
        "dns_issue", "dhcp_dns_server_failed" -> "Name lookups failing"
        "cell_edge" -> "Edge of mobile coverage"
        "handover_churn" -> "Too many cell changes"
        "late_failed_handover" -> "Cell change came too late"
        "congestion_backhaul" -> "Mobile network busy"
        "tunnel_underpass" -> "Signal blocked (tunnel or underpass)"
        "carrier_gateway_change" -> "Carrier route changed"
        "dns_retry_timer" -> "Name lookup retried as the phone woke"
        "device_or_app" -> "Phone or app issue"
        "not_a_problem" -> "Not a problem"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun minus(v: Double) = v.roundToInt().toString().replace('-', '−')
}

internal fun obj(s: String?): JsonObject =
    s?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

// ----------------------------------------------------------------------------- builder

const val MAX_POINTS = 400

/**
 * Streaming report builder. Feed every sample of a run (any table order; each table in time order)
 * then [build]. Labels from the event-feedback table (x_event_label) override nothing; they only
 * count toward [TestReport.falseDrops] when the user said "No".
 */
class ReportBuilder(private val run: RunRecord, private val probeIntervalS: Double? = null) {
    private var t0 = Long.MAX_VALUE
    private var tEnd = Long.MIN_VALUE
    private fun see(t: Long) { if (t < t0) t0 = t; if (t > tEnd) tEnd = t }

    private val paths = ArrayList<Triple<Long, Carrier, Boolean>>() // mono, carrier, satisfied
    private val wifi = ArrayList<Pair<Long, Double>>()
    private val bssidChanges = ArrayList<Long>()
    private var lastBssid: String? = null
    private val rsrp = ArrayList<Double>(); private val sinr = ArrayList<Double>()
    private val bands = HashMap<String, Int>()
    private var lastRat: String? = null; private val rats = HashMap<String, Int>(); private var nrSeen = false
    private val respW = ArrayList<Pair<Long, Double>>(); private val respM = ArrayList<Pair<Long, Double>>()
    private val speeds = ArrayList<Triple<Long, Carrier, Double>>()
    private var checks = 0; private var passed = 0
    private val probeTimes = ArrayList<Long>()
    private val hAcc = ArrayList<Double>(); private val providers = HashMap<String, Int>(); private var positions = 0; private var gpsFixes = 0
    private var satsMax: Int? = null
    private var gnssT = Long.MIN_VALUE; private var gnssUsed = 0
    private val activity = HashMap<String, Long>(); private var lastMotion: Pair<Long, String>? = null
    private val events = ArrayList<EventSample>()
    private val labels = HashMap<Long, String>()
    private var throughputMaxMs: Long? = null
    private val coverage = CoverageTracker(run)
    private val movementTrack = MovementTracker()
    private var wifiProbe = false; private var mobileProbe = false
    private val edges = HashMap<Carrier, HashMap<String, Int>>()
    private val integrityLatest = LinkedHashMap<String, Pair<Long, IntegritySample>>()

    private val eventIds = ArrayList<Long?>()

    /** An event with its rowid (so moment feedback can be stored against it). */
    fun event(id: Long, e: EventSample) { see(e.stamp.tMonoNs); events += e; eventIds += id }
    fun label(eventId: Long, label: String) { labels[eventId] = label }

    fun add(s: Sample) {
        val t = s.stamp.tMonoNs
        coverage.add(s)
        when (s) {
            is NetworkPathSample -> { see(t); paths += Triple(t, Carrier.of(s.primaryInterface, s.status), s.status == "satisfied") }
            is WifiConnectionSample -> {
                see(t)
                s.rssiDbm?.let { wifi += t to it.toDouble() }
                if (s.bssid != null && lastBssid != null && s.bssid != lastBssid) bssidChanges += t
                if (s.bssid != null) lastBssid = s.bssid
            }
            is CellSample -> if (s.serving) {
                see(t); s.rsrp?.let { rsrp += it.toDouble() }; s.sinr?.let { sinr += it.toDouble() }
                s.band?.let { bands[it] = (bands[it] ?: 0) + 1 }
                if (s.rat == "NR") nrSeen = true
            }
            is RadioSample -> { see(t); rats[s.rat] = (rats[s.rat] ?: 0) + 1; lastRat = s.rat
                if (s.rat.startsWith("NR") || (s.nrState != null && s.nrState != "none")) nrSeen = true }
            is ProbeSample -> {
                see(t)
                val c = if (s.isCellular == true || s.iface == "cellular") Carrier.MOBILE else Carrier.WIFI
                if (c == Carrier.MOBILE) mobileProbe = true
                else if (s.isCellular == false || s.iface == "wifi" || s.iface == "ethernet") wifiProbe = true
                s.edgePop?.let { pop -> edges.getOrPut(c) { HashMap() }.merge(pop, 1, Int::plus) }
                when (s.probeType) {
                    "latency" -> {
                        checks++; if (s.success && s.expectedResponse != false) passed++
                        probeTimes += t
                        if (s.success) (s.connectMs ?: s.totalMs)?.let { (if (c == Carrier.MOBILE) respM else respW) += t to it }
                    }
                    "throughput" -> if (s.success) s.mbps?.let { speeds += Triple(t, c, it) }
                    "gateway", "dns_server", "captive", "public_ip" -> { checks++; if (s.success) passed++ }
                }
            }
            is LocationSample -> {
                see(t); positions++
                s.hAccM?.let { hAcc += it }
                providers[s.provider] = (providers[s.provider] ?: 0) + 1
                if (s.provider == "gps") gpsFixes++
                movementTrack.add(s)
            }
            is IntegritySample -> {
                val key = "${s.kind}|${s.target}|${s.path ?: ""}"
                val prev = integrityLatest[key]
                if (prev == null || t >= prev.first) integrityLatest[key] = t to s
            }
            is GnssSatelliteSample -> { see(t)
                if (t != gnssT) { gnssT = t; gnssUsed = 0 }
                if (s.usedInFix) { gnssUsed++; satsMax = max(satsMax ?: 0, gnssUsed) } else if (satsMax == null) satsMax = 0 }
            is MotionSample -> { see(t)
                lastMotion?.let { (lt, a) -> activity[a] = (activity[a] ?: 0) + (t - lt) }
                lastMotion = t to (s.activity ?: "unknown") }
            is EventSample -> { see(t); events += s; eventIds += null }
            is EventLabelSample -> labels[s.eventId] = s.label
            else -> {}
        }
    }

    /** satellites used per GNSS epoch (from the gnss table), when available. */
    fun satellitesUsed(n: Int) { satsMax = max(satsMax ?: 0, n) }

    fun throughputDurationMs(ms: Long) { throughputMaxMs = ms }

    private fun s(t: Long) = (t - t0) / 1e9

    fun build(): TestReport {
        if (t0 == Long.MAX_VALUE) { t0 = 0; tEnd = 0 }
        val runEndS = run.endedAt?.let { (it - run.startedAt) / 1000.0 }
        val dur = max(s(tEnd), runEndS ?: 0.0).coerceAtLeast(0.0)

        // ---- network timeline
        val segs = ArrayList<Segment>()
        val ps = paths.sortedBy { it.first }
        for ((i, p) in ps.withIndex()) {
            val from = s(p.first); val to = if (i + 1 < ps.size) s(ps[i + 1].first) else dur
            if (to <= from) continue
            val last = segs.lastOrNull()
            if (last != null && last.carrier == p.second && kotlin.math.abs(last.toS - from) < 0.01) segs[segs.size - 1] = last.copy(toS = to)
            else segs += Segment(from, to, p.second)
        }
        val known = segs.sumOf { it.toS - it.fromS }
        val offline = segs.filter { it.carrier == Carrier.NONE }.sumOf { it.toS - it.fromS }
        val onlinePct = if (known > 0) 100.0 * (known - offline) / known else null
        val wifiExpected = segs.any { it.carrier == Carrier.WIFI }
        val mobileCovered = if (wifiExpected) segs.filter { it.carrier == Carrier.MOBILE }.sumOf { it.toS - it.fromS } else 0.0
        val pathSwitches = segs.zipWithNext().count { (a, b) -> a.carrier != b.carrier }

        // ---- moments
        val order = events.indices.sortedBy { events[it].stamp.tMonoNs }
        val evs = order.map { events[it] }
        val ids = order.map { eventIds[it] }
        val moments = ArrayList<Moment>()
        val roamTimes = ArrayList<Long>()
        for ((i, e) in evs.withIndex()) {
            val title = Plain.title(e) ?: continue
            if (e.eventType == "wifi_roam") roamTimes += e.stamp.tMonoNs
            val tS = s(e.stamp.tMonoNs)
            val carrierAt = { x: Double -> segs.firstOrNull { x >= it.fromS && x < it.toS }?.carrier }
            var lasted: Double? = null
            var before: Carrier? = null; var after: Carrier? = null
            var stayed = true
            when (e.eventType) {
                "drop" -> {
                    val path = obj(e.afterJson).str("path")
                    val rec = evs.drop(i + 1).firstOrNull { it.eventType == "reconnect" && obj(it.beforeJson).str("path") == path }
                    lasted = rec?.let { s(it.stamp.tMonoNs) - tS } ?: (dur - tS)
                    before = if (path == "cellular") Carrier.MOBILE else Carrier.WIFI
                    after = obj(e.afterJson).str("failover")?.let { if (it == "cellular") Carrier.MOBILE else Carrier.WIFI }
                        ?: carrierAt(tS + 2) ?: Carrier.NONE
                    stayed = e.severity != "critical"
                }
                "wifi_cell_switch" -> {
                    before = carrierAt(tS - 1); after = carrierAt(tS + 1)
                    val next = segs.firstOrNull { it.fromS > tS + 0.5 && it.carrier != after }
                    lasted = (next?.fromS ?: dur) - tS
                    stayed = after != Carrier.NONE
                }
                "reconnect" -> { lasted = obj(e.afterJson).num("outage_ms")?.div(1000.0) }
                "gps_degraded" -> lasted = evs.drop(i + 1).firstOrNull { it.eventType == "gps_recovered" }?.let { s(it.stamp.tMonoNs) - tS }
            }
            // a reconnect right after a path switch back is the same moment
            if (e.eventType == "reconnect" && moments.lastOrNull()?.let { it.title == title && tS - it.tS < 5 } == true) continue
            if (e.eventType == "wifi_cell_switch" && moments.lastOrNull()?.let { it.kind == "drop" && tS - it.tS < 5 } == true &&
                title == "Switched to mobile data") continue
            val cause = Plain.cause(e)
            val afterResume = (obj(e.evidenceJson)["after_resume"] as? JsonPrimitive)?.booleanOrNull == true
            moments += Moment(tS, e.eventType, title, e.severity, e.stamp.tMonoNs, lasted, stayed, before, after,
                e.likelyCause, cause?.first, cause?.second, eventId = ids[i], feedback = ids[i]?.let { labels[it] }, afterResume = afterResume)
        }
        // roams the engine did not report as events (BSSID change)
        for (t in bssidChanges) if (roamTimes.none { kotlin.math.abs(it - t) < 3_000_000_000L }) {
            moments += Moment(s(t), "wifi_roam", "Changed access point", "info", t, before = Carrier.WIFI, after = Carrier.WIFI)
            roamTimes += t
        }
        moments.sortBy { it.tS }
        val apChanges = roamTimes.size

        // ---- probes / schedule
        val onSchedule = probeIntervalS?.takeIf { it > 0 && dur > it }?.let { iv ->
            val expected = (dur / iv).toInt().coerceAtLeast(1)
            val distinct = probeTimes.map { (s(it) / iv).toInt() }.toSet().size
            (100.0 * distinct / expected).coerceAtMost(100.0)
        }
        val falseDrops = evs.indices.count { i -> evs[i].eventType == "drop" &&
            (ids[i]?.let { labels[it] }?.let { l -> Feedback.isNo(l) } == true || (evs[i].severity == "critical" && (onlinePct ?: 0.0) >= 99.9)) }

        val mW = respW.map { it.second }; val mM = respM.map { it.second }
        val medW = Stats.median(mW); val medM = Stats.median(mM)
        val wSpeeds = speeds.filter { it.second == Carrier.WIFI }.map { it.third }
        val mSpeeds = speeds.filter { it.second == Carrier.MOBILE }.map { it.third }
        val totalAct = activity.values.sum().toDouble()
        val movement = if (totalAct > 0) activity.mapValues { 100.0 * it.value / totalAct }
            .filter { it.value >= 1 }.toList().sortedByDescending { it.second }.toMap() else emptyMap()
        val tech = rats.maxByOrNull { it.value }?.key?.let { Plain.ratPlain(it) }
        val topProvider = providers.maxByOrNull { it.value }?.key
        val cov = coverage.build()
        val mv = movementTrack.build()
        val moved = run.mode in setOf("walk", "drive", "rail")
        val movementNote = if (moved && mv.stationary == true) mv.bboxM?.let { (w, h) -> "Stayed within ${round5(w)} × ${round5(h)} m" } else null
        fun edgeList(c: Carrier) = edges[c].orEmpty().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { "${it.key} ×${it.value}" }
        val posSource = when (topProvider) { "gps" -> "GPS"; "fused" -> if (gpsFixes == 0) "Wi-Fi" else "GPS + Wi-Fi"; "network" -> "Wi-Fi"; null -> null; else -> topProvider }

        return TestReport(
            runId = run.runId, title = testTitle(run),
            durationS = dur, onlinePct = onlinePct?.let { r1(it) }, mobileCoveredS = mobileCovered, offlineS = offline,
            switches = pathSwitches + apChanges, pathSwitches = pathSwitches, apChanges = apChanges,
            wifiSpeedMedian = Stats.median(wSpeeds), wifiSpeedMax = wSpeeds.maxOrNull(),
            mobileSpeedMedian = Stats.median(mSpeeds), mobileSpeedMax = mSpeeds.maxOrNull(),
            responseMs = listOfNotNull(medW, medM).maxOrNull(), responseWifiMs = medW, responseMobileMs = medM,
            responseMobileMinMs = mM.minOrNull(), responseMobileMaxMs = mM.maxOrNull(),
            wifiRssiMedian = Stats.median(wifi.map { it.second }), wifiRssiWorst = wifi.minOfOrNull { it.second },
            rsrpMedian = Stats.median(rsrp), sinrMedian = Stats.median(sinr),
            mobileTech = tech, mobileBand = bands.maxByOrNull { it.value }?.key, nrSeen = nrSeen,
            positionAccM = Stats.median(hAcc), positionSource = posSource, positions = positions, gpsFixes = gpsFixes,
            satellitesMax = satsMax,
            checksPassedPct = if (checks > 0) r1(100.0 * passed / checks) else null, checks = checks, falseDrops = falseDrops,
            onSchedulePct = onSchedule?.let { r1(it) },
            movement = movement,
            segments = segs, moments = moments,
            wifiRssi = down(wifi.map { Pt(s(it.first), it.second) }),
            newApAtS = roamTimes.sorted().map { s(it) },
            responseWifi = down(respW.map { Pt(s(it.first), it.second) }),
            responseMobile = down(respM.map { Pt(s(it.first), it.second) }),
            speedTests = speeds.map { SpeedTest(s(it.first), it.second, it.third) },
            speedTestSeconds = throughputMaxMs?.let { (it / 1000).toInt() },
            methodFingerprint = run.methodFingerprint,
            deviceModel = run.deviceModel, appVersion = run.appVersion, startedAt = run.startedAt,
            wifiTested = segs.any { it.carrier == Carrier.WIFI } || wifiProbe,
            mobileTested = segs.any { it.carrier == Carrier.MOBILE } || mobileProbe,
            bboxM = mv.bboxM?.let { r1(it.first) to r1(it.second) }, pathM = if (mv.fixesUsed > 0) r1(mv.pathM) else null,
            stationary = mv.stationary, movementNote = movementNote,
            capturedS = r1(cov.capturedMs / 1000.0), coveragePct = cov.coveragePct, gaps = cov.gapCount,
            longestGapS = r1(cov.longestGapMs / 1000.0),
            edgesWifi = edgeList(Carrier.WIFI), edgesMobile = edgeList(Carrier.MOBILE),
            integrity = integrityLatest.values.sortedWith(compareBy({ it.second.kind }, { it.second.target }, { it.second.path ?: "" }))
                .map { (_, i) -> IntegrityResult(i.kind, i.path, i.target, i.ok, i.value) },
        )
    }

    private fun round5(m: Double): Int = (Math.round(m / 5.0) * 5).toInt().coerceAtLeast(5)

    private fun r1(x: Double) = Math.round(x * 10) / 10.0

    /** Bucket-average to ≤ [MAX_POINTS] points. */
    private fun down(pts: List<Pt>): List<Pt> {
        if (pts.size <= MAX_POINTS) return pts
        val step = pts.size.toDouble() / MAX_POINTS
        return (0 until MAX_POINTS).map { i ->
            val a = (i * step).toInt(); val b = ((i + 1) * step).toInt().coerceAtMost(pts.size).coerceAtLeast(a + 1)
            val sub = pts.subList(a, b)
            Pt(sub.sumOf { it.tS } / sub.size, sub.sumOf { it.v } / sub.size)
        }
    }
}

// ----------------------------------------------------------------------------- comparison

/** "Test 3 vs Test 1 · 48% → 97% checks passed · False drops 4 → 0 · GPS fixes 0 → 88". */
data class Comparison(
    val newer: TestReport, val older: TestReport,
    val newerLabel: String, val olderLabel: String,
) {
    val checksBefore get() = older.checksPassedPct
    val checksAfter get() = newer.checksPassedPct
    val improved: Boolean get() = (checksAfter ?: 0.0) >= (checksBefore ?: 0.0)
    /** Lines "False drops 4 → 0", "GPS fixes 0 → 88", plus any other metric that moved a verdict step. */
    val deltas: List<String> get() = buildList {
        if (older.falseDrops != newer.falseDrops || older.falseDrops > 0) add("False drops ${older.falseDrops} → ${newer.falseDrops}")
        if (older.gpsFixes != newer.gpsFixes) add("GPS fixes ${older.gpsFixes} → ${newer.gpsFixes}")
        if (older.onlineVerdict != newer.onlineVerdict && older.onlinePct != null && newer.onlinePct != null)
            add("Online ${older.onlinePct.roundToInt()}% → ${newer.onlinePct.roundToInt()}%")
        if (older.switches != newer.switches) add("Switches ${older.switches} → ${newer.switches}")
    }

    companion object {
        /** Newest test vs the oldest earlier test at the same site, if any. */
        fun pick(reports: List<Pair<RunRecord, TestReport>>): Comparison? {
            // short tests (< 5 min) and tests with < 80 % coverage are not comparable
            val withSite = reports.filter { (r, t) ->
                r.site != null && t.checksPassedPct != null && !t.shortTest &&
                    (t.coveragePct == null || t.coveragePct >= TestReport.MIN_COMPARABLE_COVERAGE_PCT)
            }
            val latest = withSite.maxByOrNull { it.first.startedAt } ?: return null
            val older = withSite.filter { it.first.site.equals(latest.first.site, true) && it.first.runId != latest.first.runId }
                .minByOrNull { it.first.startedAt } ?: return null
            fun lab(r: RunRecord) = r.testNumber?.let { "Test $it" } ?: r.name
            return Comparison(latest.second, older.second, lab(latest.first), lab(older.first))
        }
    }
}

// ----------------------------------------------------------------------------- live headline

object LiveWords {
    /**
     * Headline + sub-line for the live screen.
     * @param since seconds offset at which the current carrier took over
     */
    fun headline(carrier: Carrier, wifiRssi: Int?, since: Double?, elapsedS: Double): Pair<String, String> {
        val head = when (carrier) {
            Carrier.WIFI -> "Online via Wi-Fi"
            Carrier.MOBILE -> "Online via mobile data"
            Carrier.NONE -> "Offline"
        }
        val sub = when (carrier) {
            Carrier.MOBILE -> when {
                since != null && wifiRssi != null && wifiRssi <= -80 -> "Wi-Fi too weak since ${fmtClock(since)}"
                since != null && since > 1 -> "Wi-Fi gone since ${fmtClock(since)}"
                else -> "No Wi-Fi in use"
            }
            Carrier.WIFI -> when (Verdict.wifiRssi(wifiRssi?.toDouble())) {
                Verdict.BAD, Verdict.BELOW_AVG -> "Wi-Fi is weak here"
                Verdict.NONE -> "Measuring…"
                else -> "Wi-Fi is fine"
            }
            Carrier.NONE -> since?.let { "No network since ${fmtClock(it)}" } ?: "No network"
        }
        return head to sub
    }
}
