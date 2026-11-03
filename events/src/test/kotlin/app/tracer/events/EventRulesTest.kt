package app.tracer.events

import app.tracer.core.config.Thresholds
import app.tracer.core.model.EventSample
import app.tracer.core.model.Sample
import app.tracer.core.sim.Scenarios
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Rules + cause heuristics on synthetic timelines. Each scenario is built to exhibit one cause. */
class EventRulesTest {

    private fun run(samples: List<Sample>, th: Thresholds = Thresholds()): List<EventSample> =
        EventEngine("test", th).replay(samples.asSequence())

    private fun run(name: String, th: Thresholds = Thresholds()) = run(Scenarios.byName(name), th)

    private fun List<EventSample>.ofType(t: String) = filter { it.eventType == t }
    private fun List<EventSample>.dump() = joinToString("\n") { "${it.stamp.tMonoNs / 1_000_000_000 % 100000}s ${it.eventType} ${it.likelyCause} ${it.causeConfidence}" }

    @Test fun tunnelDropIsAttributedToTunnel() {
        val ev = run("tunnel")
        val drop = ev.ofType(EventType.DROP).single()
        assertEquals(ev.dump(), Cause.TUNNEL, drop.likelyCause)
        assertTrue(drop.causeConfidence!! >= 0.8)
        assertEquals(Severity.CRITICAL, drop.severity)
        assertTrue(ev.dump(), ev.ofType(EventType.RECONNECT).size == 1)
        val outage = Json.parseToJsonElement(ev.ofType(EventType.RECONNECT).single().afterJson!!).jsonObject["outage_ms"]!!.jsonPrimitive.long
        assertTrue("outage $outage", outage in 15_000..25_000)
        assertTrue(ev.ofType(EventType.SIGNAL_DROP).isNotEmpty())
        val rat = ev.ofType(EventType.RAT_CHANGE)
        assertEquals("LTE→UMTS and back", 2, rat.size)
        assertEquals(Severity.WARNING, rat.first().severity)
        assertTrue(ev.ofType(EventType.GPS_DEGRADED).isNotEmpty())
        // evidence JSON lists candidates and a snapshot
        val evidence = Json.parseToJsonElement(drop.evidenceJson!!).jsonObject
        assertTrue(evidence["candidates"]!!.jsonArray.size >= 2)
        assertNotNull(evidence["snapshot"]!!.jsonObject["serving"])
        assertNotNull(drop.lat)
    }

    @Test fun cellEdgeDrop() {
        val ev = run("cell_edge_drop")
        val drop = ev.ofType(EventType.DROP).single()
        assertEquals(ev.dump(), Cause.CELL_EDGE, drop.likelyCause)
        assertEquals(0.75, drop.causeConfidence!!, 1e-9)
        assertEquals("recovered on new cell", 1, ev.ofType(EventType.HANDOVER).size)
    }

    @Test fun lateHandoverOvertakeThenDrop() {
        val ev = run("late_handover")
        val ot = ev.ofType(EventType.NEIGHBOUR_OVERTAKE)
        assertEquals(ev.dump(), 1, ot.size)
        val drop = ev.ofType(EventType.DROP).single()
        assertTrue(ot.single().stamp.tMonoNs < drop.stamp.tMonoNs)
        assertEquals(ev.dump(), Cause.LATE_HANDOVER, drop.likelyCause)
        assertEquals(1, ev.ofType(EventType.HANDOVER).size)
    }

    @Test fun congestionLatencySpike() {
        val ev = run("congestion")
        val spike = ev.ofType(EventType.LATENCY_SPIKE).first()
        assertEquals(ev.dump(), Cause.CONGESTION, spike.likelyCause)
        assertTrue("no handovers", ev.ofType(EventType.HANDOVER).isEmpty())
        assertTrue("spacing limits repeats", ev.ofType(EventType.LATENCY_SPIKE).size <= 2)
    }

    @Test fun interferenceBeatsCongestion() {
        val ev = run("interference")
        assertEquals(ev.dump(), Cause.INTERFERENCE, ev.ofType(EventType.LATENCY_SPIKE).first().likelyCause)
    }

