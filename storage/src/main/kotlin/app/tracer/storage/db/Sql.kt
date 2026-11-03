package app.tracer.storage.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteStatement
import app.tracer.core.model.Sample
import app.tracer.core.model.Table
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Binding helpers shared by the writer, the repository and the exporter. */
object SqlBinding {

    /** Binds [values] positionally (1-based) by runtime type. */
    fun bindAll(program: SupportSQLiteProgram, values: List<Any?>) {
        values.forEachIndexed { i, v -> bind(program, i + 1, v) }
    }

    fun bind(program: SupportSQLiteProgram, index: Int, v: Any?) {
        when (v) {
            null -> program.bindNull(index)
            is Long -> program.bindLong(index, v)
            is Int -> program.bindLong(index, v.toLong())
            is Short -> program.bindLong(index, v.toLong())
            is Byte -> program.bindLong(index, v.toLong())
            is Double -> program.bindDouble(index, v)
            is Float -> program.bindDouble(index, v.toDouble())
            is Boolean -> program.bindLong(index, if (v) 1L else 0L)
            is String -> program.bindString(index, v)
            is ByteArray -> program.bindBlob(index, v)
            else -> program.bindString(index, v.toString())
        }
    }

    /** Executes a cached insert for [sample] and clears its bindings. */
    fun insert(statement: SupportSQLiteStatement, sample: Sample): Long {
        bindAll(statement, SampleCodec.encode(sample))
        val id = statement.executeInsert()
        statement.clearBindings()
        return id
    }

    /** One-off insert (compiles and closes its own statement). */
    fun insert(db: SupportSQLiteDatabase, sample: Sample): Long {
        val st = db.compileStatement(Schema.of(sample.table).insertSql())
        try {
            return insert(st, sample)
        } finally {
            st.close()
        }
    }

    /** Positional values of a cursor row, typed by [Cursor.getType]. */
    fun cursorValue(c: Cursor, index: Int): Any? = when (c.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index)
        else -> c.getString(index)
    }
}

/** [SampleCodec.Row] over the current row of a [Cursor]; columns are looked up by name. */
class CursorRow(private val cursor: Cursor) : SampleCodec.Row {
    private val index: Map<String, Int> = cursor.columnNames.withIndex().associate { (i, n) -> n to i }

    private fun idx(col: String): Int? = index[col]

    override fun has(col: String): Boolean = index.containsKey(col)

    override fun str(col: String): String? {
        val i = idx(col) ?: return null
        return when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i).toString()
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i).toString()
            Cursor.FIELD_TYPE_BLOB -> null
            else -> cursor.getString(i)
        }
    }

    override fun long(col: String): Long? {
        val i = idx(col) ?: return null
        return when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i).toLong()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(i)?.let { s -> s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() }
            else -> null
        }
    }

    override fun dbl(col: String): Double? {
        val i = idx(col) ?: return null
        return when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i).toDouble()
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(i)?.toDoubleOrNull()
            else -> null
        }
    }
}

/** Per-table row counts for one run. */
object TableCounts {
    fun query(db: SupportSQLiteDatabase, runId: String): Map<Table, Long> {
        val out = LinkedHashMap<Table, Long>()
        for (td in Schema.sampleTables) {
            val n = db.query("SELECT COUNT(*) FROM ${td.name} WHERE run_id = ?", arrayOf<Any?>(runId)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (n > 0) out[td.table] = n
        }
        return out
    }
}

/**
 * Change ticker for writes Room's invalidation tracker cannot see (raw SQL on sample tables, raw
 * multi-table deletes/imports). [RoomRunRepository.observeRuns] re-queries on every tick.
 */
@Singleton
class StorageChanges @Inject constructor() {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun bump() {
        _version.update { it + 1 }
    }
}
