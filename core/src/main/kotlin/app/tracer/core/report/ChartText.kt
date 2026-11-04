package app.tracer.core.report

/**
 * One-line reasons shown inside an empty chart frame (Moment, Diagnostics, PDF), chosen from what the
 * run actually recorded. null = the series has data in range (draw it).
 */
object ChartText {
    const val WIFI_NOT_TESTED = "Wi-Fi not tested in this run"
    const val WIFI_NO_READINGS = "No Wi-Fi readings: Wi-Fi was off or not connected"
    const val MOBILE_NOT_TESTED = "Mobile not tested in this run"
    const val MOBILE_NO_READINGS = "No mobile signal readings: phone access not allowed or no SIM"
    const val NR_NOT_REPORTED = "This phone doesn't report 5G signal for the NR leg"
    const val SINR_NOT_REPORTED = "This phone doesn't report SINR"
    const val NO_RESPONSE = "No response checks ran in this test"
    const val NOTHING_IN_WINDOW = "No readings in this window"

    private fun inRange(pts: List<Pt>, from: Double?, to: Double?) =
        if (from == null || to == null) pts.isNotEmpty() else pts.any { it.tS in from..to }

    /** Wi-Fi RSSI chart; [from]/[to] = window in seconds (Moment ±30 s), null = whole test. */
    fun wifi(r: TestReport, from: Double? = null, to: Double? = null): String? = when {
        inRange(r.wifiRssi, from, to) -> null
        r.wifiRssi.isEmpty() && !r.wifiTested -> WIFI_NOT_TESTED
        r.wifiRssi.isEmpty() -> WIFI_NO_READINGS
        else -> NOTHING_IN_WINDOW
    }

    /** Mobile RSRP chart. Signal is logged whenever a SIM is readable, even when mobile data carried no traffic. */
    fun mobile(r: TestReport, from: Double? = null, to: Double? = null): String? = when {
        inRange(r.rsrpSeries, from, to) -> null
        r.rsrpSeries.isEmpty() && !r.mobileTested -> MOBILE_NOT_TESTED
        r.rsrpSeries.isEmpty() -> MOBILE_NO_READINGS
        else -> NOTHING_IN_WINDOW
    }

    /** Note under the mobile chart when 5G was seen (NSA) but the phone gave no NR-leg RSRP; null otherwise. */
    fun nr(r: TestReport): String? = if (r.nrSeen && r.nrRsrpSeries.isEmpty() && r.rsrpSeries.isNotEmpty()) NR_NOT_REPORTED else null

    fun sinr(r: TestReport, from: Double? = null, to: Double? = null): String? = when {
        inRange(r.sinrSeries, from, to) -> null
        r.rsrpSeries.isEmpty() -> mobile(r, from, to)
        r.sinrSeries.isEmpty() -> SINR_NOT_REPORTED
        else -> NOTHING_IN_WINDOW
    }

    fun response(r: TestReport, from: Double? = null, to: Double? = null): String? = when {
        inRange(r.responseWifi, from, to) || inRange(r.responseMobile, from, to) -> null
        r.responseWifi.isEmpty() && r.responseMobile.isEmpty() -> NO_RESPONSE
        else -> NOTHING_IN_WINDOW
    }

    /** Seconds offsets where the carrying network changed (Wi-Fi ↔ mobile ↔ offline). */
    fun switchTimes(r: TestReport): List<Double> =
        r.segments.zipWithNext().filter { (a, b) -> a.carrier != b.carrier }.map { (_, b) -> b.fromS }
}
