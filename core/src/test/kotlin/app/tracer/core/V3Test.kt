package app.tracer.core

import app.tracer.core.analysis.CoverageTracker
import app.tracer.core.analysis.Method
import app.tracer.core.analysis.MovementTracker
import app.tracer.core.analysis.SummaryBuilder
import app.tracer.core.config.RunSettings
import app.tracer.core.export.Bundle
import app.tracer.core.export.BundleSource
import app.tracer.core.export.Csv
import app.tracer.core.model.*
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import app.tracer.core.schema.TableDef
import app.tracer.core.sim.Scenarios
import app.tracer.core.util.Geo
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class V3Test {
    private val t0Utc = 1_760_000_000_000L
    private val t0Mono = 5_000_000_000_000L
    private fun st(sec: Double) = Stamp(t0Utc + (sec * 1000).toLong(), t0Mono + (sec * 1e9).toLong())
    private fun run(durS: Int, settings: String? = RunSettings().toJson().toString()) =
        RunRecord("r", "Office · T1 · x", "walk", "android", "Pixel", "17", "1", t0Utc, t0Utc + durS * 1000L,
            settingsJson = settings, tMonoNs = t0Mono, timeZone = "Asia/Dubai", completenessJson = "{\"overall\":0.9,\"gap_ms\":0}")
    private fun dev(sec: Double) = DeviceSample("r", st(sec), 0.5)
    private fun gap(fromS: Double, toS: Double, reason: String, writtenAtS: Double = toS) =
        GapSample("r", st(writtenAtS), t0Utc + (fromS * 1000).toLong(), t0Utc + (toS * 1000).toLong(), reason)

    // ---------------------------------------------------------------- coverage

    @Test fun stallsAreDetectedWithoutGapRows() {
        val c = CoverageTracker(run(300))
        // 1 Hz samples, silent 100–140 s (40 s) and 200–210 s (10 s: not a stall)
        (0 until 300).filter { it !in 100 until 140 && it !in 200 until 210 }.forEach { c.add(dev(it.toDouble())) }
        val cov = c.build()
        assertEquals(1, cov.gapCount)
        assertEquals("stall", cov.gaps.single().reason)
        assertEquals(41_000L, cov.gaps.single().durationMs) // last sample 99 s, next 140 s
        assertEquals(41_000L, cov.longestGapMs)
        assertEquals(259_000L, cov.capturedMs)
        assertEquals(86.3, cov.coveragePct!!, 0.05)
    }

    @Test fun gapRowsAndStallsMergeWithoutDoubleCounting() {
        val c = CoverageTracker(run(300))
        (0 until 300).filter { it !in 100 until 140 }.forEach { c.add(dev(it.toDouble())) }
        // heartbeat stall row covering part of the silence, a watchdog row overlapping its end, and a short process_restart
        c.add(gap(105.0, 125.0, "stall")); c.add(gap(120.0, 145.0, "watchdog")); c.add(gap(250.0, 252.0, "process_restart"))
        val cov = c.build()
        assertEquals(cov.gaps.toString(), 2, cov.gapCount)
        val first = cov.gaps.first()
        assertEquals(99_000L, first.startMs - t0Mono / 1_000_000); assertEquals(46_000L, first.durationMs) // 99 → 145 s, once
        assertEquals("watchdog", first.reason) // the row overlapping most
        assertEquals(1 to 2_000L, cov.byReason["process_restart"])
        assertEquals(300_000L - 48_000L, cov.capturedMs)
    }

    @Test fun pausesAreNotGapsAndNotStalls() {
        val c = CoverageTracker(run(300))
        (0 until 300).filter { it !in 100 until 160 }.forEach { c.add(dev(it.toDouble())) }
        c.add(gap(100.0, 160.0, "pause"))
        val cov = c.build()
        assertEquals(0, cov.gapCount)
        assertEquals(60_000L, cov.pausedMs)
        assertEquals(100.0, cov.coveragePct!!, 0.0)
        // a pause covering only part of a silence leaves the rest as a stall when it is still > 15 s
        val c2 = CoverageTracker(run(300))
        (0 until 300).filter { it !in 100 until 160 }.forEach { c2.add(dev(it.toDouble())) }
        c2.add(gap(100.0, 120.0, "pause"))
        assertEquals(40_000L, c2.build().gaps.single().durationMs)
    }

    @Test fun leadingAndTrailingSilenceAreStalls() {
        val c = CoverageTracker(run(120))
        (30 until 90).forEach { c.add(dev(it.toDouble())) }
        val cov = c.build()
        assertEquals(2, cov.gapCount)
        assertEquals(listOf(30_000L, 31_000L), cov.gaps.map { it.durationMs })
    }

    @Test fun eventsAndLabelsDoNotCountAsData() {
        val c = CoverageTracker(run(60))
        (0 until 60).forEach { c.add(EventSample("r", st(it.toDouble()), "drop", "info")) }
        assertEquals(1, c.build().gapCount) // nothing but derived rows: the whole run is a stall
    }

    // ---------------------------------------------------------------- movement

    @Test fun movementBboxPathAndStationary() {
        val m = MovementTracker()
        var p = 25.0 to 55.0
        repeat(300) { i -> // 1 m/s north for 5 min, plus a 5.7 km-accuracy fix that must be ignored
            m.add(LocationSample("r", st(i.toDouble()), p.first, p.second, hAccM = 5.0, provider = "gps"))
            p = Geo.destination(p.first, p.second, 0.0, 1.0)
        }
        m.add(LocationSample("r", st(301.0), 25.1, 55.1, hAccM = 5_700.0, provider = "gps"))
        val mv = m.build()
        assertEquals(0.0, mv.bboxM!!.first, 1.0)
        assertEquals(293.0, mv.bboxM!!.second, 6.0) // 1st–99th percentile of 300 m
        assertEquals(290.0, mv.pathM, 15.0)
        assertEquals(false, mv.stationary)

        val still = MovementTracker()
        repeat(600) { i -> // jittering ±6 m around one spot
            val (la, lo) = Geo.destination(25.0, 55.0, (i * 77 % 360).toDouble(), (i % 7).toDouble())
            still.add(LocationSample("r", st(i.toDouble()), la, lo, hAccM = 8.0, provider = "gps"))
        }
        val s = still.build()
        assertEquals(true, s.stationary)
        assertTrue("jitter must not add path: ${s.pathM}", s.pathM < 20.0)
        assertNull(MovementTracker().build().stationary)
    }

    // ---------------------------------------------------------------- summary.json + run.json

    private fun v3Samples(): List<Sample> = buildList {
        for (i in 0 until 300) {
            if (i in 100 until 130) continue // 30 s suspension, no x_gap row
            add(DeviceSample("r", st(i.toDouble()), 0.5))
            if (i % 2 == 0) {
                add(ProbeSample("r", st(i + 0.1), "latency", "https://cp.cloudflare.com/generate_204", true, connectMs = 20.0, totalMs = 60.0,
                    isCellular = false, iface = "wifi", remoteIp = if (i % 4 == 0) "104.16.1.1" else "2606:4700::1", ipVersion = if (i % 4 == 0) 4 else 6,
                    edgePop = "DXB", edgeHeaders = "{\"cf-ray\":\"8a-DXB\"}", tlsCertSha256 = "ab".repeat(32)))
                add(ProbeSample("r", st(i + 0.2), "latency", "https://cp.cloudflare.com/generate_204", true, connectMs = 40.0, totalMs = 90.0,
                    isCellular = true, iface = "cellular", remoteIp = "104.16.2.2", ipVersion = 4, edgePop = if (i < 200) "DXB" else "FRA"))
            }
            add(LocationSample("r", st(i + 0.3), 25.0 + (i % 3) * 1e-6, 55.0, hAccM = 6.0, provider = "gps"))
        }
        add(IntegritySample("r", st(10.0), "dns_doh", "wifi", "www.google.com", true, "142.250.1.1"))
        add(IntegritySample("r", st(200.0), "dns_doh", "wifi", "www.google.com", false, "10.0.0.1", "{\"system\":[\"10.0.0.1\"],\"doh\":[\"142.250.1.1\"]}"))
        add(IntegritySample("r", st(20.0), "tls_cert", "cellular", "cp.cloudflare.com", true, "ab".repeat(32)))
        add(IntegritySample("r", st(30.0), "first_hop", "cellular", "8.8.8.8", null))
    }

    @Test fun summaryReportsCoverageServersIntegrityMovement() {
        val b = SummaryBuilder(run(300))
        v3Samples().forEach(b::add)
        val j = b.build(RunSettings())
        assertEquals(2, j["summary_version"]!!.jsonPrimitive.int)
        assertEquals(1, j["gaps"]!!.jsonPrimitive.int)
        assertEquals(269.3, j["captured_s"]!!.jsonPrimitive.double, 0.05) // silence 99.3 → 130.0 s
        val cov = j["coverage"]!!.jsonObject
        assertEquals(1, cov["gaps"]!!.jsonObject["by_reason"]!!.jsonObject["stall"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertTrue(j["coverage_pct"]!!.jsonPrimitive.double in 89.0..90.5)

        val wifiEp = j["paths"]!!.jsonObject["wifi"]!!.jsonObject["endpoints"]!!.jsonObject["https://cp.cloudflare.com/generate_204"]!!.jsonObject
        assertEquals(135, wifiEp["edge_pops"]!!.jsonObject["DXB"]!!.jsonPrimitive.int)
        assertEquals(setOf("104.16.1.1", "2606:4700::1"), wifiEp["remote_ips"]!!.jsonObject.keys)
        assertEquals(setOf("4", "6"), wifiEp["ip_versions"]!!.jsonObject.keys)
        val cellEp = j["paths"]!!.jsonObject["cellular"]!!.jsonObject["endpoints"]!!.jsonObject.values.single().jsonObject
        assertEquals(setOf("DXB", "FRA"), cellEp["edge_pops"]!!.jsonObject.keys)

        val integ = j["integrity"]!!.jsonObject
        assertEquals(4, integ["checks"]!!.jsonPrimitive.int); assertEquals(1, integ["failed"]!!.jsonPrimitive.int)
        val latest = integ["latest"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, latest.size) // latest per kind + path + target
        assertEquals(false, latest.first { it["kind"]!!.jsonPrimitive.content == "dns_doh" }["ok"]!!.jsonPrimitive.boolean)

        val mv = j["movement"]!!.jsonObject
        assertEquals(true, mv["stationary"]!!.jsonPrimitive.boolean)
        assertNotNull(mv["bbox_m"]!!.jsonArray)
        assertEquals(2.0, j["event_rules"]!!.jsonObject["cause_window_s"]!!.jsonPrimitive.double, 0.0)
        assertEquals(2.0, j["method"]!!.jsonObject["cause_window_s"]!!.jsonPrimitive.double, 0.0)
    }

    private class MemSource(override val run: RunRecord, samples: List<Sample>) : BundleSource {
        private val byTable = samples.groupBy { it.table }
        override fun rows(table: TableDef): Sequence<List<Any?>> =
            byTable[table.table].orEmpty().asSequence().mapIndexed { i, s -> listOf<Any?>((i + 1).toLong()) + SampleCodec.encode(s) }
        override fun sqliteFile(): File? = null
    }

    @Test fun bundleV3RoundTripAndRunJsonCoverage() {
        val samples = v3Samples() + Scenarios.byName("tunnel").take(50).map { if (it is LocationSample) it.copy(isMock = true) else it }
        val out = ByteArrayOutputStream()
        val work = Files.createTempDirectory("b3").toFile()
        Bundle.write(MemSource(run(300), samples), out, work, "tracer-android/test", t0Utc + 400_000)
        val imported = Bundle.read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(imported.integrity.toString(), imported.ok)
        assertEquals(3, imported.manifest["schema_version"]!!.jsonPrimitive.int)
        assertEquals(3, imported.manifest["bundle_version"]!!.jsonPrimitive.int)
        assertEquals(samples.filterIsInstance<IntegritySample>().toSet(), imported.samples[Table.INTEGRITY]!!.toSet())
        val p = imported.samples[Table.PROBE]!!.filterIsInstance<ProbeSample>().first { it.edgePop != null }
        assertEquals("DXB", p.edgePop); assertNotNull(p.remoteIp); assertNotNull(p.ipVersion); assertNotNull(p.tlsCertSha256)
        assertTrue(imported.samples[Table.LOCATION]!!.any { (it as LocationSample).isMock == true })

        val m = imported.manifest
        assertEquals(1, m["coverage"]!!.jsonObject["gaps"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertNotNull(m["coverage_pct"]!!.jsonPrimitive.doubleOrNull)
        assertNotNull(m["captured_s"]!!.jsonPrimitive.doubleOrNull)
        val comp = m["completeness"]!!.jsonObject
        assertEquals(0.9, comp["overall"]!!.jsonPrimitive.double, 0.0) // as recorded …
        assertEquals(1, comp["gaps"]!!.jsonPrimitive.int)                // … plus the honest gap count
        assertEquals(2.0, m["method"]!!.jsonObject["cause_window_s"]!!.jsonPrimitive.double, 0.0)
        assertEquals(15.0, m["method"]!!.jsonObject["stall_threshold_s"]!!.jsonPrimitive.double, 0.0)
        // lean: empty v3 table is omitted when there are no rows
        val out2 = ByteArrayOutputStream()
        Bundle.write(MemSource(run(120), Scenarios.byName("tunnel")), out2, Files.createTempDirectory("b3b").toFile(), "t", t0Utc)
        val m2 = Bundle.read(ByteArrayInputStream(out2.toByteArray())).manifest
        assertTrue(m2["tables_omitted"]!!.jsonArray.any { it.jsonPrimitive.content == "integrity" })
    }

    /** A bundle written by a v1/v2 app: CSV headers without the v3 columns, no integrity.csv. */
    @Test fun olderBundlesStillImport() {
        for (v in 1..2) {
            val drop = (v until Schema.VERSION).flatMap { Schema.migrationSql(it) }
                .mapNotNull { Regex("ALTER TABLE (\\w+) ADD COLUMN (\\w+)").find(it)?.destructured?.let { (t, c) -> t to c } }
            fun csv(td: TableDef, s: List<Sample>): ByteArray {
                val cols = td.exportColumns.filterNot { c -> (td.name to c) in drop }
                val w = StringWriter()
                Csv.writeRow(w, cols)
                s.forEachIndexed { i, x ->
                    val m = td.exportColumns.zip(listOf<Any?>((i + 1).toLong()) + SampleCodec.encode(x)).toMap()
                    Csv.writeRow(w, cols.map { m[it] })
                }
                return w.toString().toByteArray()
            }
            val loc = LocationSample("r", st(1.0).copy(gpsTimeMs = 7), 25.0, 55.0, hAccM = 5.0, gpsTimeMs = 7, provider = "gps")
            val probe = ProbeSample("r", st(2.0), "latency", "https://e", true, totalMs = 30.0, isCellular = false, iface = "wifi")
            val runRow = run(60)
            val runsCols = Schema.RUNS.columnNames.let { if (v == 1) it.take(17) else it }
            val runs = StringWriter().also { w -> Csv.writeRow(w, runsCols); Csv.writeRow(w, SampleCodec.encodeRun(runRow).take(runsCols.size)) }.toString()
            val files = mapOf("runs.csv" to runs.toByteArray(), "location.csv" to csv(Schema.LOCATION, listOf(loc)), "probe.csv" to csv(Schema.PROBE, listOf(probe)))
            val manifest = buildJsonObject {
                put("schema_version", v); put("bundle_version", v)
                putJsonArray("files") { files.forEach { (n, b) -> addJsonObject { put("path", n); put("sha256", Bundle.sha256Hex(b)) } } }
            }
            val zip = ByteArrayOutputStream()
            ZipOutputStream(zip).use { z ->
                (files + ("run.json" to manifest.toString().toByteArray())).forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            }
            val imp = Bundle.read(ByteArrayInputStream(zip.toByteArray()))
            assertTrue("v$v ${imp.integrity}", imp.ok)
            assertEquals(listOf(loc), imp.samples[Table.LOCATION])
            assertEquals(listOf(probe), imp.samples[Table.PROBE])
            assertNull(imp.samples[Table.INTEGRITY])
        }
    }

    // ---------------------------------------------------------------- settings v4

    @Test fun settingsV4DefaultsAndMigration() {
        val d = RunSettings()
        assertEquals(25_000_000L, d.throughputBytes); assertEquals(5_000L, d.throughputMaxMs); assertEquals(1_000L, d.cellularDataBudgetMb)
        assertEquals(2.0, d.thresholds.causeLookbackS, 0.0)
        assertEquals(4, RunSettings.SETTINGS_VERSION)
        // a v3 store still holding the old defaults gets the new ones …
        val v3 = """{"settings_version":3,"throughput_bytes":10000000,"throughput_max_ms":4000,"cellular_data_budget_mb":500,"thresholds":{"cause_lookback_s":15.0}}"""
        val m = RunSettings.fromJson(v3)
        assertEquals(25_000_000L, m.throughputBytes); assertEquals(5_000L, m.throughputMaxMs); assertEquals(1_000L, m.cellularDataBudgetMb)
        assertEquals(2.0, m.thresholds.causeLookbackS, 0.0)
        // … user-chosen values are kept
        val custom = RunSettings.fromJson("""{"settings_version":3,"throughput_bytes":5000000,"throughput_max_ms":8000,"cellular_data_budget_mb":200}""")
        assertEquals(5_000_000L, custom.throughputBytes); assertEquals(8_000L, custom.throughputMaxMs); assertEquals(200L, custom.cellularDataBudgetMb)
        // a v4 store keeps even a value equal to an old default
        assertEquals(10_000_000L, RunSettings.fromJson(RunSettings(throughputBytes = 10_000_000).toStorageJson()).throughputBytes)
        // as recorded: no migration (method fingerprint of old runs is stable)
        assertEquals(10_000_000L, RunSettings.fromJson(v3, migrate = false).throughputBytes)
        assertEquals(15.0, RunSettings.fromJson(v3, migrate = false).thresholds.causeLookbackS, 0.0)
        assertNotEquals(Method.fingerprintOfRecorded(v3), Method.fingerprint(RunSettings()))
        assertEquals(2.0, Method.canonical(RunSettings())["cause_window_s"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun noEmDashInProtocolText() {
        app.tracer.core.analysis.Protocol.ALL.forEach { p -> assertFalse(p.description, '—' in p.description || '—' in p.title) }
    }
}
