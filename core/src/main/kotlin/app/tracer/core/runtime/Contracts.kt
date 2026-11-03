package app.tracer.core.runtime

import app.tracer.core.config.RunSettings
import app.tracer.core.export.Bundle
import app.tracer.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream

/*
 * Cross-module contracts. Pure Kotlin so every Android module codes against the same API and the
 * :app DI graph only wires interfaces:
 *   :collectors, :probes  → contribute `Collector`s via Hilt @IntoSet
 *   :storage              → binds RunRepository + SampleSink
 *   :export               → binds BundleService
 *   :pairing              → binds PairingService
 *   :app                  → implements RunController (RunService) and owns the EventEngine
 */

/** Live signals the RunService publishes so collectors can adapt (probes re-test on RAT change, etc). */
class RunSignals {
    val rat = MutableStateFlow<String?>(null)
    val primaryInterface = MutableStateFlow<String?>(null)
    /** true while recent latency probes are failing/spiking — adaptive probing tightens its interval */
    val degraded = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
}

data class CollectorContext(
    val runId: String,
    val settings: RunSettings,
    val clock: TracerClock,
    val signals: RunSignals,
    val status: CollectorStatusBoard,
)

/**
 * A source of samples. [samples] is cold: collection starts the underlying callbacks, cancellation
 * unregisters them. Must never throw for expected conditions (permission missing, feature
 * unsupported) — report via [CollectorStatusBoard] and complete or idle instead.
 */
interface Collector {
    /** location | gnss | cellular | wifi | wifi_scan | wifi_rtt | network_path | motion | device | probe_latency | probe_throughput | probe_public_ip | probe_captive | probe_internal */
    val id: String
    /** Expected max silence before the watchdog restarts this collector; null = no watchdog. */
    val watchdogMs: Long? get() = null
    fun samples(ctx: CollectorContext): Flow<Sample>
}

/** DISABLED = switched off by the user in Tracer settings: expected, never shown as a problem. */
enum class CollectorHealth { OK, DEGRADED, UNAVAILABLE, STOPPED, DISABLED }

/** Where a banner's "Fix" button should take the user. null = nothing the user can do (hardware/OS limit). */
enum class FixAction {
    /** Android › Apps › Tracer › Permissions */
    APP_PERMISSIONS,
    /** Android Location settings */
    LOCATION_SETTINGS,
    /** Android Wi-Fi settings */
    WIFI_SETTINGS,
    /** Developer options (Wi-Fi scan throttling) */
    DEVELOPER_OPTIONS,
    /** Tracer's own settings screen */
    TRACER_SETTINGS,
}

data class CollectorStatus(
    val id: String,
    val health: CollectorHealth = CollectorHealth.STOPPED,
    val lastSampleMonoNs: Long? = null,
    val samples: Long = 0,
    /** user-facing banner text, e.g. "Wi-Fi scans throttled — turn off Developer options › Wi-Fi scan throttling" */
    val message: String? = null,
    val restarts: Int = 0,
    val fix: FixAction? = null,
)

/** Shared health board; collectors report, UI shows banners, watchdog reads. */
class CollectorStatusBoard {
    private val _state = MutableStateFlow<Map<String, CollectorStatus>>(emptyMap())
    val state: StateFlow<Map<String, CollectorStatus>> = _state.asStateFlow()

    fun update(id: String, f: (CollectorStatus) -> CollectorStatus) =
        _state.update { m -> m + (id to f(m[id] ?: CollectorStatus(id))) }

    fun sample(id: String, monoNs: Long) = update(id) { it.copy(health = if (it.health == CollectorHealth.DEGRADED) it.health else CollectorHealth.OK, lastSampleMonoNs = monoNs, samples = it.samples + 1) }
    fun degraded(id: String, message: String, fix: FixAction? = null) = update(id) { it.copy(health = CollectorHealth.DEGRADED, message = message, fix = fix) }
    fun unavailable(id: String, message: String, fix: FixAction? = null) = update(id) { it.copy(health = CollectorHealth.UNAVAILABLE, message = message, fix = fix) }
    /** The user turned this collector off in Tracer settings — informational only. */
    fun disabled(id: String, message: String) = update(id) { it.copy(health = CollectorHealth.DISABLED, message = message, fix = null) }
    fun ok(id: String) = update(id) { it.copy(health = CollectorHealth.OK, message = null, fix = null) }
    fun reset() { _state.value = emptyMap() }
}

/** The single writer. Implementations batch (flush every 1 s or 200 rows) on one DB connection. */
interface SampleSink {
    /** Suspends only if the buffer is full (backpressure); never drops silently. */
    suspend fun emit(sample: Sample)
    /** Force-flush pending rows (pause/stop/onTrimMemory). */
    suspend fun flush()
    /** Rows written so far for [runId], by table — feeds completeness + notification. */
    fun counts(runId: String): StateFlow<Map<Table, Long>>
}

data class RunListItem(val run: RunRecord, val eventCount: Int, val completenessBadge: String?, val hasLinkedImport: Boolean)

