package app.tracer.core.cell

import app.tracer.core.model.CellSample
import kotlinx.serialization.json.*

/** One component carrier, before it is stamped into a CarrierSample. */
data class CarrierSpec(
    val index: Int,
    val rat: String,
    val band: String?,
    val arfcn: Int?,
    val pci: Int?,
    val bandwidthMhz: Double?,
    val primary: Boolean,
    val source: String,
)

data class RadioDerivation(
    /** corrected display RAT (never LTE/LTE_CA when an NR carrier is active) */
    val rat: String,
    /** none | nsa | nsa_advanced | sa */
    val nrState: String,
    val carriers: List<CarrierSpec>,
    /** radio.carrier_aggregation JSON (v2: includes the per-carrier list) */
    val carrierAggregationJson: String,
)

/**
 * Derives 5G state and the component-carrier list for one subscription from what public Android
 * APIs expose: TelephonyDisplayInfo (override network type), ServiceState.getCellBandwidths()
 * (one entry per active carrier, PCell first) and the CellInfo list.
 *
 * Rules:
 *  - nr_state: override NR_NSA → nsa; NR_ADVANCED → nsa_advanced; network type NR (no override) → sa;
 *    otherwise nsa if any carrier is NR, else none.
 *  - A carrier wider than 20 MHz cannot be LTE (LTE max channel = 20 MHz) → NR. In SA every carrier is NR.
 *  - Carriers are matched to cells: PCell → registered serving cell; NR carriers → the NR cell
 *    (Pixel reports the NSA leg as a non-registered NR cell); LTE SCells → other LTE cells by bandwidth.
 *  - The display RAT is corrected to NR_NSA when an NR carrier is active but the override said LTE/LTE_CA
 *    (seen on Pixel 10 Pro: 20 MHz B3 + 90 MHz n41 labelled "LTE_CA").
 */
object Carriers {
    private const val LTE_MAX_KHZ = 20_000
    private const val UNAVAILABLE = Int.MAX_VALUE

    fun derive(displayRat: String, bandwidthsKhz: List<Int>, cells: List<CellSample>): RadioDerivation {
        val widths = bandwidthsKhz.filter { it in 1 until UNAVAILABLE }
        val sa = displayRat == "NR_SA"
        val serving = cells.filter { it.serving }
        val nrCells = cells.filter { it.rat == "NR" }.sortedWith(compareByDescending<CellSample> { it.serving }.thenByDescending { it.rsrp ?: -999 })
        val lteCells = cells.filter { it.rat == "LTE" }
        val primaryCell = serving.firstOrNull()

        val used = mutableSetOf<CellSample>()
        val carriers = widths.mapIndexed { i, khz ->
            val mhz = khz / 1000.0
            val isNr = sa || khz > LTE_MAX_KHZ
            val match: CellSample? = when {
                i == 0 && primaryCell != null && (primaryCell.rat == "NR") == isNr -> primaryCell
                isNr -> nrCells.firstOrNull { it !in used }
                else -> lteCells.firstOrNull { it !in used && it !== primaryCell && it.bandwidthMhz == mhz }
                    ?: lteCells.firstOrNull { it !in used && it !== primaryCell && it.serving }
            }
            match?.let { used += it }
            CarrierSpec(
                index = i, rat = if (isNr) "NR" else "LTE", band = match?.band, arfcn = match?.earfcnOrArfcn, pci = match?.pci,
                bandwidthMhz = mhz, primary = i == 0, source = if (match != null) "bandwidth+cellinfo" else "bandwidth",
            )
        }
        val anyNr = carriers.any { it.rat == "NR" }
        val nrState = when {
            displayRat == "NR_ADVANCED" -> "nsa_advanced"
            displayRat == "NR_NSA" || displayRat == "NR_NSA_MMWAVE" -> "nsa"
            sa -> "sa"
            anyNr -> "nsa"
            else -> "none"
        }
        val rat = when {
            anyNr && (displayRat == "LTE" || displayRat == "LTE_CA" || displayRat == "LTE_ADV_PRO") -> "NR_NSA"
            else -> displayRat
        }
        val ca = buildJsonObject {
            put("active", widths.size > 1)
            put("cell_count", widths.size)
            put("aggregate_mhz", widths.sum() / 1000.0)
            putJsonArray("bandwidths_khz") { widths.forEach { add(it) } }
            put("rat", rat)
            putJsonArray("carriers") {
                carriers.forEach { c ->
                    addJsonObject {
                        put("rat", c.rat); put("band", c.band); put("arfcn", c.arfcn); put("pci", c.pci)
                        put("bandwidth_mhz", c.bandwidthMhz); put("primary", c.primary)
                    }
                }
            }
        }.toString()
        return RadioDerivation(rat, nrState, carriers, ca)
    }
}

/** Parsed radio.carrier_aggregation JSON for display (Mobile detail › Carriers). */
data class CarrierSet(val carriers: List<CarrierSpec>, val aggregateMhz: Double?, val active: Boolean) {
    companion object {
        val EMPTY = CarrierSet(emptyList(), null, false)

        /** Tolerant: missing fields → null; invalid JSON → [EMPTY]. Older rows without "carriers" give bandwidths only. */
        fun parse(json: String?): CarrierSet {
            if (json.isNullOrBlank()) return EMPTY
            val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return EMPTY
            fun JsonObject.p(k: String) = this[k] as? JsonPrimitive
            val list = (o["carriers"] as? JsonArray)?.mapIndexedNotNull { i, e ->
                val c = e as? JsonObject ?: return@mapIndexedNotNull null
                CarrierSpec(
                    index = i,
                    rat = c.p("rat")?.contentOrNull ?: "?",
                    band = c.p("band")?.contentOrNull,
                    arfcn = c.p("arfcn")?.intOrNull,
                    pci = c.p("pci")?.intOrNull,
                    bandwidthMhz = c.p("bandwidth_mhz")?.doubleOrNull,
                    primary = c.p("primary")?.booleanOrNull ?: (i == 0),
                    source = "json",
                )
            } ?: (o["bandwidths_khz"] as? JsonArray)?.mapIndexedNotNull { i, e ->
                val khz = (e as? JsonPrimitive)?.intOrNull ?: return@mapIndexedNotNull null
                CarrierSpec(i, if (khz > 20_000) "NR" else "?", null, null, null, khz / 1000.0, i == 0, "bandwidth")
            }.orEmpty()
            return CarrierSet(list, o.p("aggregate_mhz")?.doubleOrNull, o.p("active")?.booleanOrNull ?: (list.size > 1))
        }
    }
}
