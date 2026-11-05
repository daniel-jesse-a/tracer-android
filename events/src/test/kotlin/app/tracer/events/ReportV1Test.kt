package app.tracer.events

import app.tracer.core.model.*
import app.tracer.core.report.v1.CsvBundle
import app.tracer.core.report.v1.RailData
import app.tracer.core.report.v1.ReportData
import app.tracer.core.report.v1.ReportInput
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Tracer report v1 data builder. Synthetic runs exercise every rule; the same runs can be dumped
 * as a CSV bundle (REPORT_V1_DUMP=dir) and fed to build_report_data.py to cross-check the port.
 * With REPORT_V1_FIXTURE=<bundle dir of run 95596452> the acceptance values are checked.
 */
class ReportV1Test {
    companion object {
        const val T0 = 1_793_796_000_000L
        fun st(t: Long) = Stamp(t, t * 1_000_000L)

        /** 30 min: mobile 0–10 min, then Wi-Fi with stalls; dual-path probes every 2 s. */
        fun synthetic(seed: Int = 1, minutes: Int = 30, wifi: Boolean = true, location: Boolean = true): ReportInput {
            val rnd = Random(seed)
            val t1 = T0 + minutes * 60_000L
            val switchAt = if (wifi) T0 + minutes * 60_000L / 3 else Long.MAX_VALUE
            val run = RunRecord("95596452-aaaa-4bbb-8ccc-000000000001", "Rail · T2 · test", "rail", "android", "Pixel 10 Pro", "17", "1.0.30",
                T0, t1, site = "Rail", testNumber = 2, timeZone = "Asia/Dubai")
            val path = ArrayList<NetworkPathSample>(); val probes = ArrayList<ProbeSample>(); val ev = ArrayList<Pair<Long, EventSample>>()
            val loc = ArrayList<LocationSample>(); val radio = ArrayList<RadioSample>(); val wc = ArrayList<WifiConnectionSample>(); val dev = ArrayList<DeviceSample>()
            path += NetworkPathSample(run.runId, st(T0), "satisfied", "cellular", "cellular")
            if (wifi) path += NetworkPathSample(run.runId, st(switchAt), "satisfied", "wifi,cellular", "wifi")
            // stalls on Wi-Fi: every 3 min, 20–60 s
            val stallIv = ArrayList<LongArray>()
            var id = 1L
            if (wifi) {
                var t = switchAt + 60_000
                while (t < t1 - 60_000) {
                    val d = 20_000L + rnd.nextLong(40_000)
                    stallIv += longArrayOf(t, t + d)
                    ev += id++ to EventSample(run.runId, st(t), "drop", "warning", "{\"path\":\"wifi\"}", "{\"trigger\":\"probe\",\"path\":\"wifi\",\"failover\":\"cellular\"}")
                    ev += id++ to EventSample(run.runId, st(t + d), "reconnect", "info", "{\"trigger\":\"probe\",\"path\":\"wifi\"}", "{\"outage_ms\":$d,\"interface\":\"wifi\"}")
                    t += 180_000
                }
                ev += id++ to EventSample(run.runId, st(T0 + 120_000), "drop", "critical", null, "{\"path\":\"cellular\"}")
            }
            val hosts = listOf("https://cp.cloudflare.com/generate_204", "https://www.google.com/generate_204", "https://captive.apple.com/hotspot-detect.html")
            var t = T0 + 500
            var k = 0
            while (t < t1) {
                val host = hosts[k++ % hosts.size]
                val onWifi = t >= switchAt
                val inStall = stallIv.any { t >= it[0] && t <= it[1] }
                // mobile always probed
                val mOk = rnd.nextDouble() > 0.01
                probes += ProbeSample(run.runId, st(t), "latency", host, mOk, if (mOk) null else "timeout", 2.0, 90.0 + rnd.nextDouble() * 40, 120.0, 110.0,
                    if (mOk) 350.0 + rnd.nextDouble() * 200 else 3000.0, iface = "cellular", isCellular = true)
                if (onWifi) {
                    val wOk = !inStall && rnd.nextDouble() > 0.15
                    probes += ProbeSample(run.runId, st(t + 200), "latency", host, wOk, if (wOk) null else "timeout", 1.0, 180.0 + rnd.nextDouble() * 120, 200.0, 190.0,
                        if (wOk) 550.0 + rnd.nextDouble() * 400 else 3000.0, iface = "wifi", isCellular = false)
                    if (k % 5 == 0) {
                        probes += ProbeSample(run.runId, st(t + 300), "gateway", "192.168.10.1", true, totalMs = 5.0 + rnd.nextDouble() * 3, iface = "wifi", isCellular = false)
                        probes += ProbeSample(run.runId, st(t + 400), "dns_server", "192.168.10.1", true, totalMs = 6.0 + rnd.nextDouble() * 2, iface = "wifi", isCellular = false)
                    }
                }
                t += 2_000
            }
            t = T0 + 30_000
            while (t < t1) {
                probes += ProbeSample(run.runId, st(t), "throughput", "https://speed.cloudflare.com/__down", true, mbps = 40 + rnd.nextDouble() * 60, iface = "cellular", isCellular = true)
                if (t >= switchAt) probes += ProbeSample(run.runId, st(t + 5_000), "throughput", "https://speed.cloudflare.com/__down", true,
                    mbps = if (rnd.nextDouble() < .3) 0.5 else 3 + rnd.nextDouble() * 6, iface = "wifi", isCellular = false)
                if ((t - T0) % 600_000 < 120_000) {
                    probes += ProbeSample(run.runId, st(t + 1_000), "public_ip", "https://ipinfo.io/json", true, iface = "cellular", isCellular = true,
                        publicIp = "94.200.1.1", asnOrg = "Emirates Internet")
                    if (t >= switchAt) probes += ProbeSample(run.runId, st(t + 2_000), "public_ip", "https://ipinfo.io/json", true, iface = "wifi", isCellular = false,
                        publicIp = "5.38.114.${(t / 600_000) % 3 + 1}", asnOrg = "Emirates Internet")
                }
                t += 120_000
            }
            if (location) {
                t = T0
                while (t < t1) {
                    val inGap = t > T0 + 14 * 60_000 && t < T0 + 21 * 60_000
                    if (!inGap) loc += LocationSample(run.runId, st(t), 24.33 + (t - T0) / 1e9, 54.53 + (t - T0) / 5e8,
                        hAccM = if (t > T0 + 21 * 60_000) 1500.0 else 8.0, provider = if (t > T0 + 21 * 60_000) "network" else "gps")
                    t += if (t > T0 + 21 * 60_000) 10_000 else 1_000
                }
            }
            t = T0
            val rats = listOf("LTE", "NR_NSA", "LTE", "UMTS", "LTE")
            var ri = 0
            while (t < t1) { radio += RadioSample(run.runId, st(t), "1", rats[ri++ % rats.size]); t += 45_000 + rnd.nextLong(30_000) }
            if (wifi) {
                t = switchAt
                while (t < t1) { wc += WifiConnectionSample(run.runId, st(t), "Etihad Rail Wi-Fi", "ec:ee:d8:53:6a:4b", "open", -55 - rnd.nextInt(20), 400, 5805, "80", "wifi6"); t += 1_000 }
            }
            dev += DeviceSample(run.runId, st(T0), 0.95, false, false, "nominal"); dev += DeviceSample(run.runId, st(t1 - 1), 0.70, false, false, "nominal")
            val comp = buildJsonObject { put("collectors", buildJsonObject { put("latency_probes", 0.95); put("location", if (location) 0.7 else 0.0); put("radio", 1.0)
                put("motion", 1.0); put("device", 1.0); put("network_path", 1.0); put("cell_serving_polls", 0.9) }) }
            return ReportInput(run, comp, probes.sortedBy { it.stamp.tUtcMs }, ev, radio, path, loc, dev, wc,
                rail = RailData(stations = listOf(
                    buildJsonObject { put("name", "Abu Dhabi Station"); put("lat", 24.334); put("lon", 54.5297); put("label", true); put("anchor", "start"); put("dy", 20) },
                    buildJsonObject { put("name", "Dubai Station"); put("lat", 25.017); put("lon", 55.1679); put("label", true); put("anchor", "end"); put("dy", -6) },
                )))
        }
    }

    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun JsonObject.a(k: String) = this[k]!!.jsonArray
    private fun JsonElement.dd(k: String) = jsonObject[k]!!.jsonPrimitive.double

