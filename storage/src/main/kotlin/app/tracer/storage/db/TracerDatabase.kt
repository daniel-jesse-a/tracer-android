package app.tracer.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The live Tracer database (`tracer.db`, WAL). Room manages only [RunEntity] (`runs`); every sample
 * table is created from the canonical DDL by [CanonicalSchema.Callback] and accessed with raw SQL
 * through [openHelper].
 */
@Database(entities = [RunEntity::class], version = 1, exportSchema = true)
abstract class TracerDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        const val NAME = "tracer.db"

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
                .addCallback(CanonicalSchema.Callback)
                .build()
        }
    }
}
