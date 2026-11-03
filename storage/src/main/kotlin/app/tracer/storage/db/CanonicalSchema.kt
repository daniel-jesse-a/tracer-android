package app.tracer.storage.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.tracer.core.schema.Schema

/**
 * Upgrade steps for the canonical (non-Room) sample tables, keyed by the version they upgrade FROM:
 * `registry[n]` takes a database at canonical version n to n + 1.
 *
 * Rules for future steps: only ALTER/UPDATE tables that already existed at version n. Brand-new
 * tables need no step — [CanonicalSchema.install] re-runs the `CREATE TABLE IF NOT EXISTS` DDL from
 * [Schema.createAllSql] after migrating, which forward-adds them with their latest column set.
 */
object CanonicalMigrations {
    const val META_TABLE = "tracer_meta"
    const val KEY_SCHEMA_VERSION = "schema_version"

    /** Empty for canonical schema v1. */
    val registry: Map<Int, List<String>> = emptyMap()

    const val CREATE_META_SQL = "CREATE TABLE IF NOT EXISTS $META_TABLE (key TEXT PRIMARY KEY, value TEXT)"

    fun storedVersion(db: SupportSQLiteDatabase): Int? =
        db.query("SELECT value FROM $META_TABLE WHERE key = ?", arrayOf<Any?>(KEY_SCHEMA_VERSION)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0)?.toIntOrNull() else null
        }

    fun setStoredVersion(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL(
            "INSERT OR REPLACE INTO $META_TABLE (key, value) VALUES (?, ?)",
            arrayOf<Any?>(KEY_SCHEMA_VERSION, version.toString()),
        )
    }

    /**
     * Applies every registered step from the stored version up to [targetVersion] in one
     * transaction and records the new version. A database without a stored version is treated as
     * current (it is either brand new or about to receive the full DDL).
     *
     * @return the from-versions whose steps were applied, in order.
     */
    fun migrate(
        db: SupportSQLiteDatabase,
        registry: Map<Int, List<String>> = this.registry,
        targetVersion: Int = Schema.VERSION,
    ): List<Int> {
        db.execSQL(CREATE_META_SQL)
        val stored = storedVersion(db)
        if (stored == null) {
            setStoredVersion(db, targetVersion)
            return emptyList()
        }
        if (stored >= targetVersion) return emptyList()
        val applied = mutableListOf<Int>()
        db.beginTransaction()
        try {
            for (from in stored until targetVersion) {
                registry[from].orEmpty().forEach { sql -> db.execSQL(sql) }
                applied += from
            }
            setStoredVersion(db, targetVersion)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return applied
    }
}

/** Creates the canonical sample tables (everything except `runs`, which Room owns). */
object CanonicalSchema {

    /** [Schema.createAllSql] minus the `runs` CREATE. All statements are IF NOT EXISTS. */
    fun sampleTableSql(): List<String> {
        val runsCreate = Schema.RUNS.createSql(ifNotExists = true)
        return Schema.createAllSql().filterNot { it == runsCreate }
    }

    fun createSampleTables(db: SupportSQLiteDatabase) {
        sampleTableSql().forEach { db.execSQL(it) }
    }

    fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(CanonicalMigrations.CREATE_META_SQL)
        createSampleTables(db)
        CanonicalMigrations.setStoredVersion(db, Schema.VERSION)
    }

    fun onOpen(db: SupportSQLiteDatabase) {
        CanonicalMigrations.migrate(db)
        createSampleTables(db)
    }

    /** Room callback wiring [onCreate] / [onOpen]. */
    object Callback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = CanonicalSchema.onCreate(db)
        override fun onOpen(db: SupportSQLiteDatabase) = CanonicalSchema.onOpen(db)
    }
}
