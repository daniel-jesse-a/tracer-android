package app.tracer.probes

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** JVM test of the OkHttp timing/error layer against a local MockWebServer (plain HTTP). */
class HttpProbeTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private lateinit var server: MockWebServer

    /** Resolves every host to loopback so the DNS phase is exercised for "localhost". */
    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(loopback)
        })
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    @Before fun setUp() {
        server = MockWebServer()
        server.start(loopback, 0)
    }

    @After fun tearDown() {
        server.close()
    }

    private fun localhostUrl(path: String = "/") = server.url(path).newBuilder().host("localhost").build()

    @Test fun headProducesPhaseTimings() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl()).head().build())
        assertTrue(r.success)
        assertEquals(200, r.code)
        assertNull(r.error)
        assertNotNull(r.phases.dnsMs)
        assertNotNull(r.phases.connectMs)
        assertNotNull(r.phases.ttfbMs)
        assertNotNull(r.phases.totalMs)
        assertNull("plain HTTP has no TLS phase", r.phases.tlsMs)
        assertTrue(r.phases.totalMs!! >= r.phases.connectMs!!)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test fun http503IsReachable() = runBlocking<Unit> {
        // v2: any HTTP response = reachable; the status is reported, it is not a transport error.
        server.enqueue(MockResponse.Builder().code(503).build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl()).head().build())
        assertTrue(r.success)
        assertEquals(503, r.code)
        assertNull(r.error)
        assertEquals(0, r.redirectCount)
        assertEquals(503, r.finalCode)
        assertEquals(localhostUrl().toString(), r.finalUrl)
    }

    @Test fun notFoundIsReachable() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(404).build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl()).get().build())
        assertTrue(r.success)
        assertEquals(404, r.code)
        assertNull(r.error)
    }

    @Test fun redirectIsFollowedUntimed() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/hop2").build())
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/final").build())
        server.enqueue(MockResponse.Builder().code(204).build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl("/start")).head().build())
        assertTrue(r.success)
        assertNull(r.error)
        assertEquals("http_status is the first, timed hop", 302, r.code)
        assertEquals(2, r.redirectCount)
        assertEquals(204, r.finalCode)
        assertEquals(localhostUrl("/final").toString(), r.finalUrl)
        assertNotNull(r.phases.totalMs)
        assertEquals("/start", server.takeRequest().url.encodedPath)
        assertEquals("/hop2", server.takeRequest().url.encodedPath)
        val last = server.takeRequest()
        assertEquals("/final", last.url.encodedPath)
        assertEquals("HEAD", last.method)
    }

    @Test fun redirectChainIsCappedAtFive() = runBlocking<Unit> {
        repeat(8) { i -> server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/r${i + 1}").build()) }
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl("/r0")).get().build())
        assertTrue(r.success)
        assertEquals(302, r.code)
        assertEquals(MAX_REDIRECTS, r.redirectCount)
        assertEquals(302, r.finalCode)
        assertEquals(localhostUrl("/r5").toString(), r.finalUrl)
        assertEquals(1 + MAX_REDIRECTS, server.requestCount)
    }

    @Test fun redirectNotFollowedWhenDisabled() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl()).get().build(), followRedirects = false)
        assertTrue(r.success)
        assertEquals(302, r.code)
        assertEquals(0, r.redirectCount)
        assertEquals(302, r.finalCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun failedFollowUpKeepsFirstHop() = runBlocking<Unit> {
        ServerSocket(0, 50, loopback).use { silent ->
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://localhost:${silent.localPort}/portal").build())
            val fast = client.newBuilder().callTimeout(500, TimeUnit.MILLISECONDS).build()
            val r = HttpProbe.execute(fast, Request.Builder().url(localhostUrl()).get().build())
            assertTrue(r.success)
            assertNull(r.error)
            assertEquals(302, r.code)
            assertEquals(1, r.redirectCount)
            assertNull(r.finalCode)
            assertEquals("http://localhost:${silent.localPort}/portal", r.finalUrl)
        }
    }

    @Test fun silentServerTimesOut() = runBlocking<Unit> {
        // Kernel accepts the TCP handshake via the backlog; nothing ever answers.
        ServerSocket(0, 50, loopback).use { silent ->
            val fast = client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
            val r = HttpProbe.execute(fast, Request.Builder().url("http://localhost:${silent.localPort}/").head().build())
            assertFalse(r.success)
            assertNull(r.code)
            assertEquals("timeout", r.error)
        }
    }

    @Test fun discardCountsBytesUpToCap() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(10_000)).build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl()).get().build(), BodyMode.Discard(4_096))
        assertTrue(r.success)
        assertEquals(4_096L, r.bytes)
        assertNotNull(r.durationNs)
    }
}
