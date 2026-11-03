package app.tracer.ui.companion

import android.os.Build
import app.tracer.core.model.RunRecord
import kotlin.math.abs

/** A phone that has recorded alongside this one (from imported / linked runs). */
data class CompanionDevice(
    /** grouping key: platform + model, lower-case */
    val key: String,
    val name: String,
    /** android | ios | other */
    val platform: String,
    /** this phone's run linked to the companion's latest run (opens its summary), or the companion run itself */
    val openRunId: String?,
    /** |GPS offset here − GPS offset there| of the latest linked pair, ms */
    val gapMs: Long?,
    val lastSeenAt: Long,
)

object CompanionDevices {
    /** Runs recorded on this phone carry Build.MODEL (RunService) and platform "android". */
    fun isThisDevice(r: RunRecord): Boolean {
        val platformOk = r.devicePlatform.isBlank() || r.devicePlatform.equals("android", ignoreCase = true)
        val modelOk = r.deviceModel.isBlank() || r.deviceModel == Build.MODEL || r.deviceModel.endsWith(Build.MODEL)
        return platformOk && modelOk
    }

    fun displayName(r: RunRecord): String = r.deviceModel.ifBlank { if (r.devicePlatform.equals("ios", true)) "iPhone" else "Phone" }

    /** Companion devices seen in [runs], newest first. */
    fun from(runs: List<RunRecord>): List<CompanionDevice> {
        val byId = runs.associateBy { it.runId }
        return runs.filter { !isThisDevice(it) }
            .groupBy { "${it.devicePlatform.lowercase()}|${displayName(it).lowercase()}" }
            .map { (key, list) ->
                val latest = list.maxBy { it.startedAt }
                val local = runs.firstOrNull { it.linkedRunId == latest.runId && isThisDevice(it) }
                    ?: latest.linkedRunId?.let { byId[it] }?.takeIf { isThisDevice(it) }
                val theirs = latest.gpsOffsetMsStart
                val ours = local?.gpsOffsetMsStart
                CompanionDevice(
                    key = key,
                    name = displayName(latest),
                    platform = latest.devicePlatform.lowercase().ifBlank { "other" },
                    openRunId = local?.runId ?: latest.runId,
                    gapMs = if (theirs != null && ours != null) abs(theirs - ours) else null,
                    lastSeenAt = latest.startedAt,
                )
            }
            .sortedByDescending { it.lastSeenAt }
    }
}
