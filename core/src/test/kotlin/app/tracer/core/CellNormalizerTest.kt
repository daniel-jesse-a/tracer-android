package app.tracer.core

import app.tracer.core.cell.Bands
import app.tracer.core.cell.CellNormalizer
import app.tracer.core.cell.RawCell
import app.tracer.core.model.Stamp
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CellNormalizerTest {

    private fun fixture(name: String): JsonObject {
        val text = javaClass.getResource("/fixtures/cellinfo/$name")!!.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun check(name: String) {
        val fx = fixture(name)
        val now = fx["nowMonoNs"]!!.jsonPrimitive.long
        val cells = fx["cells"]!!.jsonArray.map { RawCell.fromJson(it.jsonObject) }
        val expect = fx["expect"]!!.jsonArray.map { it.jsonObject }
        assertEquals(expect.size, cells.size)
        cells.zip(expect).forEachIndexed { i, (raw, e) ->
            val c = CellNormalizer.normalize(raw, "r", Stamp(1, now), subscriptionId = 1, nowMonoNs = now)
            fun <T> eq(field: String, actual: T?, exp: JsonElement?) {
                val want: Any? = when {
                    exp == null || exp is JsonNull -> null
                    actual is Double -> exp.jsonPrimitive.double
                    actual is Long -> exp.jsonPrimitive.long
                    actual is Int -> exp.jsonPrimitive.int
                    actual is Boolean -> exp.jsonPrimitive.boolean
                    else -> exp.jsonPrimitive.contentOrNull
                }
                assertEquals("$name[$i].$field", want, actual)
            }
            eq("serving", c.serving, e["serving"]); eq("rat", c.rat, e["rat"]); eq("cell_id", c.cellId, e["cell_id"])
            eq("pci", c.pci, e["pci"]); eq("band", c.band, e["band"]); eq("bandwidth_mhz", c.bandwidthMhz, e["bandwidth_mhz"])
            eq("rsrp", c.rsrp, e["rsrp"]); eq("rsrq", c.rsrq, e["rsrq"]); eq("sinr", c.sinr, e["sinr"])
            eq("timing_advance", c.timingAdvance, e["timing_advance"]); eq("cellinfo_age_ms", c.cellinfoAgeMs, e["cellinfo_age_ms"])
        }
    }

    @Test fun lteFixture() = check("lte.json")
    @Test fun nrFixture() = check("nr.json")
    @Test fun wcdmaGsmFixture() = check("wcdma.json")

    @Test fun earfcnToBand() {
        assertEquals(3, Bands.lteBandForEarfcn(1650))
        assertEquals(20, Bands.lteBandForEarfcn(6300))
        assertEquals(1, Bands.lteBandForEarfcn(300))
        assertEquals(7, Bands.lteBandForEarfcn(3100))
        assertEquals(1842.5, Bands.lteDlMhz(1575)!!, 1e-6)
    }

    @Test fun nrArfcnToBand() {
        assertEquals(3550.0, Bands.nrFreqMhz(636666), 0.02)
        assertEquals(78, Bands.nrBandForArfcn(636666))
        assertEquals(1, Bands.nrBandForArfcn(428000)) // 2140 MHz
    }

    @Test fun outOfRangeValuesAreDropped() {
        val raw = RawCell("LTE", true, rsrp = -20, rsrq = -60, rssnr = 300, pci = 999, cid = 1L shl 30)
        val c = CellNormalizer.normalize(raw, "r", Stamp(0, 0), 1)
        assertNull(c.rsrp); assertNull(c.rsrq); assertNull(c.sinr); assertNull(c.pci); assertNull(c.cellId)
    }

    @Test fun timingAdvanceDistance() {
        assertEquals(546.84, CellNormalizer.timingAdvanceDistanceM("LTE", 7)!!, 0.01)
        assertNull(CellNormalizer.timingAdvanceDistanceM("WCDMA", 3))
    }

    @Test fun carrierAggregationJson() {
        val o = Json.parseToJsonElement(CellNormalizer.carrierAggregationJson(listOf(20000, 10000, Int.MAX_VALUE), "LTE_CA")).jsonObject
        assertTrue(o["active"]!!.jsonPrimitive.boolean)
        assertEquals(2, o["cell_count"]!!.jsonPrimitive.int)
        assertEquals(30.0, o["aggregate_mhz"]!!.jsonPrimitive.double, 1e-9)
    }
}