    @Test fun stickyClientAndRoam() {
        val ev = run("sticky_wifi")
        val spike = ev.ofType(EventType.LATENCY_SPIKE).first()
        assertEquals(ev.dump(), Cause.STICKY_CLIENT, spike.likelyCause)
        val evidence = Json.parseToJsonElement(spike.evidenceJson!!).jsonObject["candidates"]!!.jsonArray.map { it.jsonObject["cause"]!!.jsonPrimitive.content }
        assertTrue(evidence.toString(), Cause.CHANNEL_CONGESTION in evidence && Cause.WIFI_WEAK in evidence)
        val roam = ev.ofType(EventType.WIFI_ROAM).single()
        assertTrue(roam.likelyCause in setOf(Cause.STICKY_CLIENT, Cause.WIFI_WEAK))
    }

    @Test fun handoverChurnAtSpeed() {
        val ev = run("handover_churn")
        val ho = ev.ofType(EventType.HANDOVER)
        assertEquals(ev.dump(), 7, ho.size)
        assertNull("first handovers are not churn", ho.first().likelyCause)
        assertEquals(Cause.HANDOVER_CHURN, ho[2].likelyCause)
    }

    @Test fun throughputDrop() {
        val ev = run("throughput_drop")
        val td = ev.ofType(EventType.THROUGHPUT_DROP)
        assertTrue(ev.dump(), td.size in 1..2) // both 9 Mbps samples sit > 60 % below a ~50 Mbps median
        assertEquals(Cause.CONGESTION, td.first().likelyCause)
    }

    @Test fun gpsDegradedOnceWithHysteresis() {
        val ev = run("gps_degraded")
        assertEquals(ev.dump(), 1, ev.ofType(EventType.GPS_DEGRADED).size)
    }

    @Test fun captivePortal() {
        val ev = run("captive_portal")
        assertEquals(ev.dump(), 1, ev.ofType(EventType.WIFI_CELL_SWITCH).size)
        val cp = ev.ofType(EventType.CAPTIVE_PORTAL)
        assertTrue(ev.dump(), cp.isNotEmpty())
        assertEquals(Cause.CAPTIVE, cp.first().likelyCause)
        val drop = ev.ofType(EventType.DROP).single()
        assertEquals(Cause.CAPTIVE, drop.likelyCause)
    }

    @Test fun dnsSpike() {
        val ev = run("dns_issue")
        assertEquals(ev.dump(), Cause.DNS, ev.ofType(EventType.DNS_SPIKE).first().likelyCause)
        // latency spikes are judged on RTT (TCP connect), which a slow resolver does not change
        assertTrue(ev.dump(), ev.ofType(EventType.LATENCY_SPIKE).isEmpty())
    }

    @Test fun carrierGatewayChange() {
        val ev = run("carrier_gateway_change")
        val e = ev.ofType(EventType.IP_ASN_CHANGE).single()
        assertEquals(Cause.GATEWAY, e.likelyCause)
        assertEquals(Severity.WARNING, e.severity)
    }

    @Test fun thresholdsAreHonoured() {
        // raising the latency floor above the spike suppresses it
        val ev = run("congestion", Thresholds(latencyMinMs = 1000.0))
        assertTrue(ev.ofType(EventType.LATENCY_SPIKE).isEmpty())
        // thresholds JSON round-trips with iOS key names
        val th = Thresholds.fromJson("""{"latency_min_ms": 1000, "overtake_db": 9}""")
        assertEquals(1000.0, th.latencyMinMs, 0.0); assertEquals(9, th.overtakeDb)
    }

    @Test fun staleCellInfoIgnored() {
        val stale = Scenarios.byName("handover_churn").map {
            if (it is app.tracer.core.model.CellSample) it.copy(cellinfoAgeMs = 60_000) else it
        }
        assertTrue(run(stale).ofType(EventType.HANDOVER).isEmpty())
    }

    @Test fun replayIsDeterministic() {
        val s = Scenarios.byName("commute")
        assertEquals(run(s).map { it.toString() }, run(s).map { it.toString() })
        assertTrue(run(s).size > 10)
    }

    @Test fun defaultThresholdFileMatchesDefaults() {
        val text = javaClass.getResource("/event_thresholds.default.json")!!.readText()
        assertEquals(Thresholds(), Thresholds.fromJson(text))
    }
}
