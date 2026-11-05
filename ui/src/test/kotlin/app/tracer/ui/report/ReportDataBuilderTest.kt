package app.tracer.ui.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Tracer report v1 data builder: field-for-field parity with the reference build_report_data.py (expected JSON produced
 * by the Python script from the same bundles), edge cases, injection, file name, and the real 95596452 run.
 */
class ReportDataBuilderTest {
    private val ignore = setOf("$.endpoint_paths[1]")   // reference prints an em dash for "no Wi-Fi"; the app uses an en dash

    private fun parity(name: String) {
        val b = ReportFixtures.bundle(ReportFixtures.untar(ReportFixtures.resource("$name.tar.gz")))
        val rail = ReportFixtures.rail()
        val railed = ReportMap.railWithDestination(rail, ReportMap.lastFixOf(b.tables.location), "Dubai Station")
        // the committed expectations are stored without the internal rail overlay (public repo), so compare everything
        // else; with the rail asset present the overlay parts are checked by the iOS suite and the 95596452 test
        val got = ReportFixtures.withoutRail(Json.parseToJsonElement(ReportDataBuilder.build(b.run, b.tables, railed).toString()) as JsonObject)
        val want = ReportFixtures.gzJson("$name.expected.json.gz") as JsonObject
        val d = ReportFixtures.diff(got, want, ignore = ignore)
        assertTrue("$name: ${d.size} differences\n" + d.take(20).joinToString("\n"), d.isEmpty())
    }

    @Test fun fullBundleMatchesReference() = parity("full")
    @Test fun noWifiBundleMatchesReference() = parity("nowifi")

    @Test fun missingDataNeverThrows() {
        val run = ReportRun("abcdef12-0000-4000-8000-000000000000", "x", 1_000_000, 1_090_000, "android", "Pixel", "16", "1.0.25", null, null)
        val cases = listOf(
            ReportTables(),
            ReportTables(probe = listOf(mapOf("t_utc_ms" to 1_000_100L, "probe_type" to "latency", "success" to 1L, "connect_ms" to 90.0, "interface" to "cellular", "endpoint" to "https://a/b"))),
            ReportTables(location = listOf(mapOf("t_utc_ms" to 1_000_000L, "lat" to 24.4, "lon" to 54.6, "h_acc_m" to null)),
                event = listOf(mapOf("id" to 1L, "t_utc_ms" to 1_000_500L, "event_type" to "drop", "after_json" to "{\"path\":\"wifi\"}"))),
            ReportTables(networkPath = listOf(mapOf("t_utc_ms" to 1_000_000L, "primary_interface" to "wifi")), wifiConnection = listOf(mapOf("t_utc_ms" to 1L, "ssid" to null))),
        )
        for (t in cases) {
            val d = ReportDataBuilder.build(run, t, ReportFixtures.rail())
            val html = ReportRender.inject(ReportFixtures.template(), d)
            assertTrue(html.contains("\"run_id_short\":\"abcdef12\""))
            assertFalse(Regex("\\bNaN\\b").containsMatchIn(ReportRender.reportJson(d)))
        }
    }

    @Test fun androidFillsTheWifiRadioLayer() {
        val run = ReportRun("r", "x", 0, 600_000, "android", "Pixel", "16", "1", null, null)
        val probes = (0 until 200).map { i -> mapOf<String, Any?>("t_utc_ms" to i * 2_900L, "probe_type" to "latency", "success" to 1L, "connect_ms" to 40.0,
            "total_ms" to 120.0, "interface" to "wifi", "endpoint" to "https://a/") }
        val d = ReportDataBuilder.build(run, ReportTables(probe = probes, networkPath = listOf(mapOf("t_utc_ms" to 0L, "primary_interface" to "wifi")),
            wifiConnection = listOf(mapOf("t_utc_ms" to 1L, "ssid" to "Lab", "bssid" to "aa", "security" to "WPA2", "rssi_dbm" to -58L, "link_speed_mbps" to 866L, "frequency_mhz" to 5180L))))
        val radio = d["wifi_layers"]!!.jsonArray[0].jsonObject
        assertEquals("Good", radio["status"]!!.jsonPrimitive.content)
        assertTrue(radio["detail"]!!.jsonPrimitive.content, radio["detail"]!!.jsonPrimitive.content.contains("RSSI median -58 dBm (worst -58), link 866 Mbps, 5 GHz channel 36."))
        assertEquals("NR_NSA", ReportDataBuilder.templateRat("NR_NSA_MMWAVE"))
        assertEquals("LTE", ReportDataBuilder.templateRat("LTE_CA"))
        assertEquals("NONE", ReportDataBuilder.templateRat("IWLAN"))
    }

    @Test fun injectionEscapesAndFileName() {
        val html = ReportRender.inject(ReportFixtures.template(), JsonObject(mapOf("findings" to JsonArray(listOf(JsonPrimitive("</script><b>x</b>"))))))
        assertFalse(html.contains("</script><b>x"))
        assertTrue(html.contains("<\\/script><b>x<\\/b>"))
        assertEquals("Tracer_Abu_Dhabi_Station_3_20261104-1644_95596452.pdf", ReportRender.fileName("Abu Dhabi Station", "rail", 3, 1793796267469, "95596452-bc6b", 240))
        assertEquals("Tracer_rail_0_20261104-1644_95596452.pdf", ReportRender.fileName(null, "rail", null, 1793796267469, "95596452-bc6b", 240))
    }

