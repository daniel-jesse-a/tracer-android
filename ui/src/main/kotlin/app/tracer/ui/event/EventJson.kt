package app.tracer.ui.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parsing of event before/after/evidence JSON for the detail sheet. Pure and lenient. */
object EventJson {

    data class Change(val key: String, val before: String?, val after: String?)
    data class Candidate(val cause: String, val confidence: Double?, val signals: List<Pair<String, String>>)

    private fun parse(text: String?): JsonElement? =
        if (text.isNullOrBlank()) null else runCatching { Json.parseToJsonElement(text) }.getOrNull()

    private fun scalar(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonPrimitive -> e.contentOrNull ?: e.toString()
        is JsonArray -> e.joinToString(", ") { scalar(it) }
        is JsonObject -> e.toString()
    }

    /** Flattens nested objects to dotted keys ("serving.rsrp" → "-97"). Arrays render inline. */
    fun flatten(e: JsonElement?, prefix: String = ""): List<Pair<String, String>> = when (e) {
        null -> emptyList()
        is JsonObject -> e.entries.flatMap { (k, v) ->
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            if (v is JsonObject) flatten(v, key) else listOf(key to scalar(v))
        }
        else -> listOf((prefix.ifEmpty { "value" }) to scalar(e))
    }

    fun flatten(text: String?): List<Pair<String, String>> = flatten(parse(text))

    /** Union of keys, before order first; values that did not change are kept (marked equal by caller). */
    fun changes(beforeJson: String?, afterJson: String?): List<Change> {
        val b = flatten(beforeJson)
        val a = flatten(afterJson)
        val am = a.toMap()
        val bm = b.toMap()
        val keys = LinkedHashSet<String>().apply { b.forEach { add(it.first) }; a.forEach { add(it.first) } }
        return keys.map { Change(it, bm[it], am[it]) }
    }

    fun candidates(evidenceJson: String?): List<Candidate> {
        val root = parse(evidenceJson) as? JsonObject ?: return emptyList()
        val arr = runCatching { root["candidates"]?.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val cause = o["cause"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: return@mapNotNull null
            val conf = o["confidence"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
            Candidate(cause, conf, flatten(o["signals"]))
        }
    }

    /** The "snapshot" object captured at emit time (serving cell, RAT, Wi-Fi, …). */
    fun snapshot(evidenceJson: String?): List<Pair<String, String>> {
        val root = parse(evidenceJson) as? JsonObject ?: return emptyList()
        return flatten(root["snapshot"])
    }
}
