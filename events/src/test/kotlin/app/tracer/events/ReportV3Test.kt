package app.tracer.events

import app.tracer.core.model.*
import app.tracer.core.report.*
import app.tracer.core.sim.SecondBuilder
import app.tracer.core.sim.SyntheticRun
import org.junit.Assert.*
import org.junit.Test

/** Report v3: observed movement, "not tested" paths, coverage, short tests, edges, integrity, after-resume moments. */
class ReportV3Test {
    private val sim = SyntheticRun()

    private fun rec(durS: Int, mode: String = "walk", site: String? = "Office", test: Int? = 1, startedAt: Long = sim.startUtcMs) =
        RunRecord(sim.runId, "Office · T$test · x", mode, "android", "Pixel 10 Pro", "17", "1.0.17", startedAt, startedAt + durS * 1000L,
            site = site, testNumber = test, tMonoNs = sim.startMonoNs)

    private fun report(durS: Int, mode: String = "walk", speed: Double = 1.2, extra: List<Sample> = emptyList(), events: List<EventSample>? = null,
                       f: SecondBuilder.() -> Unit): TestReport {
        val samples = sim.build(durS, speedMps = { speed }, f = f) + extra
        val b = ReportBuilder(rec(durS, mode), probeIntervalS = 1.0)
        samples.forEach(b::add)
        (events ?: EventEngine(sim.runId).replay(samples.asSequence())).forEachIndexed { i, e -> b.event(i + 1L, e) }
        return b.build()
    }

    private fun SecondBuilder.cellOnly() { location(hAcc = 6.0); radio("LTE"); serving(1001, 101, -85); path("cellular"); latency(40.0, cellular = true) }

    @Test fun walkThatDidNotMoveIsStationary() {
        val r = report(400, speed = 0.0) { cellOnly() }
        assertEquals(true, r.stationary)
        assertNotNull(r.bboxM)
        assertTrue(r.pathM!! < 1.0)
        assertTrue(r.movementNote!!, r.movementNote!!.matches(Regex("Stayed within \\d+ × \\d+ m")))
        // a static test is not "stayed within": that was the plan
        assertNull(report(400, mode = "static", speed = 0.0) { cellOnly() }.movementNote)
    }

    @Test fun realWalkIsNotStationary() {
        val r = report(400, speed = 1.2) { cellOnly() }
        assertEquals(false, r.stationary)
        assertNull(r.movementNote)
        assertEquals(480.0, r.pathM!!, 30.0)
        assertEquals(480.0, kotlin.math.hypot(r.bboxM!!.first, r.bboxM!!.second), 20.0)
    }

    @Test fun badFixesDoNotInflateTheBox() {
        val wild = LocationSample(sim.runId, Stamp(sim.startUtcMs + 50_000, sim.startMonoNs + 50_000_000_000), 25.3, 55.4, hAccM = 5_700.0, provider = "gps")
        val r = report(400, speed = 0.0, extra = listOf(wild)) { cellOnly() }
        assertEquals(true, r.stationary)
    }

    @Test fun wifiNotTestedOnACellularOnlyRun() {
        val r = report(400) { cellOnly() }
        assertFalse(r.wifiTested)
        assertTrue(r.mobileTested)
        assertFalse(r.subline, r.subline.contains("Wi-Fi dipped"))
        assertFalse(r.subline, r.subline.contains("Wi-Fi held"))
        assertEquals("Mobile data only. Wi-Fi not tested.", r.subline)
        assertFalse(r.subline.contains('—'))
    }

    @Test fun nothingTested() {
        val r = report(60) { location() }
        assertFalse(r.wifiTested); assertFalse(r.mobileTested)
        assertEquals("No network was tested.", r.subline)
    }

    @Test fun coverageGapsAndShortTest() {
        // 400 s, the phone suspended the app 200–260 s (no x_gap row) and the watchdog logged an overlapping row
        val gapRow = GapSample(sim.runId, Stamp(sim.startUtcMs + 262_000, sim.startMonoNs + 262_000_000_000), sim.startUtcMs + 230_000, sim.startUtcMs + 262_000, "watchdog")
        val r = report(400, extra = listOf(gapRow)) { if (t !in 200 until 260) cellOnly() }
        assertEquals(1, r.gaps)
        assertEquals(63.0, r.longestGapS, 1.0) // ~199 → 262 s, merged once
        assertEquals(337.0, r.capturedS!!, 1.0)
        assertEquals(84.2, r.coveragePct!!, 0.5)
        assertFalse(r.shortTest)
        assertTrue(r.subline, r.subline.contains("Recording had gaps"))
        assertTrue(report(120) { cellOnly() }.shortTest)
    }

