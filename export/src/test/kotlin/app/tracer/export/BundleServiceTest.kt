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
            RunRecord(runId, "Office · T3 · 2025-10-09 12:53", "drive", "android", "Pixel 9", "16", "1.0.0", 1_760_000_000_000L, 1_760_000_080_000L,
                gpsOffsetMsStart = 350, completenessJson = "{\"badge\":\"complete\"}",
                tMonoNs = SyntheticRun().startMonoNs, site = "Office", testNumber = 3, timeZone = "Asia/Dubai"),
        )
        val samples = Scenarios.byName("congestion")
        samples.forEach { writer.emit(it) } // not flushed: export must flush first
        // rows of another run must not leak into the bundle
        Scenarios.byName("tunnel", SyntheticRun(runId = "other-run")).take(60).forEach { writer.emit(it) }

        val progress = mutableListOf<Float>()
        val zip = service.export(runId) { progress += it }

        assertThat(zip.isFile).isTrue()
        // site + test number + local start in the run's zone (Dubai, +04:00)
        assertThat(zip.name).isEqualTo("office_t03_20251009-1253_${runId.take(8)}.zip")
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

        // lean bundle v2: CSVs only for tables with rows, never iOS-only tables; summary.json present
        val entries = java.util.zip.ZipFile(zip).use { zf -> zf.entries().asSequence().map { it.name }.toSet() }
        assertThat(entries).containsAtLeast("run.json", "runs.csv", "run.sqlite", "summary.json", "track.geojson", "events.geojson")
        for (td in Schema.sampleTables) {
            val csv = "${td.name}.csv"
            if ((expected[td.table] ?: 0) > 0 && !td.table.iosOnly) assertWithMessage(csv).that(entries).contains(csv)
            else assertWithMessage(csv).that(entries).doesNotContain(csv)
        }
        assertThat(entries).doesNotContain("metrickit_payload.csv")
        assertThat(imported.manifest["bundle_version"].toString()).isEqualTo(Bundle.BUNDLE_VERSION.toString())
        assertThat(imported.manifest["time_zone"].toString()).isEqualTo("\"Asia/Dubai\"")
        assertThat(imported.run.site).isEqualTo("Office")
        assertThat(imported.run.testNumber).isEqualTo(3)
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
                // every sample table has a v_<table> view with the same rows
                assertWithMessage("v_${td.name}").that(count("SELECT COUNT(*) FROM v_${td.name}")).isEqualTo((expected[td.table] ?: 0).toLong())
            }
            // t_run_s / t_local on the views (first location row of the synthetic run is at t = 0 s)
            val first = samples.filter { it.table == Table.LOCATION }.minBy { it.stamp.tMonoNs }
            sq.rawQuery("SELECT t_run_s, t_local, t_utc_ms FROM v_location ORDER BY t_mono_ns LIMIT 1", null).use { c ->
                assertThat(c.moveToFirst()).isTrue()
                val tRun = c.getDouble(0)
                assertThat(tRun).isWithin(1e-6).of((first.stamp.tMonoNs - SyntheticRun().startMonoNs) / 1e9)
                val expectLocal = java.time.Instant.ofEpochMilli(c.getLong(2)).atOffset(java.time.ZoneOffset.ofHours(4))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")) + "+04:00"
                assertThat(c.getString(1)).isEqualTo(expectLocal)
            }
        }
    }

    /** v3 rows (integrity, probe edge columns, location.is_mock) survive live DB → run.sqlite → CSV → import; run.json reports coverage. */
    @Test fun exportsV3RowsAndCoverage() = runBlocking<Unit> {
        val sim = SyntheticRun()
        repo.createRun(
            RunRecord(runId, "Office · T4 · x", "walk", "android", "Pixel 9", "16", "1.0.0", sim.startUtcMs, sim.startUtcMs + 120_000L,
                tMonoNs = sim.startMonoNs, site = "Office", testNumber = 4, timeZone = "Asia/Dubai", completenessJson = "{\"overall\":1.0}"),
        )
        // 120 s of samples with a 40 s hole (50–90 s) and no x_gap row
        val base = sim.build(120) { if (t !in 50 until 90) { location(); latency(30.0) } }
        val st = app.tracer.core.model.Stamp(sim.startUtcMs + 10_000, sim.startMonoNs + 10_000_000_000)
        val integrity = app.tracer.core.model.IntegritySample(runId, st, "tls_cert", "cellular", "cp.cloudflare.com", true, "ab".repeat(32), "{\"issuer\":\"x\"}")
        val probe = app.tracer.core.model.ProbeSample(runId, st, "latency", "https://cp.cloudflare.com/generate_204", true, totalMs = 30.0,
            isCellular = true, iface = "cellular", remoteIp = "104.16.2.2", ipVersion = 4, edgePop = "DXB", edgeHeaders = "{\"cf-ray\":\"8a-DXB\"}", tlsCertSha256 = "ab".repeat(32))
        val mock = app.tracer.core.model.LocationSample(runId, st.copy(gpsTimeMs = 9L), 25.0, 55.0, hAccM = 3.0, gpsTimeMs = 9L, provider = "gps", isMock = true)
        (base + listOf(integrity, probe, mock)).forEach { writer.emit(it) }

        val zip = service.export(runId) { }
        val imported = zip.inputStream().use { Bundle.read(it) }
        assertWithMessage(imported.integrity.toString()).that(imported.ok).isTrue()
        assertThat(imported.samples[Table.INTEGRITY]).containsExactly(integrity)
        assertThat(imported.samples[Table.PROBE]).contains(probe)
        assertThat(imported.samples[Table.LOCATION]).contains(mock)
        val m = imported.manifest
        assertThat(m["schema_version"].toString()).isEqualTo("3")
        val gaps = (m["coverage"] as kotlinx.serialization.json.JsonObject)["gaps"] as kotlinx.serialization.json.JsonObject
        assertThat(gaps["count"].toString()).isEqualTo("1")
        val entries = java.util.zip.ZipFile(zip).use { zf -> zf.entries().asSequence().map { it.name }.toSet() }
        assertThat(entries).contains("integrity.csv")
    }

    @Test fun bundleFileNames() {
        val base = RunRecord("1a2b3c4d-0000", "x", "walk", "android", "m", "16", "1", 1_760_000_000_000L, timeZone = "UTC")
        assertThat(BundleServiceImpl.bundleFileName(base)).isEqualTo("run_20251009-0853_1a2b3c4d.zip")
        assertThat(BundleServiceImpl.bundleFileName(base.copy(site = "Main Office", testNumber = 12, timeZone = "Asia/Kolkata")))
            .isEqualTo("main-office_t12_20251009-1423_1a2b3c4d.zip")
        assertThat(BundleServiceImpl.offsetText(java.time.ZoneOffset.ofHoursMinutes(-3, -30))).isEqualTo("-03:30")
        assertThat(BundleServiceImpl.offsetText(java.time.ZoneOffset.UTC)).isEqualTo("+00:00")
    }

    @Test fun safeNames() {
        assertThat(BundleServiceImpl.safeName("  Sheikh Zayed Rd / night  ")).isEqualTo("Sheikh_Zayed_Rd_night")
        assertThat(BundleServiceImpl.safeName("///")).isEqualTo("run")
        assertThat(service.fileProviderAuthority).isEqualTo("${context.packageName}.exports")
    }
}
