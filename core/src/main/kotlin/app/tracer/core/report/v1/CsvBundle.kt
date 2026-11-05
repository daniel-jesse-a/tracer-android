package app.tracer.core.report.v1

import app.tracer.core.export.Csv
import app.tracer.core.model.*
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import kotlinx.serialization.json.*
import java.io.File

/**
 * Reads an unzipped Tracer bundle (run.json + <table>.csv, Android or iOS) into [ReportInput], and
 * writes one in the same layout (used to cross-check the port against build_report_data.py and to
 * produce the CI sample PDF).
 */
object CsvBundle {
    private val TABLES = listOf(Table.PROBE, Table.EVENT, Table.RADIO, Table.NETWORK_PATH, Table.LOCATION, Table.DEVICE, Table.WIFI_CONNECTION, Table.X_COLLECTOR_LOG)

    fun load(dir: File, rail: RailData? = null, basemap: JsonObject? = null): ReportInput {
        val rj = Json.parseToJsonElement(File(dir, "run.json").readText()).jsonObject
        val r = rj["run"]!!.jsonObject; val d = rj["device"]?.jsonObject ?: JsonObject(emptyMap())
        fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: (o[k] as? JsonPrimitive)?.doubleOrNull?.toLong()
        val run = RunRecord(
            runId = s(r, "run_id")!!, name = s(r, "name") ?: "", mode = s(r, "mode") ?: "",
            devicePlatform = s(d, "platform") ?: "android", deviceModel = s(d, "model") ?: "", osVersion = s(d, "os_version") ?: "",
            appVersion = s(d, "app_version") ?: "", startedAt = l(r, "started_at")!!, endedAt = l(r, "ended_at"),
            site = s(r, "site"), testNumber = l(r, "test_number")?.toInt(), protocol = s(r, "protocol"),
            timeZone = s(rj, "time_zone"),
        )
        val samples = HashMap<Table, List<Pair<Long, Sample>>>()
        for (t in TABLES) {
            val f = File(dir, "${t.sqlName}.csv"); if (!f.exists()) continue
            val out = ArrayList<Pair<Long, Sample>>()
            f.bufferedReader().use { br ->
                val rows = Csv.parse(br).iterator(); if (!rows.hasNext()) return@use
                val head = rows.next().map { it ?: "" }
                var n = 0L
                while (rows.hasNext()) {
                    val row = rows.next(); if (row.size == 1 && row[0] == null) continue
                    n++
                    val m = HashMap<String, Any?>(); head.forEachIndexed { i, h -> m[h] = row.getOrNull(i) }
                    if (m["run_id"] == null) m["run_id"] = run.runId
                    val id = (m["id"] as? String)?.toLongOrNull() ?: n
                    runCatching { SampleCodec.decode(t, SampleCodec.MapRow(m)) }.getOrNull()?.let { out += id to it }
                }
            }
            samples[t] = out
        }
        @Suppress("UNCHECKED_CAST")
        fun <T> of(t: Table) = samples[t].orEmpty().map { it.second as T }
        return ReportInput(
            run = run, completeness = rj["completeness"] as? JsonObject,
            probes = of(Table.PROBE), events = samples[Table.EVENT].orEmpty().map { it.first to it.second as EventSample },
            radio = of(Table.RADIO), networkPath = of(Table.NETWORK_PATH), locations = of(Table.LOCATION), device = of(Table.DEVICE),
            wifi = of(Table.WIFI_CONNECTION), collectorLog = of(Table.X_COLLECTOR_LOG), rail = rail, basemap = basemap,
            sysdiagnose = (rj["completeness"] as? JsonObject)?.get("sysdiagnose")?.let { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    fun write(inp: ReportInput, dir: File) {
        dir.mkdirs()
        val run = inp.run
        File(dir, "run.json").writeText(buildJsonObject {
            putJsonObject("run") { put("run_id", run.runId); put("name", run.name); put("mode", run.mode); put("started_at", run.startedAt); put("ended_at", run.endedAt)
                put("site", run.site); put("test_number", run.testNumber) }
            putJsonObject("device") { put("platform", run.devicePlatform); put("model", run.deviceModel); put("os_version", run.osVersion); put("app_version", run.appVersion) }
            put("time_zone", run.timeZone); put("completeness", inp.completeness ?: JsonNull)
        }.toString())
        fun table(t: Table, rows: List<Pair<Long?, Sample>>) {
            val def = Schema.of(t)
            File(dir, "${t.sqlName}.csv").bufferedWriter().use { w ->
                Csv.writeRow(w, listOf("id") + def.columnNames)
                rows.forEachIndexed { i, (id, s) -> Csv.writeRow(w, listOf<Any?>(id ?: (i + 1L)) + SampleCodec.encode(s)) }
            }
        }
        table(Table.PROBE, inp.probes.map { null to it }); table(Table.EVENT, inp.events.map { it.first to it.second })
        table(Table.RADIO, inp.radio.map { null to it }); table(Table.NETWORK_PATH, inp.networkPath.map { null to it })
        table(Table.LOCATION, inp.locations.map { null to it }); table(Table.DEVICE, inp.device.map { null to it })
        table(Table.WIFI_CONNECTION, inp.wifi.map { null to it }); table(Table.X_COLLECTOR_LOG, inp.collectorLog.map { null to it })
    }
}
