package app.tracer.collectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MotionAggregatorTest {

    @Test fun emptyWindowDrainsNull() {
        assertNull(MotionAggregator().drain())
    }

    @Test fun accelMeanAndMaxPerWindow() {
        val a = MotionAggregator()
        a.addAccel(0.0, 0.0, MotionAggregator.GRAVITY_EARTH)       // at rest → 0
        a.addAccel(0.0, 0.0, MotionAggregator.GRAVITY_EARTH + 2.0) // +2
        a.addAccel(0.0, 0.0, MotionAggregator.GRAVITY_EARTH - 1.0) // |−1| = 1
        val agg = a.drain()
        assertNotNull(agg)
        assertEquals(1.0, agg!!.accelMean!!, 1e-9)
        assertEquals(2.0, agg.accelMax!!, 1e-9)
        assertEquals(3, agg.accelCount)
        assertNull(agg.pressureKpa)
        // Accel stats reset after drain
        assertNull(a.drain())
    }

    @Test fun pressureAndRelativeAltitude() {
        val a = MotionAggregator()
        repeat(10) { a.addPressureHpa(1013.25) } // baseline = median of the first 10 readings
        val first = a.drain()!!
        assertEquals(101.325, first.pressureKpa!!, 1e-9)
        assertEquals(0.0, first.relAltM!!, 1e-9)
        repeat(25) { a.addPressureHpa(1001.29) } // ≈ 100 m higher, sustained
        val second = a.drain()!!
        assertEquals(100.0, second.relAltM!!, 1.5)
        assertNull(second.accelMean)
    }

    @Test fun singlePressureSpikeIsRejected() {
        val a = MotionAggregator()
        repeat(20) { a.addPressureHpa(1013.25) }
        a.addPressureHpa(1014.6) // squeeze/door spike ≈ −11 m (seen in "Office test 1")
        a.addPressureHpa(1013.25)
        assertEquals(0.0, a.drain()!!.relAltM!!, 0.01)
    }

    @Test fun altitudeMatchesSensorManagerFormula() {
        assertEquals(0.0, MotionAggregator.altitudeM(1013.25), 1e-9)
        assertEquals(110.9, MotionAggregator.altitudeM(1000.0), 0.5)
    }
}
