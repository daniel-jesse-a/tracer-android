package app.tracer.pairing

import app.tracer.core.export.Bundle
import app.tracer.core.model.EventSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.runtime.RunListItem
import app.tracer.core.runtime.RunRepository
import app.tracer.core.schema.ColType
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import app.tracer.pairing.live.NoopLiveLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Plain JVM tests: no Android APIs are touched by PairingServiceImpl. */
class PairingServiceTest {

    private class FakeRepository : RunRepository {
        val runs = mutableMapOf<String, RunRecord>()
        val samples = mutableMapOf<String, MutableList<Sample>>()

        override suspend fun createRun(run: RunRecord) { runs[run.runId] = run }
        override suspend fun updateRun(run: RunRecord) { if (run.runId in runs) runs[run.runId] = run }
        override suspend fun getRun(runId: String) = runs[runId]
        override fun observeRuns(): Flow<List<RunListItem>> = flowOf(emptyList())
        override suspend fun unfinishedRun(): RunRecord? = null
        override suspend fun deleteRun(runId: String) { runs.remove(runId) }
        override suspend fun samples(runId: String, table: Table, fromMonoNs: Long?, toMonoNs: Long?): List<Sample> =
            samples[runId].orEmpty().filter { it.table == table }.sortedBy { it.stamp.tMonoNs }
        override fun stream(runId: String, table: Table): Flow<Sample> = samples[runId].orEmpty().filter { it.table == table }.asFlow()
        override suspend fun counts(runId: String): Map<Table, Long> = emptyMap()
        override suspend fun events(runId: String): List<Pair<Long, EventSample>> = emptyList()
        override suspend fun setEventLabel(runId: String, eventId: Long, label: String) = Unit
        override suspend fun eventLabels(runId: String): Map<Long, String> = emptyMap()
        override suspend fun replaceEvents(runId: String, events: List<EventSample>) = Unit
        override suspend fun importBundle(bundle: Bundle.ImportedBundle): RunRecord = bundle.run
        override fun databaseFile(): File = File("unused")
        override suspend fun deleteOlderThan(cutoffUtcMs: Long): Int = 0
        override suspend fun nextTestNumber(site: String): Int = 1
    }

    private fun run(id: String, platform: String = "android") =
        RunRecord(id, "Run $id", "drive", platform, "Pixel 9", "16", "1.0.0", startedAt = 1_760_000_000_000L)

    private val repo = FakeRepository()
    private val service = PairingServiceImpl(repo, NoopLiveLink())

    @Test fun acceptScannedLinksOtherRun() = runBlocking {
        repo.createRun(run("A"))
        val qr = service.payloadFor(run("B", "ios"), gpsOffsetMs = 120)
        assertTrue(qr.startsWith("tracer://pair?d="))
        val result = service.acceptScanned("A", qr)
        assertEquals("B", result.getOrNull())
        assertEquals("B", repo.runs.getValue("A").linkedRunId)
    }

    @Test fun acceptScannedRejectsGarbageAndOwnCode() = runBlocking {
        repo.createRun(run("A"))
        assertTrue(service.acceptScanned("A", "https://example.com").isFailure)
        assertTrue(service.acceptScanned("A", "tracer://pair?d=!!!").isFailure)
        assertTrue(service.acceptScanned("A", service.payloadFor(run("A"), null)).isFailure)
        assertTrue(service.acceptScanned("missing", service.payloadFor(run("B"), null)).isFailure)
        assertEquals(null, repo.runs.getValue("A").linkedRunId)
    }

    @Test fun payloadRoundTrips() {
        val p = PairingPayload.parse(service.payloadFor(run("B", "ios"), 42))
        assertEquals("B", p?.runId)
        assertEquals("ios", p?.platform)
        assertEquals(42L, p?.gpsOffsetMs)
    }

    /** One sample per sample table, decoded from synthetic column values. */
    private fun oneOfEach(): List<Sample> = Schema.sampleTables.map { td ->
        val m = td.columns.mapIndexed { i, c ->
            c.name to when {
                c.name == "run_id" -> "r1"
                c.name == "t_utc_ms" -> 1_000L
                c.name == "t_mono_ns" -> 2_000L
                c.type == ColType.INTEGER -> (i + 3).toLong()
                c.type == ColType.REAL -> i + 0.5
                else -> "v$i"
            }
        }.toMap()
        SampleCodec.decode(td.table, SampleCodec.MapRow(m))
    }

    @Test fun withStampCoversEverySampleType() {
        val all = oneOfEach()
        assertEquals(Schema.sampleTables.map { it.table }, all.map { it.table })
        val newStamp = Stamp(9_999L, 8_888L, 7_777L)
        for (s in all) {
            val moved = s.withStamp(newStamp)
            assertEquals(s.table, moved.table)
            assertEquals(s.javaClass, moved.javaClass)
            assertEquals(newStamp, moved.stamp)
            val before = SampleCodec.encode(s)
            val after = SampleCodec.encode(moved)
            assertEquals(listOf<Any?>("r1", 9_999L, 8_888L), after.take(3))
            if (s is LocationSample) {
                // gps_time_ms comes from the sample's own field, which withStamp must not touch
                assertEquals(before.drop(3), after.drop(3))
            } else {
                assertEquals(s.table.sqlName, before.drop(3), after.drop(3))
            }
            // decode(encode(x)) is stable for the moved sample too
            val cols = Schema.of(s.table).columnNames
            val redecoded = SampleCodec.decode(s.table, SampleCodec.MapRow(cols.zip(after).toMap()))
            assertEquals(after, SampleCodec.encode(redecoded))
        }
    }

    @Test fun alignedOverlayShiftsLinkedRunOntoRunAClock() = runBlocking {
        repo.createRun(run("A"))
        repo.createRun(run("B"))
        // A: gps − utc = +100 ms; B: gps − utc = +600 ms → shift = +500 ms
        val a = (0 until 5).map { i -> LocationSample("A", Stamp(10_000L + i * 1000, 50_000_000_000L + i * 1_000_000_000L, null), 25.0, 55.0, gpsTimeMs = 10_100L + i * 1000, provider = "gps") }
        val b = (0 until 5).map { i -> LocationSample("B", Stamp(9_500L + i * 1000, 7_000_000_000L + i * 1_000_000_000L, null), 25.0, 55.0, gpsTimeMs = 10_100L + i * 1000, provider = "gps") }
        val bEvent = EventSample("B", Stamp(11_500L, 9_000_000_000L), "drop", "critical")
        repo.samples["A"] = a.toMutableList()
        repo.samples["B"] = (b + bEvent).toMutableList()

        val events = service.alignedOverlay("A", "B", Table.EVENT)
        assertEquals(1, events.size)
        val e = events.single() as EventSample
        assertEquals(12_000L, e.stamp.tUtcMs)
        // A's anchor: utc 10 000 ↔ mono 50 s; 12 000 → 52 s
        assertEquals(52_000_000_000L, e.stamp.tMonoNs)
        assertEquals("drop", e.eventType)

        val locs = service.alignedOverlay("A", "B", Table.LOCATION).map { it as LocationSample }
        assertEquals(a.map { it.stamp.tUtcMs }, locs.map { it.stamp.tUtcMs })
        assertEquals(a.map { it.stamp.tMonoNs }, locs.map { it.stamp.tMonoNs })
    }

    @Test fun noopLiveLinkIsDisconnected() = runBlocking {
        val live = service.live
        assertFalse(live.connected.value)
        live.advertise("A")
        live.stop()
        assertNotEquals(true, live.connected.value)
    }
}
