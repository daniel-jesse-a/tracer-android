package app.tracer.events

import app.tracer.core.config.Thresholds
import app.tracer.core.model.*
import app.tracer.core.sim.SecondBuilder
import app.tracer.core.sim.SyntheticRun
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** v3 rules from the iOS-run analysis: resume windows, DNS retry timer, RAT debounce, per-endpoint latency, GPS episodes, window_s. */
class ResumeRulesTest {
    private val run = SyntheticRun()
    private fun replay(samples: List<Sample>, th: Thresholds = Thresholds()) = EventEngine("t", th).replay(samples.asSequence())
    private fun List<EventSample>.ofType(t: String) = filter { it.eventType == t }
    private fun ev(e: EventSample) = Json.parseToJsonElement(e.evidenceJson!!).jsonObject
    private fun List<EventSample>.dump() = joinToString("\n") { "${(it.stamp.tMonoNs - run.startMonoNs) / 1_000_000_000}s ${it.eventType} ${it.severity} ${it.likelyCause} ${it.evidenceJson}" }
    private fun tOf(e: EventSample) = (e.stamp.tMonoNs - run.startMonoNs) / 1_000_000_000

    private fun SecondBuilder.base(rsrp: Int = -85, used: Int = 14, cn0: Double = 40.0) {
        location(); gnss(used, cn0); serving(1001, 101, rsrp, 15); path("cellular")
    }

    // ---------------------------------------------------------------- (a) resume window

    @Test fun silenceStartsResumeWindowAndResetsBaselines() {
        // 30 ms before a 20 s silence, 300 ms after (a different network state): without a baseline reset
        // every post-wake probe would be a "spike" against the old window.
        val s = run.build(140) {
            if (t in 60 until 80) return@build
            base(); latency(if (t < 60) 30.0 + t % 3 else 300.0 + t % 5)
            if (t == 100) latency(2_000.0) // 20 s into the window, after ≥ 10 fresh samples
        }
        val e = replay(s)
        val spikes = e.ofType(EventType.LATENCY_SPIKE)
        assertEquals(e.dump(), 1, spikes.size)
        val spike = spikes.single()
        assertEquals(100L, tOf(spike))
        assertEquals(Severity.INFO, spike.severity)
        assertEquals(true, ev(spike)["after_resume"]?.jsonPrimitive?.boolean)
        assertNotEquals(Cause.TUNNEL, spike.likelyCause)
        // no gps no-fix event caused by the recording gap itself
        assertTrue(e.dump(), e.ofType(EventType.GPS_DEGRADED).isEmpty())
    }

    @Test fun sameSpikeOutsideResumeWindowIsAWarning() {
        val s = run.build(80) { base(); latency(if (t == 60) 2_000.0 else 30.0 + t % 3) }
        val spike = replay(s).ofType(EventType.LATENCY_SPIKE).single()
        assertEquals(Severity.WARNING, spike.severity)
        assertNull(ev(spike)["after_resume"])
    }

    @Test fun xGapRowStartsResumeWindowEvenWithoutSilence() {
        // the service wrote a watchdog gap row (stream kept flowing, e.g. a frozen collector): next 30 s are a resume window
        val s = run.build(120) {
            base(); latency(if (t == 75) 2_000.0 else 30.0 + t % 3)
            if (t == 60) emit(GapSample(runId, stamp(), stamp().tUtcMs - 12_000, stamp().tUtcMs, "watchdog"))
        }
        val e = replay(s)
        val spike = e.ofType(EventType.LATENCY_SPIKE).single()
        assertEquals(e.dump(), Severity.INFO, spike.severity)
        assertEquals(true, ev(spike)["after_resume"]?.jsonPrimitive?.boolean)
    }

    @Test fun ratChangeAfterResumeIsInfoAndNeverTunnel() {
        // GNSS lost + signal drop + LTE→UMTS right after a 25 s suspension: a wake artefact, not a tunnel
        val s = run.build(120) {
            if (t in 40 until 65) return@build
            val after = t >= 65
            base(rsrp = if (after) -112 else -85, used = if (after) 2 else 14, cn0 = if (after) 22.0 else 40.0)
            radio(if (t >= 66) "UMTS" else "LTE")
            latency(30.0)
        }
        val e = replay(s)
        val rat = e.ofType(EventType.RAT_CHANGE).single()
        assertEquals(e.dump(), Severity.INFO, rat.severity)
        assertEquals(true, ev(rat)["after_resume"]?.jsonPrimitive?.boolean)
        assertTrue(e.dump(), e.none { it.likelyCause == Cause.TUNNEL })
    }

