package app.tracer.probes

import app.tracer.core.config.ProbePaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityLogicTest {

    // ---------------------------------------------------------- server identity

    @Test fun cfRaySuffixIsPop() {
        val h = mapOf("cf-ray" to "8d1a2b3c4d5e6f70-DXB", "server" to "cloudflare")
        assertEquals("DXB", edgePopFrom { h[it] })
    }

    @Test fun popFallbacks() {
        assertEquals("DXB52-P1", edgePopFrom { mapOf("x-amz-cf-pop" to "DXB52-P1")[it] })
        assertEquals("DXB", edgePopFrom { mapOf("x-served-by" to "cache-fra-eddf8230-FRA, cache-dxb1470021-DXB")[it] })
        assertEquals("ae-dxb-edge-001", edgePopFrom { mapOf("via" to "https/1.1 ae-dxb-edge-001.aaplimg.com (acdn/266.15)")[it] })
        assertNull(edgePopFrom { mapOf("server" to "gws", "alt-svc" to "h3=\":443\"")[it] })
        assertNull(edgePopFrom { mapOf("cf-ray" to "8d1a2b3c4d5e6f70")[it] })
    }

    @Test fun edgeHeadersOnlyPresentAndTruncated() {
        val long = "x".repeat(300)
        val h = mapOf("server" to "gws", "alt-svc" to long, "content-type" to "text/html")
        val json = Json.parseToJsonElement(edgeHeadersJson { h[it] }!!).jsonObject
        assertEquals(setOf("server", "alt-svc"), json.keys)
        assertEquals(EDGE_HEADER_MAX_CHARS, json["alt-svc"]!!.jsonPrimitive.content.length)
        assertNull(edgeHeadersJson { null })
    }

    @Test fun ipVersionAndCn() {
        assertEquals(4, ipVersionOf("1.1.1.1"))
        assertEquals(6, ipVersionOf("2606:4700::1111"))
        assertNull(ipVersionOf(null))
        assertEquals("fe80::1", stripScope("fe80::1%wlan0"))
        assertEquals("WE1", commonName("CN=WE1,O=Google Trust Services,C=US"))
        assertEquals("WE1", commonName("C=US, O=Google Trust Services, CN=WE1"))
        assertNull(commonName("O=Nobody"))
    }

    @Test fun sha256Hex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
    }

    // ---------------------------------------------------------- dns_doh

    @Test fun parsesDohJson() {
        val body = """{"Status":0,"TC":false,"Question":[{"name":"www.google.com","type":1}],
            "Answer":[{"name":"www.google.com","type":5,"TTL":60,"data":"x.example."},
                      {"name":"www.google.com","type":1,"TTL":60,"data":"142.250.181.36"},
                      {"name":"www.google.com","type":28,"TTL":60,"data":"2a00:1450:4006:80b::2004"}]}"""
        assertEquals(listOf("142.250.181.36", "2a00:1450:4006:80b::2004"), parseDohAnswers(body))
        assertEquals(emptyList<String>(), parseDohAnswers("""{"Status":3}"""))
        assertNull(parseDohAnswers("<html>"))
    }

    @Test fun compareOverlapNotEquality() {
        val c = compareDns("h", listOf("1.2.3.4", "1.2.3.5"), listOf("1.2.3.5", "9.9.9.9"))
        assertEquals(true, c.ok)
        assertEquals("system: 1.2.3.4,1.2.3.5 · doh: 1.2.3.5,9.9.9.9", c.value)
        val d = Json.parseToJsonElement(c.detailJson).jsonObject
        assertEquals(2, d["system"]!!.jsonArray.size)
        assertEquals("1", d["overlap"]!!.jsonPrimitive.content)
    }

    @Test fun compareMismatchAndUntestable() {
        assertEquals(false, compareDns("h", listOf("10.0.0.1"), listOf("1.2.3.4")).ok)
        assertEquals(false, compareDns("h", null, listOf("1.2.3.4"), systemError = "nxdomain").ok)
        assertNull(compareDns("h", listOf("1.2.3.4"), null, dohError = "timeout").ok)
        assertEquals("system: 1.2.3.4 · doh: timeout", compareDns("h", listOf("1.2.3.4"), null, dohError = "timeout").value)
        // IPv6 written differently still matches
        assertEquals(true, compareDns("h", listOf("2a00:1450:4006:080b:0:0:0:2004"), listOf("2a00:1450:4006:80b::2004")).ok)
    }

    @Test fun hostsOfUrls() {
        assertEquals(
            listOf("cp.cloudflare.com", "www.google.com", "speed.cloudflare.com"),
            hostsOf(listOf("https://cp.cloudflare.com/generate_204", "https://www.google.com/generate_204", "https://speed.cloudflare.com/__down?bytes=1", "https://www.google.com/")),
        )
        assertEquals(listOf("example.com"), hostsOf(listOf("https://example.com:8443/x")))
    }

    // ---------------------------------------------------------- tls_cert

    @Test fun certTrackerEmitsFirstAndNewOnly() {
        val t = CertTracker()
        assertEquals(CertTracker.Change.FIRST, t.observe("wifi", "a", "s1"))
        assertNull(t.observe("wifi", "a", "s1"))
        assertEquals(CertTracker.Change.FIRST, t.observe("cellular", "a", "s1"))
        assertEquals(CertTracker.Change.CHANGED, t.observe("wifi", "a", "s2"))
        assertEquals("s1", t.previous("wifi", "a", "s2"))
        assertNull(t.observe("wifi", "a", "s1")) // flip back to a known cert: nothing new
        assertEquals("0123456789abcdef · WE1", tlsCertValue("0123456789abcdef0011", "WE1"))
    }

    // ---------------------------------------------------------- first_hop

    @Test fun publicIpClassification() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.1", "100.64.0.1", "100.127.1.1", "169.254.1.1", "127.0.0.1", "0.0.0.0", "224.0.0.1")
            .forEach { assertFalse(it, isPublicIp(it)) }
        listOf("1.1.1.1", "172.32.0.1", "100.128.0.1", "94.200.1.1", "2606:4700::1111").forEach { assertTrue(it, isPublicIp(it)) }
        listOf("fe80::1", "fd00::1", "::1").forEach { assertFalse(it, isPublicIp(it)) }
        assertFalse(isPublicIp(null))
        assertFalse(isPublicIp("not-an-ip"))
    }

    @Test fun parsesTtlExceededAndReply() {
        val ttl = """PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
From 192.168.1.1 icmp_seq=1 Time to live exceeded

--- 1.1.1.1 ping statistics ---
1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms"""
        assertEquals(Hop(1, "192.168.1.1", 4.0, false), parseTtlPing(1, ttl, 4.0))
        assertEquals("10.0.0.1", parseTtlPing(2, "From 10.0.0.1: icmp_seq=1 Time to live exceeded").ip)
        assertEquals("10.0.0.1", parseTtlPing(2, "From _gateway (10.0.0.1) icmp_seq=1 Time to live exceeded").ip)
        val reply = parseTtlPing(8, "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=3.21 ms", 9.0)
        assertTrue(reply.reachedTarget)
        assertEquals(3.21, reply.rttMs!!, 1e-9)
        val none = parseTtlPing(3, "PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.\n\n--- stats ---", 1000.0)
        assertNull(none.ip); assertNull(none.rttMs)
        assertEquals(listOf("/system/bin/ping", "-n", "-c", "1", "-W", "1", "-t", "3", "1.1.1.1"), pingTtlCommand(3))
    }

    @Test fun walksToFirstPublicHop() {
        val path = mapOf(1 to "192.168.1.1", 2 to null, 3 to "100.64.0.1", 4 to "94.200.1.1", 5 to "1.1.1.1")
        val r = findFirstPublicHop { ttl -> Hop(ttl, path[ttl], null, false) }
        assertTrue(r.ok)
        assertEquals("94.200.1.1", r.publicHop!!.ip)
        assertEquals(4, r.hops.size)
        val d = Json.parseToJsonElement(r.detailJson).jsonObject
        assertEquals("4", d["ttl"]!!.jsonPrimitive.content)
        assertEquals(4, d["hops"]!!.jsonArray.size)
        // ping unusable on the first call → nothing
        assertTrue(findFirstPublicHop { null }.hops.isEmpty())
        // private all the way within the TTL budget
        assertFalse(findFirstPublicHop(maxTtl = 3) { Hop(it, "10.0.0.$it", null, false) }.ok)
    }

    // ---------------------------------------------------------- paths / throughput box

    @Test fun wifiOnlyWritesNoNoNetworkRows() {
        assertFalse(ProbePaths.WIFI_ONLY.emitsNoNetworkRows())
        assertTrue(ProbePaths.CELLULAR_ONLY.emitsNoNetworkRows())
        assertTrue(ProbePaths.DEFAULT.emitsNoNetworkRows())
        assertTrue(ProbePaths.DUAL.emitsNoNetworkRows())
    }

    @Test fun throughputCallTimeout() {
        assertEquals(16_000L, throughputCallTimeoutMs(3_000, 5_000, 30_000))
        assertEquals(30_000L, throughputCallTimeoutMs(3_000, Long.MAX_VALUE, 30_000))
        assertEquals(30_000L, throughputCallTimeoutMs(10_000, 20_000, 30_000))
    }
}
