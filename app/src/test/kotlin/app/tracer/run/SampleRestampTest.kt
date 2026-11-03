package app.tracer.run

import app.tracer.core.model.AndroidNetworkSample
import app.tracer.core.model.AnnotationSample
import app.tracer.core.model.CellSample
import app.tracer.core.model.DeviceSample
import app.tracer.core.model.EventLabelSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.GapSample
import app.tracer.core.model.GnssRawSample
import app.tracer.core.model.GnssSatelliteSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.MetricKitPayloadSample
import app.tracer.core.model.MotionSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.model.WifiConnectionSample
import app.tracer.core.model.WifiLinkSample
import app.tracer.core.model.WifiRttSample
import app.tracer.core.model.WifiScanSample
import app.tracer.core.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRestampTest {

    private val r = "run-a"
    private val st = Stamp(tUtcMs = 1_000_000L, tMonoNs = 5_000_000_000L, gpsTimeMs = 1_000_350L)

    /** One fully-populated instance of every Sample subclass (non-default values where possible). */
    private fun everySubclass(): List<Sample> = listOf(
        LocationSample(r, st, 25.1, 55.2, 10.0, 3.0, 4.0, 12.0, 0.5, 90.0, 2.0, 1, 1_000_350L, "gps"),
        MotionSample(r, st, "in_vehicle", 80, 101.3, 1.5, 0.2, 1.1),
        NetworkPathSample(r, st, "satisfied", "wifi,cellular", "wifi", false, false, "10.0.0.2", "fe80::1", "good", null),
        RadioSample(r, st, "1", "NR_NSA", "connected", "{\"active\":true}"),
        WifiConnectionSample(r, st, "Home", "aa:bb:cc:dd:ee:ff", "wpa3", -55, 866, 5180, "80", "wifi6"),
        CellSample(r, st, true, "LTE", "424", "02", 1234, 99L, 101, 1650, "3", 20.0, -85, -10, -60, 15, 12, 5, 200, 1),
        WifiScanSample(r, st, 7L, "Cafe", "11:22:33:44:55:66", -70, 2437, "20", "wifi4", "[WPA2]"),
        GnssSatelliteSample(r, st, 12, "GPS", 41.0, 45.0, 180.0, true, 1_575_420_000.0),
        ProbeSample(r, st, "latency", "https://x", true, null, 1.0, 2.0, 3.0, 4.0, 10.0, 100L, 1.5, "wlan0", false, "1.2.3.4", "AS1", "Org"),
        DeviceSample(r, st, 0.8, true, false, "nominal"),
        EventSample(r, st, "latency_spike", "warning", "{}", "{}", 25.0, 55.0, "congestion", 0.7, "{}"),
        AnnotationSample(r, st, "note", "/a.m4a"),
        MetricKitPayloadSample(r, st, "{}"),
        AndroidNetworkSample(r, st, 100L, "wifi", true, false, true, 50_000, 10_000, "wlan0", "1.1.1.1", "dns.google"),
        WifiLinkSample(r, st, "aa:bb:cc:dd:ee:ff", 600, 500, 1200, 1200, "[]"),
        GnssRawSample(r, st, 20, "{}", "[]"),
        WifiRttSample(r, st, "aa:bb:cc:dd:ee:ff", 0, 3000, 200, -60, 8, 7),
        EventLabelSample(r, st, 42L, "confirmed"),
        GapSample(r, st, 990_000L, 1_000_000L, "pause"),
    )

    @Test
    fun coversEverySampleTypeAndTable() {
        val samples = everySubclass()
        // 19 concrete Sample classes exist; the exhaustive `when` in restamp() guarantees compile-time coverage.
        assertEquals(19, samples.map { it::class }.toSet().size)
        val tables = samples.map { it.table }.toSet() + Table.CELL_NEIGHBOUR // CellSample covers both
        assertEquals(Table.entries.toSet() - Table.RUNS, tables)
    }

    @Test
    fun restampChangesOnlyRunIdAndClocks() {
        val newStamp = Stamp(tUtcMs = 2_000_000L, tMonoNs = 9_000_000_000L, gpsTimeMs = 2_000_350L)
        for (s in everySubclass()) {
            val out = s.restamp("run-b", newStamp)
            assertEquals(s::class, out::class)
            assertEquals("run-b", out.runId)
            assertEquals(newStamp, out.stamp)
            assertEquals(s.table, out.table)
            // Round trip restores the original exactly → no payload field was dropped or altered.
            assertEquals(s, out.restamp(r, st))
        }
    }

    @Test
    fun shiftsEmbeddedWallClockFields() {
        val newStamp = Stamp(tUtcMs = 1_500_000L, tMonoNs = 1L)
        val loc = everySubclass().filterIsInstance<LocationSample>().single().restamp("x", newStamp) as LocationSample
        assertEquals(1_500_350L, loc.gpsTimeMs)
        val gap = everySubclass().filterIsInstance<GapSample>().single().restamp("x", newStamp) as GapSample
        assertEquals(1_490_000L, gap.startUtcMs)
        assertEquals(1_500_000L, gap.endUtcMs)
    }

    @Test
    fun scenarioRestampPreservesOrderAndSpacing() {
        val samples = Scenarios.byName("congestion").sortedBy { it.stamp.tMonoNs }
        val first = samples.first().stamp
        val now = Stamp(tUtcMs = 1_800_000_000_000L, tMonoNs = 123_000_000_000L)
        val dUtc = now.tUtcMs - first.tUtcMs
        val dMono = now.tMonoNs - first.tMonoNs
        val out = samples.map { s ->
            s.restamp("live", Stamp(s.stamp.tUtcMs + dUtc, s.stamp.tMonoNs + dMono, s.stamp.gpsTimeMs?.plus(dUtc)))
        }
        assertEquals(now.tUtcMs, out.first().stamp.tUtcMs)
        assertEquals(now.tMonoNs, out.first().stamp.tMonoNs)
        assertTrue(out.zipWithNext().all { (a, b) -> a.stamp.tMonoNs <= b.stamp.tMonoNs })
        assertEquals(
            samples.last().stamp.tMonoNs - samples.first().stamp.tMonoNs,
            out.last().stamp.tMonoNs - out.first().stamp.tMonoNs,
        )
        assertTrue(out.all { it.runId == "live" })
    }
}
