package app.tracer.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tracer.core.model.EventSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.export.Bundle
import app.tracer.core.schema.Schema
import app.tracer.core.sim.Scenarios
import app.tracer.core.sim.SyntheticRun
import app.tracer.storage.db.CanonicalMigrations
import app.tracer.storage.db.StorageChanges
import app.tracer.storage.db.TracerDatabase
import app.tracer.storage.repo.RoomRunRepository
import app.tracer.storage.writer.BatchingSampleWriter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageTest {

    private lateinit var context: Context
    private lateinit var db: TracerDatabase
    private lateinit var changes: StorageChanges
    private lateinit var writer: BatchingSampleWriter
    private lateinit var repo: RoomRunRepository

    private val runId = SyntheticRun().runId

    private fun run(id: String = runId, startedAt: Long = 1_760_000_000_000L, endedAt: Long? = null, linked: String? = null) =
        RunRecord(id, "Test run", "drive", "android", "Pixel 9", "16", "1.0.0", startedAt, endedAt, linkedRunId = linked,
            completenessJson = "{\"overall\":0.97,\"badge\":\"complete\"}")

    private fun open() {
        db = TracerDatabase.build(context, TracerDatabase.NAME, allowMainThreadQueries = true)
        changes = StorageChanges()
        writer = BatchingSampleWriter(db, changes)
        repo = RoomRunRepository(context, db, changes)
    }

    private fun closeAll() = runBlocking<Unit> {
        writer.close()
        db.close()
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TracerDatabase.NAME)
        open()
    }

    @After fun tearDown() = closeAll()

    private fun columns(table: String): List<String> =
        db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(nameCol)) }
        }

    private fun tableExists(table: String): Boolean =
        db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf<Any?>(table))
            .use { it.moveToFirst() }

    // (1) schema parity
    @Test fun schemaParity() {
        for (td in Schema.all) {
            val expected = if (td.table == Table.RUNS) td.columnNames else td.exportColumns
            assertWithMessage(td.name).that(columns(td.name)).containsExactlyElementsIn(expected).inOrder()
        }
        assertThat(CanonicalMigrations.storedVersion(db.openHelper.writableDatabase)).isEqualTo(Schema.VERSION)
    }

    // (2) batching & counts
    @Test fun writerBatchesAndCounts() = runBlocking<Unit> {
        repo.createRun(run())
        val samples = Scenarios.byName("tunnel").take(450)
        val counts = writer.counts(runId)
        samples.forEach { writer.emit(it) }
        writer.flush()
        val expected: Map<Table, Long> = samples.groupingBy { it.table }.eachCount().mapValues { it.value.toLong() }
        assertThat(counts.value).isEqualTo(expected)
        assertThat(repo.counts(runId)).isEqualTo(expected)
        assertThat(expected.values.sum()).isEqualTo(450L)
    }

    @Test fun writerFlushesOnTimerWithoutExplicitFlush() = runBlocking<Unit> {
        val samples = Scenarios.byName("tunnel").take(5)
        val counts = writer.counts(runId)
        samples.forEach { writer.emit(it) }
        val deadline = System.currentTimeMillis() + 5_000
        while (counts.value.values.sum() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertThat(counts.value.values.sum()).isEqualTo(5L)
    }

    @Test fun countsSeedFromExistingRows() = runBlocking<Unit> {
        val samples = Scenarios.byName("tunnel").take(100)
        samples.forEach { writer.emit(it) }
        writer.flush()
        val counts = writer.counts(runId)
        writer.flush() // seed runs on the writer thread, ordered before this flush completes
        assertThat(counts.value.values.sum()).isEqualTo(100L)
    }

    // (3) round trip
    @Test fun samplesRoundTrip() = runBlocking<Unit> {
        val samples = Scenarios.byName("tunnel")
        samples.forEach { writer.emit(it) }
        writer.flush()
        for (t in listOf(Table.LOCATION, Table.CELL_SERVING, Table.CELL_NEIGHBOUR, Table.PROBE, Table.GNSS_SATELLITE, Table.NETWORK_PATH, Table.RADIO)) {
            val want = samples.filter { it.table == t }.sortedBy { it.stamp.tMonoNs }
            assertThat(want).isNotEmpty()
            assertWithMessage(t.sqlName).that(repo.samples(runId, t)).isEqualTo(want)
        }
        val locs = samples.filter { it.table == Table.LOCATION }
        assertThat(repo.stream(runId, Table.LOCATION).toList()).isEqualTo(locs)
        val from = locs[10].stamp.tMonoNs
        val to = locs[20].stamp.tMonoNs
        assertThat(repo.samples(runId, Table.LOCATION, from, to)).isEqualTo(locs.subList(10, 21))
    }

    @Test fun eventsLabelsAndReplace() = runBlocking<Unit> {
        repo.createRun(run())
        val e1 = EventSample(runId, Stamp(1, 1_000), "handover", "info")
        val e2 = EventSample(runId, Stamp(2, 2_000), "drop", "critical", lat = 25.0, lon = 55.0)
        writer.emit(e1); writer.emit(e2); writer.flush()
        val events = repo.events(runId)
        assertThat(events.map { it.second }).containsExactly(e1, e2).inOrder()
        val id = events[1].first
        repo.setEventLabel(runId, id, "false positive")
        repo.setEventLabel(runId, id, "tunnel")
        assertThat(repo.eventLabels(runId)).isEqualTo(mapOf(id to "tunnel"))
        repo.replaceEvents(runId, listOf(e2))
        assertThat(repo.events(runId).map { it.second }).containsExactly(e2)
        assertThat(repo.observeRuns().first().single().eventCount).isEqualTo(1)
    }

    @Test fun runQueriesAndDeletion() = runBlocking<Unit> {
        repo.createRun(run("a", startedAt = 1_000, endedAt = 2_000))
        repo.createRun(run("b", startedAt = 3_000, linked = "a"))
        repo.createRun(run("c", startedAt = 5_000, linked = "zzz").copy(devicePlatform = "ios"))
        assertThat(repo.unfinishedRun()?.runId).isEqualTo("b")
        val items = repo.observeRuns().first()
        assertThat(items.map { it.run.runId }).containsExactly("c", "b", "a").inOrder()
        assertThat(items.first { it.run.runId == "b" }.hasLinkedImport).isTrue()
        assertThat(items.first { it.run.runId == "c" }.hasLinkedImport).isFalse()
        assertThat(items.first().completenessBadge).isEqualTo("complete")

        writer.emit(Scenarios.byName("tunnel", SyntheticRun(runId = "a")).first()); writer.flush()
        assertThat(repo.deleteOlderThan(2_500)).isEqualTo(1)
        assertThat(repo.getRun("a")).isNull()
        assertThat(repo.counts("a")).isEmpty()
        repo.deleteRun("b")
        assertThat(repo.observeRuns().first().map { it.run.runId }).containsExactly("c")
    }

    @Test fun importBundlePersistsRunAndSamples() = runBlocking<Unit> {
        val samples = Scenarios.byName("congestion")
        val imported = Bundle.ImportedBundle(
            manifest = buildJsonObject { },
            run = run().copy(devicePlatform = "ios"),
            samples = samples.groupBy { it.table },
            integrity = emptyList(),
            sqliteBytes = null,
        )
        val rec = repo.importBundle(imported)
        assertThat(rec.endedAt).isNotNull()
        assertThat(repo.getRun(runId)?.devicePlatform).isEqualTo("ios")
        assertThat(repo.counts(runId).values.sum()).isEqualTo(samples.size.toLong())
        repo.importBundle(imported) // re-import replaces rather than duplicates
        assertThat(repo.counts(runId).values.sum()).isEqualTo(samples.size.toLong())
    }

    // (4) canonical migrations
    @Test fun canonicalMigrationsApplyRegisteredSql() {
        val sdb = db.openHelper.writableDatabase
        CanonicalMigrations.setStoredVersion(sdb, 0)
        val registry = mapOf(0 to listOf("CREATE TABLE x_mig_probe (a INTEGER)", "INSERT INTO x_mig_probe (a) VALUES (7)"))
        assertThat(CanonicalMigrations.migrate(sdb, registry, targetVersion = 1)).containsExactly(0)
        assertThat(CanonicalMigrations.storedVersion(sdb)).isEqualTo(1)
        sdb.query("SELECT a FROM x_mig_probe").use { c -> assertThat(c.moveToFirst()).isTrue(); assertThat(c.getInt(0)).isEqualTo(7) }
        // already current → nothing re-applied
        assertThat(CanonicalMigrations.migrate(sdb, registry, targetVersion = 1)).isEmpty()
    }

    @Test fun reopenMigratesVersionAndForwardAddsTables() {
        val sdb = db.openHelper.writableDatabase
        CanonicalMigrations.setStoredVersion(sdb, 0)
        sdb.execSQL("DROP TABLE x_gap")
        closeAll()
        open()
        assertThat(CanonicalMigrations.storedVersion(db.openHelper.writableDatabase)).isEqualTo(Schema.VERSION)
        assertThat(tableExists("x_gap")).isTrue()
        assertThat(columns("x_gap")).isEqualTo(Schema.X_GAP.exportColumns)
    }
}
