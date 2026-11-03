package app.tracer.core.quality

/**
 * Five-step quality scale shared by map track colouring, tiles, charts and the notification.
 * Values match the Tracer design system (dark / light swatches; light hues darkened for 4.5:1).
 */
enum class QualityLevel(val label: String, val argb: Long, val argbLight: Long, val dotted: Boolean = false) {
    // Design system "Tracer · shared data language": ramp steps differ in hue AND lightness;
    // NONE/BAD ("No service" / timeout) always adds a dotted stroke or hatch so it never relies on hue.
    EXCELLENT("Excellent", 0xFF39C5BB, 0xFF0F9488),
    GOOD("Good", 0xFF3D8BFD, 0xFF1F6FE0),
    FAIR("Fair", 0xFFF5C542, 0xFFC99A00),
    POOR("Poor", 0xFFF28B30, 0xFFD9650F),
    BAD("No service", 0xFFE5487A, 0xFFC8195A, dotted = true),
    NONE("No data", 0xFF5A5A60, 0xFFA1A1A6, dotted = true);
}

/** Connection type palette (Okabe–Ito). Used for TYPE timeline bands and the RAT map layer. */
enum class ConnType(val label: String, val argb: Long, val argbLight: Long, val dotted: Boolean = false) {
    NR_SA("5G SA", 0xFF56B4E9, 0xFF0072B2),
    NR_NSA("5G NSA", 0xFFCC79A7, 0xFFA8558A),
    LTE("LTE", 0xFFE69F00, 0xFFC27C00),
    G3("3G", 0xFFF0E442, 0xFF9E9300),
    WIFI("Wi-Fi", 0xFF1FBF8F, 0xFF00845F),
    NONE("None", 0xFF5A5A60, 0xFFA1A1A6, dotted = true);

    companion object {
        /** From radio.rat (+ primary interface): Wi-Fi wins when it carries the default network. */
        fun of(rat: String?, primaryInterface: String? = null): ConnType = when {
            primaryInterface == "wifi" -> WIFI
            rat == null -> NONE
            rat == "NR_SA" -> NR_SA
            rat.startsWith("NR") -> NR_NSA
            rat.startsWith("LTE") || rat == "IWLAN" -> LTE
            rat in setOf("UMTS", "HSPA", "HSPAP", "TDSCDMA", "WCDMA", "GSM") -> G3
            else -> NONE
        }
    }
}

object QualityScale {
    /** RSRP > −80 / −80…−90 / −90…−100 / −100…−110 / < −110 dBm */
    fun rsrp(dbm: Int?): QualityLevel = when {
        dbm == null -> QualityLevel.NONE
        dbm > -80 -> QualityLevel.EXCELLENT
        dbm > -90 -> QualityLevel.GOOD
        dbm > -100 -> QualityLevel.FAIR
        dbm > -110 -> QualityLevel.POOR
        else -> QualityLevel.BAD
    }

    /** Latency < 40 / 40–100 / 100–250 / > 250 / timeout (null + failed). */
    fun latency(ms: Double?, failed: Boolean = false): QualityLevel = when {
        failed -> QualityLevel.BAD
        ms == null -> QualityLevel.NONE
        ms < 40 -> QualityLevel.EXCELLENT
        ms < 100 -> QualityLevel.GOOD
        ms < 250 -> QualityLevel.FAIR
        else -> QualityLevel.POOR
    }

    fun sinr(db: Int?): QualityLevel = when {
        db == null -> QualityLevel.NONE
        db >= 20 -> QualityLevel.EXCELLENT
        db >= 13 -> QualityLevel.GOOD
        db >= 0 -> QualityLevel.FAIR
        db >= -5 -> QualityLevel.POOR
        else -> QualityLevel.BAD
    }

    fun wifiRssi(dbm: Int?): QualityLevel = when {
        dbm == null -> QualityLevel.NONE
        dbm > -55 -> QualityLevel.EXCELLENT
        dbm > -65 -> QualityLevel.GOOD
        dbm > -72 -> QualityLevel.FAIR
        dbm > -80 -> QualityLevel.POOR
        else -> QualityLevel.BAD
    }

    fun cn0(dbHz: Double?): QualityLevel = when {
        dbHz == null -> QualityLevel.NONE
        dbHz >= 40 -> QualityLevel.EXCELLENT
        dbHz >= 33 -> QualityLevel.GOOD
        dbHz >= 25 -> QualityLevel.FAIR
        dbHz >= 18 -> QualityLevel.POOR
        else -> QualityLevel.BAD
    }
}

/** Map track colouring layers selectable in Live Run and Replay. */
enum class QualityLayer { RSRP, SINR, LATENCY, WIFI_RSSI, RAT, SATELLITES }
