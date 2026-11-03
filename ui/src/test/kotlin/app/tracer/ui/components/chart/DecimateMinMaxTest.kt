package app.tracer.ui.components.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecimateMinMaxTest {

    private fun assertNaN(v: Float) = assertTrue("expected NaN but was $v", v.isNaN())

    @Test
    fun emptyInputGivesAllNaN() {
        val out = decimateMinMax(LongArray(0), FloatArray(0), 0, 100, 4)
        assertEquals(8, out.size)
        out.forEach { assertNaN(it) }
    }

    @Test
    fun zeroColumnsGivesEmptyArray() {
        assertEquals(0, decimateMinMax(longArrayOf(1), floatArrayOf(1f), 0, 10, 0).size)
    }

    @Test
    fun invalidRangeGivesAllNaN() {
        val out = decimateMinMax(longArrayOf(5), floatArrayOf(1f), 10, 10, 2)
        out.forEach { assertNaN(it) }
    }

    @Test
    fun minAndMaxPerColumn() {
        // range 0..100, 2 columns: [0,50) and [50,100]
        val xs = longArrayOf(0, 10, 20, 49, 50, 60, 100)
        val ys = floatArrayOf(3f, -1f, 7f, 2f, 5f, 4f, 9f)
        val out = decimateMinMax(xs, ys, 0, 100, 2)
        assertEquals(-1f, out[0]); assertEquals(7f, out[1])
        assertEquals(4f, out[2]); assertEquals(9f, out[3])
    }

    @Test
    fun pointsOutsideRangeAreIgnored() {
        val xs = longArrayOf(-10, 5, 15, 200)
        val ys = floatArrayOf(100f, 1f, 2f, -100f)
        val out = decimateMinMax(xs, ys, 0, 20, 1)
        assertEquals(1f, out[0]); assertEquals(2f, out[1])
    }

    @Test
    fun nanValuesAreSkippedAndEmptyColumnsStayNaN() {
        val xs = longArrayOf(0, 1, 2, 9)
        val ys = floatArrayOf(Float.NaN, 4f, Float.NaN, 6f)
        val out = decimateMinMax(xs, ys, 0, 10, 5) // columns of width 2
        assertEquals(4f, out[0]); assertEquals(4f, out[1])
        assertNaN(out[2]); assertNaN(out[3])
        assertNaN(out[4]); assertNaN(out[5])
        assertNaN(out[6]); assertNaN(out[7])
        assertEquals(6f, out[8]); assertEquals(6f, out[9])
    }

    @Test
    fun largeSeriesKeepsExtremes() {
        val n = 20_000
        val xs = LongArray(n) { it * 1_000_000_000L }
        val ys = FloatArray(n) { if (it == 12_345) -140f else if (it == 777) -44f else -90f }
        val out = decimateMinMax(xs, ys, xs.first(), xs.last(), 300)
        var min = Float.MAX_VALUE; var max = -Float.MAX_VALUE
        for (v in out) if (!v.isNaN()) { if (v < min) min = v; if (v > max) max = v }
        assertEquals(-140f, min); assertEquals(-44f, max)
        // every column has data: 20k points over 300 columns
        out.forEach { assertTrue(!it.isNaN()) }
    }

    @Test
    fun lowerBoundAndFloorIndex() {
        val xs = longArrayOf(10, 20, 20, 30)
        assertEquals(0, lowerBound(xs, 5))
        assertEquals(1, lowerBound(xs, 20))
        assertEquals(4, lowerBound(xs, 31))
        assertEquals(-1, floorIndex(xs, 5))
        assertEquals(0, floorIndex(xs, 15))
        assertEquals(2, floorIndex(xs, 20))
        assertEquals(3, floorIndex(xs, 30))
        assertEquals(3, floorIndex(xs, 99))
    }
}
