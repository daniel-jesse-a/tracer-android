package app.tracer.core

import app.tracer.core.model.RunState
import app.tracer.core.runtime.LiveAggregator
import app.tracer.core.sim.Scenarios
import org.junit.Assert.*
import org.junit.Test

class LiveAggregatorTest {
    @Test fun foldsScenarioIntoSnapshot() {
        val agg = LiveAggregator()
        val samples = Scenarios.byName("sticky_wifi")
        agg.reset("r", RunState.RECORDING, samples.first().stamp.tMonoNs)
        samples.forEach(agg::onSample)
        agg.tick(samples.last().stamp.tMonoNs, 0, 350)
        val s = agg.snapshot
        assertEquals("wifi", s.transport)
        assertEquals("aa:aa:aa:aa:aa:02", s.wifi?.bssid)
        assertEquals(12, s.scan.size)
        assertEquals(12, s.channelLoad.single().aps)
        assertEquals(-90, s.primaryServing?.rsrp)
        assertEquals(10, s.usedSats)
        assertNotNull(s.medianLatencyMs)
        assertTrue(s.track.size in 10..70)
        assertTrue(s.elapsedMs in 69_000..70_500)
    }
}
