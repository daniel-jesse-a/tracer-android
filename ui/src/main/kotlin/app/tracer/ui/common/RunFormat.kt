package app.tracer.ui.common

import app.tracer.core.model.RunRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * Completeness badges GPS · NET · PRB · RF · GNSS from runs.completeness_json "collectors" ratios:
 * GPS ← location, NET ← network_path, PRB ← latency_probes, RF ← cell_serving_polls, GNSS ← gnss_epochs.
 * Ratio ≥ 0.5 → captured ✓, below → missing ✕, key absent → n/a, run still recording → pending ….
 */
fun completenessBadges(run: RunRecord): List<Pair<String, BadgeState>> {
    val keys = listOf("GPS" to "location", "NET" to "network_path", "PRB" to "latency_probes", "RF" to "cell_serving_polls", "GNSS" to "gnss_epochs")
    if (run.endedAt == null) return keys.map { it.first to BadgeState.PENDING }
    val collectors = run.completenessJson
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject["collectors"] as? JsonObject }.getOrNull() }
    return keys.map { (label, key) ->
        val ratio = (collectors?.get(key) as? JsonPrimitive)?.doubleOrNull
        label to when {
            ratio == null -> BadgeState.NOT_APPLICABLE
            ratio >= 0.5 -> BadgeState.CAPTURED
            else -> BadgeState.MISSING
        }
    }
}

/** Mode tag for Mono meta lines: rail → TRAIN, drive → CAR, … */
fun modeTag(mode: String): String = when (mode) {
    "rail" -> "TRAIN"
    "drive" -> "CAR"
    "walk" -> "WALK"
    "static" -> "STATIC"
    "custom" -> "CUSTOM"
    "simulated" -> "SIM"
    else -> mode.uppercase(Locale.ROOT)
}

/** 4 062 000 ms → "1:07:42"; < 1 h → "0:22:30". */
fun formatClock(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
}

/** 98 600 m → "98.6 km"; 850 m → "0.9 km". */
fun formatKm(m: Double?): String = if (m == null) NO_VALUE else String.format(Locale.ROOT, "%.1f km", m / 1000.0)
