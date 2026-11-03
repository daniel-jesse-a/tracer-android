package app.tracer.core.pairing

import app.tracer.core.model.LocationSample
import app.tracer.core.model.Sample
import app.tracer.core.util.Stats
import kotlinx.serialization.json.*
import java.util.Base64

/** Contents of the pairing QR code. Same JSON on iOS. */
data class PairingPayload(
    val runId: String,
    val platform: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
    val runName: String?,
    val startedAt: Long?,
    /** GPS − wall clock at the moment the QR was generated, ms (null if no fix yet). */
    val gpsOffsetMs: Long?,
    val v: Int = 1,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", v); put("run_id", runId); put("platform", platform); put("model", model)
        put("os_version", osVersion); put("app_version", appVersion); put("run_name", runName)
        put("started_at", startedAt); put("gps_offset_ms", gpsOffsetMs)
    }

    /** `tracer://pair?d=<base64url(json)>` — short enough for a version-10 QR at ECC M. */
    fun toUri(): String = "tracer://pair?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(toJson().toString().toByteArray())

    companion object {
        fun parse(text: String): PairingPayload? = runCatching {
            val json = if (text.startsWith("tracer://")) {
                val d = text.substringAfter("d=").substringBefore('&')
                String(Base64.getUrlDecoder().decode(d))
            } else text
            val o = Json.parseToJsonElement(json).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull
            PairingPayload(
                runId = s("run_id")!!, platform = s("platform") ?: "unknown", model = s("model") ?: "",
                osVersion = s("os_version") ?: "", appVersion = s("app_version") ?: "", runName = s("run_name"),
                startedAt = l("started_at"), gpsOffsetMs = l("gps_offset_ms"), v = o["v"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }.getOrNull()
    }
}

/**
 * v1 offline merge: both devices log GNSS time alongside wall clock. GNSS time is common to both,
 * so a sample's "true" time is t_utc + (gps − utc) offset of its device. Run B is mapped onto A's
 * wall clock with shift = offsetB − offsetA.
 */
object ClockAlignment {
    /** Median of (gps_time_ms − t_utc_ms) over location fixes that carry GNSS time. */
    fun gpsOffsetMs(locations: List<LocationSample>): Long? {
        val d = locations.mapNotNull { l -> (l.gpsTimeMs ?: l.stamp.gpsTimeMs)?.let { (it - l.stamp.tUtcMs).toDouble() } }
        return Stats.median(d)?.toLong()
    }

    /** Offset over a sub-range (start/end of run) for drift reporting. */
    fun gpsOffsetMs(locations: List<LocationSample>, firstN: Int, fromEnd: Boolean): Long? {
        val withGps = locations.filter { (it.gpsTimeMs ?: it.stamp.gpsTimeMs) != null }
        return gpsOffsetMs(if (fromEnd) withGps.takeLast(firstN) else withGps.take(firstN))
    }

    data class Alignment(val shiftMs: Long, val offsetA: Long?, val offsetB: Long?, val method: String) {
        /** Map a run-B wall-clock time onto run-A's wall clock. */
        fun bToA(tUtcB: Long): Long = tUtcB + shiftMs
    }

    fun align(aLocations: List<LocationSample>, bLocations: List<LocationSample>,
              fallbackOffsetA: Long? = null, fallbackOffsetB: Long? = null): Alignment {
        val oa = gpsOffsetMs(aLocations) ?: fallbackOffsetA
        val ob = gpsOffsetMs(bLocations) ?: fallbackOffsetB
        return if (oa != null && ob != null) Alignment(ob - oa, oa, ob, "gps")
        else Alignment(0, oa, ob, "wall_clock")
    }

    /** Pair each A sample with the B sample nearest in aligned time, within [toleranceMs]. */
    fun <A : Sample, B : Sample> nearestPairs(a: List<A>, b: List<B>, alignment: Alignment, toleranceMs: Long = 1_000): List<Pair<A, B?>> {
        val bs = b.map { alignment.bToA(it.stamp.tUtcMs) to it }.sortedBy { it.first }
        val times = bs.map { it.first }.toLongArray()
        return a.map { s ->
            val t = s.stamp.tUtcMs
            var idx = java.util.Arrays.binarySearch(times, t)
            if (idx < 0) idx = -idx - 1
            val cand = listOfNotNull(bs.getOrNull(idx), bs.getOrNull(idx - 1)).minByOrNull { kotlin.math.abs(it.first - t) }
            s to cand?.takeIf { kotlin.math.abs(it.first - t) <= toleranceMs }?.second
        }
    }
}
