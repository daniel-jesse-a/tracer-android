package app.tracer.collectors

/*
 * Pure mapping functions (no Android imports) so they are unit-testable on the JVM.
 * Integer constants mirror the documented Android framework values; each is named after
 * the framework constant it stands for.
 */

/** TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_* */
internal object OverrideType {
    const val NONE = 0
    const val LTE_CA = 1
    const val LTE_ADVANCED_PRO = 2
    const val NR_NSA = 3
    /** Deprecated OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE; still reported by some modems. */
    const val NR_NSA_MMWAVE = 4
    const val NR_ADVANCED = 5
}

/** TelephonyManager.NETWORK_TYPE_* */
internal object NetType {
    const val UNKNOWN = 0
    const val GPRS = 1
    const val EDGE = 2
    const val UMTS = 3
    const val HSDPA = 8
    const val HSUPA = 9
    const val HSPA = 10
    const val LTE = 13
    const val HSPAP = 15
    const val GSM = 16
    const val TD_SCDMA = 17
    const val IWLAN = 18
    /** Hidden NETWORK_TYPE_LTE_CA; some OEM builds still surface it. */
    const val LTE_CA = 19
    const val NR = 20
}

/**
 * radio.rat from TelephonyDisplayInfo (override type first, then network type).
 * Returns one of NONE | GSM | UMTS | HSPA | LTE | LTE_CA | LTE_ADV_PRO | NR_NSA | NR_NSA_MMWAVE |
 * NR_ADVANCED | NR_SA | IWLAN | UNKNOWN.
 */
fun ratOf(overrideNetworkType: Int, networkType: Int): String = when (overrideNetworkType) {
    OverrideType.NR_NSA -> "NR_NSA"
    OverrideType.NR_NSA_MMWAVE -> "NR_NSA_MMWAVE"
    OverrideType.NR_ADVANCED -> "NR_ADVANCED"
    OverrideType.LTE_CA -> "LTE_CA"
    OverrideType.LTE_ADVANCED_PRO -> "LTE_ADV_PRO"
    else -> when (networkType) {
        NetType.NR -> "NR_SA"
        NetType.LTE -> "LTE"
        NetType.LTE_CA -> "LTE_CA"
        NetType.HSPA, NetType.HSPAP, NetType.HSDPA, NetType.HSUPA -> "HSPA"
        NetType.UMTS, NetType.TD_SCDMA -> "UMTS"
        NetType.GSM, NetType.EDGE, NetType.GPRS -> "GSM"
        NetType.IWLAN -> "IWLAN"
        NetType.UNKNOWN -> "NONE"
        else -> "UNKNOWN"
    }
}

private val NR_STATE_RE = Regex("""nrState=(\w+)""")
private val NR_STATE_RANK = listOf("none", "restricted", "not_restricted", "connected")

/**
 * Parses the hidden NR state out of ServiceState.toString() (one `nrState=` per
 * NetworkRegistrationInfo). The CS-domain entry is usually NONE, so the most advanced state
 * across all entries wins. Returns none | restricted | not_restricted | connected, or null.
 */
fun parseNrState(serviceStateText: String?): String? {
    if (serviceStateText == null) return null
    return NR_STATE_RE.findAll(serviceStateText)
        .map { it.groupValues[1].lowercase() }
        .filter { it in NR_STATE_RANK }
        .maxByOrNull { NR_STATE_RANK.indexOf(it) }
}

