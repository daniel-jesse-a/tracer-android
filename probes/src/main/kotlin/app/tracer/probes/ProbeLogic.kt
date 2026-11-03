package app.tracer.probes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/*
 * Pure probe logic (JVM only, no Android / OkHttp) — unit-tested directly.
 */

/**
 * Maps a transport failure to probe.error: timeout | dns | connect | tls | io.
 * Walks the cause chain so wrapped exceptions classify the same way.
 */
fun classifyError(t: Throwable): String {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 8) {
        when (cur) {
            // SocketTimeoutException extends InterruptedIOException; OkHttp's callTimeout throws InterruptedIOException("timeout").
            is InterruptedIOException -> return "timeout"
            is UnknownHostException -> return "dns"
            is ConnectException, is NoRouteToHostException -> return "connect"
            is SSLException -> return "tls"
        }
        cur = cur.cause
        depth++
    }
    return "io"
}

/** probe.error for an HTTP status: null for 2xx, "http_<code>" otherwise (redirects are not followed, so 3xx fails). */
fun httpError(code: Int): String? = if (code in 200..299) null else "http_$code"

/**
 * Adaptive latency-probe interval.
 * - degraded → max(1000, base/2)
 * - adaptive and the last 10 probes all succeeded under 40 ms → grow current by 1.5×, capped at 2× base
 * - otherwise → base
 * @param recentTotalsMs most recent probe totals, newest last; null = failed probe
 */
fun nextLatencyIntervalMs(
    baseMs: Long,
    currentMs: Long,
    degraded: Boolean,
    adaptive: Boolean,
    recentTotalsMs: List<Double?>,
): Long {
    if (degraded) return maxOf(1_000L, baseMs / 2)
    if (adaptive && recentTotalsMs.size >= 10) {
        val last10 = recentTotalsMs.takeLast(10)
        if (last10.all { it != null && it < 40.0 }) {
            val grown = (maxOf(currentMs, baseMs) * 1.5).toLong()
            return minOf(grown, baseMs * 2)
        }
    }
    return baseMs
}

/** Parsed ipinfo.io response. */
data class IpInfo(val ip: String?, val asn: String?, val asnOrg: String?)

/** Splits ipinfo's `org` ("AS5384 Emirates Telecommunications Corporation") into ("AS5384", "Emirates …"). */
fun splitAsnOrg(org: String?): Pair<String?, String?> {
    val s = org?.trim().orEmpty()
    if (s.isEmpty()) return null to null
    val first = s.substringBefore(' ')
    return if (first.length > 2 && first.startsWith("AS", ignoreCase = true) && first.substring(2).all(Char::isDigit)) {
        first.uppercase() to s.substringAfter(' ', "").trim().ifEmpty { null }
    } else {
        null to s
    }
}

/** Parses an ipinfo.io JSON body; null if the body is not a JSON object. */
fun parseIpInfo(body: String): IpInfo? {
    val o = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: IllegalArgumentException) { // SerializationException extends IllegalArgumentException
        null
    } ?: return null
    val ip = (o["ip"] as? JsonPrimitive)?.contentOrNull
    val (asn, org) = splitAsnOrg((o["org"] as? JsonPrimitive)?.contentOrNull)
    return IpInfo(ip, asn, org)
}

/** Builds the throughput URL, substituting `{bytes}`. */
fun throughputUrl(template: String, bytes: Long): String = template.replace("{bytes}", bytes.toString())

/** Megabits per second for [bytes] transferred in [durationNs]; null if not computable. */
fun mbps(bytes: Long, durationNs: Long): Double? =
    if (bytes <= 0 || durationNs <= 0) null else bytes * 8.0 / (durationNs / 1e9) / 1e6
