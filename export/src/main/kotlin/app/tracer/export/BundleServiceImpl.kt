package app.tracer.export

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import app.tracer.core.export.Bundle
import app.tracer.core.export.BundleSource
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Table
import app.tracer.core.runtime.BundleService
import app.tracer.core.runtime.RunRepository
import app.tracer.core.runtime.SampleSink
import app.tracer.core.schema.Schema
import app.tracer.core.schema.TableDef
import app.tracer.storage.db.SqlBinding
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Builds, reads and uploads Tracer bundles.
 *
 * Export pipeline: flush the writer → create a standalone `run.sqlite` from the canonical DDL →
 * copy the run's rows into it with `INSERT … SELECT` from the live database ATTACHed read-side →
 * add `v_<table>` views (t_run_s / t_local, like the CSVs) → stream the rows of `run.sqlite` into
 * [Bundle.write] (cursor by cursor; no table is held in memory). `onProgress` is invoked on a
 * background thread with values in 0..1.
 */
@Singleton
class BundleServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RunRepository,
    private val sink: SampleSink,
) : BundleService {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    override val fileProviderAuthority: String get() = "${context.packageName}.exports"

    /** Directory holding finished bundles; exposed to the FileProvider as `exports/`. */
    fun exportsDir(): File = File(context.cacheDir, EXPORTS_DIR)

    override suspend fun export(runId: String, onProgress: (Float) -> Unit): File {
        sink.flush()
        val run = repository.getRun(runId) ?: throw IllegalArgumentException("unknown run $runId")
        val liveDb = repository.databaseFile()
        return withContext(Dispatchers.IO) {
            val exportsDir = exportsDir().apply { mkdirs() }
            val workRoot = File(exportsDir, "work-$runId")
            workRoot.deleteRecursively()
            // run.sqlite must NOT live inside Bundle.write's workDir: write() copies it to
            // workDir/run.sqlite and File.copyTo(overwrite) deletes the target first.
            val stageDir = File(workRoot, "db").apply { mkdirs() }
            val bundleDir = File(workRoot, "bundle")
            val sqlite = File(stageDir, "run.sqlite")
            val totalSteps = (Schema.all.size + Schema.sampleTables.size + 1).toFloat()
            try {
                buildStandalone(sqlite, liveDb, run) { done -> onProgress(done / totalSteps) }

                val out = File(exportsDir, bundleFileName(run))
                val part = File(exportsDir, "${out.name}.part")
                SQLiteDatabase.openDatabase(sqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { ro ->
                    val source = SqliteBundleSource(run, ro, sqlite) { tablesDone ->
                        onProgress((Schema.all.size + tablesDone) / totalSteps)
                    }
                    FileOutputStream(part).use { fos ->
                        Bundle.write(source, fos, bundleDir, generator = "tracer-android/${versionName(run)}")
                    }
                }
                if (out.exists()) out.delete()
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
                onProgress(1f)
                out
            } finally {
                workRoot.deleteRecursively()
            }
        }
    }

    /**
     * Creates [target] with every statement of [Schema.createAllSql] and copies the rows of [runId]
     * from [liveDb] into it.
     *
     * The ATTACH is issued on the standalone connection (attaching the live file), not on the live
     * Room connection: android.database.sqlite.SQLiteDatabase permanently disables WAL on a
     * database object that executes ATTACH, and throws if other connections hold transactions at
     * that moment — which the writer thread routinely does. Reading the live WAL file from a second
     * connection is safe and sees every committed (flushed) row.
     */
    private fun buildStandalone(target: File, liveDb: File, run: RunRecord, onTable: (Int) -> Unit) {
        val runId = run.runId
        target.delete()
        val out = SQLiteDatabase.openOrCreateDatabase(target, null)
        try {
            Schema.createAllSql().forEach { out.execSQL(it) }
            out.execSQL("ATTACH DATABASE ? AS live", arrayOf<Any>(liveDb.absolutePath)) // outside any transaction
            try {
                out.beginTransaction()
                try {
                    Schema.all.forEachIndexed { i, td ->
                        val cols = columnsOf(td).joinToString(", ")
                        out.execSQL(
                            "INSERT INTO main.${td.name} ($cols) SELECT $cols FROM live.${td.name} WHERE run_id = ?",
                            arrayOf<Any>(runId),
                        )
                        onTable(i + 1)
                    }
                    out.setTransactionSuccessful()
                } finally {
                    out.endTransaction()
                }
            } finally {
                out.execSQL("DETACH DATABASE live")
            }
            viewSql(run).forEach { out.execSQL(it) }
        } finally {
            out.close()
        }
    }

    override suspend fun import(input: InputStream): Bundle.ImportedBundle =
        withContext(Dispatchers.IO) { Bundle.read(input) }

    override suspend fun upload(file: File, url: String): Int {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid upload URL: $url")
        require(file.isFile) { "no such file: $file" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("bundle", file.name, file.asRequestBody(ZIP))
            .build()
        val request = Request.Builder().url(httpUrl).post(body).build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.use { it.code }
                    cont.resume(code)
                }
            })
        }
    }

    private fun versionName(run: RunRecord): String {
        val name = runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: run.appVersion.ifBlank { "unknown" }
    }

    companion object {
        const val EXPORTS_DIR = "exports"
        private const val TIMEOUT_S = 60L
        private val ZIP = "application/zip".toMediaType()

        internal fun columnsOf(td: TableDef): List<String> = if (td.table == Table.RUNS) td.columnNames else td.exportColumns

        private val STEM_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", java.util.Locale.ROOT)

        /** Zone the run was recorded in (runs.time_zone), else the device's current zone. */
        internal fun zoneOf(run: RunRecord): ZoneId =
            run.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

        /** `office_t03_20261103-1831_1a2b3c4d.zip` (see [RunRecord.fileStem]). */
        fun bundleFileName(run: RunRecord): String {
            val local = Instant.ofEpochMilli(run.startedAt).atZone(zoneOf(run)).format(STEM_TIME)
            return RunRecord.fileStem(run.site, run.testNumber, local) + "_" + run.runId.take(8) + ".zip"
        }

        /** "+04:00" / "-03:30" / "+00:00" — UTC offset of the run's zone at its start. */
        internal fun offsetText(offset: ZoneOffset): String {
            val total = offset.totalSeconds
            val sign = if (total < 0) "-" else "+"
            val abs = kotlin.math.abs(total)
            return String.format(java.util.Locale.ROOT, "%s%02d:%02d", sign, abs / 3600, (abs % 3600) / 60)
        }

        /**
         * `CREATE VIEW v_<table>` for every sample table: all columns plus
         *  - `t_run_s` = (t_mono_ns − run start mono) / 1e9 (NULL when the run has no start mono), and
         *  - `t_local` = ISO local time in the run's zone with its offset, e.g. `2026-11-03T18:31:05.120+04:00`.
         * The offset is fixed at the run's start (a DST change mid-run is not reflected). It is
         * applied as a `±N minutes` modifier, which every SQLite version supports.
         */
        internal fun viewSql(run: RunRecord): List<String> {
            val offset = zoneOf(run).rules.getOffset(Instant.ofEpochMilli(run.startedAt))
            val text = offsetText(offset)
            val minutes = offset.totalSeconds / 60
            val modifier = (if (minutes < 0) "-" else "+") + kotlin.math.abs(minutes) + " minutes"
            val tRun = if (run.tMonoNs > 0) "(t_mono_ns - ${run.tMonoNs}) / 1e9" else "NULL"
            return Schema.sampleTables.map { td ->
                "CREATE VIEW IF NOT EXISTS v_${td.name} AS SELECT *, $tRun AS t_run_s, " +
                    "strftime('%Y-%m-%dT%H:%M:%f', t_utc_ms / 1000.0, 'unixepoch', '$modifier') || '$text' AS t_local " +
                    "FROM ${td.name}"
            }
        }

        /** File-system-safe run name for the bundle file name. */
        fun safeName(name: String): String =
            name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').take(40).ifEmpty { "run" }
    }
}

/** [BundleSource] streaming rows (in [TableDef.exportColumns] order) out of a standalone run.sqlite. */
internal class SqliteBundleSource(
    override val run: RunRecord,
    private val db: SQLiteDatabase,
    private val file: File,
    private val onTableRead: (Int) -> Unit,
) : BundleSource {
    private var tablesRead = 0

    override fun rows(table: TableDef): Sequence<List<Any?>> {
        onTableRead(++tablesRead)
        val cols = table.exportColumns
        val order = if (table.hasRowId) " ORDER BY id" else ""
        val sql = "SELECT ${cols.joinToString(", ")} FROM ${table.name}$order"
        return sequence {
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    yield(List(cols.size) { i -> SqlBinding.cursorValue(c, i) })
                }
            }
        }
    }

    override fun sqliteFile(): File = file
}
