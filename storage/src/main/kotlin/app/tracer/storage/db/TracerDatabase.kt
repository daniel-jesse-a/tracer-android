package app.tracer.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.tracer.core.schema.Schema

/**
 * The live Tracer database (`tracer.db`, WAL). Room manages only [RunEntity] (`runs`); every sample
 * table is created from the canonical DDL by [CanonicalSchema.Callback] and accessed with raw SQL
 * through [openHelper].
 */
@Database(entities = [RunEntity::class], version = 2, exportSchema = true)
abstract class TracerDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        const val NAME = "tracer.db"

        /**
         * Room v1 → v2: the five nullable v2 `runs` columns (site, test_number, protocol, time_zone,
         * method_fingerprint) — the `runs` statements of [Schema.migrationSql]. Each ALTER is skipped
         * when the column already exists. The sample tables are upgraded separately by
         * [CanonicalSchema.onOpen] (canonical meta version), after this migration.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Schema.migrationSql(1)
                    .filter { SqlGuards.alterTarget(it)?.first == Schema.RUNS.name }
                    .forEach { SqlGuards.exec(db, it) }
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /**
         * @param name database file name; null builds an in-memory database (tests only).
         * @param allowMainThreadQueries tests only.
         */
        fun build(context: Context, name: String? = NAME, allowMainThreadQueries: Boolean = false): TracerDatabase {
            val app = context.applicationContext ?: context
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(app, TracerDatabase::class.java)
            } else {
                Room.databaseBuilder(app, TracerDatabase::class.java, name)
            }
            if (allowMainThreadQueries) builder.allowMainThreadQueries()
            return builder
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .addCallback(CanonicalSchema.Callback)
                .build()
        }
    }
}