    @Test fun fullRun() {
        val inp = synthetic()
        val d = ReportData.build(inp)
        dump(inp, d, "full")
        val paths = d.a("paths").map { it.jsonObject }
        assertEquals(listOf("mob_active", "mob_parallel", "wifi_active"), paths.map { it["key"]!!.jsonPrimitive.content })
        val wifi = paths.last()
        assertEquals("FAIL", wifi["verdict"]!!.jsonPrimitive.content)
        assertTrue(wifi.o("stats").dd("sr") < 0.9)
        assertTrue(d.a("stalls").size >= 5)
        assertTrue(d.a("stalls").all { it.jsonObject["phase"] != null })
        assertEquals("e&", d.o("meta")["mobile_operator"]!!.jsonPrimitive.content)
        assertEquals(240, d.o("meta")["tz_offset_min"]!!.jsonPrimitive.int)
        assertTrue(d.a("phases").isNotEmpty())
        assertEquals(5, d.a("wifi_layers").size)
        assertEquals("Good", d.a("wifi_layers")[0].jsonObject["status"]!!.jsonPrimitive.content.let { if (it == "Fair" || it == "Good") "Good" else it })
        assertTrue(d.a("findings").any { it.jsonPrimitive.content.contains("Fault domain") })
        // unrecorded gap needs > 5 min after the last fix: not the case here; nofix gap exists (14–21 min)
        assertTrue(d.o("map").a("gaps").any { it.jsonObject["kind"]!!.jsonPrimitive.content == "nofix" })
        val codes = d.o("health_bins").a("codes").map { it.jsonPrimitive.int }
        assertEquals(120, codes.size)
        assertTrue(codes.contains(3))
        // the HTML injection escapes "</"
        val html = ReportData.inject("<script>__REPORT_DATA__</script>", buildJsonObject { put("x", "</script>") })
        assertFalse(html.contains("\"</script>"))
        assertEquals("Tracer_Rail_T2_20261104-1800_95596452.pdf", ReportData.fileName(inp.run).replace(Regex("_\\d{8}-\\d{4}_"), "_20261104-1800_"))
    }