    // ---------------------------------------------------------------- (b) DNS retry timer

    private fun dnsRun(spikeAt: Int, dnsMs: Double) = run.build(spikeAt + 10) {
        base()
        when {
            t < 30 -> latency(30.0, dnsMs = 8.0 + t % 3)
            t < spikeAt -> latency(30.0, dnsMs = 0.0) // cached answers: no lookup
            t == spikeAt -> latency(30.0, dnsMs = dnsMs)
            else -> latency(30.0, dnsMs = 9.0)
        }
    }

    @Test fun firstLookupAfterIdleOnRetryTimerIsDnsRetryTimer() {
        val e = replay(dnsRun(55, 2_040.0))
        val d = e.ofType(EventType.DNS_SPIKE).single()
        assertEquals(e.dump(), Cause.DNS_RETRY_TIMER, d.likelyCause)
        assertEquals(Severity.INFO, d.severity)
        assertEquals(true, ev(d)["dns_retry_timer"]?.jsonPrimitive?.boolean)
        assertEquals(Cause.DNS_RETRY_TIMER, replay(dnsRun(55, 5_010.0)).ofType(EventType.DNS_SPIKE).single().likelyCause)
    }

    @Test fun retryTimerNeedsIdleAndTheTimerValue() {
        // only 5 s idle → an ordinary DNS spike (warning, dns_issue)
        val short = replay(dnsRun(35, 2_040.0)).ofType(EventType.DNS_SPIKE).single()
        assertEquals(Severity.WARNING, short.severity)
        assertEquals(Cause.DNS, short.likelyCause)
        // idle, but 3.1 s is not a retry timer value
        val odd = replay(dnsRun(55, 3_100.0)).ofType(EventType.DNS_SPIKE).single()
        assertEquals(Severity.WARNING, odd.severity)
        assertEquals(Cause.DNS, odd.likelyCause)
    }

    // ---------------------------------------------------------------- (c) RAT debounce + tunnel

    @Test fun ratFlickerIsOneInfoEvent() {
        val s = run.build(60) { base(); latency(30.0); radio(if (t == 30) "LTE" else "NR_NSA") } // 5G→LTE→5G within 1 s
        val e = replay(s)
        assertTrue(e.dump(), e.ofType(EventType.RAT_CHANGE).isEmpty())
        val f = e.ofType(EventType.RAT_FLICKER).single()
        assertEquals(Severity.INFO, f.severity)
        assertNull(f.likelyCause)
    }

    @Test fun sustainedDowngradeIsARatChange() {
        val s = run.build(60) { base(); latency(30.0); radio(if (t >= 30) "LTE" else "NR_NSA") }
        val e = replay(s)
        val r = e.ofType(EventType.RAT_CHANGE).single()
        assertEquals(Severity.WARNING, r.severity)
        assertEquals(30L, tOf(r))
        assertTrue(e.ofType(EventType.RAT_FLICKER).isEmpty())
    }

    @Test fun downgradeWithGnssLossButNoSignalDropIsNotATunnel() {
        val s = run.build(90) {
            val inside = t >= 45
            base(used = if (inside) 2 else 14, cn0 = if (inside) 22.0 else 40.0)
            radio(if (t >= 46) "LTE" else "NR_NSA"); latency(30.0)
        }
        val e = replay(s)
        assertEquals(e.dump(), 1, e.ofType(EventType.RAT_CHANGE).size)
        assertTrue(e.dump(), e.none { it.likelyCause == Cause.TUNNEL })
    }

    @Test fun sustainedDowngradeSignalDropAndGnssLossIsATunnel() {
        val s = run.build(90) {
            val inside = t >= 45
            base(rsrp = if (inside) -110 else -85, used = if (inside) 2 else 14, cn0 = if (inside) 22.0 else 40.0)
            radio(if (t >= 46) "UMTS" else "LTE"); latency(30.0)
        }
        val e = replay(s)
        val r = e.ofType(EventType.RAT_CHANGE).single()
        assertEquals(e.dump(), Cause.TUNNEL, r.likelyCause)
        val sig = ev(r)["candidates"]!!.jsonArray.first { it.jsonObject["cause"]!!.jsonPrimitive.content == Cause.TUNNEL }.jsonObject["signals"]!!.jsonObject
        assertTrue(sig["rat_downgrade_held_ms"]!!.jsonPrimitive.long > 5_000)
    }

    // ---------------------------------------------------------------- (d) per-endpoint, bimodal latency

