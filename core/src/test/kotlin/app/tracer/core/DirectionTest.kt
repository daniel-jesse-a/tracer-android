package app.tracer.core

import app.tracer.core.cell.CarrierSet
import app.tracer.core.model.RunRecord
import app.tracer.core.report.Change
import app.tracer.core.report.ChartText
import app.tracer.core.report.Comparison
import app.tracer.core.report.Delta
import app.tracer.core.report.Direction
import app.tracer.core.report.Metric
import app.tracer.core.report.ReportBuilder
import app.tracer.core.report.Verdict
import org.junit.Assert.*
import org.junit.Test

/** Pins which way is "better" for every judged metric (user request: latency is better when lower). */
class DirectionTest {

    @Test fun lowerIsBetterMetrics() {
        val lower = listOf(
            Metric.RESPONSE, Metric.LATENCY, Metric.JITTER, Metric.LOSS, Metric.DNS, Metric.CONNECT, Metric.TLS, Metric.TTFB,
            Metric.POSITION, Metric.SWITCHES, Metric.DROPS, Metric.FALSE_DROPS, Metric.GAPS, Metric.CLOCK_GAP,
        )
        for (m in lower) {
            assertFalse("$m must be lower-is-better", m.higherIsBetter)
            assertEquals("$m 268 → 180", true, Direction.better(m, 268.0, 180.0))
            assertEquals("$m 180 → 268", false, Direction.better(m, 180.0, 268.0))
            assertEquals("lower is better", m.hint)
        }
    }

    @Test fun higherIsBetterMetrics() {
        val higher = listOf(
            Metric.WIFI_RSSI, Metric.RSRP, Metric.RSRQ, Metric.SINR, Metric.SPEED, Metric.ONLINE, Metric.CHECKS,
            Metric.SATELLITES, Metric.COVERAGE, Metric.GPS_FIXES,
        )
        for (m in higher) {
            assertTrue("$m must be higher-is-better", m.higherIsBetter)
            assertEquals("$m −80 → −60", true, Direction.better(m, -80.0, -60.0))
            assertEquals("$m 50 → 10", false, Direction.better(m, 50.0, 10.0))
            assertEquals("higher is better", m.hint)
        }
        // every metric is classified by the two lists above
        assertEquals(Metric.entries.size, 24)
    }

    @Test fun unchangedAndMissing() {
        assertNull(Direction.better(Metric.RESPONSE, 100.0, 100.0))
        assertNull(Direction.better(Metric.RESPONSE, 100.0, 104.0, tolerance = 5.0))
        assertNull(Direction.better(Metric.RESPONSE, null, 100.0))
        assertNull(Direction.better(Metric.RSRP, -90.0, Double.NaN))
        assertEquals(Change.SAME, Direction.change(Metric.SPEED, 10.0, 10.0))
        assertEquals(Change.UNKNOWN, Direction.change(Metric.SPEED, null, 10.0))
        assertEquals(Change.BETTER, Direction.change(Metric.SWITCHES, 4.0, 0.0))
        assertEquals(Change.WORSE, Direction.change(Metric.SWITCHES, 1.0, 4.0))
    }

    @Test fun goodSideOfThreshold() {
        assertTrue(Direction.isGood(Metric.RESPONSE, 120.0, 150.0))
        assertFalse(Direction.isGood(Metric.RESPONSE, 300.0, 150.0))
        assertTrue(Direction.isGood(Metric.WIFI_RSSI, -60.0, -65.0))
        assertFalse(Direction.isGood(Metric.WIFI_RSSI, -75.0, -65.0))
    }

    /** The verdict scales agree with the direction: a better value never gets a worse verdict. */
    @Test fun verdictsFollowDirection() {
        fun mono(m: Metric, a: Double, b: Double, f: (Double) -> Verdict) {
            val better = Direction.better(m, a, b) ?: return
            val (worse, best) = if (better) a to b else b to a
            assertTrue("$m: verdict($best) ≥ verdict($worse)", f(best).ordinal >= f(worse).ordinal)
        }
        val pairs = listOf(10.0 to 700.0, 40.0 to 200.0, 160.0 to 280.0, -50.0 to -85.0, -95.0 to -105.0, 1.0 to 99.0, 4.0 to 60.0)
        for ((a, b) in pairs) {
            mono(Metric.RESPONSE, a, b) { Verdict.response(it) }
            mono(Metric.POSITION, kotlin.math.abs(a), kotlin.math.abs(b)) { Verdict.position(it) }
            mono(Metric.WIFI_RSSI, a, b) { Verdict.wifiRssi(it) }
            mono(Metric.RSRP, a, b) { Verdict.rsrp(it) }
            mono(Metric.SINR, a, b) { Verdict.sinr(it) }
            mono(Metric.SPEED, kotlin.math.abs(a), kotlin.math.abs(b)) { Verdict.speed(it) }
            mono(Metric.ONLINE, kotlin.math.abs(a), kotlin.math.abs(b)) { Verdict.online(it) }
            mono(Metric.CHECKS, kotlin.math.abs(a), kotlin.math.abs(b)) { Verdict.checks(it) }
            mono(Metric.CLOCK_GAP, kotlin.math.abs(a), kotlin.math.abs(b)) { Verdict.link(it) }
        }
        for ((a, b) in listOf(0 to 1, 1 to 4, 3 to 7, 0 to 9)) mono(Metric.SWITCHES, a.toDouble(), b.toDouble()) { Verdict.stability(it.toInt()) }
    }