    @Test fun noWifi() {
        val d = ReportData.build(synthetic(seed = 2, wifi = false))
        assertEquals(listOf("mob_active"), d.a("paths").map { it.jsonObject["key"]!!.jsonPrimitive.content })
        assertEquals(0, d.a("wifi_layers").size); assertEquals(0, d.a("stalls").size); assertEquals(0, d.a("phases").size)
        assertEquals("–", d.a("endpoint_paths")[1].jsonPrimitive.content)
    }

    @Test fun noLocation() {
        val d = ReportData.build(synthetic(seed = 3, location = false))
        assertEquals(0, d.o("track").a("fixes").size)
        assertTrue(d.a("stalls").all { it.jsonObject["conf"]!!.jsonPrimitive.content == "stale" })
        assertEquals(0, d.o("map").a("labels").count { it.jsonObject["text"]!!.jsonPrimitive.content.startsWith("Start") })
    }

    @Test fun shortRunAndEmpty() {
        val d = ReportData.build(synthetic(seed = 4, minutes = 1))
        assertEquals(4, d.o("health_bins").a("codes").size)
        assertTrue(d.a("paths").size <= 2)
        // nothing at all
        val run = RunRecord("00000000-0000-4000-8000-00000000000e", "Empty", "walk", "android", "Pixel", "17", "1", T0, T0 + 90_000)
        val e = ReportData.build(ReportInput(run))
        assertTrue(e.a("paths").isEmpty()); assertTrue(e.a("kpis").isEmpty())
        assertEquals(1, e.a("segments").size)
    }

