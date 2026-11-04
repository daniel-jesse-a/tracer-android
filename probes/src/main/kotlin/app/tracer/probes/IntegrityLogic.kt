package app.tracer.probes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.security.MessageDigest

/*
 * Pure logic for server identity (probe.remote_ip / edge_pop / edge_headers / tls_cert_sha256) and
 * the integrity checks (integrity table: dns_doh, tls_cert, first_hop). No Android / OkHttp imports,
 * unit-tested on the JVM (tools/verify-jvm.sh).
 */

// ---------------------------------------------------------------- server identity

/**
 * Response headers that identify the edge / CDN node that answered, in the order they are written
 * to probe.edge_headers (lower-case keys). Only headers present on the response are written.
 */
val EDGE_HEADER_KEYS: List<String> = listOf(
    "cf-ray", "x-amz-cf-pop", "x-amz-cf-id", "x-served-by", "x-cache", "via", "server", "alt-svc",
    "x-apple-edge", "x-apple-request-uuid", "cdnuuid",
)

/** Max characters kept per edge header value. */
const val EDGE_HEADER_MAX_CHARS = 120

/**
 * JSON object of the edge headers present ([lookup] returns a header value by lower-case name, or
 * null), values truncated to [EDGE_HEADER_MAX_CHARS]. null when none of [EDGE_HEADER_KEYS] is present.
 */
fun edgeHeadersJson(lookup: (String) -> String?): String? {
    val present = EDGE_HEADER_KEYS.mapNotNull { k -> lookup(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it.take(EDGE_HEADER_MAX_CHARS) } }
    if (present.isEmpty()) return null
    return buildJsonObject { present.forEach { (k, v) -> put(k, v) } }.toString()
}

private val IATA_RE = Regex("^[A-Za-z]{3}$")
private val FASTLY_NODE_RE = Regex("""cache-[a-z0-9]+-([A-Z]{3})\b""")
private val APPLE_VIA_RE = Regex("""\b([a-z]{2}-[a-z]{3}\d*-edge[-a-z0-9]*)""")

/**
 * Best-effort edge PoP from the response headers:
 *  - Cloudflare `cf-ray: 8d1a2b3c4d5e6f70-DXB` → "DXB"
 *  - CloudFront `x-amz-cf-pop: DXB52-P1` → "DXB52-P1"
 *  - Fastly `x-served-by: cache-dxb1234-DXB` → "DXB" (last node of the chain)
 *  - Apple `x-apple-edge` value, else a `via` node such as `ae-dxb-edge-001` → that node name
 * null when nothing identifies the PoP (e.g. Google, which exposes no PoP header).
 */
fun edgePopFrom(lookup: (String) -> String?): String? {
    lookup("cf-ray")?.trim()?.substringAfterLast('-', "")?.takeIf { IATA_RE.matches(it) }?.let { return it.uppercase() }
    lookup("x-amz-cf-pop")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(32) }
    lookup("x-served-by")?.let { v -> FASTLY_NODE_RE.findAll(v).lastOrNull()?.groupValues?.get(1)?.let { return it } }
    lookup("x-apple-edge")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(32) }
    lookup("via")?.let { v -> APPLE_VIA_RE.find(v.lowercase())?.groupValues?.get(1)?.let { return it.take(32) } }
    return null
}

/** IP literal without IPv6 scope id ("fe80::1%wlan0" → "fe80::1"). */
fun stripScope(ip: String): String = ip.substringBefore('%')

/** 4 | 6 for an IP literal, null if neither. */
fun ipVersionOf(ip: String?): Int? = when {
    ip.isNullOrBlank() -> null
    ip.contains(':') -> 6
    ip.count { it == '.' } == 3 -> 4
    else -> null
}

/** Lower-case hex SHA-256 of [bytes]. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** CN of an X.500 distinguished name ("CN=WE1, O=Google Trust Services, C=US" → "WE1"); null if absent. */
fun commonName(dn: String?): String? {
    if (dn.isNullOrBlank()) return null
    // RFC 2253 / toString() forms; values with escaped commas are rare in issuer CNs.
    return dn.split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substring(3)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
}

// ---------------------------------------------------------------- dns_doh

