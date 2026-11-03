package app.tracer.probes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SntpTest {

    @Test fun requestHeaderAndTimestampRoundTrip() {
        val t0 = 1_762_000_000_123L
        val req = Sntp.buildRequest(t0)
        assertEquals(48, req.size)
        assertEquals(0x23, req[0].toInt() and 0xFF) // LI 0, VN 4, mode 3
        assertEquals(t0, Sntp.readTimestamp(req, 40))
        for (ms in listOf(0L, 1L, 999L, 1_000L, 2_085_978_497_000L /* 1 s after the 2036-02-07 era rollover (exact instant encodes as the reserved zero timestamp) */, 2_085_978_497_001L)) {
            val b = ByteArray(48)
            Sntp.writeTimestamp(b, 40, ms)
            assertEquals(ms, Sntp.readTimestamp(b, 40))
        }
    }

    @Test fun offsetArithmetic() {
        // Server 500 ms ahead, 40 ms symmetric path: t0=1000, t1=1520, t2=1530, t3=1050 → offset 500
        assertEquals(500L, Sntp.offsetMs(1_000, 1_520, 1_530, 1_050))
        assertEquals(-250L, Sntp.offsetMs(10_000, 9_760, 9_770, 10_030))
    }

    @Test fun craftedServerReply() {
        val t0 = 1_762_000_000_000L
        val t3 = t0 + 40
        val reply = ByteArray(48)
        reply[0] = ((0 shl 6) or (4 shl 3) or 4).toByte() // server mode
        reply[1] = 2 // stratum
        Sntp.writeTimestamp(reply, 24, t0)          // originate echoes our transmit
        Sntp.writeTimestamp(reply, 32, t0 + 1_220)  // server receive (server is 1200 ms ahead, 20 ms one-way)
        Sntp.writeTimestamp(reply, 40, t0 + 1_221)  // server transmit
        assertEquals(1_200L, Sntp.offsetFromReply(reply, 48, t0, t3))
    }

    @Test fun rejectsBadReplies() {
        val t0 = 1_762_000_000_000L
        val good = ByteArray(48).also {
            it[0] = 0x24; it[1] = 1
            Sntp.writeTimestamp(it, 24, t0); Sntp.writeTimestamp(it, 32, t0); Sntp.writeTimestamp(it, 40, t0)
        }
        assertNull(Sntp.offsetFromReply(good, 47, t0, t0))                                  // short
        assertNull(Sntp.offsetFromReply(good.copyOf().also { it[1] = 0 }, 48, t0, t0))      // kiss-o'-death stratum 0
        assertNull(Sntp.offsetFromReply(good.copyOf().also { it[0] = 0x23 }, 48, t0, t0))   // client mode
        assertNull(Sntp.offsetFromReply(good.copyOf().also { it[0] = 0xE4.toByte() }, 48, t0, t0)) // LI=3 unsynchronised
        assertNull(Sntp.offsetFromReply(good, 48, t0 + 5_000, t0 + 5_000))                  // originate mismatch
    }
}
