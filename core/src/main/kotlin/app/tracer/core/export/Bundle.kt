package app.tracer.core.export

import app.tracer.core.model.*
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import app.tracer.core.schema.TableDef
import kotlinx.serialization.json.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What the Android export module provides: rows already read from the run's SQLite. */
interface BundleSource {
    val run: RunRecord
    /** Values in [TableDef.exportColumns] order (id first for sample tables). */
    fun rows(table: TableDef): Sequence<List<Any?>>
    /** A standalone run.sqlite built with Schema.createAllSql(), or null to skip. */
    fun sqliteFile(): File?
}

data class BundleFile(val path: String, val bytes: Long, val sha256: String, val rows: Long? = null)

data class Manifest(
    val schemaVersion: Int,
    val bundleVersion: Int,
    val generator: String,
    val generatedAtUtcMs: Long,
    val run: RunRecord,
    val files: List<BundleFile>,
    val schemaFingerprintSha256: String,
    val raw: JsonObject,
)

object Bundle {
    const val BUNDLE_VERSION = 1
    const val MANIFEST = "run.json"

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Writes the ZIP: run.sqlite, <table>.csv for every table, track.geojson, events.geojson, run.json.
     * Files are staged in [workDir] so each SHA-256 is computed over exactly the bytes that are zipped.
     */
    fun write(source: BundleSource, out: OutputStream, workDir: File, generator: String, nowUtcMs: Long = System.currentTimeMillis()): Manifest {
        workDir.mkdirs()
        val staged = mutableListOf<Pair<File, Long?>>()

        source.sqliteFile()?.let { db ->
            val f = File(workDir, "run.sqlite")
            // never copy a file onto itself (copyTo(overwrite) deletes the target first)
            if (db.canonicalFile != f.canonicalFile) db.copyTo(f, overwrite = true)
            staged += f to null
        }

        val locs = mutableListOf<LocationSample>()
        val serving = mutableListOf<CellSample>()
        val events = mutableListOf<EventSample>()

        // runs.csv
        File(workDir, "runs.csv").also { f ->
            f.bufferedWriter().use { w -> Csv.writeRow(w, Schema.RUNS.exportColumns); Csv.writeRow(w, SampleCodec.encodeRun(source.run)) }
            staged += f to 1L
        }

        for (td in Schema.sampleTables) {
            val f = File(workDir, "${td.name}.csv")
            var n = 0L
            f.bufferedWriter().use { w ->
                Csv.writeRow(w, td.exportColumns)
                for (row in source.rows(td)) {
                    Csv.writeRow(w, row); n++
                    if (td.table == Table.LOCATION || td.table == Table.CELL_SERVING || td.table == Table.EVENT) {
                        val m = td.exportColumns.zip(row).toMap()
                        when (val s = SampleCodec.decode(td.table, SampleCodec.MapRow(m))) {
                            is LocationSample -> locs += s
                            is CellSample -> serving += s
                            is EventSample -> events += s
                            else -> {}
                        }
                    }
                }
            }
            staged += f to n
        }

        File(workDir, "track.geojson").also { it.writeText(GeoJson.track(source.run.runId, locs, serving).toString()); staged += it to null }
        File(workDir, "events.geojson").also { it.writeText(GeoJson.events(source.run.runId, events).toString()); staged += it to null }

        val files = staged.map { (f, rows) -> BundleFile(f.name, f.length(), sha256Hex(f), rows) }
        val fingerprint = sha256Hex(Schema.sharedFingerprint().toByteArray())
        val manifestJson = manifestJson(source.run, files, generator, nowUtcMs, fingerprint)
        val manifestFile = File(workDir, MANIFEST).also { it.writeText(manifestJson.toString()) }

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            (staged.map { it.first } + manifestFile).forEach { f ->
                zip.putNextEntry(ZipEntry(f.name).apply { time = nowUtcMs })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return Manifest(Schema.VERSION, BUNDLE_VERSION, generator, nowUtcMs, source.run, files, fingerprint, manifestJson)
    }

    private fun parseOrString(s: String?): JsonElement =
        if (s == null) JsonNull else runCatching { Json.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) }

