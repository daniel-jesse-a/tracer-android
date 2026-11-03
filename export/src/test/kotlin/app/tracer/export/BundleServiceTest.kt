package app.tracer.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.tracer.core.export.Bundle
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Table
import app.tracer.core.schema.Schema
import app.tracer.core.sim.Scenarios
import app.tracer.core.sim.SyntheticRun
import app.tracer.storage.db.StorageChanges
import app.tracer.storage.db.TracerDatabase
import app.tracer.storage.repo.RoomRunRepository
import app.tracer.storage.writer.BatchingSampleWriter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleServiceTest {

    private lateinit var context: Context
    private lateinit var db: TracerDatabase
    private lateinit var writer: BatchingSampleWriter
    private lateinit var repo: RoomRunRepository
    private lateinit var service: BundleServiceImpl

    private val runId = SyntheticRun().runId

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TracerDatabase.NAME)
        db = TracerDatabase.build(context, TracerDatabase.NAME, allowMainThreadQueries = true)
        val changes = StorageChanges()
        writer = BatchingSampleWriter(db, changes)
        repo = RoomRunRepository(context, db, changes)
        service = BundleServiceImpl(context, repo, writer)
    }

    @After fun tearDown() = runBlocking<Unit> {
        writer.close()
        db.close()
    }

    @Test fun exportThenReadBack() = runBlocking<Unit> {
        repo.createRun(
            RunRecord(runId, "Congestion / SZR", "drive", "android", "Pixel 9", "16", "1.0.0", 1_760_000_000_000L, 1_760_000_080_000L,
                gpsOffsetMsStart = 350, completenessJson = "{\"badge\":\"complete\"}"),
        )
        val samples = Scenarios.byName("congestion")
        samples.forEach { writer.emit(it) } // not flushed: export must flush first
        // rows of another run must not leak into the bundle
        Scenarios.byName("tunnel", SyntheticRun(runId = "other-run")).take(60).forEach { writer.emit(it) }

        val progress = mutableListOf<Float>()
        val zip = service.export(runId) { progress += it }

        assertThat(zip.isFile).isTrue()
        assertThat(zip.name).isEqualTo("Congestion_SZR_${runId.take(8)}.zip")
        assertThat(progress).isNotEmpty()
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress).isInOrder()
        assertThat(File(service.exportsDir(), "work-$runId").exists()).isFalse()

        val imported = zip.inputStream().use { Bundle.read(it) }
        assertWithMessage(imported.integrity.toString()).that(imported.ok).isTrue()
        assertThat(imported.run.runId).isEqualTo(runId)
        assertThat(imported.run.gpsOffsetMsStart).isEqualTo(350L)

        val expected = samples.groupingBy { it.table }.eachCount()
        for (td in Schema.sampleTables) {
            assertWithMessage(td.name).that(imported.samples[td.table]?.size ?: 0).isEqualTo(expected[td.table] ?: 0)
        }
        assertThat(imported.samples[Table.LOCATION]).isEqualTo(samples.filter { it.table == Table.LOCATION })

        val bytes = imported.sqliteBytes
        assertThat(bytes).isNotNull()
        val copy = File(context.cacheDir, "check-run.sqlite").apply { writeBytes(bytes ?: ByteArray(0)) }
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { sq ->
            fun count(sql: String, vararg args: String): Long =
                sq.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            assertThat(count("SELECT COUNT(*) FROM runs")).isEqualTo(1L)
            for (td in Schema.sampleTables) {
                assertWithMessage(td.name).that(count("SELECT COUNT(*) FROM ${td.name}")).isEqualTo((expected[td.table] ?: 0).toLong())
                assertWithMessage(td.name).that(count("SELECT COUNT(*) FROM ${td.name} WHERE run_id <> ?", runId)).isEqualTo(0L)
            }
        }
    }

    @Test fun safeNames() {
        assertThat(BundleServiceImpl.safeName("  Sheikh Zayed Rd / night  ")).isEqualTo("Sheikh_Zayed_Rd_night")
        assertThat(BundleServiceImpl.safeName("///")).isEqualTo("run")
        assertThat(service.fileProviderAuthority).isEqualTo("${context.packageName}.exports")
    }
}
