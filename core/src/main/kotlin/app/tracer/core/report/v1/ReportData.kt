package app.tracer.core.report.v1

import app.tracer.core.model.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Tracer report v1: run tables → ReportData JSON (REPORT_DATA_CONTRACT.md).
 * Line-for-line port of tracer-report-v1/build_report_data.py. The rules (thresholds, windows,
 * phases, verdicts, wording) are the spec; keep this file in step with the reference script and the
 * TypeScript port (report-v1/ts/buildReportData.ts). Pure Kotlin, no Android.
 *
 * Missing data never throws: every section degrades to null / [] and the template renders
 * "Not captured in this run.".
 */

/** Etihad Rail overlay (rail_ergt.json): lines [[[lat,lon]…]…], stations, optional destination. */
data class RailData(
    val lines: JsonArray = JsonArray(emptyList()),
    val stations: List<JsonObject> = emptyList(),
    val destination: JsonObject? = null,
    val attribution: String? = null,
) {
    companion object {
        fun parse(text: String): RailData {
            val o = Json.parseToJsonElement(text).jsonObject
            return RailData(
                lines = o["lines"] as? JsonArray ?: JsonArray(emptyList()),
                stations = (o["stations"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList(),
                destination = o["destination"] as? JsonObject,
                attribution = (o["attribution"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    /** Station nearest to (lat, lon) as a destination object {name, lat, lon}. */
    fun nearestStation(lat: Double, lon: Double): JsonObject? = stations.minByOrNull { s ->
        val la = s.d("lat") ?: return@minByOrNull Double.MAX_VALUE
        val lo = s.d("lon") ?: return@minByOrNull Double.MAX_VALUE
        val dx = (lo - lon) * cos(Math.toRadians(lat)); val dy = la - lat; dx * dx + dy * dy
    }?.let { s -> buildJsonObject { put("name", s["name"] ?: JsonNull); put("lat", s["lat"] ?: JsonNull); put("lon", s["lon"] ?: JsonNull) } }
}

/** Everything the builder reads. Lists may be empty; [completeness] is run.json `completeness`. */
class ReportInput(
    val run: RunRecord,
    val completeness: JsonObject? = null,
    val probes: List<ProbeSample> = emptyList(),
    /** (event id, event) */
    val events: List<Pair<Long, EventSample>> = emptyList(),
    val radio: List<RadioSample> = emptyList(),
    val networkPath: List<NetworkPathSample> = emptyList(),
    val locations: List<LocationSample> = emptyList(),
    val device: List<DeviceSample> = emptyList(),
    val wifi: List<WifiConnectionSample> = emptyList(),
    val collectorLog: List<CollectorLogSample> = emptyList(),
    val rail: RailData? = null,
    /** {image, z, x0, y0, scale, crop, attribution} or null */
    val basemap: JsonObject? = null,
    /** destination station override from New Test; null = nearest station to the last fix */
    val destination: JsonObject? = null,
    /** "sysdiagnose" completeness note (iOS); Android: null */
    val sysdiagnose: String? = null,
)

/** One-line "what this means" under each KPI tile (plain language, no em dashes). */
object Hints {
    fun success(sr: Double): String = "Share of web requests over Wi-Fi that worked. " + when {
        sr >= .99 -> "Healthy."
        sr >= .9 -> "Users notice occasional failed loads."
        else -> "About 1 in ${Math.max(2, Math.round(1 / Math.max(1 - sr, 1e-9)).toInt())} requests failed."
    }
    fun noInternet(f: Double): String = "Time Wi-Fi was connected but the internet did not work. " +
        if (f > .2) "Too high: frequent cut-outs." else if (f > 0) "Short cut-outs only." else "None."
    fun speed(m: Double?): String = "Typical download speed. " + when {
        m == null -> "Not measured."
        m < 1 -> "Too slow for most apps."
        m < 5 -> "Messaging and browsing only; video struggles."
        m < 20 -> "Fine for video calls; HD video may buffer."
        else -> "Enough for HD video and calls."
    }
    fun p95(ms: Double?): String = "Wait time of the slowest 5% of requests. " + when {
        ms == null -> "Not measured."
        ms > 1000 -> "Over 1 s feels broken."
        ms > 800 -> "Noticeably slow at times."
        else -> "Responsive."
    }
    fun lan(good: Boolean, wanBad: Boolean): String = when {
        good && wanBad -> "The Wi-Fi router itself answers: the fault is its internet link (backhaul)."
        good -> "The Wi-Fi router itself answers reliably."
        else -> "The Wi-Fi router itself is unreliable: check the access point and LAN."
    }
    fun mobile(verdict: String): String = "The phone SIM on its own: the baseline to compare against. " +
        if (verdict == "PASS") "Healthy." else "Also had problems."
}

object ReportData {
    const val BIN = 15_000L

    fun build(inp: ReportInput): JsonObject = Builder(inp).build()

    /** HTML with the JSON injected in place of __REPORT_DATA__ ("</" escaped). */
    fun inject(template: String, data: JsonObject): String =
        template.replace("__REPORT_DATA__", data.toString().replace("</", "<\\/"))

    /** Tracer_<site|mode>_<test#>_<yyyyMMdd-HHmm>_<runId8>.pdf */
    fun fileName(run: RunRecord): String {
        val zone = runCatching { ZoneId.of(run.timeZone ?: "UTC") }.getOrDefault(ZoneOffset.UTC)
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(zone).format(Instant.ofEpochMilli(run.startedAt))
        val who = (run.site ?: run.mode).replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "run" }
        val num = run.testNumber?.let { "T$it" } ?: "T0"
        return "Tracer_${who}_${num}_${ts}_${run.runId.take(8)}.pdf"
    }
}

// ------------------------------------------------------------------------------------- helpers

internal fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

private fun median(xs: List<Double>): Double? {
    val v = xs.filter { !it.isNaN() }.sorted(); if (v.isEmpty()) return null
    val n = v.size; return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2
}

/** pandas Series.quantile (linear). */
private fun quantile(xs: List<Double>, q: Double): Double? {
    val v = xs.filter { !it.isNaN() }.sorted(); if (v.isEmpty()) return null
    val pos = (v.size - 1) * q; val lo = floor(pos).toInt(); val hi = min(lo + 1, v.size - 1)
    return v[lo] + (v[hi] - v[lo]) * (pos - lo)
}

private fun fmt(p: String, vararg a: Any?): String = String.format(Locale.ROOT, p, *a)
private fun f0(x: Double) = fmt("%.0f", x)
private fun f1(x: Double) = fmt("%.1f", x)
private fun thousands(n: Int) = String.format(Locale.US, "%,d", n)
private fun JsonObjectBuilder.putD(k: String, v: Double?) = put(k, v?.takeIf { !it.isNaN() && !it.isInfinite() })

/** most_common(1) with Python's tie-break: first-inserted wins. */
private fun <T> majority(xs: List<T>): T {
    val c = LinkedHashMap<T, Int>(); xs.forEach { c[it] = (c[it] ?: 0) + 1 }
    var best = xs.first(); var bn = -1
    for ((k, n) in c) if (n > bn) { best = k; bn = n }
    return best
}

private class Seg(val kind: String, var start: Long, var end: Long, var label: String = "")

private class PStats(val n: Int, val sr: Double?, val dns: Double?, val con: Double?, val tls: Double?, val ttfb: Double?, val tot: Double?, val p95: Double?) {
    fun json() = buildJsonObject {
        put("n", n); putD("sr", sr); putD("dns", dns); putD("con", con); putD("tls", tls); putD("ttfb", ttfb); putD("tot", tot); putD("p95", p95)
    }
}

private fun pstats(df: List<ProbeSample>): PStats {
    val ok = df.filter { it.success }
    return PStats(
        df.size, if (df.isEmpty()) null else df.count { it.success }.toDouble() / df.size,
        median(ok.mapNotNull { it.dnsMs }), median(ok.mapNotNull { it.connectMs }), median(ok.mapNotNull { it.tlsMs }),
        median(ok.mapNotNull { it.ttfbMs }), median(ok.mapNotNull { it.totalMs }), quantile(ok.mapNotNull { it.totalMs }, 0.95),
    )
}

private val ProbeSample.path: String get() = if (isCellular == true || iface == "cellular") "cellular" else iface ?: "wifi"

// ------------------------------------------------------------------------------------- builder

private class Builder(private val inp: ReportInput) {
    private val run = inp.run
    private val T0 = run.startedAt
    private val T1 = run.endedAt ?: max(T0, listOfNotNull(
        inp.probes.maxOfOrNull { it.stamp.tUtcMs }, inp.locations.maxOfOrNull { it.stamp.tUtcMs },
        inp.networkPath.maxOfOrNull { it.stamp.tUtcMs }).maxOrNull() ?: T0)
    private val tzMin: Int = runCatching {
        ZoneId.of(run.timeZone ?: "UTC").rules.getOffset(Instant.ofEpochMilli(T0)).totalSeconds / 60
    }.getOrDefault(0)
    private val ios = run.devicePlatform == "ios"

    private fun hm(t: Long): String {
        val s = Math.floorMod(t + tzMin * 60_000L, 86_400_000L) / 60_000L
        return fmt("%02d:%02d", s / 60, s % 60)
    }

    // ---------- segments
    private val segs: List<Seg> = run {
        val out = ArrayList<Seg>()
        for (r in inp.networkPath.sortedBy { it.stamp.tUtcMs }) {
            val k = if (r.primaryInterface == "wifi") "wifi" else "cellular"
            if (out.isEmpty() || out.last().kind != k) out += Seg(k, max(r.stamp.tUtcMs, T0), T1)
            if (out.size > 1) out[out.size - 2].end = out.last().start
        }
        val kept = out.filter { it.end - it.start > 30_000 }.ifEmpty { listOf(Seg("cellular", T0, T1)) }
        kept.first().start = T0; kept.last().end = T1
        kept
    }

    private fun active(t: Long): String {
        for (s in segs) if ((s.start <= t && t < s.end) || (t == T1 && s.end == T1)) return s.kind
        return segs.last().kind
    }
    private fun activeD(t: Double): String {
        for (s in segs) if ((s.start <= t && t < s.end) || (t == T1.toDouble() && s.end == T1)) return s.kind
        return segs.last().kind
    }

    private val ssid: String = inp.wifi.mapNotNull { it.ssid?.takeIf { s -> s.isNotBlank() } }.let { if (it.isEmpty()) "Wi-Fi" else majorityMode(it) }
    private val operator: String = run {
        val asn = inp.probes.filter { it.probeType == "public_ip" && it.path == "cellular" }.mapNotNull { it.asnOrg }
        if (asn.isEmpty()) "mobile" else {
            val m = majorityMode(asn)
            mapOf("Emirates Internet" to "e&", "Emirates Integrated Telecommunications Company PJSC" to "du")[m] ?: m
        }
    }

    /** pandas mode: most frequent, ties → smallest value. */
    private fun majorityMode(xs: List<String>): String {
        val c = xs.groupingBy { it }.eachCount(); val top = c.values.max()
        return c.filter { it.value == top }.keys.sorted().first()
    }

    private val lat: List<Pair<ProbeSample, String>> = inp.probes.filter { it.probeType == "latency" }.map { it to active(it.stamp.tUtcMs) }
    private val latSorted = lat.map { it.first }.sortedBy { it.stamp.tUtcMs }
    private val tv = LongArray(latSorted.size) { latSorted[it].stamp.tUtcMs }

    // ---------- stalls
    private class Stall(val id: Long, val t: Long, val dur: Double) {
        var lat: Double? = null; var lon: Double? = null; var acc: Double? = null; var conf: String = "stale"; var phase: String? = null
    }
    private val stalls = ArrayList<Stall>()
    private val mobDrops = ArrayList<Long>()

    private fun pathOf(js: String?): String? = runCatching {
        val o = Json.parseToJsonElement(js ?: return null).jsonObject
        (o["path"] as? JsonPrimitive)?.contentOrNull ?: (o["interface"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    init {
        val ev = inp.events.sortedBy { it.second.stamp.tUtcMs }
        val recon = ev.filter { it.second.eventType == "reconnect" }
        for ((id, e) in ev.filter { it.second.eventType == "drop" }) {
            val pth = pathOf(e.afterJson)
            val nxt = recon.firstOrNull { (_, r) -> r.stamp.tUtcMs > e.stamp.tUtcMs && (r.beforeJson ?: "").contains("\"path\":\"$pth\"") }
            val end = nxt?.second?.stamp?.tUtcMs ?: T1
            if (pth == "wifi") stalls += Stall(id, e.stamp.tUtcMs, (end - e.stamp.tUtcMs) / 1000.0) else mobDrops += e.stamp.tUtcMs
        }
    }

    // ---------- health
    private fun lowerBound(x: Double): Int { var lo = 0; var hi = tv.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (tv[m] < x) lo = m + 1 else hi = m }; return lo }

    fun health(t: Double, kind: String): Int {
        if (kind == "wifi" && stalls.any { it.t <= t && it.t + it.dur * 1000 >= t }) return 3
        val i0 = lowerBound(t - 10_000); val i1 = lowerBound(t + 10_000)
        val w = (i0 until i1).map { latSorted[it] }.filter { it.path == kind }
        if (w.isEmpty()) return -1
        val sr = w.count { it.success }.toDouble() / w.size
        val med = median(w.filter { it.success }.mapNotNull { it.connectMs })
        if (sr < .5) return 3
        if (sr >= .95 && med != null && med < 150) return 0
        if (sr >= .8 && med != null && med < 400) return 1
        return 2
    }

    private val codes: List<Int> = run {
        val out = ArrayList<Int>(); var t = T0
        while (t < T1) { val c = t + ReportData.BIN / 2.0; out += health(c, activeD(c)); t += ReportData.BIN }
        out
    }

    private fun share(lo: Long, hi: Long): Map<Int, Double> {
        val c = HashMap<Int, Int>()
        codes.forEachIndexed { i, h -> val tt = T0 + i * ReportData.BIN; if (lo <= tt && tt < hi) c[h] = (c[h] ?: 0) + 1 }
        val n = max(c.values.sum(), 1)
        return listOf(0, 1, 2, 3, -1).associateWith { (c[it] ?: 0).toDouble() / n }
    }

    private fun win(kind: String): LongArray? { val ss = segs.filter { it.kind == kind }; return if (ss.isEmpty()) null else longArrayOf(ss.first().start, ss.last().end) }

    // ---------- paths
    private class PathR(val key: String, val kind: String, val stats: PStats, val thr: JsonObject?, val thrMedian: Double?, val resolver: JsonObject?,
                        val gateway: JsonObject?, val health: Map<Int, Double>?, val json: JsonObject, val verdict: String)

    private fun inWin(type: String, kind: String, act: String) = inp.probes.filter { it.probeType == type && it.path == kind && active(it.stamp.tUtcMs) == act }

    private fun thrStats(df: List<ProbeSample>): Pair<JsonObject?, Double?> {
        if (df.isEmpty()) return null to null
        val v = df.filter { it.success }.mapNotNull { it.mbps }.filter { !it.isNaN() }
        val med = median(v)
        return buildJsonObject {
            put("n", df.size); putD("median", med); putD("min", v.minOrNull()); putD("max", v.maxOrNull())
            put("under1", df.count { (it.mbps ?: 0.0) < 1 })
        } to med
    }

    private val paths: List<PathR> = run {
        val out = ArrayList<PathR>()
        val defs = listOf(
            listOf("mob_active", "cellular", "cellular", "$operator mobile · active path", "$operator (active)"),
            listOf("mob_parallel", "cellular", "wifi", "$operator mobile · in parallel", "$operator (parallel)"),
            listOf("wifi_active", "wifi", "wifi", ssid, "Wi-Fi"),
        )
        for ((key, kind, act, label, short) in defs) {
            val d = lat.filter { it.first.path == kind && it.second == act }.map { it.first }
            val w = win(act)
            if (d.size < 20 || w == null) continue
            val st = pstats(d)
            val (th, thMed) = thrStats(inWin("throughput", kind, act))
            val dn = inWin("dns_server", kind, act); val g = inWin("gateway", kind, act)
            val isActive = kind == act
            val h = if (isActive) share(w[0], w[1]) else null
            val resolver = if (dn.isNotEmpty()) {
                val eps = dn.map { it.endpoint }.distinct()
                buildJsonObject {
                    put("label", eps.take(1).joinToString(", ") + if (eps.size > 1) " +${eps.size - 1}" else "")
                    putD("median_ms", median(dn.mapNotNull { it.totalMs })); putD("sr", dn.count { it.success }.toDouble() / dn.size)
                }
            } else null
            val gateway = if (g.isNotEmpty()) {
                val gsr = g.count { it.success }.toDouble() / g.size
                buildJsonObject {
                    put("label", g.first().endpoint); putD("sr", gsr); putD("median_ms", median(g.mapNotNull { it.totalMs }))
                    put("note", if (kind == "cellular" && gsr == 0.0) "carrier gateway blocks ICMP" else "")
                }
            } else null
            val sr = st.sr ?: 0.0; val p95 = st.p95 ?: 0.0; val tm = thMed ?: 0.0; val none = h?.get(3) ?: 0.0
            val v = when {
                sr < .9 || none > .2 -> "FAIL"
                sr < .99 || p95 > 800 || (th != null && tm < 20) -> "WATCH"
                else -> "PASS"
            }
            val text = fmt("%.1f", sr * 100) + "% of HTTPS checks succeeded, " + (st.con?.let { f0(it) } ?: "–") + " ms median connect, p95 " + f0(p95) + " ms" +
                (if (th != null) ", " + fmt("%.1f", tm) + " Mbps median throughput" else "") +
                (if (h != null && none > 0) ", no internet " + f0(none * 100) + "% of the time" else "") + "."
            val json = buildJsonObject {
                put("key", key); put("kind", kind); put("label", label); put("short", short)
                put("window", buildJsonArray { add(w[0]); add(w[1]) })
                put("stats", st.json()); put("thr", th ?: JsonNull); put("resolver", resolver ?: JsonNull); put("gateway", gateway ?: JsonNull)
                put("health", h?.let { m -> buildJsonObject { m.forEach { (k, x) -> put(k.toString(), x) } } } ?: JsonNull)
                put("verdict", v); put("verdict_text", text)
            }
            out += PathR(key, kind, st, th, thMed, resolver, gateway, h, json, v)
        }
        out
    }

    // ---------- series
    private fun seriesConnect(kind: String): JsonArray = buildJsonArray {
        val d = lat.map { it.first }.filter { it.path == kind && it.success && it.connectMs != null }
        d.groupBy { Math.floorDiv(it.stamp.tUtcMs - T0, 30_000L) }.toSortedMap().forEach { (b, g) ->
            val m = median(g.mapNotNull { it.connectMs }) ?: return@forEach
            add(buildJsonArray { add(T0 + b * 30_000 + 15_000); add(Math.round(m * 10) / 10.0) })
        }
    }
    private fun seriesFail(kind: String): JsonArray = buildJsonArray {
        lat.map { it.first }.filter { it.path == kind && !it.success }.groupBy { Math.floorDiv(it.stamp.tUtcMs - T0, 60_000L) }.toSortedMap()
            .forEach { (b, g) -> add(buildJsonArray { add(T0 + b * 60_000); add(g.size) }) }
    }

    /** Radio rows of the main subscription (Android may report two SIMs). */
    private val rad: List<RadioSample> = run {
        val main = inp.radio.groupingBy { it.serviceId }.eachCount().maxByOrNull { it.value }?.key
        inp.radio.filter { it.serviceId == main }.sortedBy { it.stamp.tUtcMs }.map { it.copy(rat = ratKey(it.rat, it.nrState)) }
    }
    private fun ratKey(rat: String, nr: String?): String = when {
        rat == "NR_SA" || nr == "sa" -> "NR_SA"
        rat.startsWith("NR") || nr == "nsa" || nr == "nsa_advanced" -> "NR_NSA"
        rat.startsWith("LTE") -> "LTE"
        rat == "UMTS" || rat == "WCDMA" || rat == "TDSCDMA" -> "UMTS"
        rat.startsWith("HSPA") -> "HSPA"
        rat == "GSM" || rat == "EDGE" || rat == "GPRS" -> "GSM"
        rat == "NONE" || rat == "UNKNOWN" -> "NONE"
        else -> rat
    }
    private fun radDur(i: Int): Long = (if (i + 1 < rad.size) rad[i + 1].stamp.tUtcMs else T1) - rad[i].stamp.tUtcMs

    // ---------- location
    private val L = inp.locations.sortedBy { it.stamp.tUtcMs }
    private val fpm: List<Triple<Long, Int, Double?>> =
        L.groupBy { Math.floorDiv(it.stamp.tUtcMs - T0, 60_000L) }.toSortedMap().map { (b, g) -> Triple(T0 + b * 60_000, g.size, median(g.mapNotNull { it.hAccM })) }

    private fun locAt(t: Long): LocationSample? {
        if (L.isEmpty()) return null
        var lo = 0; var hi = L.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (L[m].stamp.tUtcMs <= t) lo = m + 1 else hi = m }
        return L[max(lo - 1, 0)]
    }

    init {
        for (s in stalls) {
            val f = locAt(s.t) ?: continue
            val age = (s.t - f.stamp.tUtcMs) / 1000.0
            s.lat = f.lat; s.lon = f.lon; s.acc = f.hAccM
            val acc = f.hAccM
            s.conf = if (acc != null && acc <= 60 && age <= 120) "precise" else if (acc != null && age <= 120) "coarse" else "stale"
        }
    }

    // ---------- phases
    private class Phase(val state: String, val start: Long, var end: Long) { var key = ""; var label = ""; var n = 0; var s = 0.0 }
    private val phases: List<Phase> = run {
        val ww = win("wifi")
        if (ww == null || stalls.isEmpty()) return@run emptyList()
        fun state(t: Long): String {
            val m = fpm.firstOrNull { it.first <= t && t < it.first + 60_000 } ?: return "none"
            val acc = m.third
            return if (acc != null && acc <= 60) "precise" else "approx"
        }
        val names = mapOf("precise" to "located (GPS)", "none" to "no location", "approx" to "approximate position")
        val mins = generateSequence(ww[0]) { it + 60_000 }.takeWhile { it < ww[1] }.toList()
        val raw = mins.map { state(it) }
        val sm = raw.indices.map { i -> majority(raw.subList(max(0, i - 3), min(raw.size, i + 4))) }
        val ph = ArrayList<Phase>()
        for ((t, st) in mins.zip(sm)) {
            if (ph.isNotEmpty() && ph.last().state == st) ph.last().end = min(t + 60_000, ww[1])
            else ph += Phase(st, t, min(t + 60_000, ww[1]))
        }
        val merged = ArrayList<Phase>()
        for (p in ph) if (merged.isNotEmpty() && (p.end - p.start < 300_000 || merged.last().state == p.state)) merged.last().end = p.end else merged += p
        if (merged.isEmpty()) return@run emptyList()
        merged.last().end = ww[1]
        merged.forEachIndexed { i, p ->
            p.key = ('A' + i).toString(); p.label = "${p.key} · ${names[p.state]}"
            val inn = stalls.filter { p.start <= it.t && it.t < p.end }
            p.n = inn.size; p.s = inn.sumOf { it.dur }; inn.forEach { it.phase = p.key }
        }
        merged.filter { it.end > it.start }
    }

    // ---------- Wi-Fi layers
    private val wp = paths.firstOrNull { it.key == "wifi_active" }
    private val mp = paths.firstOrNull { it.key == "mob_parallel" }
    private val ma = paths.firstOrNull { it.key == "mob_active" }

    private fun band(freq: Int?): String? = when { freq == null -> null; freq < 3000 -> "2.4 GHz"; freq < 5925 -> "5 GHz"; else -> "6 GHz" }
    private fun channel(freq: Int?): Int? = when {
        freq == null -> null
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> null
    }

    private val layers: List<JsonObject> = run {
        val w = wp ?: return@run emptyList()
        val out = ArrayList<JsonObject>()
        val bssids = inp.wifi.mapNotNull { it.bssid }.distinct().size
        val sec = inp.wifi.mapNotNull { it.security }.let { if (it.isEmpty()) "unknown" else majorityMode(it) }
        fun layer(name: String, status: String, detail: String) = buildJsonObject { put("name", name); put("status", status); put("detail", detail) }
        val rssi = inp.wifi.mapNotNull { it.rssiDbm?.toDouble() }
        if (ios || rssi.isEmpty()) {
            out += layer("Radio · phone ↔ AP", "No data",
                "SSID \"$ssid\", $bssids BSSID(s), security $sec. " +
                    if (ios) "RSSI not exposed by the OS API; use the Android companion or sysdiagnose." else "No Wi-Fi radio readings captured.")
        } else {
            val med = median(rssi)!!; val worst = rssi.min()
            val link = median(inp.wifi.mapNotNull { it.linkSpeedMbps?.toDouble() })
            val freq = inp.wifi.mapNotNull { it.frequencyMhz }.let { if (it.isEmpty()) null else it.groupingBy { f -> f }.eachCount().maxBy { e -> e.value }.key }
            val status = when { med >= -67 -> "Good"; med >= -75 -> "Fair"; med >= -82 -> "Poor"; else -> "Failing" }
            out += layer("Radio · phone ↔ AP", status,
                "SSID \"$ssid\", $bssids BSSID(s), security $sec. RSSI median ${f0(med).replace('-', '−')} dBm (worst ${f0(worst).replace('-', '−')} dBm)" +
                    (link?.let { ", link ${f0(it)} Mbps" } ?: "") +
                    (band(freq)?.let { b -> ", $b" + (channel(freq)?.let { " ch $it" } ?: "") } ?: "") + ".")
        }
        w.gateway?.let { g ->
            val sr = g.d("sr") ?: 0.0; val med = g.d("median_ms")
            out += layer("LAN · gateway ${(g["label"] as JsonPrimitive).content}",
                if (sr >= .99 && (med ?: 0.0) < 30) "Good" else if (sr >= .9) "Fair" else "Failing",
                f0(sr * 100) + "% reachable, median " + (med?.let { f1(it) } ?: "–") + " ms")
        }
        w.resolver?.let { dn ->
            val sr = dn.d("sr") ?: 0.0; val med = dn.d("median_ms")
            out += layer("DNS · ${(dn["label"] as JsonPrimitive).content}",
                if (sr >= .99 && med != null && med < 50) "Good" else if (sr >= .9) "Fair" else "Failing",
                f0(sr * 100) + "% reachable, median " + (med?.let { f1(it) } ?: "–") + " ms")
        }
        val sr = w.stats.sr ?: 0.0
        val ips = inp.probes.filter { it.probeType == "public_ip" && it.path == "wifi" }.sortedBy { it.stamp.tUtcMs }.mapNotNull { it.publicIp }
        val changes = if (ips.isEmpty()) 0 else (ips.indices.count { it == 0 || ips[it] != ips[it - 1] }) - 1
        out += layer("WAN · backhaul to internet", if (sr >= .99) "Good" else if (sr >= .95) "Fair" else if (sr >= .9) "Poor" else "Failing",
            fmt("%.1f", sr * 100) + "% of internet checks succeed, p95 " + fmt("%.1f", (w.stats.p95 ?: 0.0) / 1000) + " s, ${stalls.size} stalls (" +
                f0(stalls.sumOf { it.dur } / 60) + " min). Public IP changed ${max(changes, 0)}× (" + ips.distinct().joinToString(", ") + ").")
        val tot = w.stats.tot
        out += layer("Application · HTTPS", if (tot != null && tot < 400) "Good" else if (tot != null && tot < 600) "Fair" else "Poor",
            "Median total " + (tot?.let { f0(it) } ?: "–") + " ms" + (mp?.stats?.tot?.let { " vs ${f0(it)} ms on mobile in parallel." } ?: "."))
        out
    }

    /**
     * The station the run ends nearest to. When recording carried on > 5 min after the last fix (the
     * phone lost location on the train), the run ended further along: take the terminal station in
     * the direction of travel (start station → last fix) beyond the last fix.
     */
    private fun autoDestination(rail: RailData?): JsonObject? {
        val r = rail ?: return null; val last = L.lastOrNull() ?: return null
        if (T1 - last.stamp.tUtcMs > 300_000) {
            // heading: from the station the run started nearest to (else the first fix) to the last fix
            val origin = r.nearestStation(L.first().lat, L.first().lon)
            val oLat = origin?.d("lat") ?: L.first().lat; val oLon = origin?.d("lon") ?: L.first().lon
            val k = cos(Math.toRadians(last.lat))
            val vx = (last.lon - oLon) * k; val vy = last.lat - oLat
            val len = Math.sqrt(vx * vx + vy * vy)
            if (len > 0.005) {
                fun proj(la: Double, lo: Double) = ((lo - oLon) * k * vx + (la - oLat) * vy) / len
                val pLast = proj(last.lat, last.lon)
                val ahead = r.stations.mapNotNull { st -> val la = st.d("lat"); val lo = st.d("lon"); if (la == null || lo == null) null else Triple(st, la, lo) }
                    .filter { (_, la, lo) -> proj(la, lo) > pLast }.maxByOrNull { (_, la, lo) -> proj(la, lo) }
                if (ahead != null) return buildJsonObject { put("name", ahead.first["name"] ?: JsonNull); put("lat", ahead.second); put("lon", ahead.third) }
            }
        }
        return r.nearestStation(last.lat, last.lon)
    }

    // ---------- build
    fun build(): JsonObject {
        val comp = (inp.completeness?.get("collectors") as? JsonObject) ?: JsonObject(emptyMap())
        fun cv(k: String) = comp.d(k)

        // endpoints
        val (ka, kb) = if (wp != null) "cellular" to "wifi" else "cellular" to null
        val act = if (wp != null) "wifi" else "cellular"
        val endpoints = buildJsonArray {
            for (host in lat.map { it.first.endpoint }.distinct()) {
                val da = lat.filter { it.first.endpoint == host && it.first.path == ka && it.second == act }.map { it.first }
                val db = if (kb != null) lat.filter { it.first.endpoint == host && it.first.path == kb && it.second == act }.map { it.first } else emptyList()
                add(buildJsonObject {
                    put("host", host.substringAfterLast("//").substringBefore('/'))
                    put("a", if (da.isNotEmpty()) pstats(da).json() else JsonNull); put("b", if (db.isNotEmpty()) pstats(db).json() else JsonNull)
                })
            }
        }

        // quality
        val logGap = inp.collectorLog.mapNotNull { Regex("(\\d+) s").find(it.message ?: "")?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        val fixGap = if (L.size > 1) ((1 until L.size).maxOf { L[it].stamp.tUtcMs - L[it - 1].stamp.tUtcMs } / 1000).toInt() else 0
        val dev = inp.device.sortedBy { it.stamp.tUtcMs }
        val cellRf = if (ios) 0.0 else cv("cell_serving_polls") ?: if (inp.completeness?.get("cell_rf")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) 1.0 else 0.0
        val quality = buildJsonArray {
            add(buildJsonObject { put("collector", "Latency probes"); putD("captured", cv("latency_probes")); put("note", "${thousands(inp.probes.size)} probes in total") })
            add(buildJsonObject { put("collector", "Location"); putD("captured", cv("location")); put("note", "${thousands(L.size)} fixes; longest gap ${max(logGap, if (ios) 0 else fixGap)} s") })
            add(buildJsonObject {
                put("collector", "Radio / motion / device / path")
                putD("captured", listOf("radio", "motion", "device", "network_path").mapNotNull { cv(it) }.minOrNull())
                put("note", if (dev.isNotEmpty()) {
                    val b0 = dev.first().battery; val b1 = dev.last().battery
                    val th = dev.mapNotNull { it.thermalState }.let { if (it.isEmpty()) "unknown" else majorityMode(it) }
                    (if (b0 != null && b1 != null) "battery ${f0(b0 * 100)}% → ${f0(b1 * 100)}%, " else "") + "thermal $th"
                } else "")
            })
            add(buildJsonObject { put("collector", "Cell RF (RSRP/SINR)"); put("captured", cellRf); put("note", if (ios) "not exposed on iOS" else "") })
            add(buildJsonObject {
                put("collector", "sysdiagnose"); put("captured", JsonNull)
                put("note", if (ios) inp.sysdiagnose ?: (inp.completeness?.get("sysdiagnose") as? JsonPrimitive)?.contentOrNull ?: "none" else "not applicable on Android")
            })
        }

        // KPIs
        val stallMin = stalls.sumOf { it.dur } / 60
        val kp = ArrayList<JsonObject>()
        fun kpi(label: String, value: String, sub: String, tone: String = "", hint: String = "") =
            buildJsonObject { put("label", label); put("value", value); put("sub", sub); put("tone", tone); if (hint.isNotEmpty()) put("hint", hint) }
        if (wp != null) {
            val none = wp.health?.get(3) ?: 0.0
            kp += kpi("Wi-Fi · HTTPS success", fmt("%.1f", (wp.stats.sr ?: 0.0) * 100) + "%",
                mp?.let { "mobile in parallel: " + fmt("%.1f", (it.stats.sr ?: 0.0) * 100) + "%" } ?: "", if (wp.verdict == "FAIL") "bad" else "",
                Hints.success(wp.stats.sr ?: 0.0))
            kp += kpi("Wi-Fi time without internet", f0(none * 100) + "%", "${stalls.size} stalls · ${f0(stallMin)} min", if (none > .2) "bad" else "",
                Hints.noInternet(none))
            kp += kpi("Wi-Fi throughput (median)", fmt("%.1f", wp.thrMedian ?: 0.0) + " Mbps",
                if (mp != null && mp.thr != null) "mobile in parallel: " + fmt("%.1f", mp.thrMedian ?: 0.0) + " Mbps" else "", "",
                Hints.speed(wp.thrMedian))
            kp += kpi("Wi-Fi request p95", fmt("%.1f", (wp.stats.p95 ?: 0.0) / 1000) + " s",
                mp?.let { "mobile in parallel: " + fmt("%.2f", (it.stats.p95 ?: 0.0) / 1000) + " s" } ?: "", "", Hints.p95(wp.stats.p95))
        }
        val lan = layers.firstOrNull { (it["name"] as JsonPrimitive).content.startsWith("LAN") }
        if (wp != null && lan != null) {
            val st = (lan["status"] as JsonPrimitive).content
            kp += kpi("Wi-Fi LAN gateway", st, (lan["detail"] as JsonPrimitive).content, if (st == "Good") "good" else "bad",
                Hints.lan(st == "Good", (wp.stats.sr ?: 0.0) < .95))
        }
        if (ma != null) kp += kpi("$operator mobile (active) · success", fmt("%.1f", (ma.stats.sr ?: 0.0) * 100) + "%",
            f0(ma.thrMedian ?: 0.0) + " Mbps median", if (ma.verdict == "PASS") "good" else "", Hints.mobile(ma.verdict))

        // findings
        val findings = ArrayList<String>()
        if (wp != null && layers.isNotEmpty()) {
            val lanOk = layers.any { (it["name"] as JsonPrimitive).content.startsWith("LAN") && (it["status"] as JsonPrimitive).content == "Good" }
            val sr = wp.stats.sr ?: 0.0
            if (lanOk && sr < .9) findings += "<b>Fault domain is the Wi-Fi WAN/backhaul.</b> The LAN gateway answered reliably while ${f0((1 - sr) * 100)}% of internet checks failed."
            if (mp != null) findings += "<b>Mobile in parallel</b> succeeded ${fmt("%.1f", (mp.stats.sr ?: 0.0) * 100)}% of the time over the same minutes."
        }
        if (phases.isNotEmpty()) findings += "<b>Stalls by phase:</b> " +
            phases.joinToString("; ") { p -> "${p.key} ${f0(p.s / max((p.end - p.start) / 1000.0, 1.0) * 100)}% down" } + "."
        val locCov = cv("location")
        if (L.isNotEmpty() && (locCov ?: 1.0) < .8) findings += "<b>Location coverage was ${f0((locCov ?: 0.0) * 100)}%.</b> Positions are missing or approximate for part of the route."

        // meta
        val meta = buildJsonObject {
            put("run_id", run.runId); put("run_id_short", run.runId.take(8)); put("platform", run.devicePlatform); put("device", run.deviceModel)
            put("os", (if (ios) "iOS" else "Android") + " " + run.osVersion); put("app_version", run.appVersion)
            put("start_ms", T0); put("end_ms", T1); put("tz_offset_min", tzMin); put("mobile_operator", operator)
            put("title", "${run.name.ifBlank { "Network test" }} · ${hm(T0)}–${hm(T1)}" + if (wp != null) ": $ssid vs $operator mobile" else "")
            put("scope", f0((T1 - T0) / 60000.0) + " min of continuous testing; " + segs.joinToString("; ") { "${it.label} ${hm(it.start)}–${hm(it.end)}" } +
                ". ${thousands(inp.probes.size)} probes, ${inp.probes.count { it.probeType == "throughput" }} throughput tests, ${rad.size} radio samples, ${thousands(L.size)} location fixes.")
            put("source_note", "Source: Tracer bundle ${run.runId} · generated by Tracer report v1.")
        }

        // map
        val rail = inp.rail
        val destination = inp.destination ?: autoDestination(rail) ?: rail?.destination
        val gaps = buildJsonArray {
            for (i in 1 until L.size) if (L[i].stamp.tUtcMs - L[i - 1].stamp.tUtcMs > 300_000) add(buildJsonObject {
                put("a", buildJsonArray { add(L[i - 1].lat); add(L[i - 1].lon) }); put("b", buildJsonArray { add(L[i].lat); add(L[i].lon) })
                put("kind", "nofix"); put("label", "${hm(L[i - 1].stamp.tUtcMs)}–${hm(L[i].stamp.tUtcMs)} no fixes")
            })
            val last = L.lastOrNull()
            if (last != null && T1 - last.stamp.tUtcMs > 300_000 && destination != null) {
                val dla = destination.d("lat"); val dlo = destination.d("lon")
                if (dla != null && dlo != null) add(buildJsonObject {
                    put("a", buildJsonArray { add(last.lat); add(last.lon) }); put("b", buildJsonArray { add(dla); add(dlo) })
                    put("kind", "unrecorded"); put("label", "not recorded")
                })
            }
        }
        // stations far from this run's track stay off the map bounds (template: in_view=false) and unlabelled
        val bb = if (L.isNotEmpty()) doubleArrayOf(L.minOf { it.lat }, L.maxOf { it.lat }, L.minOf { it.lon }, L.maxOf { it.lon }) else null
        val destName = (destination?.get("name") as? JsonPrimitive)?.contentOrNull
        val unrecorded = gaps.any { (it as? JsonObject)?.get("kind")?.let { k -> (k as JsonPrimitive).content } == "unrecorded" }
        fun inView(st: JsonObject): Boolean {
            val b = bb ?: return false
            val la = st.d("lat") ?: return false; val lo = st.d("lon") ?: return false
            if (unrecorded && (st["name"] as? JsonPrimitive)?.contentOrNull == destName) return true
            val padLat = max((b[1] - b[0]) * 0.2, 0.05); val padLon = max((b[3] - b[2]) * 0.2, 0.05)
            return la >= b[0] - padLat && la <= b[1] + padLat && lo >= b[2] - padLon && lo <= b[3] + padLon
        }
        val stations = rail?.stations?.map { st -> if (inView(st)) st else JsonObject(st + ("in_view" to JsonPrimitive(false))) } ?: emptyList()
        val labels = buildJsonArray {
            if (L.isNotEmpty()) {
                add(buildJsonObject { put("lat", L.first().lat); put("lon", L.first().lon); put("text", "Start ${hm(T0)}"); put("anchor", "start"); put("dy", -6) })
                add(buildJsonObject { put("lat", L.last().lat); put("lon", L.last().lon); put("text", "Last fix ${hm(L.last().stamp.tUtcMs)}"); put("anchor", "end"); put("dy", 14) })
            }
            stations.forEach { s ->
                if ((s["label"] as? JsonPrimitive)?.booleanOrNull == true && (s["in_view"] as? JsonPrimitive)?.booleanOrNull != false) add(buildJsonObject {
                    put("lat", s["lat"] ?: JsonNull); put("lon", s["lon"] ?: JsonNull); put("text", s["name"] ?: JsonNull)
                    put("anchor", s["anchor"] ?: JsonPrimitive("start")); put("dy", s["dy"] ?: JsonPrimitive(0))
                })
            }
        }
        val basemapAttr = (inp.basemap?.get("attribution") as? JsonPrimitive)?.contentOrNull

        // recommendations: static + rules
        val recs = buildList {
            add("Keep recording until arrival; auto-stop only inside a station geofence.")
            if (ios) add("Run the Android companion for RSRP/SINR, cell ID, Wi-Fi RSSI and raw GNSS.")
            add("Repeat at the same time of day with Wi-Fi-only and mobile-only legs.")
            if (L.isNotEmpty() && (locCov ?: 1.0) < .8) add("Keep location on \"Allow all the time\" and the phone near a window; location coverage was low.")
            if (wp != null && (wp.stats.sr ?: 1.0) < .9 && layers.any { (it["name"] as JsonPrimitive).content.startsWith("LAN") && (it["status"] as JsonPrimitive).content == "Good" })
                add("Escalate the Wi-Fi backhaul (WAN) with the operator: the LAN side was healthy.")
            if ((T1 - T0) < 300_000) add("Record at least 5 minutes so results are comparable.")
        }

        return buildJsonObject {
            put("meta", meta)
            put("kpis", JsonArray(kp.take(6)))
            put("paths", JsonArray(paths.map { it.json }))
            put("segments", buildJsonArray { segs.forEach { s -> add(buildJsonObject { put("kind", s.kind); put("start", s.start); put("end", s.end); put("label", s.label) }) } })
            put("health_bins", buildJsonObject { put("t0", T0); put("bin_ms", ReportData.BIN); put("codes", buildJsonArray { codes.forEach { add(it) } }) })
            put("rat", buildJsonArray { rad.forEachIndexed { i, r -> add(buildJsonObject { put("t", r.stamp.tUtcMs); put("dur", radDur(i)); put("rat", r.rat) }) } })
            put("rat_share", buildJsonObject {
                for (kind in listOf("cellular", "wifi")) {
                    val ww = win(kind) ?: continue
                    val idx = rad.indices.filter { rad[it].stamp.tUtcMs >= ww[0] && rad[it].stamp.tUtcMs < ww[1] }
                    if (idx.isEmpty()) continue
                    val total = idx.sumOf { radDur(it) }.toDouble()
                    val changes = idx.indices.count { j -> j == 0 || rad[idx[j]].rat != rad[idx[j - 1]].rat }
                    put("While on ${if (kind == "cellular") "mobile data" else ssid}", buildJsonObject {
                        put("minutes", (ww[1] - ww[0]) / 60000.0); put("changes", changes)
                        put("share", buildJsonObject { idx.groupBy { rad[it].rat }.toSortedMap().forEach { (k, v) -> put(k, if (total > 0) v.sumOf { radDur(it) } / total else 0.0) } })
                    })
                }
            })
            put("rat_note", if (ios) "iOS does not expose RSRP/SINR; signal levels need the Android companion." else "")
            put("connect_series", buildJsonObject { put("cellular", seriesConnect("cellular")); put("wifi", seriesConnect("wifi")) })
            put("failures", buildJsonObject { put("cellular", seriesFail("cellular")); put("wifi", seriesFail("wifi")) })
            put("stalls", buildJsonArray {
                stalls.forEach { s -> add(buildJsonObject {
                    put("id", s.id); put("t", s.t); put("dur", s.dur); s.phase?.let { put("phase", it) }
                    putD("lat", s.lat); putD("lon", s.lon); putD("acc", s.acc); put("conf", s.conf)
                }) }
            })
            put("mobile_drops", buildJsonArray { mobDrops.forEach { add(it) } })
            put("throughput", buildJsonArray {
                inp.probes.filter { it.probeType == "throughput" }.forEach { p -> add(buildJsonObject {
                    put("t", p.stamp.tUtcMs); put("path", p.path); putD("mbps", p.mbps); put("ok", p.success)
                }) }
            })
            put("fixes_per_min", buildJsonArray { fpm.forEach { (t, n, acc) -> add(buildJsonArray { add(t); add(n); add(acc) }) } })
            put("track", buildJsonObject {
                put("fixes", buildJsonArray {
                    L.forEach { f -> add(buildJsonArray {
                        add(Math.round(f.lat * 1e6) / 1e6); add(Math.round(f.lon * 1e6) / 1e6)
                        add(health(f.stamp.tUtcMs.toDouble(), active(f.stamp.tUtcMs))); add(f.stamp.tUtcMs)
                        add(f.hAccM?.let { Math.round(it * 10) / 10.0 } ?: 0.0)
                    }) }
                })
            })
            put("map", buildJsonObject {
                put("basemap", inp.basemap ?: JsonNull)
                put("rail_lines", rail?.lines ?: JsonArray(emptyList()))
                put("stations", JsonArray(stations))
                put("labels", labels); put("gaps", gaps)
                put("attribution", listOfNotNull(basemapAttr, rail?.attribution).filter { it.isNotBlank() }.joinToString(" · "))
            })
            put("wifi_layers", JsonArray(layers))
            put("endpoints", endpoints)
            put("endpoint_paths", buildJsonArray { add("$operator mobile"); add(if (wp != null) "Wi-Fi" else "–") })
            put("phases", buildJsonArray {
                phases.forEach { p -> add(buildJsonObject {
                    put("state", p.state); put("start", p.start); put("end", p.end); put("key", p.key); put("label", p.label); put("stalls", p.n); put("stall_s", p.s)
                }) }
            })
            put("quality", quality)
            put("findings", buildJsonArray { findings.forEach { add(it) } })
            put("recommendations", buildJsonObject { put("Next test", buildJsonArray { recs.forEach { add(it) } }) })
        }
    }

    init {
        segs.forEach { it.label = if (it.kind == "cellular") "$operator mobile data" else ssid }
    }
}