    @Test fun deltaText() {
        assertEquals("Response 268 → 180 ms", Delta.of(Metric.RESPONSE, 268.0, 180.0).text)
        assertEquals(true, Delta.of(Metric.RESPONSE, 268.0, 180.0).better)
        assertEquals("False drops 4 → 0", Delta.of(Metric.FALSE_DROPS, 4.0, 0.0).text)
        assertEquals(true, Delta.of(Metric.FALSE_DROPS, 4.0, 0.0).better)
        assertEquals("Switches 1 → 4", Delta.of(Metric.SWITCHES, 1.0, 4.0).text)
        assertEquals(false, Delta.of(Metric.SWITCHES, 1.0, 4.0).better)
        assertEquals("Online 95% → 100%", Delta.of(Metric.ONLINE, 95.0, 99.6).text)
        assertEquals(true, Delta.of(Metric.GPS_FIXES, 0.0, 88.0).better)
    }

    @Test fun comparisonDeltasCarryDirection() {
        val run = RunRecord("r", "Office · T1", "walk", "android", "Pixel", "17", "1.0", 0L, 600_000L, site = "Office", testNumber = 1)
        val base = ReportBuilder(run).build()
        val older = base.copy(falseDrops = 4, gpsFixes = 0, switches = 1, responseMs = 268.0, checksPassedPct = 48.0, onlinePct = 95.0)
        val newer = base.copy(falseDrops = 0, gpsFixes = 88, switches = 4, responseMs = 180.0, checksPassedPct = 97.0, onlinePct = 100.0)
        val c = Comparison(newer, older, "Test 3", "Test 1")
        assertTrue(c.improved)
        val byMetric = c.changes.associateBy { it.metric }
        assertEquals(true, byMetric.getValue(Metric.FALSE_DROPS).better)
        assertEquals(true, byMetric.getValue(Metric.GPS_FIXES).better)
        assertEquals(true, byMetric.getValue(Metric.RESPONSE).better)
        assertEquals("Response 268 → 180 ms", byMetric.getValue(Metric.RESPONSE).text)
        assertEquals(false, byMetric.getValue(Metric.SWITCHES).better)
        assertEquals(true, byMetric.getValue(Metric.ONLINE).better)
        assertEquals(c.changes.map { it.text }, c.deltas)
        // a slower newer test is flagged worse, not better
        val slower = Comparison(older.copy(responseMs = 400.0), older, "Test 2", "Test 1")
        assertEquals(false, slower.changes.first { it.metric == Metric.RESPONSE }.better)
        assertFalse(Comparison(newer.copy(checksPassedPct = 40.0), older, "a", "b").improved)
    }

    @Test fun emptyChartReasons() {
        val run = RunRecord("r", "x", "walk", "android", "Pixel", "17", "1.0", 0L, 600_000L)
        val base = ReportBuilder(run).build()
        val p = listOf(app.tracer.core.report.Pt(10.0, -60.0))
        assertEquals(ChartText.WIFI_NOT_TESTED, ChartText.wifi(base.copy(wifiTested = false)))
        assertEquals(ChartText.WIFI_NO_READINGS, ChartText.wifi(base.copy(wifiTested = true)))
        assertNull(ChartText.wifi(base.copy(wifiRssi = p)))
        assertEquals(ChartText.NOTHING_IN_WINDOW, ChartText.wifi(base.copy(wifiRssi = p), 100.0, 160.0))
        assertEquals(ChartText.MOBILE_NOT_TESTED, ChartText.mobile(base.copy(mobileTested = false)))
        assertEquals(ChartText.MOBILE_NO_READINGS, ChartText.mobile(base.copy(mobileTested = true)))
        // signal recorded while mobile data carried no traffic: still drawn
        assertNull(ChartText.mobile(base.copy(mobileTested = false, rsrpSeries = p)))
        assertEquals(ChartText.NR_NOT_REPORTED, ChartText.nr(base.copy(nrSeen = true, rsrpSeries = p)))
        assertNull(ChartText.nr(base.copy(nrSeen = true, rsrpSeries = p, nrRsrpSeries = p)))
        assertNull(ChartText.nr(base.copy(nrSeen = false, rsrpSeries = p)))
        assertEquals(ChartText.NO_RESPONSE, ChartText.response(base))
    }

    @Test fun carrierJsonParses() {
        val json = """{"active":true,"cell_count":2,"aggregate_mhz":110.0,"bandwidths_khz":[20000,90000],"rat":"NR_NSA",
            "carriers":[{"rat":"LTE","band":"B3","arfcn":1300,"pci":12,"bandwidth_mhz":20.0,"primary":true},
                        {"rat":"NR","band":"n41","arfcn":504990,"pci":null,"bandwidth_mhz":90.0,"primary":false}]}"""
        val s = CarrierSet.parse(json)
        assertEquals(2, s.carriers.size)
        assertEquals(110.0, s.aggregateMhz!!, 0.01)
        assertTrue(s.active)
        assertEquals("B3", s.carriers[0].band); assertTrue(s.carriers[0].primary)
        assertEquals("NR", s.carriers[1].rat); assertEquals(504990, s.carriers[1].arfcn); assertNull(s.carriers[1].pci)
        assertEquals(CarrierSet.EMPTY, CarrierSet.parse(null))
        assertEquals(CarrierSet.EMPTY, CarrierSet.parse("not json"))
        val old = CarrierSet.parse("""{"active":false,"cell_count":1,"aggregate_mhz":20.0,"bandwidths_khz":[20000]}""")
        assertEquals(1, old.carriers.size); assertEquals(20.0, old.carriers[0].bandwidthMhz!!, 0.01)
    }
}
