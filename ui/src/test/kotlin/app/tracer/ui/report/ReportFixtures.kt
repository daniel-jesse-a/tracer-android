package app.tracer.ui.report

import app.tracer.core.export.Csv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Test helpers: read report-v1 fixtures (tar.gz bundles of run.json + CSVs, gzipped JSON) and compare JSON with tolerance. */
object ReportFixtures {
    val TABLES = listOf("probe", "event", "location", "radio", "network_path", "wifi_connection", "device", "x_collector_log", "annotation")

    fun resource(name: String): InputStream = ReportFixtures::class.java.classLoader!!.getResourceAsStream("report-v1/$name")
        ?: error("missing test resource report-v1/$name")

    fun gzJson(name: String): JsonElement = Json.parseToJsonElement(GZIPInputStream(resource(name)).readBytes().toString(Charsets.UTF_8))

    /** Minimal ustar reader: file name (last path part) → bytes. */
    fun untar(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val ins = GZIPInputStream(input).buffered()
        val header = ByteArray(512)
        while (true) {
            if (ins.readNBytes(header, 0, 512) < 512 || header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100).trimEnd('\u0000')
            val prefix = String(header, 345, 155).trimEnd('\u0000')
            val size = String(header, 124, 12).trim('\u0000', ' ').ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val data = ByteArray(size.toInt()); ins.readNBytes(data, 0, data.size)
            val pad = ((512 - size % 512) % 512).toInt(); ins.readNBytes(ByteArray(pad), 0, pad)
            if (type == '0' || type == '\u0000') out[(if (prefix.isNotEmpty()) "$prefix/$name" else name).substringAfterLast('/')] = data
        }
        return out
    }

    fun rows(csv: ByteArray?): List<Row> {
        if (csv == null) return emptyList()
        val all = Csv.parse(csv.toString(Charsets.UTF_8).removePrefix("﻿").reader()).toList()
        val head = all.firstOrNull() ?: return emptyList()
        return all.drop(1).filter { it.size > 1 || !it.firstOrNull().isNullOrEmpty() }.map { r -> head.indices.associate { i -> (head[i] ?: "") to r.getOrNull(i) } }
    }

    class Bundle(val run: ReportRun, val runJson: JsonObject, val tables: ReportTables, val annotation: List<Row>)

    fun bundle(files: Map<String, ByteArray>): Bundle {
        val runJson = Json.parseToJsonElement(files.getValue("run.json").toString(Charsets.UTF_8)) as JsonObject
        fun t(n: String) = rows(files["$n.csv"])
        return Bundle(ReportRun.fromRunJson(runJson), runJson, ReportTables(t("probe"), t("event"), t("location"), t("radio"), t("network_path"),
            t("wifi_connection"), t("device"), t("x_collector_log")), t("annotation"))
    }

    fun bundleDir(dir: File): Map<String, ByteArray> = dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }

    /** The kit's rail asset when present locally / in CI; null in a plain public checkout. */
    fun rail(): Rail? = listOf("src/main/assets/report/rail_ergt.json", "ui/src/main/assets/report/rail_ergt.json").map(::File).firstOrNull { it.exists() }
        ?.let { Rail.parse(Json.parseToJsonElement(it.readText()) as JsonObject) }

    fun template(): String = listOf("src/main/assets/report/tracer-report-template.html", "ui/src/main/assets/report/tracer-report-template.html")
        .map(::File).first { it.exists() }.readText()

    /** Expected output as if built without the rail overlay (for checkouts without the internal asset). */
    fun withoutRail(e: JsonObject): JsonObject {
        val m = e["map"] as JsonObject
        val labels = (m["labels"] as JsonArray).take(2)
        val gaps = (m["gaps"] as JsonArray).filter { ((it as JsonObject)["kind"] as JsonPrimitive).content == "nofix" }
        val bm = m["basemap"] as? JsonObject
        val attr = (bm?.get("attribution") as? JsonPrimitive)?.content ?: ""
        val nm = JsonObject(LinkedHashMap(m).apply { put("rail_lines", JsonArray(emptyList())); put("stations", JsonArray(emptyList()))
            put("labels", JsonArray(labels)); put("gaps", JsonArray(gaps)); put("attribution", JsonPrimitive(attr)) })
        return JsonObject(LinkedHashMap(e).apply { put("map", nm) })
    }

    /** Deep compare; numbers within 1e-9 relative. Returns the differences (path: got vs want). */
    fun diff(a: JsonElement, b: JsonElement, p: String = "$", ignore: Set<String> = emptySet(), out: MutableList<String> = mutableListOf()): List<String> {
        if (p in ignore) return out
        when {
            a is JsonObject && b is JsonObject -> {
                if (a.keys != b.keys) out += "$p keys ${a.keys.sorted()} vs ${b.keys.sorted()}"
                for (k in a.keys intersect b.keys) diff(a.getValue(k), b.getValue(k), "$p.$k", ignore, out)
            }
            a is JsonArray && b is JsonArray -> {
                if (a.size != b.size) out += "$p length ${a.size} vs ${b.size}"
                for (i in 0 until minOf(a.size, b.size)) diff(a[i], b[i], "$p[$i]", ignore, out)
            }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a.doubleOrNull != null && b.doubleOrNull != null && a.booleanOrNull == null -> {
                val x = a.doubleOrNull!!; val y = b.doubleOrNull!!
                if (kotlin.math.abs(x - y) > maxOf(1e-9, 1e-9 * kotlin.math.abs(y))) out += "$p $x vs $y"
            }
            a is JsonNull && b is JsonNull -> {}
            else -> if (a != b) out += "$p ${a.toString().take(120)} vs ${b.toString().take(120)}"
        }
        return out
    }

    fun bytes(i: InputStream): ByteArray = ByteArrayOutputStream().also { i.copyTo(it) }.toByteArray()
}
