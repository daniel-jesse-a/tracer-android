package app.tracer.events

import app.tracer.core.model.RunRecord
import app.tracer.core.report.*
import app.tracer.core.sim.SyntheticRun
import org.junit.Assert.*
import org.junit.Test

class ReportTest {
    private val run = SyntheticRun()

    /** Office walk: Wi-Fi fades at 60 s, mobile carries 60–155 s, back on Wi-Fi to 192 s; one AP change at 42 s. */
    private fun office() = run.build(192, speedMps = { 1.2 }) {
        location(hAcc = 10.0); radio("LTE"); serving(1001, 101, -83, 25)
        val onWifi = t < 60 || t >= 155
        path(if (onWifi) "wifi" else "cellular")
        if (onWifi) wifi("ER-Mobile", if (t < 42) "aa:aa:aa:aa:aa:01" else "aa:aa:aa:aa:aa:02", rssi = if (t < 60) -60 - t / 2 else -62)
        if (t % 2 == 0) latency(if (onWifi) 40.0 else 120.0, cellular = !onWifi)
        if (t == 20) throughput(333.0, cellular = false)
        if (t == 100) throughput(167.0, cellular = true)
    }

    private fun report(): TestReport {
        val samples = office()
        val rec = RunRecord(run.runId, "Office · T3 · x", "walk", "android", "Pixel 10 Pro", "17", "1.0.16",
            run.startUtcMs, run.startUtcMs + 192_000, site = "Office", testNumber = 3)
        val b = ReportBuilder(rec, probeIntervalS = 2.0)
        samples.forEach(b::add)
        EventEngine(run.runId).replay(samples.asSequence()).forEachIndexed { i, e -> b.event(i.toLong() + 1, e) }
        return b.build()
    }

    @Test fun summaryNumbers() {
        val r = report()
        assertEquals("Office · Test 3", r.title)
        assertEquals(100.0, r.onlinePct!!, 0.01)
        assertEquals("Stayed online", r.headline)
        assertTrue(r.subline, r.subline.contains("Mobile data covered 95 s"))
        assertEquals(2, r.pathSwitches)
        assertTrue(r.apChanges >= 1)
        assertEquals(Verdict.VERY_GOOD, r.wifiSpeedVerdict)
        assertEquals(Verdict.VERY_GOOD, r.mobileSpeedVerdict)
        assertEquals(Verdict.ABOVE_AVG, r.mobileVerdict)       // −83 dBm
        assertEquals(Verdict.ABOVE_AVG, r.responseVerdict)     // 120 ms on mobile
        assertEquals(Verdict.AVERAGE, r.positionVerdict)       // ±10 m
        assertEquals(listOf(Carrier.WIFI, Carrier.MOBILE, Carrier.WIFI), r.segments.map { it.carrier })
        assertTrue(r.moments.map { it.title }.toString(), r.moments.any { it.title == "Changed access point" })
        assertTrue(r.moments.map { it.title }.toString(), r.moments.any { it.title == "Back on Wi-Fi" })
    }

    @Test fun jsonRoundTrip() {
        val r = report()
        val back = ReportJson.decodeString(ReportJson.encodeString(r))
        assertEquals(r, back)
    }

    @Test fun verdictScale() {
        assertEquals(Verdict.BAD, Verdict.wifiRssi(-90.0))
        assertEquals(Verdict.BELOW_AVG, Verdict.stability(4))
        assertEquals(Verdict.BELOW_AVG, Verdict.checks(48.0))
        assertEquals(Verdict.NONE, Verdict.speed(null))
        assertEquals(4, Verdict.ABOVE_AVG.segments)
    }

    @Test fun liveWords() {
        assertEquals("Online via mobile data" to "Wi-Fi too weak since 1:00", LiveWords.headline(Carrier.MOBILE, -90, 60.0, 84.0))
    }
}
