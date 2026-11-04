package app.tracer.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StallDetectorTest {
    private val s = 1_000_000_000L // 1 s in ns

    @Test fun regularTicksAreNotStalls() {
        val d = StallDetector()
        d.reset(1_000, 0)
        for (i in 1..30) assertNull(d.observe(1_000 + i * 1_000L, i * s, recording = true))
    }

    @Test fun jumpOver15sIsAStall() {
        val d = StallDetector()
        d.reset(10_000, 10 * s)
        assertNull(d.observe(11_000, 11 * s, true))
        val st = d.observe(51_000, 51 * s, true)!!
        assertEquals(StallDetector.Stall(11_000, 51_000, 40_000), st)
        assertNull(d.observe(52_000, 52 * s, true)) // reported once
    }

    @Test fun exactly15sIsNotAStall() {
        val d = StallDetector()
        d.reset(0, 0)
        assertNull(d.observe(15_000, 15 * s, true))
    }

    @Test fun pauseNeverReadsAsStall() {
        val d = StallDetector()
        d.reset(0, 0)
        assertNull(d.observe(60_000, 60 * s, recording = false)) // paused: mark moves only
        assertNull(d.observe(61_000, 61 * s, recording = true))
    }

    @Test fun wallClockChangeAloneIsNotAStall() {
        val d = StallDetector()
        d.reset(0, 0)
        assertNull(d.observe(3_600_000, 1 * s, true)) // NTP / user moved the clock by 1 h
    }

    @Test fun backwardsWallClockUsesMonotonicDuration() {
        val d = StallDetector()
        d.reset(100_000, 0)
        assertEquals(StallDetector.Stall(100_000, 130_000, 30_000), d.observe(50_000, 30 * s, true))
    }

    @Test fun olderObservationDoesNotMoveMark() {
        val d = StallDetector()
        d.reset(0, 10 * s)
        assertNull(d.observe(0, 5 * s, true))
        assertNull(d.observe(11_000, 11 * s, true))
    }

    @Test fun firstObservationWithoutResetOnlySetsMark() {
        assertNull(StallDetector().observe(0, 100 * s, true))
    }
}
