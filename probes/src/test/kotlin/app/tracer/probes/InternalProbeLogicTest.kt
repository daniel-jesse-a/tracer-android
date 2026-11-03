package app.tracer.probes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalProbeLogicTest {

    @Test fun dnsQueryWireFormat() {
        val q = buildDnsQuery(0xBEEF, "a.bc")
        val expected = byteArrayOf(
            0xBE.toByte(), 0xEF.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
            1, 'a'.code.toByte(), 2, 'b'.code.toByte(), 'c'.code.toByte(), 0,
            0, 1, 0, 1,
        )
        assertArrayEquals(expected, q)
    }

    @Test fun dnsQueryDefaultName() {
        val q = buildDnsQuery(1)
        // 12 header + (17+1)+(7+1)+(3+1) labels + root + 4
        assertEquals(12 + 18 + 8 + 4 + 1 + 4, q.size)
        assertEquals(17, q[12].toInt())
        assertEquals("connectivitycheck", String(q, 13, 17, Charsets.US_ASCII))
        assertEquals(7, q[30].toInt())
    }

    @Test(expected = IllegalArgumentException::class) fun dnsQueryRejectsEmptyLabel() {
        buildDnsQuery(1, "a..b")
    }

    private fun reply(id: Int, flags1: Int, flags2: Int, an: Int = 1) = byteArrayOf(
        (id shr 8).toByte(), id.toByte(), flags1.toByte(), flags2.toByte(), 0, 1, (an shr 8).toByte(), an.toByte(), 0, 0, 0, 0,
    )

    @Test fun dnsReplyParsing() {
        val ok = parseDnsReply(reply(0x1234, 0x81, 0x80, an = 2), 12, 0x1234)!!
        assertEquals(0, ok.rcode)
        assertEquals(2, ok.answers)
        assertFalse(ok.truncated)
        val nx = parseDnsReply(reply(0x1234, 0x81, 0x83, an = 0), 12, 0x1234)!!
        assertEquals(3, nx.rcode) // NXDOMAIN
        val servfail = parseDnsReply(reply(7, 0x83, 0x82), 12, 7)!!
        assertEquals(2, servfail.rcode)
        assertTrue(servfail.truncated)
    }

    @Test fun dnsReplyRejectsMismatch() {
        assertNull("other id", parseDnsReply(reply(0x1111, 0x81, 0x80), 12, 0x2222))
        assertNull("query, not response", parseDnsReply(reply(5, 0x01, 0x00), 12, 5))
        assertNull("short", parseDnsReply(reply(5, 0x81, 0x80), 11, 5))
        assertNull("short buffer", parseDnsReply(ByteArray(4), 4, 0))
        // id compared as unsigned 16-bit
        assertEquals(0xFFFF, parseDnsReply(reply(0xFFFF, 0x81, 0x80), 12, 0xFFFF)!!.id)
    }

    @Test fun pingParsing() {
        val out = """
            PING 192.168.1.1 (192.168.1.1) from 192.168.1.23 wlan0: 56(84) bytes of data.
            64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=3.21 ms

            --- 192.168.1.1 ping statistics ---
            1 packets transmitted, 1 received, 0% packet loss, time 0ms
            rtt min/avg/max/mdev = 3.211/3.211/3.211/0.000 ms
        """.trimIndent()
        assertEquals(3.21, parsePingRttMs(out)!!, 1e-9)
        assertEquals(PingOutcome(3.21, null), pingOutcome(0, out))
        assertEquals(12.0, parsePingRttMs("64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time=12 ms")!!, 1e-9)
        assertEquals(1.0, parsePingRttMs("icmp_seq=1 ttl=64 time<1 ms")!!, 1e-9)
    }

    @Test fun pingFailures() {
        val lost = "PING 10.0.0.1 (10.0.0.1) 56(84) bytes of data.\n\n--- 10.0.0.1 ping statistics ---\n1 packets transmitted, 0 received, 100% packet loss, time 0ms\n"
        assertNull(parsePingRttMs(lost)) // "time 0ms" in the summary is not a reply
        assertEquals(PingOutcome(null, "timeout"), pingOutcome(1, lost))
        assertEquals(PingOutcome(null, "timeout"), pingOutcome(null, ""))
        assertEquals(PingOutcome(null, "io"), pingOutcome(2, "ping: unknown iface wlan9"))
        assertFalse(pingOutcome(2, "").replied)
        assertTrue(pingBindFailed("ping: SO_BINDTODEVICE: Operation not permitted"))
        assertTrue(pingBindFailed("ping: unknown iface wlan9"))
        assertFalse(pingBindFailed(lost))
    }

    @Test fun pingCommandLine() {
        assertEquals(listOf("/system/bin/ping", "-c", "1", "-W", "1", "-I", "wlan0", "192.168.1.1"), pingCommand("192.168.1.1", "wlan0"))
        assertEquals(listOf("/system/bin/ping", "-c", "1", "-W", "1", "10.0.0.1"), pingCommand("10.0.0.1", null))
    }

    @Test fun cadence() {
        assertEquals(2_000L, internalProbeIntervalMs(500))
        assertEquals(6_000L, internalProbeIntervalMs(6_000))
    }
}
