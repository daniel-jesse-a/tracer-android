package app.tracer.probes

import app.tracer.core.runtime.TowerLocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * LRU cache of tower lookups — hits AND definite misses — mirrored to a JSON file so it survives
 * restarts. Thread-safe (all methods synchronized). Pure JVM.
 *
 * File format: {"<key>": {"lat":..,"lon":..,"range":..,"source":".."} | null, ...}
 */
class TowerCache(private val file: File?, private val capacity: Int = 500) {

    /** Wrapper so a cached miss (null location) is distinguishable from "not cached". */
    data class Entry(val location: TowerLocation?)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }
    private var loaded = false

    @Synchronized
    fun get(key: String): Entry? {
        ensureLoaded()
        return map[key]
    }

    @Synchronized
    fun put(key: String, location: TowerLocation?) {
        ensureLoaded()
        map[key] = Entry(location)
        persist()
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return map.size
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return
            for ((k, v) in root) {
                map[k] = Entry(if (v is JsonObject) decode(v) else null)
            }
        } catch (e: IOException) {
            map.clear()
        } catch (e: IllegalArgumentException) { // malformed JSON
            map.clear()
        }
    }

    private fun persist() {
        val f = file ?: return
        val json = JsonObject(map.mapValues { (_, e) -> e.location?.let(::encode) ?: JsonNull })
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: IOException) {
            // Cache persistence is best-effort; the in-memory LRU still works.
        }
    }

    private fun encode(t: TowerLocation): JsonObject = buildJsonObject {
        put("lat", t.lat)
        put("lon", t.lon)
        put("range", t.rangeM)
        put("source", t.source)
    }

    private fun decode(o: JsonObject): TowerLocation? {
        val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return TowerLocation(
            lat = lat,
            lon = lon,
            rangeM = (o["range"] as? JsonPrimitive)?.doubleOrNull,
            source = (o["source"] as? JsonPrimitive)?.contentOrNull ?: "opencellid",
        )
    }

    companion object {
        fun key(radio: String, mcc: String, mnc: String, tac: Int, cellId: Long) = "$radio:$mcc:$mnc:$tac:$cellId"
    }
}

/** CellSample.rat → OpenCelliD `radio` value (GSM | UMTS | LTE | NR | CDMA), or null if unsupported. */
fun openCellIdRadio(rat: String): String? = when (rat.uppercase()) {
    "LTE", "LTE_CA", "LTE_ADV_PRO" -> "LTE"
    "NR", "NR_SA", "NR_NSA", "NR_ADVANCED" -> "NR"
    "WCDMA", "UMTS", "HSPA", "TDSCDMA" -> "UMTS"
    "GSM" -> "GSM"
    "CDMA" -> "CDMA"
    else -> null
}

/**
 * Parses an OpenCelliD /cell/get JSON response.
 * Returns Result.success(location) for a hit, Result.success(null) for a definite "not found",
 * and Result.failure for anything else (rate limit, bad key, garbage) so it is not cached.
 */
fun parseOpenCellIdResponse(body: String): Result<TowerLocation?> {
    val o = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    } ?: return Result.failure(IOException("not a JSON object"))
    val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull
    val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull
    if (lat != null && lon != null) {
        return Result.success(TowerLocation(lat, lon, (o["range"] as? JsonPrimitive)?.doubleOrNull, "opencellid"))
    }
    val error = (o["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val code = (o["code"] as? JsonPrimitive)?.contentOrNull
    // OpenCelliD: code 1 = "Cell not found".
    return if (code == "1" || error.contains("not found", ignoreCase = true)) Result.success(null)
    else Result.failure(IOException("OpenCelliD error: $error"))
}
