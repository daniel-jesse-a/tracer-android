package app.tracer.core

import app.tracer.core.analysis.Method
import app.tracer.core.analysis.Protocol
import app.tracer.core.analysis.SummaryBuilder
import app.tracer.core.cell.Carriers
import app.tracer.core.config.ProbeEndpoint
import app.tracer.core.config.ProbePaths
import app.tracer.core.config.RunSettings
import app.tracer.core.model.*
import app.tracer.core.sim.Scenarios
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V2Test {
    private val st = Stamp(1, 1)

    /** Office test 3 (Pixel 10 Pro): bandwidths [20000, 90000], LTE B3 PCell + NR n41 leg reported as a neighbour, display "LTE_CA". */
    @Test fun ninetyMhzCarrierIsNrNotLte() {
        val cells = listOf(
            CellSample("r", st, true, "LTE", "424", "02", 42115, 3525121, 225, 1651, "B3", 20.0, -83, -4, null, 25, 13, 1, 200, 2),
            CellSample("r", st, false, "NR", null, null, null, null, 960, 522990, "n41", null, -95, -11, null, 12, null, null, 200, 2),
        )
        val d = Carriers.derive("LTE_CA", listOf(20000, 90000), cells)
        assertEquals("NR_NSA", d.rat)
        assertEquals("nsa", d.nrState)
        assertEquals(listOf("LTE", "NR"), d.carriers.map { it.rat })
        val nr = d.carriers[1]
        assertEquals("n41", nr.band); assertEquals(522990, nr.arfcn); assertEquals(960, nr.pci); assertEquals(90.0, nr.bandwidthMhz!!, 0.0)
        assertEquals("B3", d.carriers[0].band); assertTrue(d.carriers[0].primary)
        assertTrue(d.carrierAggregationJson.contains("\"carriers\""))
        // override says NR_NSA → nsa; NR_ADVANCED → nsa_advanced; NR with no override → sa
        assertEquals("nsa_advanced", Carriers.derive("NR_ADVANCED", listOf(20000, 100000), cells).nrState)
        assertEquals("sa", Carriers.derive("NR_SA", listOf(100000), emptyList()).nrState)
        assertEquals("none", Carriers.derive("LTE", listOf(20000), cells.take(1)).nrState)
        assertEquals("LTE", Carriers.derive("LTE", listOf(20000), cells.take(1)).rat)
    }

    @Test fun methodFingerprintFlagsDifferentMethods() {
        val a = RunSettings()
        assertEquals(Method.fingerprint(a), Method.fingerprint(a.copy(retentionDays = 7, openCellIdKey = "x")))   // non-method settings don't matter
        assertNotEquals(Method.fingerprint(a), Method.fingerprint(a.copy(throughputBytes = 1_000_000)))
        assertNotEquals(Method.fingerprint(a), Method.fingerprint(a.copy(dualPathProbing = false)))
        assertNotEquals(Method.fingerprint(a), Method.fingerprint(a.copy(thresholds = a.thresholds.copy(latencyMinMs = 200.0))))
        assertNotEquals(Method.fingerprint(a), Method.fingerprint(a.copy(endpoints = a.endpoints.drop(1))))
        // protocol settings are frozen → same fingerprint every time, different per variant
        val p1 = Protocol.byId("walk10-dual")!!; val p2 = Protocol.byId("walk10-cell")!!
        assertEquals(Method.fingerprint(p1.settings(), p1.id), Method.fingerprint(p1.settings(), p1.id))
        assertNotEquals(Method.fingerprint(p1.settings(), p1.id), Method.fingerprint(p2.settings(), p2.id))
        assertEquals(ProbePaths.CELLULAR_ONLY, p2.settings().paths)
        assertEquals(listOf("throughput_bytes"), Method.diff(Method.canonical(a), Method.canonical(a.copy(throughputBytes = 1))))
    }

    @Test fun endpointExpectation() {
        val e = ProbeEndpoint("https://x/generate_204", "x", expectStatus = 204)
        assertTrue(e.isExpected(204)); assertFalse(e.isExpected(200)); assertFalse(e.isExpected(302))
        assertTrue(ProbeEndpoint("https://y/", "y").isExpected(200)); assertFalse(ProbeEndpoint("https://y/", "y").isExpected(302))
    }

    @Test fun settingsV3Migration() {
        val v2 = """{"settings_version":2,"wifi_rtt_enabled":false,"endpoints":[{"url":"https://cp.cloudflare.com/generate_204","label":"Cloudflare"}]}"""
        val s = RunSettings.fromJson(v2)
        assertTrue("v3 turns RTT on", s.wifiRttEnabled)
        assertEquals(204, s.endpoints.single().expectStatus)
        val v3 = RunSettings.fromJson(RunSettings(wifiRttEnabled = false).toStorageJson())
        assertFalse(v3.wifiRttEnabled)
    }

    @Test fun summaryAggregatesPerPath() {
        val run = RunRecord("r", "Office · T1 · x", "walk", "android", "Pixel", "17", "1", 1_760_000_000_000L, 1_760_000_120_000L,
            settingsJson = RunSettings().toJson().toString(), timeZone = "Asia/Dubai", tMonoNs = 5_000_000_000_000L)
        val b = SummaryBuilder(run)
        val samples = Scenarios.byName("sticky_wifi") + listOf(
            ProbeSample("r", st, "latency", "https://e", true, httpStatus = 302, redirectCount = 1, finalUrl = "https://e/en", expectedResponse = false, isCellular = false, connectMs = 30.0, totalMs = 90.0),
            ProbeSample("r", st, "gateway", "192.168.1.1", true, totalMs = 4.0, isCellular = false),
            WifiRoamSample("r", st, "Office", "a", "b", "5", "5", -80, -60, 0, 25.0, 30.0, 55.0, true, true, false),
            EventSample("r", st, "drop", "warning"),
        )
        samples.forEach(b::add)
        val j = b.build(RunSettings())
        val wifi = j["paths"]!!.jsonObject["wifi"]!!.jsonObject
        assertTrue(wifi["reachable_rate"]!!.jsonPrimitive.double > 0.9)
        assertEquals(0.0, wifi["expected_response_rate"]!!.jsonPrimitive.double, 0.0) // only the redirect row carried an expectation
        assertEquals(4.0, wifi["internal"]!!.jsonObject["gateway_rtt_ms"]!!.jsonObject["median"]!!.jsonPrimitive.double, 0.0)
        assertNotNull(wifi["latency_ms"]!!.jsonObject["ttfb"]!!.jsonObject["p95"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(1, j["wifi_mobility"]!!.jsonObject["roams"]!!.jsonPrimitive.int)
        assertEquals(1, j["events"]!!.jsonObject["by_type_severity"]!!.jsonObject["drop:warning"]!!.jsonPrimitive.int)
        assertEquals(16, j["method_fingerprint"]!!.jsonPrimitive.content.length)
        assertNotNull(j["radio"]!!.jsonObject["wifi_rssi_worst_dbm"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun runNaming() {
        assertEquals("Office · T3 · 2026-11-03 18:31", RunRecord.displayName("Office", 3, "2026-11-03 18:31"))
        assertEquals("etihad-rail-hq_t03_20261103-1831", RunRecord.fileStem("Etihad Rail HQ", 3, "20261103-1831"))
    }

    @Test fun fingerprintUsesSettingsAsRecorded() {
        val v1 = """{"location_interval_ms":1000,"throughput_bytes":1000000,"throughput_interval_ms":60000,"endpoints":[{"url":"https://1.1.1.1/cdn-cgi/trace","label":"Cloudflare"}]}"""
        val now = RunSettings().toJson().toString()
        assertNotEquals(Method.fingerprintOfRecorded(v1), Method.fingerprintOfRecorded(now))
        assertEquals("https://1.1.1.1/cdn-cgi/trace", RunSettings.fromJson(v1, migrate = false).endpoints.single().url)
        assertEquals("https://cp.cloudflare.com/generate_204", RunSettings.fromJson(v1).endpoints.single().url)
    }
}