    /** Acceptance: REPORT_V1_FIXTURE=<dir with run.json + CSVs of run 95596452>. */
    @Test fun fixture95596452() {
        val dir = System.getenv("REPORT_V1_FIXTURE")?.let { File(it) }?.takeIf { it.isDirectory } ?: return
        val d = ReportData.build(CsvBundle.load(dir))
        val p = d.a("paths").associate { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject }
        fun near(exp: Double, got: Double) = assertEquals(exp, got, exp * 0.001)
        near(0.673, p["wifi_active"]!!.o("stats").dd("sr").let { Math.round(it * 1000) / 1000.0 })
        near(0.979, p["mob_parallel"]!!.o("stats").dd("sr").let { Math.round(it * 1000) / 1000.0 })
        near(0.999, p["mob_active"]!!.o("stats").dd("sr").let { Math.round(it * 1000) / 1000.0 })
        assertEquals(72, d.a("stalls").size)
        near(0.47, p["wifi_active"]!!.o("health").dd("3").let { Math.round(it * 100) / 100.0 })
        near(81.7, p["mob_active"]!!.o("thr").dd("median").let { Math.round(it * 10) / 10.0 })
        near(31.1, p["mob_parallel"]!!.o("thr").dd("median").let { Math.round(it * 10) / 10.0 })
        near(4.4, p["wifi_active"]!!.o("thr").dd("median").let { Math.round(it * 10) / 10.0 })
    }

    /** CI: REPORT_V1_SAMPLE_OUT=dir writes sample HTML(s) for headless-Chrome printing (fixture when present, else synthetic). */
    @Test fun writeSamples() {
        val out = System.getenv("REPORT_V1_SAMPLE_OUT")?.let { File(it) } ?: return
        out.mkdirs()
        val tpl = listOf("../ui/src/main/assets/report/tracer-report-template.html", "ui/src/main/assets/report/tracer-report-template.html")
            .map { File(it) }.first { it.exists() }.readText()
        val railFile = listOf("../ui/src/main/assets/report/rail_ergt.json", "ui/src/main/assets/report/rail_ergt.json").map { File(it) }.firstOrNull { it.exists() }
        val rail = railFile?.let { RailData.parse(it.readText()) }
        val fixture = System.getenv("REPORT_V1_FIXTURE")?.let { File(it) }?.takeIf { it.isDirectory }
        val cases = buildList {
            if (fixture != null) add("Tracer_report_95596452" to CsvBundle.load(fixture, rail))
            add("sample_synthetic_full" to synthetic())
            add("sample_no_wifi" to synthetic(seed = 2, wifi = false))
            add("sample_no_location" to synthetic(seed = 3, location = false))
            add("sample_under_2_min" to synthetic(seed = 4, minutes = 1))
        }
        for ((name, inp) in cases) {
            val withRail = if (rail != null && inp.rail?.lines?.isEmpty() != false) ReportInput(inp.run, inp.completeness, inp.probes, inp.events, inp.radio,
                inp.networkPath, inp.locations, inp.device, inp.wifi, inp.collectorLog, rail, inp.basemap, inp.destination) else inp
            val data = ReportData.build(withRail)
            File(out, "$name.json").writeText(data.toString())
            File(out, "$name.html").writeText(ReportData.inject(tpl, data))
        }
    }

    private fun dump(inp: ReportInput, d: JsonObject, name: String) {
        val dir = System.getenv("REPORT_V1_DUMP")?.let { File(it, name) } ?: return
        CsvBundle.write(inp, dir); File(dir, "kotlin.json").writeText(d.toString())
    }
}
