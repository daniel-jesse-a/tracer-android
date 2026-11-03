package app.tracer.core

import app.tracer.core.analysis.Completeness
import app.tracer.core.analysis.WorstSegments
import app.tracer.core.config.RunSettings
import app.tracer.core.config.Thresholds
import app.tracer.core.export.Bundle
import app.tracer.core.export.BundleSource
import app.tracer.core.export.Csv
import app.tracer.core.model.*
import app.tracer.core.pairing.ClockAlignment
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import app.tracer.core.schema.TableDef
import app.tracer.core.sim.Scenarios
import app.tracer.core.sim.SyntheticRun
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.util.zip.ZipInputStream

class ExportIntegrityTest {

    private class MemSource(override val run: RunRecord, samples: List<Sample>) : BundleSource {
        private val byTable = samples.groupBy { it.table }
        override fun rows(table: TableDef): Sequence<List<Any?>> =
            byTable[table.table].orEmpty().asSequence().mapIndexed { i, s -> listOf<Any?>((i + 1).toLong()) + SampleCodec.encode(s) }
        override fun sqliteFile(): File? = null
    }

    private fun run(id: String) = RunRecord(id, "Sheikh Zayed Rd", "drive", "android", "SM-S931B", "16", "1.0.0", 1_760_000_000_000, 1_760_000_120_000,
        settingsJson = RunSettings().toJson().toString(), completenessJson = "{\"overall\":0.98}", gpsOffsetMsStart = 350)

    @Test fun bundleRoundTripWithChecksums() {
        val samples = Scenarios.byName("tunnel")
        val out = ByteArrayOutputStream()
        val work = Files.createTempDirectory("bundle").toFile()
        val manifest = Bundle.write(MemSource(run("run-a"), samples), out, work, "tracer-android/1.0.0", 1_760_000_200_000)

        // every table has a CSV, plus geojson + manifest
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        Schema.all.forEach { assertTrue("${it.name}.csv", "${it.name}.csv" in names) }
        assertTrue(names.containsAll(listOf("track.geojson", "events.geojson", "run.json")))
        assertEquals(manifest.files.map { it.path }.toSet() + "run.json", names)
        assertEquals(samples.count { it is LocationSample }.toLong(), manifest.files.first { it.path == "location.csv" }.rows)

        val imported = Bundle.read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(imported.integrity.toString(), imported.ok)
        assertEquals("run-a", imported.run.runId)
        assertEquals(samples.count { it is CellSample && it.serving }, imported.samples[Table.CELL_SERVING]!!.size)
        val expectedLoc = samples.filterIsInstance<LocationSample>().first()
        val gotLoc = imported.samples[Table.LOCATION]!!.first() as LocationSample
        assertEquals(expectedLoc.lat, gotLoc.lat, 1e-12); assertEquals(expectedLoc.gpsTimeMs, gotLoc.gpsTimeMs)

        val track = Json.parseToJsonElement(String(work.resolve("track.geojson").readBytes())).jsonObject
        val coords = track["features"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(samples.count { it is LocationSample }, coords.size)
        assertEquals(1, imported.manifest["schema_version"]!!.jsonPrimitive.int)
    }

    @Test fun tamperedFileIsDetected() {
        val out = ByteArrayOutputStream()
        Bundle.write(MemSource(run("run-b"), Scenarios.byName("congestion")), out, Files.createTempDirectory("b").toFile(), "t", 1)
        // rewrite one entry
        val src = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        val tampered = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(tampered).use { zo ->
            while (true) {
                val e = src.nextEntry ?: break
                val bytes = src.readBytes()
                zo.putNextEntry(java.util.zip.ZipEntry(e.name))
                zo.write(if (e.name == "probe.csv") bytes + "1,x,1,1,latency,e,1,,,,,,,,,,,,,\r\n".toByteArray() else bytes)
                zo.closeEntry()
            }
        }
        val imported = Bundle.read(ByteArrayInputStream(tampered.toByteArray()))
        assertFalse(imported.ok)
        assertEquals("probe.csv", imported.integrity.single().path)
    }

    @Test fun csvQuotingRoundTrip() {
        val w = StringWriter()
        Csv.writeRow(w, listOf("a,b", "say \"hi\"", null, 1.0, 2.5, "line\nbreak", ""))
        val parsed = Csv.parse(StringReader(w.toString())).toList()
        assertEquals(listOf("a,b", "say \"hi\"", null, "1", "2.5", "line\nbreak", null), parsed.single())
    }

    @Test fun settingsAndThresholdsJsonRoundTrip() {
        val s = RunSettings(latencyIntervalMs = 4000, batterySaver = true, openCellIdKey = "secret", thresholds = Thresholds(latencyMinMs = 200.0, overtakeDb = 8))
        val back = RunSettings.fromJson(s.toStorageJson())
        assertEquals(s, back)
        assertFalse("export must not contain key", s.toJson().toString().contains("secret"))
        assertEquals(12_000L, s.effective().latencyIntervalMs)
    }

    @Test fun pairingPayloadAndAlignment() {
        val p = PairingPayload("run-x", "android", "SM-S931B", "16", "1.0.0", "Metro", 123, 350)
        assertEquals(p, PairingPayload.parse(p.toUri()))
        assertNull(PairingPayload.parse("garbage"))

        val a = Scenarios.byName("congestion", SyntheticRun(runId = "a", gpsOffsetMs = 350)).filterIsInstance<LocationSample>()
        // iPhone clock is 1.2 s fast relative to GPS → its offset is −850
        val b = Scenarios.byName("congestion", SyntheticRun(runId = "b", startUtcMs = 1_760_000_001_200L, gpsOffsetMs = -850)).filterIsInstance<LocationSample>()
        val al = ClockAlignment.align(a, b)
        assertEquals("gps", al.method)
        assertEquals(-1200L, al.shiftMs)
        val pairs = ClockAlignment.nearestPairs(a, b, al, 200)
        assertTrue(pairs.all { it.second != null })
        assertEquals(a[10].lat, pairs[10].second!!.lat, 1e-9)
    }

    @Test fun completenessAndWorstSegments() {
        val samples = Scenarios.byName("cell_edge_drop")
        val obs = mapOf("location" to 90L, "gnss_epochs" to 90L, "cell_serving_polls" to 90L, "wifi_scans" to 3L, "latency_probes" to 45L * 4, "device" to 3L, "motion" to 90L)
        val c = Completeness.compute(90_000, 0, obs, RunSettings())
        assertEquals(Completeness.Badge.COMPLETE, c.badge)
        val partial = Completeness.compute(90_000, 30_000, obs.mapValues { it.value / 3 }, RunSettings())
        assertNotEquals(Completeness.Badge.COMPLETE, partial.badge)

        val segs = WorstSegments.rank(samples.filterIsInstance<LocationSample>(), samples.filterIsInstance<CellSample>().filter { it.serving },
            samples.filterIsInstance<ProbeSample>(), emptyList(), windowS = 10)
        assertTrue(segs.isNotEmpty())
        // the 50–60 s window (RSRP −121, all probes failing) is the worst
        val worst = segs.first()
        assertTrue(worst.reasons.toString(), "packet_loss" in worst.reasons && "weak_rsrp" in worst.reasons)
    }
}
