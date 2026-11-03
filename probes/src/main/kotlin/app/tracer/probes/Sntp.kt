package app.tracer.probes

/**
 * Pure SNTP v4 (RFC 4330) packet helpers. Times are Unix epoch milliseconds.
 *
 * offset = ((t1 − t0) + (t2 − t3)) / 2 where t0 = client transmit, t1 = server receive,
 * t2 = server transmit, t3 = client receive.
 */
object Sntp {
    const val PACKET_SIZE = 48
    const val PORT = 123

    /** Seconds between 1900-01-01 (NTP era 0) and 1970-01-01. */
    private const val NTP_UNIX_OFFSET_S = 2_208_988_800L
    private const val ORIGINATE_OFFSET = 24
    private const val RECEIVE_OFFSET = 32
    private const val TRANSMIT_OFFSET = 40

    /** Server reply fields needed for the offset computation. */
    data class Reply(val leap: Int, val version: Int, val mode: Int, val stratum: Int, val originateMs: Long, val receiveMs: Long, val transmitMs: Long)

    /** 48-byte client request: LI=0, VN=4, Mode=3 (client), transmit timestamp = [t0Ms]. */
    fun buildRequest(t0Ms: Long): ByteArray {
        val buf = ByteArray(PACKET_SIZE)
        buf[0] = ((0 shl 6) or (4 shl 3) or 3).toByte() // 0x23
        writeTimestamp(buf, TRANSMIT_OFFSET, t0Ms)
        return buf
    }

    fun writeTimestamp(buf: ByteArray, offset: Int, unixMs: Long) {
        val seconds = Math.floorDiv(unixMs, 1000L) + NTP_UNIX_OFFSET_S
        val millis = Math.floorMod(unixMs, 1000L)
        // Round up so that readTimestamp (which truncates) returns exactly the same millisecond.
        val fraction = (millis * 0x1_0000_0000L + 999L) / 1000L
        write32(buf, offset, seconds and 0xFFFF_FFFFL)
        write32(buf, offset + 4, fraction)
    }

    /**
     * Reads an NTP timestamp. Seconds with the MSB clear are taken as NTP era 1 (≥ 2036-02-07),
     * which keeps the conversion correct for 1968–2104.
     */
    fun readTimestamp(buf: ByteArray, offset: Int): Long {
        var seconds = read32(buf, offset)
        val fraction = read32(buf, offset + 4)
        if (seconds == 0L && fraction == 0L) return 0L
        if ((seconds and 0x8000_0000L) == 0L) seconds += 0x1_0000_0000L
        return (seconds - NTP_UNIX_OFFSET_S) * 1000L + fraction * 1000L / 0x1_0000_0000L
    }

    /** Parses a server reply; null if it is malformed or not a usable server/broadcast response. */
    fun parseReply(buf: ByteArray, length: Int = buf.size): Reply? {
        if (length < PACKET_SIZE || buf.size < PACKET_SIZE) return null
        val b0 = buf[0].toInt() and 0xFF
        val leap = b0 shr 6
        val version = (b0 shr 3) and 0x7
        val mode = b0 and 0x7
        val stratum = buf[1].toInt() and 0xFF
        if (leap == 3 || (mode != 4 && mode != 5) || stratum !in 1..15) return null
        val transmit = readTimestamp(buf, TRANSMIT_OFFSET)
        if (transmit == 0L) return null
        return Reply(leap, version, mode, stratum, readTimestamp(buf, ORIGINATE_OFFSET), readTimestamp(buf, RECEIVE_OFFSET), transmit)
    }

    fun offsetMs(t0: Long, t1: Long, t2: Long, t3: Long): Long = ((t1 - t0) + (t2 - t3)) / 2

    /**
     * Validates a reply against the request (the originate timestamp must echo [t0Ms], allowing
     * for the 1 ms resolution of the round trip) and returns the clock offset in ms.
     */
    fun offsetFromReply(buf: ByteArray, length: Int, t0Ms: Long, t3Ms: Long): Long? {
        val r = parseReply(buf, length) ?: return null
        if (r.originateMs != 0L && kotlin.math.abs(r.originateMs - t0Ms) > 1) return null
        return offsetMs(t0Ms, r.receiveMs, r.transmitMs, t3Ms)
    }

    private fun read32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or (buf[off + 3].toLong() and 0xFF)

    private fun write32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v shr 24).toByte()
        buf[off + 1] = (v shr 16).toByte()
        buf[off + 2] = (v shr 8).toByte()
        buf[off + 3] = v.toByte()
    }
}
