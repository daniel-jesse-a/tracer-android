package app.tracer.collectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedFilterTest {
    @Test fun dropsSpeedWithinItsAccuracy() {
        assertNull(speedOrNull(0.4, 0.9))
        assertNull(speedOrNull(1.0, 1.0))
        assertNull(speedOrNull(0.0, 0.1))
        assertNull(speedOrNull(null, 0.5))
        assertNull(speedOrNull(Double.NaN, null))
    }

    @Test fun keepsMeaningfulSpeed() {
        assertEquals(1.4, speedOrNull(1.4, 0.3)!!, 0.0)
        assertEquals(2.0, speedOrNull(2.0, null)!!, 0.0)
    }
}
