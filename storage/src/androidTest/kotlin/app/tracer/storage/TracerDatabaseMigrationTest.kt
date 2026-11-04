package app.tracer.storage

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tracer.core.model.Table
import app.tracer.core.schema.Schema
import app.tracer.storage.db.CanonicalMigrations
import app.tracer.storage.db.SqlGuards
import app.tracer.storage.db.TracerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes a database exactly as the v1 app left it (Room user_version 1, v1 `runs`, v1 canonical
 * sample DDL, tracer_meta = 1), runs Room's 1 → 2 → 3 migrations validated against the exported v3
 * schema, then opens it with the real database class (canonical v1 → v3 in onOpen).
 *
 * The v1 database is built by hand rather than from an exported 1.json, because the repository
 * only keeps the schema JSON Room generates for the current version.
 */
@RunWith(AndroidJUnit4::class)
class TracerDatabaseMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TracerDatabase::class.java)

    @Test
    fun migrateV1ToV3AndValidate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        val v2Cols = (1 until Schema.VERSION).flatMap { Schema.migrationSql(it) }.mapNotNull { SqlGuards.alterTarget(it) }.groupBy({ it.first }, { it.second })
        val newTables = setOf(Table.WAYPOINT, Table.WIFI_ROAM, Table.X_ANDROID_CARRIER, Table.X_COLLECTOR_LOG, Table.INTEGRITY)
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            for (td in Schema.all) {
                if (td.table in newTables) continue
                val v1 = td.copy(columns = td.columns.filterNot { it.name in v2Cols[td.name].orEmpty() })
                raw.execSQL(v1.createSql(ifNotExists = false))
                v1.indexSql().forEach { raw.execSQL(it) }
            }
            raw.execSQL(CanonicalMigrations.CREATE_META_SQL)
            raw.execSQL("INSERT INTO ${CanonicalMigrations.META_TABLE} (key, value) VALUES ('${CanonicalMigrations.KEY_SCHEMA_VERSION}', '1')")
            raw.execSQL(
                "INSERT INTO runs (run_id, t_utc_ms, t_mono_ns, name, mode, device_platform, device_model, os_version, app_version, started_at) " +
                    "VALUES ('v1', 1, 1, 'Old', 'walk', 'android', 'Pixel', '16', '1.0.8', 1)",
            )
            raw.version = 1
        }

        // Room's view: runs matches the exported current (v3) schema after MIGRATION_1_2 + MIGRATION_2_3
        // (sample tables are not Room's).
        helper.runMigrationsAndValidate(dbName, 3, false, *TracerDatabase.MIGRATIONS).close()

        val db = TracerDatabase.build(context, dbName)
        try {
            val sdb = db.openHelper.writableDatabase // runs the canonical callback (onOpen)
            for (td in Schema.all) {
                val expected = if (td.table == Table.RUNS) td.columnNames else td.exportColumns
                val actual = sdb.query("PRAGMA table_info(${td.name})").use { c ->
                    val n = c.getColumnIndexOrThrow("name")
                    buildList { while (c.moveToNext()) add(c.getString(n)) }
                }
                assertEquals(td.name, expected.toSet(), actual.toSet())
            }
            assertEquals(Schema.VERSION, CanonicalMigrations.storedVersion(sdb))
            sdb.query("SELECT name FROM runs WHERE run_id = 'v1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("Old", c.getString(0)) }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
