package app.tracer.storage.repo

import android.content.Context
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import app.tracer.core.export.Bundle
import app.tracer.core.model.EventLabelSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.runtime.RunListItem
import app.tracer.core.runtime.RunRepository
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import app.tracer.storage.db.CursorRow
import app.tracer.storage.db.RunEntity
import app.tracer.storage.db.SqlBinding
import app.tracer.storage.db.StorageChanges
import app.tracer.storage.db.TableCounts
import app.tracer.storage.db.TracerDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RunRepository] over [TracerDatabase]: `runs` through Room, sample tables through raw SQL on
 * [TracerDatabase.openHelper] decoded with [SampleCodec].
 */
@Singleton
class RoomRunRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TracerDatabase,
    private val changes: StorageChanges,
) : RunRepository {

    private val dao get() = database.runDao()
    private val io = Dispatchers.IO

    private fun readable(): SupportSQLiteDatabase = database.openHelper.readableDatabase
    private fun writable(): SupportSQLiteDatabase = database.openHelper.writableDatabase

    override suspend fun createRun(run: RunRecord) = dao.insert(RunEntity.from(run))

    override suspend fun updateRun(run: RunRecord) {
        dao.update(RunEntity.from(run))
    }

    override suspend fun getRun(runId: String): RunRecord? = dao.get(runId)?.toRecord()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRuns(): Flow<List<RunListItem>> =
        changes.version
            .flatMapLatest { dao.observeAll() }
            .map { entities -> withContext(io) { toListItems(entities) } }
            .distinctUntilChanged()

    private fun toListItems(entities: List<RunEntity>): List<RunListItem> {
        val eventCounts = HashMap<String, Int>()
        readable().query("SELECT run_id, COUNT(*) FROM ${Schema.EVENT.name} GROUP BY run_id").use { c ->
            while (c.moveToNext()) {
                val id = if (c.isNull(0)) null else c.getString(0)
                if (id != null) eventCounts[id] = c.getInt(1)
            }
        }
        val ids = entities.mapTo(HashSet()) { it.runId }
        return entities.map { e ->
            RunListItem(
                run = e.toRecord(),
                eventCount = eventCounts[e.runId] ?: 0,
                completenessBadge = badgeOf(e.completenessJson),
                hasLinkedImport = e.linkedRunId != null && e.linkedRunId in ids,
            )
        }
    }

    override suspend fun unfinishedRun(): RunRecord? = dao.unfinished()?.toRecord()

    override suspend fun deleteRun(runId: String) = withContext(io) {
        val db = writable()
        db.beginTransaction()
        try {
            deleteRunRows(db, runId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.bump()
    }

    override suspend fun samples(runId: String, table: Table, fromMonoNs: Long?, toMonoNs: Long?): List<Sample> =
        withContext(io) {
            require(table != Table.RUNS) { "runs is not a sample table" }
            val td = Schema.of(table)
            val where = StringBuilder("run_id = ?")
            val args = mutableListOf<Any?>(runId)
            if (fromMonoNs != null) { where.append(" AND t_mono_ns >= ?"); args += fromMonoNs }
            if (toMonoNs != null) { where.append(" AND t_mono_ns <= ?"); args += toMonoNs }
            val q = SimpleSQLiteQuery("SELECT * FROM ${td.name} WHERE $where ORDER BY t_mono_ns, id", args.toTypedArray())
            readable().query(q).use { c ->
                val row = CursorRow(c)
                val out = ArrayList<Sample>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) out += SampleCodec.decode(table, row)
                out
            }
        }

    override fun stream(runId: String, table: Table): Flow<Sample> = flow {
        require(table != Table.RUNS) { "runs is not a sample table" }
        val td = Schema.of(table)
        var lastId = Long.MIN_VALUE
        while (true) {
            val page = ArrayList<Sample>(STREAM_PAGE)
            var pageLastId = lastId
            val q = SimpleSQLiteQuery(
                "SELECT * FROM ${td.name} WHERE run_id = ? AND id > ? ORDER BY id LIMIT $STREAM_PAGE",
                arrayOf<Any?>(runId, lastId),
            )
            readable().query(q).use { c ->
                val row = CursorRow(c)
                val idCol = c.getColumnIndexOrThrow("id")
                while (c.moveToNext()) {
                    pageLastId = c.getLong(idCol)
                    page += SampleCodec.decode(table, row)
                }
            }
            for (s in page) emit(s)
            if (page.size < STREAM_PAGE) break
            lastId = pageLastId
        }
    }.flowOn(io)

    override suspend fun counts(runId: String): Map<Table, Long> = withContext(io) { TableCounts.query(readable(), runId) }

    override suspend fun events(runId: String): List<Pair<Long, EventSample>> = withContext(io) {
        val q = SimpleSQLiteQuery(
            "SELECT * FROM ${Schema.EVENT.name} WHERE run_id = ? ORDER BY t_mono_ns, id",
            arrayOf<Any?>(runId),
        )
        readable().query(q).use { c ->
            val row = CursorRow(c)
            val idCol = c.getColumnIndexOrThrow("id")
            val out = ArrayList<Pair<Long, EventSample>>()
            while (c.moveToNext()) {
                val s = SampleCodec.decode(Table.EVENT, row)
                if (s is EventSample) out += c.getLong(idCol) to s
            }
            out
        }
    }

    override suspend fun setEventLabel(runId: String, eventId: Long, label: String) {
        withContext(io) {
            val stamp = Stamp(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
            SqlBinding.insert(writable(), EventLabelSample(runId, stamp, eventId, label))
        }
    }

    override suspend fun eventLabels(runId: String): Map<Long, String> = withContext(io) {
        val q = SimpleSQLiteQuery(
            "SELECT event_id, label FROM ${Schema.X_EVENT_LABEL.name} WHERE run_id = ? ORDER BY id",
            arrayOf<Any?>(runId),
        )
        readable().query(q).use { c ->
            val out = LinkedHashMap<Long, String>()
            while (c.moveToNext()) {
                if (c.isNull(0) || c.isNull(1)) continue
                out[c.getLong(0)] = c.getString(1) // ascending id → latest label wins
            }
            out
        }
    }

    override suspend fun replaceEvents(runId: String, events: List<EventSample>) {
        withContext(io) {
            val db = writable()
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM ${Schema.EVENT.name} WHERE run_id = ?", arrayOf<Any?>(runId))
                val st = db.compileStatement(Schema.EVENT.insertSql())
                try {
                    events.forEach { e -> SqlBinding.insert(st, if (e.runId == runId) e else e.copy(runId = runId)) }
                } finally {
                    st.close()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        changes.bump()
    }

    override suspend fun importBundle(bundle: Bundle.ImportedBundle): RunRecord {
        val all = bundle.samples.values.flatten()
        val lastUtc = all.maxOfOrNull { it.stamp.tUtcMs }
        // An imported run is never "recording": give it an end so unfinishedRun() ignores it.
        val run = bundle.run.let { r -> if (r.endedAt != null) r else r.copy(endedAt = lastUtc ?: r.startedAt) }
        withContext(io) {
            val db = writable()
            db.beginTransaction()
            try {
                deleteRunRows(db, run.runId) // re-import replaces
                db.execSQL(
                    "INSERT INTO ${Schema.RUNS.name} (${Schema.RUNS.columnNames.joinToString(", ")}) " +
                        "VALUES (${Schema.RUNS.columnNames.joinToString(", ") { "?" }})",
                    SampleCodec.encodeRun(run).toTypedArray(),
                )
                for (td in Schema.sampleTables) {
                    val rows = bundle.samples[td.table] ?: continue
                    if (rows.isEmpty()) continue
                    val st = db.compileStatement(td.insertSql())
                    try {
                        // Chunked iteration; everything stays in ONE transaction.
                        rows.asSequence().chunked(IMPORT_CHUNK).forEach { chunk ->
                            chunk.forEach { s -> SqlBinding.insert(st, s) }
                        }
                    } finally {
                        st.close()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        changes.bump()
        return run
    }

    override fun databaseFile(): File = context.getDatabasePath(TracerDatabase.NAME)

    override suspend fun deleteOlderThan(cutoffUtcMs: Long): Int {
        val n = withContext(io) {
            val db = writable()
            val ids = db.query("SELECT run_id FROM ${Schema.RUNS.name} WHERE started_at < ?", arrayOf<Any?>(cutoffUtcMs)).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) if (!c.isNull(0)) out += c.getString(0)
                out
            }
            if (ids.isNotEmpty()) {
                db.beginTransaction()
                try {
                    ids.forEach { deleteRunRows(db, it) }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            ids.size
        }
        if (n > 0) changes.bump()
        return n
    }

    private fun deleteRunRows(db: SupportSQLiteDatabase, runId: String) {
        for (td in Schema.all) {
            db.execSQL("DELETE FROM ${td.name} WHERE run_id = ?", arrayOf<Any?>(runId))
        }
    }

    companion object {
        const val STREAM_PAGE = 5_000
        private const val IMPORT_CHUNK = 1_000

        /** `badge` field of runs.completeness_json, or null if absent/unparseable. */
        fun badgeOf(completenessJson: String?): String? {
            if (completenessJson.isNullOrBlank()) return null
            return runCatching {
                Json.parseToJsonElement(completenessJson).jsonObject["badge"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
    }
}
