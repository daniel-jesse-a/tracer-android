package app.tracer.core.model

/** Row in the `runs` table. Timestamps are UTC epoch ms. */
data class RunRecord(
    val runId: String,
    val name: String,
    /** walk | drive (Car) | rail (Train) | custom | static | simulated */
    val mode: String,
    /** android | ios */
    val devicePlatform: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val linkedRunId: String? = null,
    val settingsJson: String? = null,
    val gpsOffsetMsStart: Long? = null,
    val gpsOffsetMsEnd: Long? = null,
    val ntpOffsetMs: Long? = null,
    val completenessJson: String? = null,
    // runs also carries the common triple for uniformity with every other table.
    val tUtcMs: Long = startedAt,
    val tMonoNs: Long = 0L,
    // v2
    /** Site the run was recorded at, e.g. "Office" */
    val site: String? = null,
    /** 1-based test number per site */
    val testNumber: Int? = null,
    /** Protocol id (e.g. "walk10-dual") or null for a free run */
    val protocol: String? = null,
    /** IANA zone of the device at start, e.g. "Asia/Dubai" */
    val timeZone: String? = null,
    /** Method fingerprint (see core/analysis/Method.kt) */
    val methodFingerprint: String? = null,
) {
    companion object {
        /** "Office · T3 · 2026-11-03 18:31" */
        fun displayName(site: String, testNumber: Int, localStart: String): String = "$site · T$testNumber · $localStart"
        /** "office_t03_20261103-1831" — file-name safe */
        fun fileStem(site: String?, testNumber: Int?, localStartCompact: String): String {
            val s = (site ?: "run").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "run" }
            return if (testNumber != null) "%s_t%02d_%s".format(s, testNumber, localStartCompact) else "%s_%s".format(s, localStartCompact)
        }
    }
}

enum class RunState { IDLE, PREFLIGHT, RECORDING, PAUSED, STOPPING }
