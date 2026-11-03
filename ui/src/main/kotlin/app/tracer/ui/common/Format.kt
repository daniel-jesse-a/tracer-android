package app.tracer.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Placeholder for a missing value — shared by every formatter. */
const val NO_VALUE = "—"

/** 3 723 000 ms → "01:02:03"; ≥ 100 h keeps growing ("123:00:00"). Negative → 00:00:00. */
fun formatDuration(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}

/** −85 → "−85" (true minus sign for typographic alignment); null → "—". Unit not included. */
fun formatDbm(dbm: Int?): String = when {
    dbm == null -> NO_VALUE
    dbm < 0 -> "−${-dbm}"
    else -> dbm.toString()
}

/** 42.4 → "42"; 3.25 → "3.3" (< 10 ms keeps one decimal); null → "—". Unit not included. */
fun formatMs(ms: Double?): String = when {
    ms == null || ms.isNaN() -> NO_VALUE
    ms < 10 -> String.format(Locale.ROOT, "%.1f", ms)
    else -> ms.roundToLong().toString()
}

/** Percent with no decimals; null → "—". */
fun formatPct(pct: Double?): String = if (pct == null || pct.isNaN()) NO_VALUE else "${pct.roundToInt()}"

/** 1 536 → "1.5 KB" (binary units). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.ROOT, "%.1f %s", v, units[i])
}

/** UTC epoch ms → local "yyyy-MM-dd HH:mm". */
fun formatDateTime(utcMs: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(utcMs))
