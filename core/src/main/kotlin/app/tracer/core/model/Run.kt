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
)

enum class RunState { IDLE, PREFLIGHT, RECORDING, PAUSED, STOPPING }