    @Test fun bimodalAndPerEndpointBaselinesDoNotSpike() {
        val a = "https://cp.cloudflare.com/generate_204"; val b = "https://www.etisalat.ae/"
        val s = run.build(120) {
            base()
            // A: 70 % ~20 ms, 30 % ~300 ms (two server groups); B: always ~250 ms
            latency(if (t % 10 < 7) 20.0 + t % 3 else 300.0 + t % 7, endpoint = a)
            latency(if (t == 100) 1_200.0 else 250.0 + t % 5, endpoint = b)
            if (t == 90) latency(950.0, endpoint = a)
        }
        val e = replay(s)
        val spikes = e.ofType(EventType.LATENCY_SPIKE)
        assertEquals(e.dump(), listOf(90L, 100L), spikes.map { tOf(it) })
        val before = Json.parseToJsonElement(spikes.first().beforeJson!!).jsonObject
        assertTrue(before["endpoint_p90_ms"]!!.jsonPrimitive.double >= 300.0)
    }

    @Test fun latencyNeedsTenSamplesPerEndpoint() {
        val s = run.build(30) { base(); latency(if (t == 8) 900.0 else 20.0) }
        assertTrue(replay(s).ofType(EventType.LATENCY_SPIKE).isEmpty())
    }

    // ---------------------------------------------------------------- (e) GPS episodes

    @Test fun gpsDegradedFiresOnceAndRecoversOnce() {
        // iOS: baseline stuck at 24.5 m while accuracy sat at ~5.7 km for 86 min, with repeated events
        val s = run.build(30 + 900 + 40) {
            location(hAcc = if (t in 30 until 930) 5_700.0 else 5.0); gnss(14, 40.0); latency(30.0); path("cellular")
        }
        val e = replay(s)
        val d = e.ofType(EventType.GPS_DEGRADED)
        assertEquals(e.dump(), 1, d.size)
        val rec = e.ofType(EventType.GPS_RECOVERED).single()
        assertEquals(Severity.INFO, rec.severity)
        assertTrue(tOf(rec) in 935L..945L)
        val after = Json.parseToJsonElement(rec.afterJson!!).jsonObject
        assertTrue(after["degraded_s"]!!.jsonPrimitive.long >= 900)
        assertNotNull(Json.parseToJsonElement(d.single().afterJson!!).jsonObject["baseline_h_acc_m"])
    }

    @Test fun gpsBaselineFollowsAllFixes() {
        val e = EventEngine("t")
        run.build(120) { location(hAcc = if (t < 30) 5.0 else 5_700.0) }.forEach { e.onSample(it) }
        assertEquals(5_700.0, e.ctx.gpsHAccBaseline()!!, 0.1)
    }

    // ---------------------------------------------------------------- (f) evidence window

    @Test fun everyEventRecordsTheWindowUsed() {
        val s = run.build(90) { base(); latency(if (t == 60) 2_000.0 else 30.0 + t % 3); radio(if (t == 40) "LTE" else "NR_NSA") }
        val e = replay(s, Thresholds(causeLookbackS = 4.0))
        assertTrue(e.isNotEmpty())
        e.forEach { assertEquals(it.eventType, 4.0, ev(it)["window_s"]!!.jsonPrimitive.double, 0.0) }
        assertEquals(2.0, Thresholds().causeLookbackS, 0.0)
        // the never-used 15 s default migrates to the 2 s actually used; an explicit other value is kept
        assertEquals(2.0, Thresholds.fromJson(buildJsonObject { put("cause_lookback_s", 15.0) }).causeLookbackS, 0.0)
        assertEquals(3.0, Thresholds.fromJson(buildJsonObject { put("cause_lookback_s", 3.0) }).causeLookbackS, 0.0)
        assertEquals(15.0, Thresholds.fromJson(buildJsonObject { put("cause_lookback_s", 15.0) }, migrate = false).causeLookbackS, 0.0)
    }

    @Test fun diagnosisWaitsForTheConfiguredWindow() {
        val e = EventEngine("t", Thresholds(causeLookbackS = 4.0))
        var emittedAt = -1L
        for (x in run.build(80) { base(); latency(if (t == 60) 2_000.0 else 30.0 + t % 3) }) {
            if (e.onSample(x).any { it.eventType == EventType.LATENCY_SPIKE } && emittedAt < 0) emittedAt = (x.stamp.tMonoNs - run.startMonoNs) / 1_000_000
        }
        assertTrue("emitted at $emittedAt", emittedAt in 64_000L..65_100L)
    }
}
