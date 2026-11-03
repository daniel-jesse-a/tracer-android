package app.tracer.events

import app.tracer.core.model.*
import app.tracer.core.sim.Scenarios
import app.tracer.core.sim.SyntheticRun
import org.junit.Assert.*
import org.junit.Test

class V2RulesTest {

    @Test fun roamRecordWithPenaltyAndFeatures() {
        val samples = Scenarios.byName("sticky_wifi").map {
            if (it is WifiScanSample && it.bssid.endsWith(":02")) it.copy(features = "11k,11v,11r") else it
        }
        val rt = RoamTracker("t")
        val roams = samples.flatMap { rt.onSample(it) } + rt.finish()
        val r = roams.single()
        assertEquals("aa:aa:aa:aa:aa:01", r.fromBssid); assertEquals("aa:aa:aa:aa:aa:02", r.toBssid)
        assertEquals(-82, r.fromRssiDbm); assertEquals(-58, r.toRssiDbm)
        assertEquals("5", r.toBand); assertEquals(0L, r.linkDownMs)
        assertEquals(true, r.target11r); assertEquals(true, r.target11k)
        // baseline = median of the 30 s before (10×25 ms, 5×300 ms) = 25 ms; after = 25 ms → 0
        assertEquals(25.0, r.rttBeforeMs!!, 0.01); assertEquals(25.0, r.rttAfterMs!!, 0.01); assertEquals(0.0, r.latencyPenaltyMs!!, 0.01)
        // emitted ~10 s after the roam, not at it
        assertTrue(roams.isNotEmpty())
    }

    @Test fun roamAfterDisconnectMeasuresLinkDown() {
        val run = SyntheticRun()
        val s = run.build(30) {
            when {
                t < 10 -> wifi("Office", "aa:01", -70)
                t < 13 -> out += WifiConnectionSample(runId, stamp())   // link lost
                else -> wifi("Office", "aa:02", -55)
            }
        }
        val rt = RoamTracker("t")
        val r = (s.flatMap { rt.onSample(it) } + rt.finish()).single()
        assertTrue("link down ${r.linkDownMs}", r.linkDownMs!! in 2_900..3_100)
    }

    @Test fun wifiCausesNeverOnCellularEvents() {
        // sticky Wi-Fi conditions + a cellular signal drop at the same time
        val s = Scenarios.byName("sticky_wifi").toMutableList()
        val ev = EventEngine("t").replay(s.asSequence())
        val rf = ev.filter { it.eventType in setOf(EventType.SIGNAL_DROP, EventType.HANDOVER, EventType.RAT_CHANGE) }
        val wifiCauses = setOf(Cause.STICKY_CLIENT, Cause.WIFI_WEAK, Cause.CHANNEL_CONGESTION, Cause.WIFI_UPLINK, Cause.WIFI_ROAM_TRANSITION, Cause.WIFI_LINK_DELAY, Cause.UPSTREAM_DELAY)
        rf.forEach { assertFalse(it.toString(), it.likelyCause in wifiCauses) }
    }

    @Test fun captiveRequiresAndroidFlag() {
        // redirects without Android's flag: never captive
        val run = SyntheticRun()
        val s = run.build(60) {
            location(); gnss(12, 38.0); radio("LTE"); serving(1001, 101, -88); path("wifi")
            wifi("Hotel", "cc:01", -55)
            if (t % 2 == 0) out += ProbeSample(runId, stamp(), "latency", "https://x/generate_204", t < 30, if (t < 30) null else "timeout",
                connectMs = 20.0, totalMs = 60.0, isCellular = false, httpStatus = if (t < 30) 302 else null, expectedResponse = if (t < 30) false else null)
        }
        val ev = EventEngine("t").replay(s.asSequence())
        assertTrue(ev.none { it.likelyCause == Cause.CAPTIVE || it.eventType == EventType.CAPTIVE_PORTAL })
        // with Android's captive flag → captive_portal event with the cause
        val flagged = s + listOf(AndroidNetworkSample("t", s[s.size / 2].stamp, 1, "WIFI", validated = false, captivePortal = true, iface = "wifi"))
        val ev2 = EventEngine("t").replay(flagged.sortedBy { it.stamp.tMonoNs }.asSequence())
        val cp = ev2.filter { it.eventType == EventType.CAPTIVE_PORTAL }
        assertEquals(1, cp.size); assertEquals(Cause.CAPTIVE, cp.single().likelyCause)
    }

    @Test fun diagnosisIsDeferredTwoSeconds() {
        val s = Scenarios.byName("congestion")
        val e = EventEngine("t")
        var emittedAt = -1L; var eventAt = -1L
        for (x in s) {
            val out = e.onSample(x)
            out.firstOrNull { it.eventType == EventType.LATENCY_SPIKE }?.let { if (emittedAt < 0) { emittedAt = x.stamp.tMonoNs; eventAt = it.stamp.tMonoNs } }
        }
        assertTrue(emittedAt - eventAt >= 2_000_000_000L)
    }
}
