package app.tracer.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.tracer.core.model.EventSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.export.Bundle
import app.tracer.core.schema.Schema
import app.tracer.core.schema.TableDef
import app.tracer.core.model.CollectorLogSample
import app.tracer.core.model.WaypointSample
import app.tracer.core.model.WifiRoamSample
import app.tracer.storage.db.SqlGuards
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
        // The seed query runs asynchronously on the writer thread; wait for it rather than
        // relying on dispatcher ordering (flaky under Robolectric).
        val seeded = kotlinx.coroutines.withTimeoutOrNull(5_000) {
            counts.first { it.values.sum() == 100L }
        }
        assertThat(seeded?.values?.sum()).isEqualTo(100L)
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

    // (5) v2 run fields + test numbers
    @Test fun v2RunFieldsRoundTripAndNextTestNumber() = runBlocking<Unit> {
        assertThat(repo.nextTestNumber("Office")).isEqualTo(1)
        val r = run("a").copy(site = "Office", testNumber = 3, protocol = "walk10-dual", timeZone = "Asia/Dubai", methodFingerprint = "0123456789abcdef")
        repo.createRun(r)
        assertThat(repo.getRun("a")).isEqualTo(r)
        repo.createRun(run("b").copy(site = "office ", testNumber = 7))
        repo.createRun(run("c").copy(site = "Lab", testNumber = 12))
        repo.createRun(run("d")) // no site
        assertThat(repo.nextTestNumber("OFFICE")).isEqualTo(8)
        assertThat(repo.nextTestNumber("Lab")).isEqualTo(13)
        assertThat(repo.nextTestNumber("Garage")).isEqualTo(1)
        repo.updateRun(r.copy(site = "Garage"))
        assertThat(repo.getRun("a")?.site).isEqualTo("Garage")
        assertThat(repo.nextTestNumber("Garage")).isEqualTo(4)
    }

    @Test fun v2SampleTablesRoundTrip() = runBlocking<Unit> {
        val st = Stamp(1_760_000_000_000L, 9_000_000_000L)
        val w = WaypointSample(runId, st, 1, "door B", 25.1, 55.2, 6.0, "fused")
        val roam = WifiRoamSample(runId, st, "Corp", "aa:aa:aa:aa:aa:01", "aa:aa:aa:aa:aa:02", "5", "6", -78, -55, 0L, 12.5, 20.0, 32.5, true, true, false)
        val log = CollectorLogSample(runId, st, "wifi_scan", "degraded", "throttled", 1)
        listOf(w, roam, log).forEach { writer.emit(it) }
        writer.flush()
        assertThat(repo.samples(runId, Table.WAYPOINT)).containsExactly(w)
        assertThat(repo.samples(runId, Table.WIFI_ROAM)).containsExactly(roam)
        assertThat(repo.samples(runId, Table.X_COLLECTOR_LOG)).containsExactly(log)
    }

    @Test fun canonicalRegistryExcludesRunsAndGuardsExistingColumns() {
        val v2 = CanonicalMigrations.registry.getValue(1)
        assertThat(v2).isNotEmpty()
        assertThat(v2.none { SqlGuards.alterTarget(it)?.first == "runs" }).isTrue()
        // every column already exists on a fresh v2 database → re-running the step is a no-op
        val sdb = db.openHelper.writableDatabase
        CanonicalMigrations.setStoredVersion(sdb, 1)
        assertThat(CanonicalMigrations.migrate(sdb)).containsExactly(1)
        assertThat(CanonicalMigrations.storedVersion(sdb)).isEqualTo(Schema.VERSION)
    }

    // (6) Room v1 → v2 + canonical v1 → v2 on a database written by the v1 app
    @Test fun migratesV1DatabaseKeepingRows() = runBlocking<Unit> {
        closeAll()
        context.deleteDatabase(TracerDatabase.NAME)
        createV1Database()

        open()
        val sdb = db.openHelper.writableDatabase
        for (td in Schema.all) {
            val expected = if (td.table == Table.RUNS) td.columnNames else td.exportColumns
            // new columns are appended by ALTER, so existing tables keep v1 order + v2 columns at the end;
            // compare as a set for those, exactly for tables created fresh.
            assertWithMessage(td.name).that(columns(td.name)).containsExactlyElementsIn(expected)
        }
        for (t in listOf("waypoint", "wifi_roam", "x_android_carrier", "x_collector_log")) assertWithMessage(t).that(tableExists(t)).isTrue()
        assertThat(CanonicalMigrations.storedVersion(sdb)).isEqualTo(Schema.VERSION)
        sdb.query("PRAGMA user_version").use { c -> c.moveToFirst(); assertThat(c.getInt(0)).isEqualTo(2) }

        val old = repo.getRun("v1-run")
        assertThat(old).isNotNull()
        assertThat(old!!.name).isEqualTo("Old run")
        assertThat(old.site).isNull()
        assertThat(old.testNumber).isNull()
        val probes = repo.samples("v1-run", Table.PROBE)
        assertThat(probes).hasSize(1)
        assertThat((probes.single() as app.tracer.core.model.ProbeSample).httpStatus).isNull()
        assertThat(repo.counts("v1-run")[Table.WIFI_SCAN]).isEqualTo(1L)

        // v2 rows can be written into the upgraded database
        repo.updateRun(old.copy(site = "Office", testNumber = 1))
        assertThat(repo.nextTestNumber("office")).isEqualTo(2)
        val w = WaypointSample("v1-run", Stamp(1, 2), 1, null)
        writer.emit(w); writer.flush()
        assertThat(repo.samples("v1-run", Table.WAYPOINT)).containsExactly(w)

        // re-opening a migrated database is a no-op
        closeAll()
        open()
        assertThat(repo.getRun("v1-run")?.site).isEqualTo("Office")
    }

    /** Writes `tracer.db` exactly as the v1 app left it: Room user_version 1, v1 runs, v1 canonical DDL, meta = 1. */
    private fun createV1Database() {
        val v2Cols: Map<String, Set<String>> = Schema.migrationSql(1).mapNotNull { SqlGuards.alterTarget(it) }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val newTables = setOf(Table.WAYPOINT, Table.WIFI_ROAM, Table.X_ANDROID_CARRIER, Table.X_COLLECTOR_LOG)
        fun v1(td: TableDef) = td.copy(columns = td.columns.filterNot { it.name in v2Cols[td.name].orEmpty() })
        val file = context.getDatabasePath(TracerDatabase.NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            for (td in Schema.all) {
                if (td.table in newTables) continue
                val d = v1(td)
                raw.execSQL(d.createSql(ifNotExists = false))
                d.indexSql().forEach { raw.execSQL(it) }
            }
            raw.execSQL("CREATE TABLE tracer_meta (key TEXT PRIMARY KEY, value TEXT)")
            raw.execSQL("INSERT INTO tracer_meta (key, value) VALUES ('schema_version', '1')")
            raw.execSQL(
                "INSERT INTO runs (run_id, t_utc_ms, t_mono_ns, name, mode, device_platform, device_model, os_version, app_version, started_at, ended_at) " +
                    "VALUES ('v1-run', 1000, 5, 'Old run', 'walk', 'android', 'Pixel 9', '16', '1.0.8', 1000, 2000)",
            )
            raw.execSQL(
                "INSERT INTO probe (run_id, t_utc_ms, t_mono_ns, probe_type, endpoint, success, total_ms) " +
                    "VALUES ('v1-run', 1100, 100000000, 'latency', 'https://cp.cloudflare.com/generate_204', 1, 42.0)",
            )
            raw.execSQL(
                "INSERT INTO wifi_scan (run_id, t_utc_ms, t_mono_ns, scan_id, bssid, rssi_dbm, frequency_mhz) " +
                    "VALUES ('v1-run', 1200, 200000000, 1, 'aa:bb:cc:dd:ee:ff', -60, 5180)",
            )
            raw.version = 1
        }
        // sanity: really v1
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { ro ->
            ro.rawQuery("PRAGMA table_info(runs)", null).use { c ->
                val n = c.getColumnIndexOrThrow("name")
                val cols = buildList { while (c.moveToNext()) add(c.getString(n)) }
                assertThat(cols).doesNotContain("site")
            }
        }
    }
}