    @Test fun destinationAndBasemapPlan() {
        val rail = Rail(stations = listOf(Station("Abu Dhabi Station", 24.334, 54.530), Station("Al Faya", 24.375, 54.844), Station("Dubai Station", 25.017, 55.168)))
        assertEquals("Al Faya", ReportMap.railWithDestination(rail, 24.3535 to 54.8398, null)!!.destination!!.name)
        assertEquals("Dubai Station", ReportMap.railWithDestination(rail, 24.3535 to 54.8398, "dubai station")!!.destination!!.name)
        assertNull(ReportMap.railWithDestination(rail, null, null)!!.destination)
        assertEquals("Dubai Station", ReportMap.pickedDestination(listOf(mapOf("t_utc_ms" to 1L, "text" to "mark"), mapOf("t_utc_ms" to 2L, "text" to "destination:Dubai Station"))))
        val plan = ReportMap.planBasemap(listOf(24.450 to 54.380, 24.334 to 54.529, 24.353 to 54.839, 25.017 to 55.167))!!
        val long = maxOf(plan.crop[2], plan.crop[3])
        assertTrue("$long", long in 1000..1400)
        assertTrue(plan.tiles.size <= 64)
    }

    /**
     * CI sample: the injected report HTML for the `full` fixture (or the real run when present), written to
     * build/report-sample/ so the workflow can print it to Tracer_report_sample.pdf with headless Chrome.
     */
    @Test fun writeSampleForCi() {
        val real = listOf("src/test/resources/report-v1/95596452", "ui/src/test/resources/report-v1/95596452").map(::File).firstOrNull { File(it, "run.json").exists() }
        val b = ReportFixtures.bundle(real?.let(ReportFixtures::bundleDir) ?: ReportFixtures.untar(ReportFixtures.resource("full.tar.gz")))
        val rail = ReportMap.railWithDestination(ReportFixtures.rail(), ReportMap.lastFixOf(b.tables.location), ReportMap.pickedDestination(b.annotation) ?: "Dubai Station")
        val data = ReportDataBuilder.build(b.run, b.tables, rail)
        val out = File(if (File("src").exists()) "build/report-sample" else "ui/build/report-sample").apply { mkdirs() }
        File(out, "report-data.json").writeText(data.toString())
        File(out, "report.html").writeText(ReportRender.inject(ReportFixtures.template(), data))
        assertTrue(File(out, "report.html").length() > 30_000)
    }

    /**
     * The real Abu Dhabi → Dubai run. Internal data, so not in this public repo: put the exported bundle (run.json + CSVs)
     * and the kit's sample/report-data.json (as sample-report-data.json) in ui/src/test/resources/report-v1/95596452/.
     */
    @Test fun run95596452MatchesSample() {
        val dir = listOf("src/test/resources/report-v1/95596452", "ui/src/test/resources/report-v1/95596452").map(::File)
            .firstOrNull { File(it, "run.json").exists() && File(it, "sample-report-data.json").exists() }
        assumeTrue("bundle + sample-report-data.json not in test resources report-v1/95596452", dir != null)
        val sample = Json.parseToJsonElement(File(dir, "sample-report-data.json").readText()) as JsonObject
        val b = ReportFixtures.bundle(ReportFixtures.bundleDir(dir!!))
        val rail = ReportMap.railWithDestination(ReportFixtures.rail(), ReportMap.lastFixOf(b.tables.location), "Dubai Station")
        val d = Json.parseToJsonElement(ReportDataBuilder.build(b.run, b.tables, rail, sample["map"]!!.jsonObject["basemap"] as? JsonObject).toString()) as JsonObject
        fun path(o: JsonObject, k: String) = o["paths"]!!.jsonArray.map { it.jsonObject }.first { it["key"]!!.jsonPrimitive.content == k }
        fun check(f: (JsonObject) -> Double, shown: String, digits: Int, what: String) {
            val got = f(d); val want = f(sample)
            assertTrue("$what: $got vs $want", kotlin.math.abs(got - want) <= kotlin.math.abs(want) * 0.001)
            assertEquals("$what as shown", shown, ReportDataBuilder.fx(got, digits))
        }
        check({ path(it, "wifi_active")["stats"]!!.jsonObject["sr"]!!.jsonPrimitive.double * 100 }, "67.3", 1, "Wi-Fi success %")
        check({ path(it, "mob_parallel")["stats"]!!.jsonObject["sr"]!!.jsonPrimitive.double * 100 }, "97.9", 1, "mobile in parallel %")
        check({ path(it, "mob_active")["stats"]!!.jsonObject["sr"]!!.jsonPrimitive.double * 100 }, "99.9", 1, "mobile active %")
        assertEquals(72, d["stalls"]!!.jsonArray.size)
        check({ path(it, "wifi_active")["health"]!!.jsonObject["3"]!!.jsonPrimitive.double * 100 }, "47", 0, "Wi-Fi no internet %")
        check({ path(it, "mob_active")["thr"]!!.jsonObject["median"]!!.jsonPrimitive.double }, "81.7", 1, "mobile active Mbps")
        check({ path(it, "mob_parallel")["thr"]!!.jsonObject["median"]!!.jsonPrimitive.double }, "31.1", 1, "mobile parallel Mbps")
        check({ path(it, "wifi_active")["thr"]!!.jsonObject["median"]!!.jsonPrimitive.double }, "4.4", 1, "Wi-Fi Mbps")
        val want: JsonElement = if (ReportFixtures.rail() == null) ReportFixtures.withoutRail(sample) else sample
        val diffs = ReportFixtures.diff(d, want, ignore = ignore)
        assertTrue(diffs.take(20).joinToString("\n"), diffs.isEmpty())
    }
}
