package app.tracer.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tracer.core.model.Table
import app.tracer.core.schema.Schema
import app.tracer.storage.db.CanonicalMigrations
import app.tracer.storage.db.TracerDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Creates the exported Room schema v1, validates it, then opens it with the real database class. */
@RunWith(AndroidJUnit4::class)
class TracerDatabaseMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TracerDatabase::class.java)

    @Test
    fun createV1AndValidate() {
        helper.createDatabase(dbName, 1).close()
        // No migrations exist yet: validating v1 → v1 checks the runs table against the exported schema.
        helper.runMigrationsAndValidate(dbName, 1, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TracerDatabase.build(context, dbName)
        try {
            val sdb = db.openHelper.writableDatabase // runs the canonical callback (onOpen)
            for (td in Schema.all) {
                val expected = if (td.table == Table.RUNS) td.columnNames else td.exportColumns
                val actual = sdb.query("PRAGMA table_info(${td.name})").use { c ->
                    val n = c.getColumnIndexOrThrow("name")
                    buildList { while (c.moveToNext()) add(c.getString(n)) }
                }
                assertEquals(td.name, expected, actual)
            }
            assertEquals(Schema.VERSION, CanonicalMigrations.storedVersion(sdb))
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