/** WifiInfo.SECURITY_TYPE_* (API 31) → OPEN | WEP | WPA2 | WPA3 | OWE | ENTERPRISE | UNKNOWN. */
fun securityName(securityType: Int): String = when (securityType) {
    0 -> "OPEN"            // SECURITY_TYPE_OPEN
    1 -> "WEP"             // SECURITY_TYPE_WEP
    2 -> "WPA2"            // SECURITY_TYPE_PSK
    4 -> "WPA3"            // SECURITY_TYPE_SAE
    6 -> "OWE"             // SECURITY_TYPE_OWE
    3, 5, 9, 10, 11, 12 -> "ENTERPRISE" // EAP, EAP_WPA3_ENTERPRISE_192_BIT, EAP_WPA3_ENTERPRISE, OSEN, PASSPOINT_R1_R2, PASSPOINT_R3
    else -> "UNKNOWN"      // UNKNOWN(-1), WAPI_PSK(7), WAPI_CERT(8), DPP(13), future values
}

/** Strips surrounding quotes from WifiInfo.getSSID()/WifiSsid.toString(); "<unknown ssid>" and blanks → null. */
fun unquoteSsid(raw: String?): String? {
    if (raw == null) return null
    val s = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
    return s.takeUnless { it.isEmpty() || s == "<unknown ssid>" }
}

/** Redacted / placeholder BSSIDs → null. */
fun normalizeBssid(raw: String?): String? =
    raw?.lowercase()?.takeUnless { it.isBlank() || it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }

/** network_path.link_quality from LinkCapabilities downstream bandwidth estimate. */
fun linkQualityOf(downstreamKbps: Int?): String = when {
    downstreamKbps == null -> "unknown"
    downstreamKbps >= 20_000 -> "good"
    downstreamKbps >= 2_000 -> "moderate"
    downstreamKbps > 0 -> "minimal"
    else -> "unknown"
}

/**
 * network_path.primary_interface: the underlying (non-VPN) transport when present, "vpn" only
 * when VPN is the sole transport, "none" when empty.
 */
fun primaryInterfaceOf(transports: List<String>): String {
    if (transports.isEmpty()) return "none"
    val order = listOf("wifi", "cellular", "ethernet", "bluetooth")
    order.firstOrNull { it in transports }?.let { return it }
    val nonVpn = transports.filter { it != "vpn" }
    return if (nonVpn.isEmpty()) "vpn" else "other"
}

/** GnssStatus.CONSTELLATION_* → name. */
fun constellationName(type: Int): String = when (type) {
    1 -> "GPS"
    2 -> "SBAS"
    3 -> "GLONASS"
    4 -> "QZSS"
    5 -> "BEIDOU"
    6 -> "GALILEO"
    7 -> "IRNSS"
    else -> "UNKNOWN"
}

/** PowerManager.THERMAL_STATUS_* → device.thermal_state. */
fun thermalName(status: Int): String = when (status) {
    0 -> "nominal"
    1 -> "light"
    2 -> "moderate"
    3 -> "severe"
    4 -> "critical"
    5 -> "emergency"
    6 -> "shutdown"
    else -> "nominal"
}

/** DetectedActivity.* → motion.activity. */
fun activityName(type: Int): String = when (type) {
    0 -> "in_vehicle"
    1 -> "on_bicycle"
    2 -> "walking"   // ON_FOOT
    3 -> "still"
    5 -> "tilting"
    7 -> "walking"
    8 -> "running"
    else -> "unknown"
}

/**
 * Centre frequency (MHz) for a channel on an MloLink band (WifiScanner.WIFI_BAND_*: 1=2.4 GHz,
 * 2=5 GHz, 8=6 GHz). Returns null when unknown.
 */
fun mloFrequencyMhz(band: Int, channel: Int): Int? = when (band) {
    1 -> if (channel == 14) 2484 else if (channel in 1..13) 2407 + 5 * channel else null
    2 -> if (channel in 1..196) 5000 + 5 * channel else null
    8 -> if (channel == 2) 5935 else if (channel in 1..233) 5950 + 5 * channel else null
    else -> null
}

/** MloLink band → "2.4" | "5" | "6" | "60" | null. */
fun mloBandName(band: Int): String? = when (band) {
    1 -> "2.4"; 2 -> "5"; 8 -> "6"; 16 -> "60"; else -> null
}
