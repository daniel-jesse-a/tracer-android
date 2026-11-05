package app.tracer.ui.report

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Tracer report v1: run tables → ReportData (REPORT_DATA_CONTRACT.md). Line-for-line port of the reference
 * build_report_data.py, in the same order as the iOS TypeScript port (src/report/v1/build.ts). Pure Kotlin (no
 * Android imports) so it runs in JVM unit tests and in CI. Missing tables or columns give empty sections
 * ("Not captured in this run." in the template), never an exception.
 */

/** One table row keyed by DB column name; values are Long / Double / String / null (SampleCodec) or CSV strings. */
typealias Row = Map<String, Any?>

/** The run.json parts the builder reads: run, device, time_zone, completeness. */
data class ReportRun(
    val runId: String, val name: String?, val startedAt: Long, val endedAt: Long?,
    val platform: String?, val model: String?, val osVersion: String?, val appVersion: String?,
    val timeZone: String?, val completeness: JsonElement?,
) {
    companion object {
        fun fromRunJson(o: JsonObject): ReportRun {
            val run = o["run"] as JsonObject; val dev = (o["device"] as? JsonObject) ?: JsonObject(emptyMap())
            fun s(x: JsonObject, k: String) = (x[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun l(x: JsonObject, k: String) = (x[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
            return ReportRun(s(run, "run_id")!!, s(run, "name"), l(run, "started_at")!!, l(run, "ended_at"),
                s(dev, "platform"), s(dev, "model"), s(dev, "os_version"), s(dev, "app_version"),
                s(o, "time_zone"), o["completeness"])
        }
    }
}

data class Station(val name: String, val lat: Double, val lon: Double, val label: Boolean = false, val anchor: String = "start", val dy: Double = 0.0, val raw: JsonObject? = null)
data class Destination(val name: String, val lat: Double, val lon: Double)
data class Rail(val lines: JsonArray = JsonArray(emptyList()), val stations: List<Station> = emptyList(), val destination: Destination? = null, val attribution: String? = null) {
    companion object {
        fun parse(o: JsonObject): Rail = Rail(
            lines = (o["lines"] as? JsonArray) ?: JsonArray(emptyList()),
            stations = (o["stations"] as? JsonArray).orEmpty().mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                Station((s["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                    (s["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null, (s["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                    (s["label"] as? JsonPrimitive)?.booleanOrNull ?: false, (s["anchor"] as? JsonPrimitive)?.content ?: "start",
                    (s["dy"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, s)
            },
            destination = (o["destination"] as? JsonObject)?.let { d ->
                Destination(d["name"]!!.jsonPrimitive.content, d["lat"]!!.jsonPrimitive.doubleOrNull!!, d["lon"]!!.jsonPrimitive.doubleOrNull!!)
            },
            attribution = (o["attribution"] as? JsonPrimitive)?.content,
        )
    }
}

data class ReportTables(
    val probe: List<Row> = emptyList(), val event: List<Row> = emptyList(), val location: List<Row> = emptyList(),
    val radio: List<Row> = emptyList(), val networkPath: List<Row> = emptyList(), val wifiConnection: List<Row> = emptyList(),
    val device: List<Row> = emptyList(), val collectorLog: List<Row> = emptyList(),
)

object ReportDataBuilder {
    const val BIN = 15_000L

    fun build(run: ReportRun, tables: ReportTables, rail: Rail? = null, basemap: JsonObject? = null, tzOffsetMin: Int? = null): JsonObject {
        val t0 = run.startedAt; val t1 = run.endedAt ?: run.startedAt
        val tzMin = tzOffsetMin ?: if (run.timeZone == "Asia/Dubai" || run.timeZone == "Asia/Muscat") 240 else 0
        val p = tables.probe; val e = tables.event; val w = tables.wifiConnection; val dev = tables.device; val cl = tables.collectorLog
        val loc = sortBy(tables.location.filter { num(it, "lat") != null && num(it, "lon") != null }) { t(it) }
        fun hm(ms: Double): String { val d = Instant.ofEpochMilli(ms.toLong() + tzMin * 60_000L).atOffset(ZoneOffset.UTC); return "%02d:%02d".format(d.hour, d.minute) }

        // ---------- active-path segments (network_path.primary_interface)
        var segs = mutableListOf<Seg>()
        for (r in sortBy(tables.networkPath) { t(it) }) {
            val k = if (str(r, "primary_interface") == "wifi") "wifi" else "cellular"
            if (segs.isEmpty() || segs.last().kind != k) segs.add(Seg(k, max(t(r), t0.toDouble()).toLong(), t1))
            if (segs.size > 1) segs[segs.size - 2].end = segs.last().start
        }
        segs = segs.filter { it.end - it.start > 30_000 }.toMutableList()
        if (segs.isEmpty()) segs = mutableListOf(Seg("cellular", t0, t1))
        segs.first().start = t0; segs.last().end = t1
        fun active(tt: Double): String {
            for (s in segs) if ((s.start <= tt && tt < s.end) || (tt == t1.toDouble() && s.end == t1)) return s.kind
            return segs.last().kind
        }

        val ssid = mode(w.map { str(it, "ssid") }) ?: "Wi-Fi"
        val asnRaw = mode(p.filter { str(it, "probe_type") == "public_ip" && str(it, "interface") == "cellular" }.map { str(it, "asn_org") })
        val operator = when (asnRaw) { null -> "mobile"; "Emirates Internet" -> "e&"; "Emirates Integrated Telecommunications Company PJSC" -> "du"; else -> asnRaw }
        for (s in segs) s.label = if (s.kind == "cellular") "$operator mobile data" else ssid

        // ---------- probes
        fun iface(r: Row) = str(r, "interface")
        fun ok(r: Row) = num(r, "success") == 1.0
        val lat = p.filter { str(it, "probe_type") == "latency" }.map { L(it, active(t(it))) }
        fun pstats(rows: List<Row>): Stats {
            val s = rows.filter(::ok)
            return Stats(rows.size, if (rows.isNotEmpty()) mean(rows.map { num(it, "success") }) else null,
                median(s.map { num(it, "dns_ms") }), median(s.map { num(it, "connect_ms") }), median(s.map { num(it, "tls_ms") }),
                median(s.map { num(it, "ttfb_ms") }), median(s.map { num(it, "total_ms") }), quantile(s.map { num(it, "total_ms") }, 0.95))
        }

        // ---------- stalls: Wi-Fi drop → next Wi-Fi reconnect
        val ev = sortBy(e) { t(it) }
        val recon = ev.filter { str(it, "event_type") == "reconnect" }
        val stalls = mutableListOf<LinkedHashMap<String, Any?>>(); val mobDrops = mutableListOf<Long>()
        for (r in ev.filter { str(it, "event_type") == "drop" }) {
            val pth = pathOf(str(r, "after_json"))
            val nxt = recon.firstOrNull { t(it) > t(r) && (str(it, "before_json") ?: "").contains("\"path\":\"$pth\"") }
            val end = nxt?.let { t(it) } ?: t1.toDouble()
            if (pth == "wifi") stalls.add(linkedMapOf("id" to (num(r, "id") ?: 0.0).toLong(), "t" to t(r).toLong(), "dur" to (end - t(r)) / 1000))
            else mobDrops.add(t(r).toLong())
        }
        val stallIv = stalls.map { (it["t"] as Long).toDouble() to (it["t"] as Long) + (it["dur"] as Double) * 1000 }

        // ---------- health bins (15 s, active path, ±10 s window)
        val latS = sortBy(lat) { t(it.r) }
        val tv = latS.map { t(it.r) }
        fun health(tt: Double, kind: String): Int {
            if (kind == "wifi" && stallIv.any { (a, b) -> a <= tt && b >= tt }) return 3
            val i0 = searchsorted(tv, tt - 10_000); val i1 = searchsorted(tv, tt + 10_000)
            val win = latS.subList(i0, i1).filter { iface(it.r) == kind }.map { it.r }
            if (win.isEmpty()) return -1
            val sr = mean(win.map { num(it, "success") })!!
            val med = median(win.filter(::ok).map { num(it, "connect_ms") })
            if (sr < 0.5) return 3
            if (sr >= 0.95 && med != null && med < 150) return 0
            if (sr >= 0.8 && med != null && med < 400) return 1
            return 2
        }
        val codes = mutableListOf<Int>()
        run { var tt = t0; while (tt < t1) { val c = tt + BIN / 2.0; codes.add(health(c, active(c))); tt += BIN } }
        fun share(lo: Long, hi: Long): LinkedHashMap<String, Double> {
            val c = HashMap<Int, Int>(); var n = 0
            codes.forEachIndexed { i, h -> val s = t0 + i * BIN; if (lo <= s && s < hi) { c[h] = (c[h] ?: 0) + 1; n++ } }
            val d = max(n, 1).toDouble()
            return linkedMapOf("0" to (c[0] ?: 0) / d, "1" to (c[1] ?: 0) / d, "2" to (c[2] ?: 0) / d, "3" to (c[3] ?: 0) / d, "-1" to (c[-1] ?: 0) / d)
        }

        // ---------- paths
        val thr = p.filter { str(it, "probe_type") == "throughput" }
        val dnsS = p.filter { str(it, "probe_type") == "dns_server" }
        val gw = p.filter { str(it, "probe_type") == "gateway" }
        fun inWin(rows: List<Row>, kind: String, act: String) = rows.filter { iface(it) == kind && active(t(it)) == act }
        fun thrStats(rows: List<Row>): LinkedHashMap<String, Any?>? {
            if (rows.isEmpty()) return null
            val v = rows.filter(::ok).mapNotNull { num(it, "mbps") }
            return linkedMapOf("n" to rows.size, "median" to median(v), "min" to v.minOrNull(), "max" to v.maxOrNull(), "under1" to rows.count { (num(it, "mbps") ?: 0.0) < 1 })
        }
        fun win(kind: String): LongArray? { val ss = segs.filter { it.kind == kind }; return if (ss.isEmpty()) null else longArrayOf(ss.first().start, ss.last().end) }
        val paths = mutableListOf<LinkedHashMap<String, Any?>>()
        val defs = listOf(
            listOf("mob_active", "cellular", "cellular", "$operator mobile · active path", "$operator (active)"),
            listOf("mob_parallel", "cellular", "wifi", "$operator mobile · in parallel", "$operator (parallel)"),
            listOf("wifi_active", "wifi", "wifi", ssid, "Wi-Fi"),
        )
        for ((key, kind, actKind, label, short) in defs) {
            val d = lat.filter { iface(it.r) == kind && it.act == actKind }.map { it.r }
            val wv = win(actKind)
            if (d.size < 20 || wv == null) continue
            val st = pstats(d); val th = thrStats(inWin(thr, kind, actKind)); val dn = inWin(dnsS, kind, actKind); val g = inWin(gw, kind, actKind)
            val h = if (kind == actKind) share(wv[0], wv[1]) else null
            val eps = dn.map { str(it, "endpoint") ?: "" }.distinct()
            val gSr = if (g.isNotEmpty()) mean(g.map { num(it, "success") }) else null
            val pp = linkedMapOf<String, Any?>("key" to key, "kind" to kind, "label" to label, "short" to short, "window" to listOf(wv[0], wv[1]),
                "stats" to st.json(), "thr" to th,
                "resolver" to if (dn.isNotEmpty()) linkedMapOf("label" to eps.take(1).joinToString(", ") + (if (eps.size > 1) " +${eps.size - 1}" else ""),
                    "median_ms" to median(dn.map { num(it, "total_ms") }), "sr" to mean(dn.map { num(it, "success") })) else null,
                "gateway" to if (g.isNotEmpty()) linkedMapOf("label" to str(g[0], "endpoint"), "sr" to gSr, "median_ms" to median(g.map { num(it, "total_ms") }),
                    "note" to if (kind == "cellular" && gSr == 0.0) "carrier gateway blocks ICMP" else "") else null,
                "health" to h)
            val sr = st.sr ?: 0.0; val p95 = st.p95 ?: 0.0; val tm = (th?.get("median") as Double?) ?: 0.0; val none = h?.get("3") ?: 0.0
            pp["verdict"] = if (sr < 0.9 || none > 0.2) "FAIL" else if (sr < 0.99 || p95 > 800 || (th != null && tm < 20)) "WATCH" else "PASS"
            pp["verdict_text"] = "${fx(sr * 100, 1)}% of HTTPS checks succeeded, ${st.con?.let { fx(it, 0) } ?: "–"} ms median connect, p95 ${fx(p95, 0)} ms" +
                (if (th != null) ", ${fx(tm, 1)} Mbps median throughput" else "") + (if (h != null && none > 0) ", no internet ${fx(none * 100, 0)}% of the time" else "") + "."
            pp["_stats"] = st; pp["_health"] = h; pp["_thr"] = th
            paths.add(pp)
        }

        // ---------- series
        fun seriesConnect(kind: String): List<List<Any?>> {
            val by = java.util.TreeMap<Long, MutableList<Double>>()
            for (x in lat) if (iface(x.r) == kind && ok(x.r)) {
                val b = floor((t(x.r) - t0) / 30_000).toLong(); val c = num(x.r, "connect_ms")
                val l = by.getOrPut(b) { mutableListOf() }; if (c != null) l.add(c)
            }
            return by.mapNotNull { (b, v) -> median(v)?.let { listOf(t0 + b * 30_000 + 15_000, roundTo(it, 1)) } }
        }
        fun seriesFail(kind: String): List<List<Any?>> {
            val by = java.util.TreeMap<Long, Int>()
            for (x in lat) if (iface(x.r) == kind && num(x.r, "success") == 0.0) { val b = floor((t(x.r) - t0) / 60_000).toLong(); by[b] = (by[b] ?: 0) + 1 }
            return by.map { (b, n) -> listOf(t0 + b * 60_000, n) }
        }
        val radSorted = sortBy(tables.radio) { t(it) }
        val rad = radSorted.mapIndexed { i, r -> Rad(t(r), str(r, "rat") ?: "NONE", (if (i + 1 < radSorted.size) t(radSorted[i + 1]) else t1.toDouble()) - t(r)) }
        val rat = rad.map { linkedMapOf("t" to it.t.toLong(), "dur" to it.dur.toLong(), "rat" to it.rat) }
        val ratShare = linkedMapOf<String, Any?>()
        for (kind in listOf("cellular", "wifi")) {
            val wv = win(kind) ?: continue
            if (rad.isEmpty()) continue
            val d = rad.filter { it.t >= wv[0] && it.t < wv[1] }
            if (d.isEmpty()) continue
            val tot = d.sumOf { it.dur }
            val by = java.util.TreeMap<String, Double>(); for (r in d) by[r.rat] = (by[r.rat] ?: 0.0) + r.dur
            ratShare["While on ${if (kind == "cellular") "mobile data" else ssid}"] = linkedMapOf("minutes" to (wv[1] - wv[0]) / 60_000.0,
                "changes" to changesWithFirst(d.map { it.rat }), "share" to LinkedHashMap(by.mapValues { it.value / tot }))
        }

        // ---------- location
        val fixes = mutableListOf<List<Any?>>(); val fpm = mutableListOf<List<Any?>>()
        val lt = loc.map { t(it) }
        if (loc.isNotEmpty()) {
            for (r in loc) fixes.add(listOf(roundTo(num(r, "lat")!!, 6), roundTo(num(r, "lon")!!, 6), health(t(r), active(t(r))), t(r).toLong(), num(r, "h_acc_m")?.let { roundTo(it, 1) }))
            val by = java.util.TreeMap<Long, MutableList<Row>>()
            for (r in loc) by.getOrPut(floor((t(r) - t0) / 60_000).toLong()) { mutableListOf() }.add(r)
            for ((b, g) in by) fpm.add(listOf(t0 + b * 60_000, g.size, median(g.map { num(it, "h_acc_m") })))
        }
        for (s in stalls) {
            val st = (s["t"] as Long).toDouble()
            val f = if (loc.isEmpty()) null else loc[max(searchsorted(lt, st, right = true) - 1, 0)]
            val acc = f?.let { num(it, "h_acc_m") }; val age = f?.let { (st - t(it)) / 1000 }
            s["lat"] = f?.let { num(it, "lat") }; s["lon"] = f?.let { num(it, "lon") }; s["acc"] = acc
            s["conf"] = if (f != null && acc != null && acc <= 60 && age!! <= 120) "precise" else if (f != null && acc != null && age!! <= 120) "coarse" else "stale"
        }

        // ---------- phases
        var phases = mutableListOf<LinkedHashMap<String, Any?>>()
        val ww = win("wifi")
        if (ww != null && stalls.isNotEmpty()) {
            fun state(tt: Long): String {
                val m = fpm.firstOrNull { (it[0] as Long) <= tt && tt < (it[0] as Long) + 60_000 } ?: return "none"
                val acc = m[2] as Double?
                return if (acc != null && acc <= 60) "precise" else "approx"
            }
            val names = mapOf("precise" to "located (GPS)", "none" to "no location", "approx" to "approximate position")
            val mins = mutableListOf<Long>(); run { var x = ww[0]; while (x < ww[1]) { mins.add(x); x += 60_000 } }
            val raw = mins.map(::state)
            val sm = raw.indices.map { i ->                                                  // ±3 min majority filter (first seen wins ties)
                val c = LinkedHashMap<String, Int>(); for (s in raw.subList(max(0, i - 3), min(raw.size, i + 4))) c[s] = (c[s] ?: 0) + 1
                var best = ""; var n = -1; for ((k, v) in c) if (v > n) { best = k; n = v }; best
            }
            val ph = mutableListOf<LinkedHashMap<String, Any?>>()
            mins.forEachIndexed { i, x ->
                val stt = sm[i]
                if (ph.isNotEmpty() && ph.last()["state"] == stt) ph.last()["end"] = min(x + 60_000, ww[1])
                else ph.add(linkedMapOf("state" to stt, "start" to x, "end" to min(x + 60_000, ww[1])))
            }
            val merged = mutableListOf<LinkedHashMap<String, Any?>>()
            for (q in ph) {                                                                // fold runs < 5 min into the previous phase
                if (merged.isNotEmpty() && ((q["end"] as Long) - (q["start"] as Long) < 300_000 || merged.last()["state"] == q["state"])) merged.last()["end"] = q["end"]
                else merged.add(q)
            }
            phases = merged
            if (phases.isNotEmpty()) phases.last()["end"] = ww[1]
            phases.forEachIndexed { i, q ->
                val key = ('A' + i).toString(); q["key"] = key; q["label"] = "$key · ${names[q["state"]]}"
                val inn = stalls.filter { (q["start"] as Long) <= (it["t"] as Long) && (it["t"] as Long) < (q["end"] as Long) }
                q["stalls"] = inn.size; q["stall_s"] = inn.sumOf { it["dur"] as Double }
                for (s in inn) s["phase"] = key
            }
            phases = phases.filter { (it["end"] as Long) > (it["start"] as Long) }.toMutableList()
        }

        // ---------- Wi-Fi layer diagnosis (rules)
        val wp = paths.firstOrNull { it["key"] == "wifi_active" }; val mp = paths.firstOrNull { it["key"] == "mob_parallel" }
        fun stOf(x: Map<String, Any?>?) = x?.get("_stats") as Stats?
        val layers = mutableListOf<LinkedHashMap<String, Any?>>()
        val platform = run.platform
        val stallSum = stalls.sumOf { it["dur"] as Double }
        if (wp != null) {
            @Suppress("UNCHECKED_CAST") val g = wp["gateway"] as Map<String, Any?>?
            @Suppress("UNCHECKED_CAST") val dn = wp["resolver"] as Map<String, Any?>?
            layers.add(radioLayer(w, ssid, platform))
            if (g != null) { val sr = (g["sr"] as Double?) ?: 0.0; val m = g["median_ms"] as Double?
                layers.add(layer("LAN · gateway ${g["label"]}", if (sr >= 0.99 && (m ?: 0.0) < 30) "Good" else if (sr >= 0.9) "Fair" else "Failing",
                    "${fx(sr * 100, 0)}% reachable, median ${m?.let { fx(it, 1) } ?: "–"} ms")) }
            if (dn != null) { val sr = (dn["sr"] as Double?) ?: 0.0; val m = dn["median_ms"] as Double?
                layers.add(layer("DNS · ${dn["label"]}", if (sr >= 0.99 && m != null && m < 50) "Good" else if (sr >= 0.9) "Fair" else "Failing",
                    "${fx(sr * 100, 0)}% reachable, median ${m?.let { fx(it, 1) } ?: "–"} ms")) }
            val sr = stOf(wp)!!.sr ?: 0.0
            val ips = p.filter { str(it, "probe_type") == "public_ip" && iface(it) == "wifi" }.mapNotNull { str(it, "public_ip") }
            val changes = if (ips.isNotEmpty()) changesWithFirst(ips) - 1 else 0
            layers.add(layer("WAN · backhaul to internet", if (sr >= 0.99) "Good" else if (sr >= 0.95) "Fair" else if (sr >= 0.9) "Poor" else "Failing",
                "${fx(sr * 100, 1)}% of internet checks succeed, p95 ${fx((stOf(wp)!!.p95 ?: 0.0) / 1000, 1)} s, ${stalls.size} stalls (${fx(stallSum / 60, 0)} min). Public IP changed ${max(changes, 0)}× (${ips.distinct().joinToString(", ")})."))
            val tot = stOf(wp)!!.tot
            layers.add(layer("Application · HTTPS", if (tot == null) "No data" else if (tot < 400) "Good" else if (tot < 600) "Fair" else "Poor",
                if (tot == null) "No successful requests." else "Median total ${fx(tot, 0)} ms" + (stOf(mp)?.tot?.let { " vs ${fx(it, 0)} ms on mobile in parallel." } ?: ".")))
        }

        // ---------- endpoints
        val kb = if (wp != null) "wifi" else null; val act = if (wp != null) "wifi" else "cellular"
        val endpoints = lat.mapNotNull { str(it.r, "endpoint") }.distinct().map { host ->
            val da = lat.filter { str(it.r, "endpoint") == host && iface(it.r) == "cellular" && it.act == act }.map { it.r }
            val db = if (kb != null) lat.filter { str(it.r, "endpoint") == host && iface(it.r) == kb && it.act == act }.map { it.r } else emptyList()
            linkedMapOf("host" to host.split("//").last().split("/")[0], "a" to if (da.isNotEmpty()) pstats(da).json() else null, "b" to if (db.isNotEmpty()) pstats(db).json() else null)
        }

        // ---------- KPIs, findings, quality
        val compAll = run.completeness as? JsonObject
        val comp: Map<String, Double> = (compAll?.get("collectors") as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap() ?: emptyMap()
        val gapSecs = cl.mapNotNull { Regex("(\\d+) s").find(str(it, "message") ?: "")?.groupValues?.get(1)?.toLong() }
        val radioKeys = comp.filterKeys { it in setOf("radio", "motion", "device", "network_path") }.values
        val quality = listOf(
            linkedMapOf("collector" to "Latency probes", "captured" to comp["latency_probes"], "note" to "${thousands(p.size)} probes in total"),
            linkedMapOf("collector" to "Location", "captured" to comp["location"], "note" to "${thousands(loc.size)} fixes; longest gap ${if (cl.isNotEmpty()) (gapSecs + 0L).max() else 0} s"),
            linkedMapOf("collector" to "Radio / motion / device / path", "captured" to radioKeys.minOrNull(),
                "note" to if (dev.isNotEmpty()) "battery ${fx((num(dev.first(), "battery") ?: 0.0) * 100, 0)}% → ${fx((num(dev.last(), "battery") ?: 0.0) * 100, 0)}%, thermal ${mode(dev.map { str(it, "thermal_state") }) ?: "unknown"}" else ""),
            linkedMapOf("collector" to "Cell RF (RSRP/SINR)", "captured" to if ((compAll?.get("cell_rf") as? JsonPrimitive)?.booleanOrNull == true) 1.0 else 0.0,
                "note" to if (platform == "ios") "not exposed on iOS" else ""),
            linkedMapOf("collector" to "sysdiagnose", "captured" to null, "note" to (compAll?.get("sysdiagnose")?.takeIf { it !is JsonNull } ?: JsonPrimitive("none"))),
        )
        var kp = mutableListOf<LinkedHashMap<String, Any?>>()
        if (wp != null) {
            val ws = stOf(wp)!!; @Suppress("UNCHECKED_CAST") val wh = wp["_health"] as Map<String, Double>; @Suppress("UNCHECKED_CAST") val wt = wp["_thr"] as Map<String, Any?>?
            val ms = stOf(mp); @Suppress("UNCHECKED_CAST") val mt = mp?.get("_thr") as Map<String, Any?>?
            kp.add(linkedMapOf("label" to "Wi-Fi · HTTPS success", "value" to "${fx((ws.sr ?: 0.0) * 100, 1)}%", "sub" to if (ms != null) "mobile in parallel: ${fx((ms.sr ?: 0.0) * 100, 1)}%" else "", "tone" to if (wp["verdict"] == "FAIL") "bad" else ""))
            kp.add(linkedMapOf("label" to "Wi-Fi time without internet", "value" to "${fx(wh["3"]!! * 100, 0)}%", "sub" to "${stalls.size} stalls · ${fx(stallSum / 60, 0)} min", "tone" to if (wh["3"]!! > 0.2) "bad" else ""))
            kp.add(linkedMapOf("label" to "Wi-Fi throughput (median)", "value" to "${fx((wt?.get("median") as Double?) ?: 0.0, 1)} Mbps", "sub" to if (mp != null && mt != null) "mobile in parallel: ${fx((mt["median"] as Double?) ?: 0.0, 1)} Mbps" else ""))
            kp.add(linkedMapOf("label" to "Wi-Fi request p95", "value" to "${fx((ws.p95 ?: 0.0) / 1000, 1)} s", "sub" to if (ms != null) "mobile in parallel: ${fx((ms.p95 ?: 0.0) / 1000, 2)} s" else ""))
        }
        val lan = layers.filter { (it["name"] as String).startsWith("LAN") }
        if (wp != null && lan.isNotEmpty()) kp.add(linkedMapOf("label" to "Wi-Fi LAN gateway", "value" to lan[0]["status"], "sub" to lan[0]["detail"], "tone" to if (lan[0]["status"] == "Good") "good" else "bad"))
        val ma = paths.firstOrNull { it["key"] == "mob_active" }
        if (ma != null) { @Suppress("UNCHECKED_CAST") val mt = ma["_thr"] as Map<String, Any?>?
            kp.add(linkedMapOf("label" to "$operator mobile (active) · success", "value" to "${fx((stOf(ma)!!.sr ?: 0.0) * 100, 1)}%", "sub" to "${fx((mt?.get("median") as Double?) ?: 0.0, 0)} Mbps median", "tone" to if (ma["verdict"] == "PASS") "good" else "")) }
        kp = kp.take(6).toMutableList()
        val findings = mutableListOf<String>()
        if (wp != null && layers.isNotEmpty()) {
            val lanOk = layers.any { (it["name"] as String).startsWith("LAN") && it["status"] == "Good" }
            val wsr = stOf(wp)!!.sr ?: 0.0
            if (lanOk && wsr < 0.9) findings.add("<b>Fault domain is the Wi-Fi WAN/backhaul.</b> The LAN gateway answered reliably while ${fx((1 - wsr) * 100, 0)}% of internet checks failed.")
            if (mp != null) findings.add("<b>Mobile in parallel</b> succeeded ${fx((stOf(mp)!!.sr ?: 0.0) * 100, 1)}% of the time over the same minutes.")
        }
        if (phases.isNotEmpty()) findings.add("<b>Stalls by phase:</b> " + phases.joinToString("; ") { q ->
            "${q["key"]} ${fx((q["stall_s"] as Double) / max(((q["end"] as Long) - (q["start"] as Long)) / 1000.0, 1.0) * 100, 0)}% down" } + ".")
        if (loc.isNotEmpty() && (comp["location"] ?: 1.0) < 0.8) findings.add("<b>Location coverage was ${fx((comp["location"] ?: 0.0) * 100, 0)}%.</b> Positions are missing or approximate for part of the route.")

        val ios = platform == "ios"
        val meta = linkedMapOf("run_id" to run.runId, "run_id_short" to run.runId.take(8), "platform" to platform, "device" to run.model,
            "os" to "${if (ios) "iOS" else "Android"} ${run.osVersion ?: ""}", "app_version" to run.appVersion, "start_ms" to t0, "end_ms" to t1,
            "tz_offset_min" to tzMin, "mobile_operator" to operator,
            "title" to "${run.name?.takeIf { it.isNotEmpty() } ?: "Network test"} · ${hm(t0.toDouble())}–${hm(t1.toDouble())}" + (if (wp != null) ": $ssid vs $operator mobile" else ""),
            "scope" to "${fx((t1 - t0) / 60_000.0, 0)} min of continuous testing; " + segs.joinToString("; ") { "${it.label} ${hm(it.start.toDouble())}–${hm(it.end.toDouble())}" } +
                ". ${thousands(p.size)} probes, ${thr.size} throughput tests, ${rad.size} radio samples, ${thousands(loc.size)} location fixes.",
            "source_note" to "Source: Tracer bundle ${run.runId} · generated by Tracer report v1.")

        // ---------- map
        val r0 = rail ?: Rail()
        val gaps = mutableListOf<Map<String, Any?>>(); val labels = mutableListOf<Map<String, Any?>>()
        if (loc.isNotEmpty()) {
            for (i in 1 until loc.size) if (lt[i] - lt[i - 1] > 300_000) gaps.add(linkedMapOf("a" to listOf(num(loc[i - 1], "lat"), num(loc[i - 1], "lon")),
                "b" to listOf(num(loc[i], "lat"), num(loc[i], "lon")), "kind" to "nofix", "label" to "${hm(lt[i - 1])}–${hm(lt[i])} no fixes"))
            val first = loc.first(); val last = loc.last()
            labels.add(linkedMapOf("lat" to num(first, "lat"), "lon" to num(first, "lon"), "text" to "Start ${hm(t0.toDouble())}", "anchor" to "start", "dy" to -6))
            labels.add(linkedMapOf("lat" to num(last, "lat"), "lon" to num(last, "lon"), "text" to "Last fix ${hm(t(last))}", "anchor" to "end", "dy" to 14))
            val dsn = r0.destination
            if (t1 - t(last) > 300_000 && dsn != null) gaps.add(linkedMapOf("a" to listOf(num(last, "lat"), num(last, "lon")), "b" to listOf(dsn.lat, dsn.lon), "kind" to "unrecorded", "label" to "not recorded"))
        }
        for (s in r0.stations) if (s.label) labels.add(linkedMapOf("lat" to s.lat, "lon" to s.lon, "text" to s.name, "anchor" to s.anchor, "dy" to s.dy))
        val bmAttr = (basemap?.get("attribution") as? JsonPrimitive)?.content

        for (q in paths) { q.remove("_stats"); q.remove("_health"); q.remove("_thr") }
        return toJson(linkedMapOf(
            "meta" to meta, "kpis" to kp, "paths" to paths, "segments" to segs.map { linkedMapOf("kind" to it.kind, "start" to it.start, "end" to it.end, "label" to it.label) },
            "health_bins" to linkedMapOf("t0" to t0, "bin_ms" to BIN, "codes" to codes), "rat" to rat, "rat_share" to ratShare,
            "rat_note" to if (ios) "iOS does not expose RSRP/SINR; signal levels need the Android companion." else "",
            "connect_series" to linkedMapOf("cellular" to seriesConnect("cellular"), "wifi" to seriesConnect("wifi")),
            "failures" to linkedMapOf("cellular" to seriesFail("cellular"), "wifi" to seriesFail("wifi")),
            "stalls" to stalls, "mobile_drops" to mobDrops,
            "throughput" to thr.map { linkedMapOf("t" to t(it).toLong(), "path" to iface(it), "mbps" to num(it, "mbps"), "ok" to ok(it)) },
            "fixes_per_min" to fpm, "track" to linkedMapOf("fixes" to fixes),
            "map" to linkedMapOf("basemap" to basemap, "rail_lines" to r0.lines, "stations" to r0.stations.map { it.raw ?: JsonObject(emptyMap()) },
                "labels" to labels, "gaps" to gaps, "attribution" to listOfNotNull(bmAttr, r0.attribution).filter { it.isNotEmpty() }.joinToString(" · ")),
            "wifi_layers" to layers, "endpoints" to endpoints, "endpoint_paths" to if (wp != null) listOf("$operator mobile", "Wi-Fi") else listOf("$operator mobile", "–"),
            "phases" to phases, "quality" to quality, "findings" to findings,
            "recommendations" to linkedMapOf("Next test" to listOf("Keep recording until arrival; auto-stop only inside a station geofence.",
                "Run the Android companion for RSRP/SINR, cell ID, Wi-Fi RSSI and raw GNSS.", "Repeat at the same time of day with Wi-Fi-only and mobile-only legs.")),
        )) as JsonObject
    }

    /**
     * Radio · phone ↔ AP. iPhone apps get no RSSI, so the layer is "No data" (reference wording). Android fills it from
     * wifi_connection (WifiInfo): median RSSI on the app's Wi-Fi scale (Good ≥ −65, Fair ≥ −72, Poor ≥ −80 dBm, else Failing),
     * link speed, band and channel.
     */
    private fun radioLayer(w: List<Row>, ssid: String, platform: String?): LinkedHashMap<String, Any?> {
        val bssids = w.mapNotNull { str(it, "bssid") }.distinct().size
        val sec = mode(w.map { str(it, "security") }) ?: "unknown"
        val rssi = median(w.map { num(it, "rssi_dbm") })
        if (platform == "ios" || rssi == null) return layer("Radio · phone ↔ AP", "No data",
            "SSID \"$ssid\", $bssids BSSID(s), security $sec. RSSI not exposed by the OS API; use the Android companion or sysdiagnose.")
        val link = median(w.map { num(it, "link_speed_mbps") })
        val freq = mode(w.map { num(it, "frequency_mhz") })
        val worst = w.mapNotNull { num(it, "rssi_dbm") }.minOrNull()
        val band = freq?.let { if (it >= 5925) "6 GHz" else if (it >= 4900) "5 GHz" else "2.4 GHz" }
        val channel = freq?.let { wifiChannel(it.toInt()) }
        return layer("Radio · phone ↔ AP", if (rssi >= -65) "Good" else if (rssi >= -72) "Fair" else if (rssi >= -80) "Poor" else "Failing",
            "SSID \"$ssid\", $bssids BSSID(s), security $sec. RSSI median ${fx(rssi, 0)} dBm (worst ${fx(worst ?: rssi, 0)})" +
                (link?.let { ", link ${fx(it, 0)} Mbps" } ?: "") + (band?.let { ", $it" } ?: "") + (channel?.let { " channel $it" } ?: "") + ".")
    }

    fun wifiChannel(f: Int): Int? = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5955..7115 -> (f - 5950) / 5
        f in 5000..5900 -> (f - 5000) / 5
        else -> null
    }

    /** radio.rat as the Android collector writes it (TelephonyDisplayInfo override, then network type) → template RAT keys. */
    fun templateRat(rat: String?): String = when (rat) {
        null -> "NONE"
        "NR_SA" -> "NR_SA"
        "NR_NSA", "NR_NSA_MMWAVE", "NR_ADVANCED", "NR" -> "NR_NSA"
        "LTE", "LTE_CA", "LTE_ADV_PRO" -> "LTE"
        "HSPA", "UMTS", "GSM", "NONE" -> rat
        else -> "NONE"     // IWLAN, UNKNOWN
    }

    // ---------------------------------------------------------------- internals

    private class Seg(val kind: String, var start: Long, var end: Long, var label: String? = null)
    private class L(val r: Row, val act: String)
    private class Rad(val t: Double, val rat: String, val dur: Double)
    private class Stats(val n: Int, val sr: Double?, val dns: Double?, val con: Double?, val tls: Double?, val ttfb: Double?, val tot: Double?, val p95: Double?) {
        fun json() = linkedMapOf("n" to n, "sr" to sr, "dns" to dns, "con" to con, "tls" to tls, "ttfb" to ttfb, "tot" to tot, "p95" to p95)
    }

    private fun layer(name: String, status: String, detail: String) = linkedMapOf<String, Any?>("name" to name, "status" to status, "detail" to detail)
    private fun pathOf(js: String?): String? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(js ?: "") as JsonObject
        ((o["path"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content) ?: (o["interface"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }.getOrNull()

    private fun t(r: Row) = num(r, "t_utc_ms") ?: 0.0
    fun num(r: Row, k: String): Double? = when (val v = r[k]) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }
    fun str(r: Row, k: String): String? = when (val v = r[k]) { null -> null; is String -> v.ifEmpty { null }; is Double -> if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString(); else -> v.toString() }

    // pandas / Python semantics
    fun median(xs: List<Double?>): Double? { val v = xs.filterNotNull().sorted(); if (v.isEmpty()) return null; val m = v.size / 2; return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2 }
    fun quantile(xs: List<Double?>, q: Double): Double? { val v = xs.filterNotNull().sorted(); if (v.isEmpty()) return null; val pos = (v.size - 1) * q; val lo = floor(pos).toInt(); val hi = ceil(pos).toInt(); return v[lo] + (v[hi] - v[lo]) * (pos - lo) }
    fun mean(xs: List<Double?>): Double? { val v = xs.filterNotNull(); return if (v.isEmpty()) null else v.sum() / v.size }
    fun <T : Comparable<T>> mode(xs: List<T?>): T? { val c = xs.filterNotNull().groupingBy { it }.eachCount(); if (c.isEmpty()) return null; val top = c.values.max(); return c.filterValues { it == top }.keys.min() }
    fun <T> changesWithFirst(xs: List<T>): Int = xs.indices.count { it == 0 || xs[it] != xs[it - 1] }
    fun searchsorted(a: List<Double>, v: Double, right: Boolean = false): Int { var lo = 0; var hi = a.size; while (lo < hi) { val mid = (lo + hi) ushr 1; if (if (right) a[mid] <= v else a[mid] < v) lo = mid + 1 else hi = mid }; return lo }
    /** Python format(x, '.Nf'): exact binary value, ties to even. */
    fun fx(x: Double, d: Int): String = if (!x.isFinite()) (if (x.isNaN()) "nan" else if (x > 0) "inf" else "-inf") else BigDecimal(x).setScale(d, RoundingMode.HALF_EVEN).toPlainString()
    fun thousands(n: Int): String = "%,d".format(java.util.Locale.US, n)
    fun roundTo(x: Double, d: Int): Double = BigDecimal(x).setScale(d, RoundingMode.HALF_EVEN).toDouble()
    private fun <T> sortBy(xs: List<T>, key: (T) -> Double): List<T> = xs.sortedBy(key)   // stable

    /** Kotlin values → JSON; non-finite numbers become null (template JSON.parse must succeed). */
    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Double -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
        is Float -> if (v.isFinite()) JsonPrimitive(v) else JsonNull
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) }.let { LinkedHashMap(it) })
        is Iterable<*> -> JsonArray(v.map(::toJson))
        is LongArray -> JsonArray(v.map { JsonPrimitive(it) })
        else -> JsonPrimitive(v.toString())
    }
}