const val DOH_URL = "https://cloudflare-dns.com/dns-query"

/** DoH JSON query URL for [host] / [type] ("A" | "AAAA"). */
fun dohUrl(host: String, type: String): String = "$DOH_URL?name=$host&type=$type"

/**
 * Addresses from a DoH JSON (application/dns-json) body: Answer records of type 1 (A) / 28 (AAAA);
 * CNAMEs are skipped. null when the body is not a valid DoH response (or Status != 0 and no answers).
 */
fun parseDohAnswers(body: String): List<String>? {
    val o = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    } ?: return null
    val status = (o["Status"] as? JsonPrimitive)?.intOrNull ?: return null
    val answers = (o["Answer"] as? JsonArray).orEmpty().mapNotNull { a ->
        val rec = a as? JsonObject ?: return@mapNotNull null
        val type = (rec["type"] as? JsonPrimitive)?.intOrNull
        if (type != 1 && type != 28) return@mapNotNull null
        (rec["data"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
    // NXDOMAIN (3) etc. → empty answer list, still a valid response
    return if (status == 0 || answers.isNotEmpty()) answers else emptyList()
}

/** Canonical form for comparing IP literals (lower-case, no scope, IPv6 zero-run normalised via expand). */
fun canonicalIp(ip: String): String {
    val s = stripScope(ip.trim()).lowercase()
    if (!s.contains(':')) return s
    return expandIpv6(s) ?: s
}

/** "2001:db8::1" → "2001:0db8:0000:0000:0000:0000:0000:0001"; null if not parseable. Embedded IPv4 tails unsupported. */
fun expandIpv6(s: String): String? {
    if (s.contains('.')) return null
    val parts = s.split("::")
    if (parts.size > 2) return null
    val head = parts[0].split(':').filter { it.isNotEmpty() }
    val tail = if (parts.size == 2) parts[1].split(':').filter { it.isNotEmpty() } else emptyList()
    val missing = 8 - head.size - tail.size
    if (missing < 0 || (parts.size == 1 && missing != 0)) return null
    val groups = head + List(missing) { "0" } + tail
    if (groups.any { it.length > 4 || !it.all { c -> c.isDigit() || c in 'a'..'f' } }) return null
    return groups.joinToString(":") { it.padStart(4, '0') }
}

/** Network prefix used as a softer comparison: IPv4 /24, IPv6 /48. */
fun ipPrefix(ip: String): String {
    val c = canonicalIp(ip)
    return if (c.contains(':')) c.split(':').take(3).joinToString(":") + "::/48" else c.substringBeforeLast('.') + ".0/24"
}

/** Result of comparing the system resolver with DoH for one host on one path. */
data class DnsComparison(val ok: Boolean?, val value: String, val detailJson: String)

/**
 * ok = true when the answer sets share at least one address (CDNs rotate, so overlap, not equality);
 * false when both resolved without overlap, or the system resolver failed/returned nothing while DoH
 * resolved; null when DoH itself failed (cannot test).
 * @param system null = system lookup failed with [systemError]
 * @param doh null = DoH failed with [dohError]
 */
fun compareDns(
    host: String,
    system: List<String>?,
    doh: List<String>?,
    systemError: String? = null,
    dohError: String? = null,
): DnsComparison {
    val sys = system.orEmpty().map(::canonicalIp).distinct()
    val dns = doh.orEmpty().map(::canonicalIp).distinct()
    val overlap = sys.intersect(dns.toSet())
    val prefixOverlap = sys.map(::ipPrefix).toSet().intersect(dns.map(::ipPrefix).toSet()).isNotEmpty()
    val ok: Boolean? = when {
        doh == null -> null
        dns.isEmpty() && sys.isEmpty() -> null // nothing to compare (both empty / NXDOMAIN)
        sys.isEmpty() || dns.isEmpty() -> false
        else -> overlap.isNotEmpty()
    }
    fun list(l: List<String>?, err: String?) = when {
        l == null -> err ?: "error"
        l.isEmpty() -> "none"
        else -> l.joinToString(",") { stripScope(it.trim()) }
    }
    val value = "system: ${list(system, systemError)} · doh: ${list(doh, dohError)}"
    val detail = buildJsonObject {
        put("host", host)
        putJsonArray("system") { system.orEmpty().forEach { add(JsonPrimitive(stripScope(it.trim()))) } }
        putJsonArray("doh") { doh.orEmpty().forEach { add(JsonPrimitive(stripScope(it.trim()))) } }
        systemError?.let { put("system_error", it) }
        dohError?.let { put("doh_error", it) }
        put("overlap", overlap.size)
        put("prefix_overlap", prefixOverlap)
        put("doh_server", DOH_URL)
    }.toString()
    return DnsComparison(ok, value, detail)
}

/** Hosts of [urls] (scheme://host[:port]/…), lower-case, distinct, order kept. */
fun hostsOf(urls: List<String>): List<String> = urls.mapNotNull { u ->
    u.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfter('@').let { h -> if (h.startsWith("[")) h.substringBefore(']').trimStart('[') else h.substringBefore(':') }
        .lowercase().takeIf { it.isNotEmpty() }
}.distinct()

// ---------------------------------------------------------------- tls_cert

/**
 * Remembers leaf fingerprints seen per (path, host) in this run. [observe] returns
 *  - FIRST when the key had no fingerprint yet,
 *  - CHANGED when a fingerprint never seen for that key appears (CDNs with several certs emit
 *    once per distinct cert, not on every flip back),
 *  - null when already seen (nothing to emit).
 */
class CertTracker {
    enum class Change { FIRST, CHANGED }
    private val seen = HashMap<Pair<String, String>, LinkedHashSet<String>>()
    private val last = HashMap<Pair<String, String>, String>()

    @Synchronized
    fun observe(path: String, host: String, sha256: String): Change? {
        val key = path to host
        val set = seen.getOrPut(key) { LinkedHashSet() }
        val change = when {
            set.isEmpty() -> Change.FIRST
            sha256 in set -> null
            else -> Change.CHANGED
        }
        set += sha256
        return change
    }

    /** Most recently emitted fingerprint for (path, host), excluding [current]. */
    @Synchronized
    fun previous(path: String, host: String, current: String): String? = seen[path to host]?.lastOrNull { it != current }
}

/** integrity.value for tls_cert: "1a2b3c4d5e6f7a8b · WE1". */
fun tlsCertValue(sha256: String, issuerCn: String?): String = sha256.take(16) + (issuerCn?.let { " · $it" } ?: "")

/** integrity.detail_json for tls_cert. */
fun tlsCertDetailJson(
    sha256: String,
    issuerCn: String?,
    subjectCn: String?,
    notAfterUtcMs: Long?,
    previousSha256: String?,
    remoteIp: String?,
    edgePop: String?,
): String = buildJsonObject {
    put("sha256", sha256)
    issuerCn?.let { put("issuer_cn", it) }
    subjectCn?.let { put("subject_cn", it) }
    notAfterUtcMs?.let { put("not_after_utc_ms", it) }
    previousSha256?.let { put("previous_sha256", it) }
    remoteIp?.let { put("remote_ip", it) }
    edgePop?.let { put("edge_pop", it) }
}.toString()

// ---------------------------------------------------------------- first_hop

/** First-hop target and limits. */
const val FIRST_HOP_TARGET = "1.1.1.1"
const val FIRST_HOP_MAX_TTL = 8

/** `/system/bin/ping -n -c 1 -W 1 -t <ttl> <target>` (-n: no reverse DNS of hops). */
fun pingTtlCommand(ttl: Int, target: String = FIRST_HOP_TARGET, binary: String = "/system/bin/ping"): List<String> =
    listOf(binary, "-n", "-c", "1", "-W", "1", "-t", ttl.toString(), target)

/** One TTL step: [ip] = the router that answered (TTL exceeded) or the target (echo reply); null = no answer. */
data class Hop(val ttl: Int, val ip: String?, val rttMs: Double?, val reachedTarget: Boolean)

private val FROM_PAREN_RE = Regex("""From\s+\S+\s+\(([0-9.]+)\)""")
private val FROM_RE = Regex("""From\s+([0-9.]+)""")
private val REPLY_PAREN_RE = Regex("""bytes from\s+\S+\s+\(([0-9.]+)\)""")
private val REPLY_RE = Regex("""bytes from\s+([0-9.]+)""")

/**
 * Parses one TTL-limited ping's output:
 *  "From 192.168.1.1 icmp_seq=1 Time to live exceeded" → hop 192.168.1.1
 *  "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=3.2 ms" → reached the target
 * [elapsedMs] (process wall time) is used as the RTT upper bound when the line carries none.
 */
fun parseTtlPing(ttl: Int, output: String, elapsedMs: Double? = null): Hop {
    (REPLY_PAREN_RE.find(output) ?: REPLY_RE.find(output))?.let { m ->
        return Hop(ttl, m.groupValues[1].trimEnd('.'), parsePingRttMs(output) ?: elapsedMs, reachedTarget = true)
    }
    val from = output.lineSequence().firstOrNull { it.contains("Time to live exceeded", ignoreCase = true) || it.contains("TTL exceeded", ignoreCase = true) }
        ?.let { line -> (FROM_PAREN_RE.find(line) ?: FROM_RE.find(line))?.groupValues?.get(1)?.trimEnd('.') }
    return Hop(ttl, from, if (from != null) parsePingRttMs(output) ?: elapsedMs else null, reachedTarget = false)
}

/** Parses a dotted IPv4 literal into 4 octets, or null. */
private fun ipv4Octets(ip: String): IntArray? {
    val p = ip.trim().split('.')
    if (p.size != 4) return null
    val o = IntArray(4)
    for (i in 0..3) { o[i] = p[i].toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
    return o
}

/**
 * true for a globally routable address: not RFC 1918 (10/8, 172.16/12, 192.168/16), CGNAT
 * (100.64/10), link-local (169.254/16), loopback, 0/8, multicast/reserved (≥ 224), nor IPv6
 * ULA (fc00::/7), link-local (fe80::/10) or loopback.
 */
fun isPublicIp(ip: String?): Boolean {
    if (ip.isNullOrBlank()) return false
    if (ip.contains(':')) {
        val c = canonicalIp(ip)
        val first = c.substringBefore(':').toIntOrNull(16) ?: return false
        return !(c == "0000:0000:0000:0000:0000:0000:0000:0001" || first and 0xFE00 == 0xFC00 || first and 0xFFC0 == 0xFE80 || first == 0)
    }
    val o = ipv4Octets(ip) ?: return false
    return when {
        o[0] == 10 || o[0] == 127 || o[0] == 0 -> false
        o[0] == 172 && o[1] in 16..31 -> false
        o[0] == 192 && o[1] == 168 -> false
        o[0] == 100 && o[1] in 64..127 -> false
        o[0] == 169 && o[1] == 254 -> false
        o[0] >= 224 -> false
        else -> true
    }
}

/** first_hop outcome: [publicHop] = the first public hop (null if none within the TTL budget). */
data class FirstHopResult(val publicHop: Hop?, val hops: List<Hop>) {
    val ok: Boolean get() = publicHop != null
    val detailJson: String
        get() = buildJsonObject {
            put("target", FIRST_HOP_TARGET)
            publicHop?.let { put("ttl", it.ttl); put("is_target", it.reachedTarget) }
            putJsonArray("hops") {
                hops.forEach { h ->
                    addJsonObject {
                        put("ttl", h.ttl)
                        h.ip?.let { put("ip", it) }
                        h.rttMs?.let { put("rtt_ms", Math.round(it * 10) / 10.0) }
                        if (h.reachedTarget) put("target", true)
                    }
                }
            }
        }.toString()
}

/**
 * Walks TTL 1..[maxTtl] with [probe] (one TTL-limited ping each) and stops at the first public hop
 * or when the target answered. [probe] returning null = ping unusable (stop, nothing found).
 */
inline fun findFirstPublicHop(maxTtl: Int = FIRST_HOP_MAX_TTL, probe: (Int) -> Hop?): FirstHopResult {
    val hops = ArrayList<Hop>()
    for (ttl in 1..maxTtl) {
        val hop = probe(ttl) ?: break
        hops += hop
        if (isPublicIp(hop.ip)) return FirstHopResult(hop, hops)
        if (hop.reachedTarget) break
    }
    return FirstHopResult(null, hops)
}
