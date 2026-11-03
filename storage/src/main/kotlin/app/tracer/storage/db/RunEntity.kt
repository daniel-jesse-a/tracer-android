package app.tracer.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.tracer.core.model.RunRecord

/**
 * Room mapping of the canonical `runs` table. Field declaration order is the column order Room
 * generates, and it MUST equal `Schema.RUNS.columnNames` (verified by the schema parity test).
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "t_utc_ms") val tUtcMs: Long,
    @ColumnInfo(name = "t_mono_ns") val tMonoNs: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "device_platform") val devicePlatform: String,
    @ColumnInfo(name = "device_model") val deviceModel: String,
    @ColumnInfo(name = "os_version") val osVersion: String,
    @ColumnInfo(name = "app_version") val appVersion: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "linked_run_id") val linkedRunId: String?,
    @ColumnInfo(name = "settings_json") val settingsJson: String?,
    @ColumnInfo(name = "gps_offset_ms_start") val gpsOffsetMsStart: Long?,
    @ColumnInfo(name = "gps_offset_ms_end") val gpsOffsetMsEnd: Long?,
    @ColumnInfo(name = "ntp_offset_ms") val ntpOffsetMs: Long?,
    @ColumnInfo(name = "completeness_json") val completenessJson: String?,
    // v2 (nullable, appended in Schema.RUNS order; added to existing databases by TracerDatabase.MIGRATION_1_2)
    @ColumnInfo(name = "site") val site: String? = null,
    @ColumnInfo(name = "test_number") val testNumber: Int? = null,
    @ColumnInfo(name = "protocol") val protocol: String? = null,
    @ColumnInfo(name = "time_zone") val timeZone: String? = null,
    @ColumnInfo(name = "method_fingerprint") val methodFingerprint: String? = null,
) {
    fun toRecord(): RunRecord = RunRecord(
        runId = runId,
        name = name,
        mode = mode,
        devicePlatform = devicePlatform,
        deviceModel = deviceModel,
        osVersion = osVersion,
        appVersion = appVersion,
        startedAt = startedAt,
        endedAt = endedAt,
        linkedRunId = linkedRunId,
        settingsJson = settingsJson,
        gpsOffsetMsStart = gpsOffsetMsStart,
        gpsOffsetMsEnd = gpsOffsetMsEnd,
        ntpOffsetMs = ntpOffsetMs,
        completenessJson = completenessJson,
        tUtcMs = tUtcMs,
        tMonoNs = tMonoNs,
        site = site,
        testNumber = testNumber,
        protocol = protocol,
        timeZone = timeZone,
        methodFingerprint = methodFingerprint,
    )

    companion object {
        fun from(r: RunRecord): RunEntity = RunEntity(
            runId = r.runId,
            tUtcMs = r.tUtcMs,
            tMonoNs = r.tMonoNs,
            name = r.name,
            mode = r.mode,
            devicePlatform = r.devicePlatform,
            deviceModel = r.deviceModel,
            osVersion = r.osVersion,
            appVersion = r.appVersion,
            startedAt = r.startedAt,
            endedAt = r.endedAt,
            linkedRunId = r.linkedRunId,
            settingsJson = r.settingsJson,
            gpsOffsetMsStart = r.gpsOffsetMsStart,
            gpsOffsetMsEnd = r.gpsOffsetMsEnd,
            ntpOffsetMs = r.ntpOffsetMs,
            completenessJson = r.completenessJson,
            site = r.site,
            testNumber = r.testNumber,
            protocol = r.protocol,
            timeZone = r.timeZone,
            methodFingerprint = r.methodFingerprint,
        )
    }
}
