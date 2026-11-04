package app.tracer.core

import app.tracer.core.geo.PositionFilter
import app.tracer.core.model.LocationSample
import app.tracer.core.model.Stamp
import app.tracer.core.util.Geo
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PositionFilterTest {
    private fun fix(i: Int, lat: Double, lon: Double, acc: Double, provider: String = "fused", speed: Double? = null) =
        LocationSample("r", Stamp(1_000L * i, 1_000_000_000L * i), lat, lon, hAccM = acc, speedMps = speed, provider = provider)

    /** Indoors on a desk: fused/network fixes wander ±25 m; the display position must stay within a few metres. */
    @Test fun stationaryIndoorsStaysPut() {
        val rnd = Random(7)
        val f = PositionFilter()
        val lat0 = 25.2; val lon0 = 55.27
        val out = (0 until 300).mapNotNull { i ->
            val dLat = (rnd.nextDouble() - 0.5) * 50 / 111_320.0
            val dLon = (rnd.nextDouble() - 0.5) * 50 / 100_700.0
            val prov = if (i % 7 == 0) "network" else "fused"
            f.add(fix(i, lat0 + dLat, lon0 + dLon, if (prov == "network") 40.0 else 12.0, prov), still = if (i > 20) true else null)
        }
        val tail = out.drop(60)
        val spread = tail.maxOf { a -> tail.maxOf { b -> Geo.haversineM(a.lat, a.lon, b.lat, b.lon) } }
        assertTrue("display spread $spread m", spread < 8.0)
    }

    /** Walking 1.4 m/s with GPS: the filter must keep up (lag < 10 m). */
    @Test fun walkingFollows() {
        val f = PositionFilter()
        var last: LocationSample? = null
        for (i in 0 until 120) {
            val lat = 25.2 + i * 1.4 / 111_320.0
            last = f.add(fix(i, lat, 55.27, 5.0, "gps", speed = 1.4), still = false) ?: last
        }
        val truth = 25.2 + 119 * 1.4 / 111_320.0
        val lag = Geo.haversineM(last!!.lat, last.lon, truth, 55.27)
        assertTrue("lag $lag m", lag < 10.0)
    }

    /** A 2 km outlier (cell-tower fix) is ignored. */
    @Test fun rejectsOutlier() {
        val f = PositionFilter()
        repeat(20) { f.add(fix(it, 25.2, 55.27, 8.0)) }
        val r = f.add(fix(21, 25.22, 55.27, 1500.0, "network"))
        assertTrue(r == null || Geo.haversineM(r.lat, r.lon, 25.2, 55.27) < 5)
    }
}