interface RunRepository {
    suspend fun createRun(run: RunRecord)
    suspend fun updateRun(run: RunRecord)
    suspend fun getRun(runId: String): RunRecord?
    fun observeRuns(): Flow<List<RunListItem>>
    /** The run that was RECORDING when the process died (ended_at null), if any. */
    suspend fun unfinishedRun(): RunRecord?
    suspend fun deleteRun(runId: String)
    /** Ordered by t_mono_ns. For large tables prefer [stream]. */
    suspend fun samples(runId: String, table: Table, fromMonoNs: Long? = null, toMonoNs: Long? = null): List<Sample>
    fun stream(runId: String, table: Table): Flow<Sample>
    suspend fun counts(runId: String): Map<Table, Long>
    /** rowid → event, for labels and the event detail sheet. */
    suspend fun events(runId: String): List<Pair<Long, EventSample>>
    suspend fun setEventLabel(runId: String, eventId: Long, label: String)
    suspend fun eventLabels(runId: String): Map<Long, String>
    /** Replace events of a run (re-running rules with new thresholds in Replay). */
    suspend fun replaceEvents(runId: String, events: List<EventSample>)
    /** Persist an imported (typically iOS) bundle as a read-only run. */
    suspend fun importBundle(bundle: Bundle.ImportedBundle): RunRecord
    /** Absolute path of the live SQLite file (for export ATTACH). */
    fun databaseFile(): File
    suspend fun deleteOlderThan(cutoffUtcMs: Long): Int
    /** max(test_number) + 1 for runs at [site] (case-insensitive), 1 if none. */
    suspend fun nextTestNumber(site: String): Int
}

/** :export. */
interface BundleService {
    /** Builds `<name>_<runId>.zip` in the app cache share dir; returns the file. */
    suspend fun export(runId: String, onProgress: (Float) -> Unit = {}): File
    suspend fun import(input: InputStream): Bundle.ImportedBundle
    /** Optional HTTPS upload (multipart, settings.uploadUrl). Returns HTTP status. */
    suspend fun upload(file: File, url: String): Int
    /** FileProvider authority used for share intents: "${applicationId}.exports" */
    val fileProviderAuthority: String
}

/** :pairing. */
interface PairingService {
    fun payloadFor(run: RunRecord, gpsOffsetMs: Long?): String
    /** Called with QR text scanned on this device; stores linked_run_id on the current run. */
    suspend fun acceptScanned(currentRunId: String, qrText: String): Result<String>
    /** Overlay helper: aligned samples of [linkedRunId] mapped onto [runId]'s wall clock. */
    suspend fun alignedOverlay(runId: String, linkedRunId: String, table: Table): List<Sample>
    /** v2 stub: live status over the local network. */
    val live: LiveLink
}

interface LiveLink {
    val connected: StateFlow<Boolean>
    suspend fun advertise(runId: String) {}
    suspend fun stop() {}
}

/** RunService exposes this through a bound-service binder (and a Hilt singleton facade). */
interface RunController {
    val state: StateFlow<RunState>
    val live: StateFlow<LiveSnapshot>
    val runId: StateFlow<String?>
    /**
     * Recorded-time budget of the current run in ms (protocol duration or the length limit passed to
     * [start]); null = open-ended. Default: always null (implementations without auto-stop).
     */
    val limitMs: StateFlow<Long?> get() = NO_LIMIT
    /**
     * @param site where the run is recorded ("Office"); the service assigns the next test number for the
     *        site and names the run "Office · T3 · 2026-11-03 18:31" (falls back to [name] when null).
     * @param protocolId a [app.tracer.core.analysis.Protocol] id: fixed settings + fixed duration (auto-stop);
     *        [settings] is ignored when set.
     * @param durationLimitMs auto-stop after this much recorded (un-paused) time; null = no limit.
     *        A protocol's own duration wins when both are set.
     */
    fun start(
        name: String, mode: String, settings: RunSettings, simulateScenario: String? = null, site: String? = null, protocolId: String? = null,
        durationLimitMs: Long? = null,
    )
    fun pause()
    fun resume()
    fun stop()
    fun markMoment(text: String?, audioPath: String? = null)
    /** Tap-to-mark: [tappedAt] is captured at the tap; the label may be added afterwards (null = unlabeled). */
    fun markWaypoint(label: String?, tappedAt: Stamp)
}

private val NO_LIMIT: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()

/** :collectors. Active SIMs for pre-flight + per-subscription TelephonyManager. */
data class SimInfo(val subscriptionId: Int, val slotIndex: Int, val carrierName: String?, val mccMnc: String?, val isDefaultData: Boolean, val isEmbedded: Boolean)

interface DeviceInfoProvider {
    fun activeSims(): List<SimInfo>
    /** null = unknown (Android exposes no API; inferred from recent failed startScan calls). */
    fun wifiScanThrottled(): Boolean?
    fun isIgnoringBatteryOptimizations(): Boolean
    fun freeStorageBytes(): Long
    fun deviceModel(): String
    fun osVersion(): String
    fun appVersion(): String
}

/** :probes. OpenCelliD lookup with on-disk cache; null if no key/offline DB or not found. */
data class TowerLocation(val lat: Double, val lon: Double, val rangeM: Double?, val source: String)

interface TowerLookup {
    suspend fun locate(rat: String, mcc: String, mnc: String, tac: Int, cellId: Long): TowerLocation?
}

/** :probes. SNTP check for runs.ntp_offset_ms (server − device wall clock, ms). */
interface NtpClient {
    suspend fun offsetMs(host: String = "time.google.com", timeoutMs: Int = 3_000): Long?
}