    @Test fun comparisonSkipsShortAndPoorlyCoveredTests() {
        fun rep(test: Int, durS: Int, coverage: Double?, checks: Double = 90.0) =
            ReportBuilder(rec(durS, test = test)).build().copy(durationS = durS.toDouble(), coveragePct = coverage, checksPassedPct = checks)
        fun r(test: Int, start: Long) = rec(600, test = test, startedAt = start).copy(runId = "run-$test")
        val good1 = r(1, 1_000L) to rep(1, 600, 99.0, 48.0)
        val short = r(2, 2_000L) to rep(2, 120, 100.0)
        val holes = r(3, 3_000L) to rep(3, 600, 70.0)
        val good4 = r(4, 4_000L) to rep(4, 600, 97.0)
        val c = Comparison.pick(listOf(good1, short, holes, good4))!!
        assertEquals("Test 4", c.newerLabel); assertEquals("Test 1", c.olderLabel)
        // newest is short → the newest comparable one is used
        val newestShort = r(5, 5_000L) to rep(5, 100, 100.0)
        assertEquals("Test 4", Comparison.pick(listOf(good1, good4, newestShort))!!.newerLabel)
        assertNull(Comparison.pick(listOf(good1, short, holes)))
    }

    @Test fun edgesAndIntegrity() {
        val extra = buildList {
            for (i in 0 until 30) add(ProbeSample(sim.runId, Stamp(sim.startUtcMs + i * 1000L, sim.startMonoNs + i * 1_000_000_000L + 500), "latency",
                "https://cp.cloudflare.com/generate_204", true, connectMs = 20.0, isCellular = false, iface = "wifi", edgePop = if (i < 20) "DXB" else "MCT"))
            for (i in 0 until 5) add(ProbeSample(sim.runId, Stamp(sim.startUtcMs + i * 1000L, sim.startMonoNs + i * 1_000_000_000L + 700), "latency",
                "https://cp.cloudflare.com/generate_204", true, connectMs = 40.0, isCellular = true, iface = "cellular", edgePop = "FRA"))
            add(IntegritySample(sim.runId, Stamp(sim.startUtcMs + 1_000, sim.startMonoNs + 1_000_000_000), "dns_doh", "wifi", "www.google.com", true, "142.250.1.1"))
            add(IntegritySample(sim.runId, Stamp(sim.startUtcMs + 9_000, sim.startMonoNs + 9_000_000_000), "dns_doh", "wifi", "www.google.com", false, "10.0.0.1"))
            add(IntegritySample(sim.runId, Stamp(sim.startUtcMs + 9_000, sim.startMonoNs + 9_000_000_000), "dns_doh", "cellular", "www.google.com", true, "142.250.1.1"))
        }
        val r = report(60, extra = extra) { location() }
        assertTrue(r.wifiTested); assertTrue(r.mobileTested)
        assertEquals(listOf("DXB ×20", "MCT ×10"), r.edgesWifi)
        assertEquals(listOf("FRA ×5"), r.edgesMobile)
        assertEquals(2, r.integrity.size)
        assertEquals(IntegrityResult("dns_doh", "wifi", "www.google.com", false, "10.0.0.1"), r.integrity.first { it.path == "wifi" })
        assertEquals(r, ReportJson.decodeString(ReportJson.encodeString(r)))
    }

    @Test fun afterResumeMomentsAreFlaggedAndNotCounted() {
        val st = Stamp(sim.startUtcMs + 100_000, sim.startMonoNs + 100_000_000_000)
        val drop = EventSample(sim.runId, st, "drop", "critical", afterJson = "{\"path\":\"cellular\"}", evidenceJson = "{\"after_resume\":true,\"window_s\":2.0}")
        val r = report(400, events = listOf(drop)) { cellOnly() }
        val m = r.moments.single { it.kind == "drop" }
        assertTrue(m.afterResume)
        assertFalse(m.stayedOnline)
        assertEquals("Stayed online", r.headline)
        val back = ReportJson.decodeString(ReportJson.encodeString(r))!!
        assertTrue(back.moments.single { it.kind == "drop" }.afterResume)
        // the same drop without the flag counts
        val plain = report(400, events = listOf(drop.copy(evidenceJson = null))) { cellOnly() }
        assertNotEquals("Stayed online", plain.headline)
    }

    @Test fun flickerAndRecoveryAreNotMomentsButGpsEpisodeHasDuration() {
        val r = report(200) {
            cellOnly(); radio(if (t == 50) "UMTS" else "LTE")
            location(hAcc = if (t in 100 until 140) 900.0 else 6.0)
        }
        assertTrue(r.moments.none { it.kind == "rat_flicker" || it.kind == "gps_recovered" })
        val gps = r.moments.single { it.kind == "gps_degraded" }
        assertEquals(50.0, gps.lastedS!!, 2.0) // 100 s → recovered 10 s after the last bad fix
    }

    @Test fun noEmDashInUserText() {
        val keys = listOf("sticky_client", "dns_retry_timer", "tunnel_underpass", "carrier_gateway_change", "something_new")
        keys.forEach { assertFalse(Plain.causeTitle(it).contains('—')) }
        val r = report(400) { cellOnly() }
        listOf(r.headline, r.subline, r.title).forEach { assertFalse(it, it.contains('—')) }
        r.moments.forEach { assertFalse(it.title, it.title.contains('—')) }
    }
}
