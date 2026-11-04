package app.tracer.storage.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.tracer.core.schema.Schema

/**
 * Upgrade steps for the canonical (non-Room) sample tables, keyed by the version they upgrade FROM:
 * `registry[n]` takes a database at canonical version n to n + 1.
 *
 * Rules for future steps: only ALTER/UPDATE tables that already existed at version n. Brand-new
 * tables need no step — [CanonicalSchema.onOpen] runs the `CREATE TABLE IF NOT EXISTS` DDL from
 * [Schema.createAllSql] first, which adds them with their latest column set. Because of that, an
 * `ALTER TABLE … ADD COLUMN` may target a column that already exists (table created just now, or a
 * step re-run after an interrupted upgrade): [SqlGuards.exec] skips those instead of failing.
 */
object CanonicalMigrations {
    const val META_TABLE = "tracer_meta"
    const val KEY_SCHEMA_VERSION = "schema_version"

    /**
     * v1 → v2: [Schema.migrationSql] minus the `runs` statements (Room owns `runs`; its columns are
     * added by [TracerDatabase.MIGRATION_1_2]).
     * v2 → v3: location.is_mock, probe.remote_ip/ip_version/edge_pop/edge_headers/tls_cert_sha256,
     * and the `integrity` table (CREATE IF NOT EXISTS, already created by onOpen's DDL pass).
     */
    val registry: Map<Int, List<String>> = mapOf(
        1 to Schema.migrationSql(1).filterNot { SqlGuards.alterTarget(it)?.first == Schema.RUNS.name },
        2 to Schema.migrationSql(2).filterNot { SqlGuards.alterTarget(it)?.first == Schema.RUNS.name },
    )

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
                registry[from].orEmpty().forEach { sql -> SqlGuards.exec(db, sql) }
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

    /**
     * Order matters: (1) create missing tables (IF NOT EXISTS — new tables get their full v-current
     * column set), (2) ALTER existing tables for a stored version below [Schema.VERSION] (each ALTER
     * guarded by PRAGMA table_info), (3) record the new version (inside [CanonicalMigrations.migrate]).
     */
    fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL(CanonicalMigrations.CREATE_META_SQL)
        createSampleTables(db)
        CanonicalMigrations.migrate(db)
    }

    /** Room callback wiring [onCreate] / [onOpen]. */
    object Callback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = CanonicalSchema.onCreate(db)
        override fun onOpen(db: SupportSQLiteDatabase) = CanonicalSchema.onOpen(db)
    }
}

/** Idempotent DDL helpers shared by the canonical and the Room migrations. */
object SqlGuards {
    private val ALTER_ADD = Regex("""^\s*ALTER\s+TABLE\s+(\w+)\s+ADD\s+COLUMN\s+(\w+)""", RegexOption.IGNORE_CASE)

    /** (table, column) of an `ALTER TABLE t ADD COLUMN c …` statement, else null. */
    fun alterTarget(sql: String): Pair<String, String>? = ALTER_ADD.find(sql)?.let { it.groupValues[1] to it.groupValues[2] }

    fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info($table)").use { c ->
            val n = c.getColumnIndexOrThrow("name")
            buildSet { while (c.moveToNext()) add(c.getString(n).lowercase()) }
        }

    /** Executes [sql]; an ADD COLUMN whose column already exists is skipped. */
    fun exec(db: SupportSQLiteDatabase, sql: String) {
        val target = alterTarget(sql)
        if (target != null && target.second.lowercase() in columns(db, target.first)) return
        db.execSQL(sql)
    }
}
