package app.tracer.run

import android.content.Context
import android.content.SharedPreferences

/**
 * Restart-safety record in SharedPreferences("run_state"), rewritten every 5 s while a run is
 * active and cleared on a clean stop. After process death it tells the next RunService instance
 * which run to resume and how long the recording gap was.
 */
internal class RunHeartbeat(context: Context) {

    data class Snapshot(
        val runId: String,
        val settingsJson: String?,
        val scenario: String?,
        val lastAliveUtcMs: Long,
        val lastAliveMonoNs: Long,
        val startedMonoNs: Long,
        val paused: Boolean,
        val pausedAtUtcMs: Long,
        val pausedAtMonoNs: Long,
        val pausedMsTotal: Long,
        val gapMsTotal: Long,
        /** Protocol run: its id, so a resumed service keeps the fixed duration + auto-stop. */
        val protocolId: String? = null,
        /**
         * Projected wall-clock end of the protocol run (start + duration + pauses/gaps so far),
         * rewritten with every heartbeat; 0 = not a protocol run. Auto-stop itself is decided on
         * recorded (un-paused) time, so a pause or a process restart never shortens the protocol.
         */
        val protocolEndUtcMs: Long = 0L,
    )

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasActiveRun(): Boolean = prefs.getString(K_RUN_ID, null) != null

    fun write(s: Snapshot) {
        prefs.edit()
            .putString(K_RUN_ID, s.runId)
            .putString(K_SETTINGS, s.settingsJson)
            .putString(K_SCENARIO, s.scenario)
            .putLong(K_ALIVE_UTC, s.lastAliveUtcMs)
            .putLong(K_ALIVE_MONO, s.lastAliveMonoNs)
            .putLong(K_STARTED_MONO, s.startedMonoNs)
            .putBoolean(K_PAUSED, s.paused)
            .putLong(K_PAUSED_AT_UTC, s.pausedAtUtcMs)
            .putLong(K_PAUSED_AT_MONO, s.pausedAtMonoNs)
            .putLong(K_PAUSED_MS, s.pausedMsTotal)
            .putLong(K_GAP_MS, s.gapMsTotal)
            .putString(K_PROTOCOL, s.protocolId)
            .putLong(K_PROTOCOL_END_UTC, s.protocolEndUtcMs)
            .apply()
    }

    fun read(): Snapshot? {
        val runId = prefs.getString(K_RUN_ID, null) ?: return null
        return Snapshot(
            runId = runId,
            settingsJson = prefs.getString(K_SETTINGS, null),
            scenario = prefs.getString(K_SCENARIO, null),
            lastAliveUtcMs = prefs.getLong(K_ALIVE_UTC, 0L),
            lastAliveMonoNs = prefs.getLong(K_ALIVE_MONO, 0L),
            startedMonoNs = prefs.getLong(K_STARTED_MONO, 0L),
            paused = prefs.getBoolean(K_PAUSED, false),
            pausedAtUtcMs = prefs.getLong(K_PAUSED_AT_UTC, 0L),
            pausedAtMonoNs = prefs.getLong(K_PAUSED_AT_MONO, 0L),
            pausedMsTotal = prefs.getLong(K_PAUSED_MS, 0L),
            gapMsTotal = prefs.getLong(K_GAP_MS, 0L),
            protocolId = prefs.getString(K_PROTOCOL, null),
            protocolEndUtcMs = prefs.getLong(K_PROTOCOL_END_UTC, 0L),
        )
    }

    /** Synchronous so a clean stop is never mistaken for a crash if the process dies right after. */
    fun clear() { prefs.edit().clear().commit() }

    private companion object {
        const val PREFS = "run_state"
        const val K_RUN_ID = "run_id"
        const val K_SETTINGS = "settings_json"
        const val K_SCENARIO = "scenario"
        const val K_ALIVE_UTC = "last_alive_utc_ms"
        const val K_ALIVE_MONO = "last_alive_mono_ns"
        const val K_STARTED_MONO = "started_mono_ns"
        const val K_PAUSED = "paused"
        const val K_PAUSED_AT_UTC = "paused_at_utc_ms"
        const val K_PAUSED_AT_MONO = "paused_at_mono_ns"
        const val K_PAUSED_MS = "paused_ms_total"
        const val K_GAP_MS = "gap_ms_total"
        const val K_PROTOCOL = "protocol_id"
        const val K_PROTOCOL_END_UTC = "protocol_end_utc_ms"
    }
}
