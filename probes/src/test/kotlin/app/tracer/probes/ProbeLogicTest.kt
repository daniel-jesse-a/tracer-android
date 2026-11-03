package app.tracer.probes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ProbeLogicTest {

    @Test fun errorClassification() {
        assertEquals("timeout", classifyError(SocketTimeoutException("read timed out")))
        assertEquals("timeout", classifyError(InterruptedIOException("timeout")))
        assertEquals("dns", classifyError(UnknownHostException("nope.invalid")))
        assertEquals("connect", classifyError(ConnectException("refused")))
        assertEquals("tls", classifyError(SSLHandshakeException("bad cert")))
        assertEquals("io", classifyError(IOException("reset")))
        assertEquals("dns", classifyError(IOException("wrapped", UnknownHostException("x"))))
    }

    @Test fun httpStatusClassification() {
        assertNull(httpError(200))
        assertNull(httpError(204))
        assertEquals("http_503", httpError(503))
        assertEquals("http_301", httpError(301))
        assertEquals("http_404", httpError(404))
    }

    @Test fun ipinfoOrgParsing() {
        val body = """{"ip":"94.200.1.2","city":"Dubai","org":"AS5384 Emirates Telecommunications Corporation"}"""
        val info = parseIpInfo(body)!!
        assertEquals("94.200.1.2", info.ip)
        assertEquals("AS5384", info.asn)
        assertEquals("Emirates Telecommunications Corporation", info.asnOrg)
    }

    @Test fun ipinfoOrgEdgeCases() {
        assertEquals(null to "Some Org", splitAsnOrg("Some Org"))
        assertEquals("AS15802" to null, splitAsnOrg("AS15802"))
        assertEquals(null to null, splitAsnOrg(null))
        assertEquals(IpInfo("1.2.3.4", null, null), parseIpInfo("""{"ip":"1.2.3.4"}"""))
        assertNull(parseIpInfo("not json"))
        assertNull(parseIpInfo("[1,2]"))
    }

    @Test fun adaptiveInterval() {
        val fast = List(10) { 20.0 }
        assertEquals(1_000L, nextLatencyIntervalMs(2_000, 2_000, degraded = true, adaptive = true, recentTotalsMs = fast))
        assertEquals(1_000L, nextLatencyIntervalMs(1_000, 1_000, degraded = true, adaptive = true, recentTotalsMs = fast))
        assertEquals(3_000L, nextLatencyIntervalMs(2_000, 2_000, degraded = false, adaptive = true, recentTotalsMs = fast))
        assertEquals(4_000L, nextLatencyIntervalMs(2_000, 3_000, degraded = false, adaptive = true, recentTotalsMs = fast))
        assertEquals(4_000L, nextLatencyIntervalMs(2_000, 4_000, degraded = false, adaptive = true, recentTotalsMs = fast))
        assertEquals(2_000L, nextLatencyIntervalMs(2_000, 4_000, degraded = false, adaptive = false, recentTotalsMs = fast))
        assertEquals(2_000L, nextLatencyIntervalMs(2_000, 3_000, false, true, fast.dropLast(1) + listOf(null)))
        assertEquals(2_000L, nextLatencyIntervalMs(2_000, 3_000, false, true, fast.dropLast(1) + listOf(45.0)))
        assertEquals(2_000L, nextLatencyIntervalMs(2_000, 2_000, false, true, List(9) { 10.0 }))
    }

    @Test fun throughputHelpers() {
        assertEquals("https://x/__down?bytes=1000000", throughputUrl("https://x/__down?bytes={bytes}", 1_000_000))
        assertEquals(8.0, mbps(1_000_000, 1_000_000_000L)!!, 1e-9)
        assertNull(mbps(0, 1))
        assertNull(mbps(10, 0))
    }
}
