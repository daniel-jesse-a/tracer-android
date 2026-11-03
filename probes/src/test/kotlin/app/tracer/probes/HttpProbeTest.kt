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

    @Test fun http503IsClassified() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(503).build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl()).head().build())
        assertFalse(r.success)
        assertEquals(503, r.code)
        assertEquals("http_503", r.error)
    }

    @Test fun redirectIsNotFollowed() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
        val r = HttpProbe.execute(client, Request.Builder().url(localhostUrl()).get().build(), isSuccess = { it == 204 })
        assertFalse(r.success)
        assertEquals("http_302", r.error)
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