    fun manifestJson(run: RunRecord, files: List<BundleFile>, generator: String, nowUtcMs: Long, fingerprint: String): JsonObject = buildJsonObject {
        put("schema_version", Schema.VERSION)
        put("bundle_version", BUNDLE_VERSION)
        put("generator", generator)
        put("generated_at_utc_ms", nowUtcMs)
        put("schema_fingerprint_sha256", fingerprint)
        putJsonObject("run") {
            put("run_id", run.runId); put("name", run.name); put("mode", run.mode)
            put("started_at", run.startedAt); put("ended_at", run.endedAt); put("linked_run_id", run.linkedRunId)
        }
        putJsonObject("device") {
            put("platform", run.devicePlatform); put("model", run.deviceModel)
            put("os_version", run.osVersion); put("app_version", run.appVersion)
        }
        put("settings", parseOrString(run.settingsJson))
        putJsonObject("offsets") {
            put("gps_offset_ms_start", run.gpsOffsetMsStart); put("gps_offset_ms_end", run.gpsOffsetMsEnd); put("ntp_offset_ms", run.ntpOffsetMs)
        }
        put("completeness", parseOrString(run.completenessJson))
        putJsonArray("files") {
            files.forEach { f -> addJsonObject { put("path", f.path); put("bytes", f.bytes); put("sha256", f.sha256); f.rows?.let { put("rows", it) } } }
        }
    }

    // ---------------- Import ----------------

    class ImportedBundle(
        val manifest: JsonObject,
        val run: RunRecord,
        val samples: Map<Table, List<Sample>>,
        val integrity: List<IntegrityIssue>,
        val sqliteBytes: ByteArray?,
    ) {
        val ok: Boolean get() = integrity.isEmpty()
    }

    data class IntegrityIssue(val path: String, val problem: String)

    /**
     * Reads an Android or iOS Tracer bundle. Verifies every SHA-256 in run.json, parses CSVs into
     * samples (unknown tables and columns are ignored → forward compatible).
     */
    fun read(input: InputStream, tables: Set<Table> = Table.entries.toSet()): ImportedBundle {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) {
                    val name = e.name.substringAfterLast('/')
                    require(!name.contains("..")) { "bad entry ${e.name}" }
                    entries[name] = zip.readBytes()
                }
            }
        }
        val manifestBytes = entries[MANIFEST] ?: error("bundle has no $MANIFEST")
        val manifest = Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
        val issues = mutableListOf<IntegrityIssue>()
        manifest["files"]?.jsonArray?.forEach { fe ->
            val o = fe.jsonObject
            val path = o["path"]!!.jsonPrimitive.content
            val want = o["sha256"]?.jsonPrimitive?.contentOrNull
            val bytes = entries[path]
            when {
                bytes == null -> issues += IntegrityIssue(path, "missing")
                want != null && sha256Hex(bytes) != want.lowercase() -> issues += IntegrityIssue(path, "sha256 mismatch")
            }
        }

        val runsCsv = entries["runs.csv"] ?: error("bundle has no runs.csv")
        val run = csvRows(runsCsv).firstOrNull()?.let { SampleCodec.decodeRun(SampleCodec.MapRow(it)) } ?: error("runs.csv is empty")

        val samples = mutableMapOf<Table, List<Sample>>()
        for (td in Schema.sampleTables) {
            if (td.table !in tables) continue
            val bytes = entries["${td.name}.csv"] ?: continue
            samples[td.table] = csvRows(bytes).mapNotNull { m -> runCatching { SampleCodec.decode(td.table, SampleCodec.MapRow(m)) }.getOrNull() }.toList()
        }
        return ImportedBundle(manifest, run, samples, issues, entries["run.sqlite"])
    }

    private fun csvRows(bytes: ByteArray): Sequence<Map<String, String?>> {
        val rows = Csv.parse(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8).buffered()).iterator()
        if (!rows.hasNext()) return emptySequence()
        val header = rows.next().map { it ?: "" }
        return rows.asSequence().filter { it.any { v -> v != null } }.map { r -> header.zip(r + List(maxOf(0, header.size - r.size)) { null }).toMap() }
    }
}
