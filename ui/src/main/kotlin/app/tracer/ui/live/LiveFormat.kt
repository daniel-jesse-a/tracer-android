package app.tracer.ui.live

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Number formatting for the live/analysis screens. Pure; "—" for missing values, U+2212 minus. */
internal object Fmt {
    const val DASH = "—"
    private const val MINUS = '−'

    private fun minus(s: String): String = s.replace('-', MINUS)

    fun int(v: Int?): String = v?.let { minus(it.toString()) } ?: DASH
    fun long(v: Long?): String = v?.let { minus(it.toString()) } ?: DASH
    fun d0(v: Double?): String = v?.takeIf { !it.isNaN() }?.let { minus(it.roundToLong().toString()) } ?: DASH
    fun d1(v: Double?): String = v?.takeIf { !it.isNaN() }?.let { minus(String.format(Locale.US, "%.1f", it)) } ?: DASH
    fun pct(fraction: Double?): String = fraction?.takeIf { !it.isNaN() }?.let { (it * 100).roundToInt().toString() } ?: DASH

    /** 0:42 · 12:05 · 1:02:33 */
    fun clock(ms: Long): String {
        val totalS = (ms.coerceAtLeast(0) / 1000)
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 00:42:17 */
    fun hms(ms: Long): String {
        val totalS = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%02d:%02d:%02d", totalS / 3600, (totalS % 3600) / 60, totalS % 60)
    }

    private val TS_MS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    /** Wall-clock HH:MM:SS.mmm (detail rows) in the device zone. */
    fun timestampMs(utcMs: Long): String = TS_MS.format(Instant.ofEpochMilli(utcMs).atZone(ZoneId.systemDefault()))

    /** Wall-clock HH:MM:SS (lists). */
    fun timestamp(utcMs: Long): String = TS.format(Instant.ofEpochMilli(utcMs).atZone(ZoneId.systemDefault()))

    /** Signed ms, e.g. "+350" / "−20". */
    fun signedMs(ms: Long): String = (if (ms >= 0) "+" else MINUS.toString()) + abs(ms)

    /** Signed dB, e.g. "+2". */
    fun signedDb(db: Int): String = (if (db >= 0) "+" else MINUS.toString()) + abs(db)

    /** 1.2 km / 850 m */
    fun distance(m: Double?): String = when {
        m == null -> DASH
        m >= 1000 -> String.format(Locale.US, "%.1f km", m / 1000)
        else -> "${m.roundToInt()} m"
    }

    /** "22 s ago" / "4 m ago" style ages (seconds or minutes). */
    fun ageSeconds(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} m"

    /** Device clock − GPS time in ms, given GPS − wall clock (LiveSnapshot.gpsOffsetMs). */
    fun clockVsGps(gpsMinusWallMs: Long): Long = -gpsMinusWallMs

    /** 0x1A3F0C2 */
    fun hex(v: Long?): String = v?.let { "0x" + java.lang.Long.toHexString(it).uppercase(Locale.US) } ?: DASH
}
