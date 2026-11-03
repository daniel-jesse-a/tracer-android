package app.tracer.probes

/*
 * Pure logic of the internal probes (probe_internal): DNS query build/parse and ping-output parse.
 * No Android imports — unit-tested on the JVM (tools/verify-jvm.sh).
 */

/** Name queried at each DHCP DNS server (A record). Small, anycast, always resolvable. */
const val DNS_PROBE_QNAME = "connectivitycheck.gstatic.com"

/** A parsed DNS response header. */
data class DnsReply(val id: Int, val rcode: Int, val answers: Int, val truncated: Boolean)

/**
 * Minimal DNS query: header (ID=[id], RD=1, QDCOUNT=1) + one question [qname] / [qtype] (A) / IN.
 * @throws IllegalArgumentException for an empty name or a label longer than 63 bytes
 */
fun buildDnsQuery(id: Int, qname: String = DNS_PROBE_QNAME, qtype: Int = 1): ByteArray {
    val labels = qname.trimEnd('.').split('.')
    require(labels.isNotEmpty() && labels.all { it.isNotEmpty() }) { "bad qname: $qname" }
    val encoded = labels.map { it.toByteArray(Charsets.US_ASCII) }
    require(encoded.all { it.size <= 63 }) { "label too long: $qname" }
    val size = 12 + encoded.sumOf { it.size + 1 } + 1 + 4
    val out = ByteArray(size)
    out[0] = (id shr 8).toByte(); out[1] = id.toByte()
    out[2] = 0x01 // QR=0, OPCODE=0, AA=0, TC=0, RD=1
    out[3] = 0x00 // RA=0, Z=0, RCODE=0
    out[5] = 1 // QDCOUNT = 1 (bytes 4..5); AN/NS/AR = 0
    var o = 12
    for (label in encoded) {
        out[o++] = label.size.toByte()
        label.copyInto(out, o); o += label.size
    }
    out[o++] = 0 // root
    out[o++] = (qtype shr 8).toByte(); out[o++] = qtype.toByte()
    out[o++] = 0; out[o] = 1 // QCLASS IN
    return out
}

/**
 * Parses the header of a DNS response. Returns null unless [length] ≥ 12 bytes, QR=1 and the ID
 * equals [expectedId] (a stray/late answer to another query is not our reply).
 */
fun parseDnsReply(data: ByteArray, length: Int, expectedId: Int): DnsReply? {
    if (length < 12 || data.size < 12) return null
    val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    if (id != (expectedId and 0xFFFF)) return null
    val flags1 = data[2].toInt() and 0xFF
    if (flags1 and 0x80 == 0) return null // QR bit: not a response
    val rcode = data[3].toInt() and 0x0F
    val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
    return DnsReply(id, rcode, answers, truncated = flags1 and 0x02 != 0)
}

private val PING_TIME_RE = Regex("""time[=<]\s*([0-9]+(?:\.[0-9]+)?)\s*ms""")

/** RTT from ping output ("64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=3.21 ms"), or null when no reply. */
fun parsePingRttMs(output: String): Double? = PING_TIME_RE.find(output)?.groupValues?.get(1)?.toDoubleOrNull()

/** Outcome of one ping: [rttMs] when a reply arrived, otherwise [error] = timeout | io. */
data class PingOutcome(val rttMs: Double?, val error: String?) {
    val replied: Boolean get() = rttMs != null
}

/**
 * @param exitCode process exit status, or null when the process was killed after the deadline.
 *        ping exits 0 = reply, 1 = no reply, 2 = other error (bad option, bind refused …).
 */
fun pingOutcome(exitCode: Int?, output: String): PingOutcome {
    val rtt = parsePingRttMs(output)
    return when {
        rtt != null -> PingOutcome(rtt, null)
        exitCode == null || exitCode == 1 -> PingOutcome(null, "timeout")
        else -> PingOutcome(null, "io")
    }
}

/** ping refused to bind to the interface (-I needs privileges on some builds) → retry unbound. */
fun pingBindFailed(output: String): Boolean =
    output.contains("SO_BINDTODEVICE") || output.contains("unknown iface", ignoreCase = true) ||
        output.contains("Operation not permitted") || output.contains("Cannot assign requested address")

/** `/system/bin/ping -c 1 -W 1 [-I ifname] gw` */
fun pingCommand(gateway: String, ifname: String?, binary: String = "/system/bin/ping"): List<String> = buildList {
    add(binary); add("-c"); add("1"); add("-W"); add("1")
    if (!ifname.isNullOrBlank()) { add("-I"); add(ifname) }
    add(gateway)
}

/** Internal-probe cadence: the latency interval, but never faster than every 2 s. */
fun internalProbeIntervalMs(latencyIntervalMs: Long): Long = maxOf(latencyIntervalMs, 2_000L)
