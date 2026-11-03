package app.tracer.core.cell

import app.tracer.core.model.CellSample
import app.tracer.core.model.Stamp
import kotlinx.serialization.json.*

/**
 * Raw, un-normalised values exactly as the Android getters return them (including
 * CellInfo.UNAVAILABLE = Int.MAX_VALUE and UNAVAILABLE_LONG = Long.MAX_VALUE sentinels).
 * The Android mapper in :collectors only copies fields; all interpretation happens here so it
 * is unit-testable on the JVM with JSON fixtures.
 */
data class RawCell(
    /** LTE | NR | WCDMA | GSM | TDSCDMA | CDMA */
    val type: String,
    val registered: Boolean,
    /** CellInfo.getTimestampNanos() — elapsedRealtimeNanos when the modem produced it */
    val timestampNanos: Long? = null,
    val mcc: String? = null,
    val mnc: String? = null,
    /** TAC (LTE/NR) or LAC (GSM/WCDMA) */
    val tac: Int? = null,
    /** CI (LTE 28-bit), NCI (NR 36-bit), CID (GSM/WCDMA) */
    val cid: Long? = null,
    /** PCI (LTE/NR), PSC (WCDMA), BSIC (GSM) */
    val pci: Int? = null,
    /** EARFCN / NR-ARFCN / UARFCN / ARFCN */
    val arfcn: Int? = null,
    val bands: List<Int> = emptyList(),
    val bandwidthKhz: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val rssi: Int? = null,
    val rssnr: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val ssRsrp: Int? = null,
    val ssRsrq: Int? = null,
    val ssSinr: Int? = null,
    val csiRsrp: Int? = null,
    val csiRsrq: Int? = null,
    val csiSinr: Int? = null,
    /** NR timing advance in µs (CellSignalStrengthNr.getTimingAdvanceMicros, API 34+) */
    val nrTimingAdvanceMicros: Int? = null,
    val rscp: Int? = null,
    val ecNo: Int? = null,
) {
    companion object {
        /** Parse a fixture / debug dump. Keys are camelCase field names. */
        fun fromJson(o: JsonObject): RawCell {
            fun i(k: String) = o[k]?.jsonPrimitive?.longOrNull?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
            fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            return RawCell(
                type = s("type")!!, registered = o["registered"]?.jsonPrimitive?.booleanOrNull ?: false,
                timestampNanos = l("timestampNanos"), mcc = s("mcc"), mnc = s("mnc"), tac = i("tac"), cid = l("cid"),
                pci = i("pci"), arfcn = i("arfcn"), bands = o["bands"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList(),
                bandwidthKhz = i("bandwidthKhz"), rsrp = i("rsrp"), rsrq = i("rsrq"), rssi = i("rssi"), rssnr = i("rssnr"),
                cqi = i("cqi"), timingAdvance = i("timingAdvance"), ssRsrp = i("ssRsrp"), ssRsrq = i("ssRsrq"), ssSinr = i("ssSinr"),
                csiRsrp = i("csiRsrp"), csiRsrq = i("csiRsrq"), csiSinr = i("csiSinr"), nrTimingAdvanceMicros = i("nrTimingAdvanceMicros"),
                rscp = i("rscp"), ecNo = i("ecNo"),
            )
        }
    }
}

object CellNormalizer {

    private const val UNAVAILABLE = Int.MAX_VALUE
    private const val UNAVAILABLE_LONG = Long.MAX_VALUE

    private fun Int?.valid(range: IntRange): Int? = this?.takeIf { it != UNAVAILABLE && it in range }
    private fun Long?.validId(maxBits: Int): Long? =
        this?.takeIf { it != UNAVAILABLE_LONG && it != UNAVAILABLE.toLong() && it >= 0 && it < (1L shl maxBits) }
    private fun String?.validPlmn(): String? = this?.takeIf { it.isNotBlank() && it.all(Char::isDigit) && it.length in 2..3 }

    /**
     * @param nowMonoNs current elapsedRealtimeNanos, used to compute cellinfo_age_ms (staleness of cached CellInfo)
     */
    fun normalize(raw: RawCell, runId: String, stamp: Stamp, subscriptionId: Int?, nowMonoNs: Long = stamp.tMonoNs): CellSample {
        val age = raw.timestampNanos?.takeIf { it in 1 until UNAVAILABLE_LONG }?.let { ((nowMonoNs - it) / 1_000_000L).coerceAtLeast(0) }
        val base = CellSample(
            runId = runId, stamp = stamp, serving = raw.registered, rat = raw.type,
            mcc = raw.mcc.validPlmn(), mnc = raw.mnc.validPlmn(), cellinfoAgeMs = age, subscriptionId = subscriptionId,
        )
        return when (raw.type) {
            "LTE" -> {
                val earfcn = raw.arfcn.valid(0..262143)
                base.copy(
                    tac = raw.tac.valid(0..65535), cellId = raw.cid.validId(28), pci = raw.pci.valid(0..503), earfcnOrArfcn = earfcn,
                    band = (raw.bands.firstOrNull { it in 1..256 } ?: earfcn?.let { Bands.lteBandForEarfcn(it) })?.let { "B$it" },
                    bandwidthMhz = raw.bandwidthKhz.valid(1_400..20_000)?.let { it / 1000.0 },
                    rsrp = raw.rsrp.valid(-140..-43), rsrq = raw.rsrq.valid(-34..3), rssi = raw.rssi.valid(-113..-51),
                    sinr = raw.rssnr.valid(-20..30), cqi = raw.cqi.valid(0..15), timingAdvance = raw.timingAdvance.valid(0..1282),
                )
            }
            "NR" -> {
                val arfcn = raw.arfcn.valid(0..3279165)
                base.copy(
                    tac = raw.tac.valid(0..16777215), cellId = raw.cid.validId(36), pci = raw.pci.valid(0..1007), earfcnOrArfcn = arfcn,
                    band = (raw.bands.firstOrNull { it in 1..512 } ?: arfcn?.let { Bands.nrBandForArfcn(it) })?.let { "n$it" },
                    bandwidthMhz = raw.bandwidthKhz.valid(5_000..400_000)?.let { it / 1000.0 },
                    rsrp = raw.ssRsrp.valid(-156..-31) ?: raw.csiRsrp.valid(-156..-31),
                    rsrq = raw.ssRsrq.valid(-43..20) ?: raw.csiRsrq.valid(-43..20),
                    sinr = raw.ssSinr.valid(-23..40) ?: raw.csiSinr.valid(-23..40),
                    timingAdvance = raw.nrTimingAdvanceMicros.valid(0..1282),
                )
            }
            "WCDMA", "TDSCDMA" -> base.copy(
                tac = raw.tac.valid(0..65535), cellId = raw.cid.validId(28), pci = raw.pci.valid(0..511),
                earfcnOrArfcn = raw.arfcn.valid(0..16383),
                band = raw.arfcn.valid(0..16383)?.let { Bands.umtsBandForUarfcn(it) }?.let { "U$it" },
                rsrp = (raw.rscp ?: raw.rsrp).valid(-120..-24), rsrq = raw.ecNo.valid(-24..1), rssi = raw.rssi.valid(-113..-51),
            )
            "GSM" -> base.copy(
                tac = raw.tac.valid(0..65535), cellId = raw.cid.validId(16), pci = raw.pci.valid(0..63),
                earfcnOrArfcn = raw.arfcn.valid(0..1023), rssi = raw.rssi.valid(-113..-51),
                timingAdvance = raw.timingAdvance.valid(0..219),
            )
            else -> base
        }
    }

    /** Approximate UE→cell distance in metres implied by timing advance. */
    fun timingAdvanceDistanceM(rat: String, ta: Int?): Double? = when {
        ta == null -> null
        rat == "LTE" -> ta * 78.12
        rat == "NR" -> ta * 149.9  // ta stored as round-trip µs: c·t/2
        rat == "GSM" -> ta * 553.85
        else -> null
    }

    /** radio.carrier_aggregation JSON (from ServiceState.getCellBandwidths). */
    fun carrierAggregationJson(bandwidthsKhz: List<Int>, rat: String?): String = buildJsonObject {
        val valid = bandwidthsKhz.filter { it > 0 && it != UNAVAILABLE }
        put("active", valid.size > 1)
        put("cell_count", valid.size)
        put("aggregate_mhz", valid.sum() / 1000.0)
        putJsonArray("bandwidths_khz") { valid.forEach { add(it) } }
        rat?.let { put("rat", it) }
    }.toString()
}

object Bands {
    private data class LteBand(val band: Int, val fdlLow: Double, val nOffs: Int, val nMax: Int)

    private val lte = listOf(
        LteBand(1, 2110.0, 0, 599), LteBand(2, 1930.0, 600, 1199), LteBand(3, 1805.0, 1200, 1949),
        LteBand(4, 2110.0, 1950, 2399), LteBand(5, 869.0, 2400, 2649), LteBand(7, 2620.0, 2750, 3449),
        LteBand(8, 925.0, 3450, 3799), LteBand(12, 729.0, 5010, 5179), LteBand(13, 746.0, 5180, 5279),
        LteBand(14, 758.0, 5280, 5379), LteBand(17, 734.0, 5730, 5849), LteBand(18, 860.0, 5850, 5999),
        LteBand(19, 875.0, 6000, 6149), LteBand(20, 791.0, 6150, 6449), LteBand(25, 1930.0, 8040, 8689),
        LteBand(26, 859.0, 8690, 9039), LteBand(28, 758.0, 9210, 9659), LteBand(29, 717.0, 9660, 9769),
        LteBand(30, 2350.0, 9770, 9869), LteBand(32, 1452.0, 9920, 10359), LteBand(34, 2010.0, 36200, 36349),
        LteBand(38, 2570.0, 37750, 38249), LteBand(39, 1880.0, 38250, 38649), LteBand(40, 2300.0, 38650, 39649),
        LteBand(41, 2496.0, 39650, 41589), LteBand(42, 3400.0, 41590, 43589), LteBand(43, 3600.0, 43590, 45589),
        LteBand(46, 5150.0, 46790, 54539), LteBand(48, 3550.0, 55240, 56739), LteBand(66, 2110.0, 66436, 67335),
        LteBand(71, 617.0, 68586, 68935),
    )

    fun lteBandForEarfcn(earfcn: Int): Int? = lte.firstOrNull { earfcn in it.nOffs..it.nMax }?.band

    fun lteDlMhz(earfcn: Int): Double? = lte.firstOrNull { earfcn in it.nOffs..it.nMax }?.let { it.fdlLow + 0.1 * (earfcn - it.nOffs) }

    /** 3GPP 38.104 global frequency raster. */
    fun nrFreqMhz(arfcn: Int): Double = when {
        arfcn < 600_000 -> 0.005 * arfcn
        arfcn < 2_016_667 -> 3000.0 + 0.015 * (arfcn - 600_000)
        else -> 24250.08 + 0.06 * (arfcn - 2_016_667)
    }

    // Ordered by deployment likelihood so overlapping ranges resolve to the common band (n78 over n77, etc).
    private val nrDl = listOf(
        78 to (3300.0..3800.0), 77 to (3300.0..4200.0), 79 to (4400.0..5000.0), 41 to (2496.0..2690.0),
        1 to (2110.0..2170.0), 3 to (1805.0..1880.0), 28 to (758.0..803.0), 20 to (791.0..821.0),
        8 to (925.0..960.0), 5 to (869.0..894.0), 7 to (2620.0..2690.0), 40 to (2300.0..2400.0), 38 to (2570.0..2620.0),
        71 to (617.0..652.0), 66 to (2110.0..2200.0), 25 to (1930.0..1995.0), 2 to (1930.0..1990.0),
        258 to (24250.0..27500.0), 257 to (26500.0..29500.0), 261 to (27500.0..28350.0), 260 to (37000.0..40000.0),
    )

    fun nrBandForArfcn(arfcn: Int): Int? {
        val f = nrFreqMhz(arfcn)
        return nrDl.firstOrNull { f in it.second }?.first
    }

    fun umtsBandForUarfcn(uarfcn: Int): Int? = when (uarfcn) {
        in 10562..10838 -> 1
        in 9662..9938 -> 2
        in 1162..1513 -> 3
        in 4357..4458 -> 5
        in 2937..3088 -> 8
        else -> null
    }
}
