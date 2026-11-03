package app.tracer.run

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.tracer.BuildConfig
import app.tracer.R
import app.tracer.core.analysis.Completeness
import app.tracer.core.analysis.Method
import app.tracer.core.analysis.Protocol
import app.tracer.core.config.RunSettings
import app.tracer.core.model.AnnotationSample
import app.tracer.core.model.CollectorLogSample
import app.tracer.core.model.DeviceSample
import app.tracer.core.model.GapSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.RunState
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.model.TracerClock
import app.tracer.core.model.WaypointSample
import app.tracer.core.model.WifiScanSample
import app.tracer.core.pairing.ClockAlignment
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.CollectorContext
import app.tracer.core.runtime.CollectorHealth
import app.tracer.core.runtime.CollectorStatusBoard
import app.tracer.core.runtime.DeviceInfoProvider
import app.tracer.core.runtime.LiveAggregator
import app.tracer.core.runtime.NtpClient
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunRepository
import app.tracer.core.runtime.RunSignals
import app.tracer.core.runtime.SampleSink
import app.tracer.core.sim.Scenarios
import app.tracer.core.sim.SyntheticRun
import app.tracer.events.EventEngine
import app.tracer.events.RoamTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service that owns the run lifecycle.
 *
 * Threading model:
 *  - Commands (start/pause/resume/stop/mark/recover) run on the main thread, serialised by
 *    [commandLock]. All [Session] bookkeeping fields are touched only from these commands and
 *    from the main-thread loops.
 *  - Samples arrive on Dispatchers.Default (collectors / simulator) and go through [onSample],
 *    serialised by [sampleLock], which also guards [aggregator], the EventEngine and the RoamTracker.
 *  - Lock order: commandLock → sampleLock (never the reverse).
 */
@AndroidEntryPoint
class RunService : LifecycleService() {

    @Inject lateinit var collectors: Set<@JvmSuppressWildcards Collector>
    @Inject lateinit var sink: SampleSink
    @Inject lateinit var repository: RunRepository
    @Inject lateinit var deviceInfo: DeviceInfoProvider
    @Inject lateinit var ntp: NtpClient
    @Inject lateinit var status: CollectorStatusBoard
    @Inject lateinit var signals: RunSignals
    @Inject lateinit var clock: TracerClock
    @Inject lateinit var facade: RunControllerFacade

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> Log.e(TAG, "uncaught in RunService", t) },
    )
    private val commandLock = Mutex()
    private val sampleLock = Mutex()
    private val aggregator = LiveAggregator()
    private val binder = LocalBinder()
    private lateinit var notifications: RunNotifications
    private lateinit var heartbeat: RunHeartbeat
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    @Volatile private var session: Session? = null

    /** Local binder: `(binder as RunService.LocalBinder).controller`. */
    inner class LocalBinder : Binder() {
        val controller: RunController get() = facade
    }

    private class Session(
        val run: RunRecord,
        /** As configured by the user, or the protocol's frozen settings (raw, battery-saver flag intact) — used for completeness. */
        val settings: RunSettings,
        val scenario: String?,
        val engine: EventEngine,
        val startedMonoNs: Long,
        @Volatile var ctx: CollectorContext,
        /** Fixed-duration protocol of this run (auto-stop after [Protocol.durationS] of recorded time), or null. */
        val protocol: Protocol? = null,
        /** Fixed test length of a free run (auto-stop after this much recorded time), or null. Ignored for protocol runs. */
        val durationLimitMs: Long? = null,
    ) {
        /** Recorded-time budget: the protocol's duration wins, else the free-run length limit. */
        val limitMs: Long? get() = protocol?.let { it.durationS * 1_000L } ?: durationLimitMs
        /** Wi-Fi roam records; fed every sample in [onSample] under sampleLock. */
        val roams = RoamTracker(run.runId)
        /** Last waypoint seq written (per run, 1-based); seeded from the table on recovery. */
        var waypointSeq = 0
        /** Set once the protocol auto-stop was queued, so the tick loop requests it only once. */
        var autoStopQueued = false
        /** Set once the battery fell to ≤ 15 % and battery-saver intervals were forced on. */
        @Volatile var lowBatterySaver = false
        val jobs = HashMap<Collector, Job>()
        val launchedAtMonoNs = HashMap<Collector, Long>()
        val loops = ArrayList<Job>()
        var simJob: Job? = null
        var simSamples: List<Sample>? = null
        @Volatile var simIndex = 0
        var state = RunState.RECORDING
        var pausedAtUtcMs = 0L
        var pausedAtMonoNs = 0L
        var pausedMsTotal = 0L
        var gapMsTotal = 0L
        var ntpOffsetMs: Long? = null
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RunNotifications(this).also { it.ensureChannels() }
        heartbeat = RunHeartbeat(this)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val promoted = promoteForeground()
                val name = intent?.getStringExtra(EXTRA_NAME) ?: getString(R.string.default_run_name)
                val mode = intent?.getStringExtra(EXTRA_MODE) ?: "drive"
                val settings = intent?.getStringExtra(EXTRA_SETTINGS_JSON)
                    ?.let { runCatching { RunSettings.fromJson(it) }.getOrNull() } ?: RunSettings()
                val scenario = intent?.getStringExtra(EXTRA_SCENARIO)?.takeIf { it in Scenarios.names }
                val site = intent?.getStringExtra(EXTRA_SITE)?.trim()?.takeIf { it.isNotEmpty() }
                val protocolId = intent?.getStringExtra(EXTRA_PROTOCOL)
                val limitMs = intent?.getLongExtra(EXTRA_DURATION_LIMIT_MS, 0L)?.takeIf { it > 0L }
                command {
                    if (promoted) startLocked(name, mode, settings, scenario, site, protocolId, limitMs)
                    else failStartLocked(R.string.alert_start_failed)
                }
            }
            null, ACTION_RESUME_AFTER_DEATH -> {
                // Called for START_STICKY re-creation (null intent) and from MainActivity's recovery.
                val promoted = promoteForeground()
                command { recoverLocked(promoted) }
            }
            ACTION_PAUSE -> command { pauseLocked() }
            ACTION_RESUME -> command { resumeLocked() }
            ACTION_STOP -> command { stopLocked() }
            ACTION_MARK -> {
                val text = intent?.getStringExtra(EXTRA_TEXT)
                val audio = intent?.getStringExtra(EXTRA_AUDIO_PATH)
                command { markLocked(text, audio) }
            }
            ACTION_WAYPOINT -> {
                val label = intent?.getStringExtra(EXTRA_LABEL)
                val utc = intent?.getLongExtra(EXTRA_TAP_UTC_MS, 0L) ?: 0L
                val mono = intent?.getLongExtra(EXTRA_TAP_MONO_NS, 0L) ?: 0L
                val tappedAt = if (utc > 0L && mono > 0L) Stamp(utc, mono) else null
                command { waypointLocked(label, tappedAt) }
            }
            else -> command { if (session == null) stopIfIdle() }
        }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (session != null) scope.launch(Dispatchers.Default) { runCatching { sink.flush() } }
    }

    override fun onDestroy() {
        // A destroy without stopLocked() (e.g. external stopService) leaves the heartbeat in place, so
        // the run is resumed with a process_restart gap next time.
        if (session != null) {
            facade.publishState(RunState.IDLE)
            facade.publishLive(aggregator.snapshot.copy(state = RunState.IDLE))
        }
        session = null
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ commands

    private fun command(block: suspend () -> Unit) {
        scope.launch { commandLock.withLock { block() } }
    }

    private suspend fun startLocked(
        name: String, mode: String, userSettings: RunSettings, scenario: String?, site: String?, protocolId: String?,
        durationLimitMs: Long? = null,
    ) {
        session?.let { s -> // already running: re-publish so the caller's PREFLIGHT state resolves
            facade.publishRunId(s.run.runId); facade.publishState(s.state); return
        }
        val protocol = Protocol.byId(protocolId)
        if (protocolId != null && protocol == null) Log.w(TAG, "unknown protocol '$protocolId' — starting a free run")
        val settings = protocol?.let { protocolSettings(it, userSettings) } ?: userSettings
        val now = clock.now()
        val zone = ZoneId.systemDefault()
        val testNumber = site?.let { st ->
            try { repository.nextTestNumber(st) } catch (e: CancellationException) { throw e } catch (t: Throwable) { Log.w(TAG, "nextTestNumber failed", t); 1 }
        }
        val runName = if (site != null && testNumber != null) {
            RunRecord.displayName(site, testNumber, Instant.ofEpochMilli(now.tUtcMs).atZone(zone).format(DISPLAY_TIME))
        } else name
        val run = RunRecord(
            runId = UUID.randomUUID().toString(),
            name = runName,
            mode = if (scenario != null) "simulated" else mode,
            devicePlatform = "android",
            deviceModel = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            appVersion = BuildConfig.VERSION_NAME,
            startedAt = now.tUtcMs,
            settingsJson = settings.toJson().toString(),
            tUtcMs = now.tUtcMs,
            tMonoNs = now.tMonoNs,
            site = site,
            testNumber = testNumber,
            protocol = protocol?.id,
            timeZone = zone.id,
            methodFingerprint = Method.fingerprint(settings, protocol?.id),
        )
        try {
            repository.createRun(run)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "createRun failed", t)
            failStartLocked(R.string.alert_start_failed)
            return
        }
        val s = newSession(run, settings, scenario, now.tMonoNs, protocol, if (protocol == null) durationLimitMs else null)
        beginSession(s)
        launchNtp(s)
    }

    /**
     * The protocol's frozen settings. Only fields that do not change *what* is measured (and are not
     * part of [Method.fingerprint]) are carried over from the user's settings: OpenCelliD key,
     * upload URL, retention and the free-storage floor.
     */
    private fun protocolSettings(p: Protocol, user: RunSettings): RunSettings = p.settings().copy(
        openCellIdKey = user.openCellIdKey,
        uploadUrl = user.uploadUrl,
        retentionDays = user.retentionDays,
        minFreeStorageMb = user.minFreeStorageMb,
    )

    private fun failStartLocked(@StringRes alert: Int) {
        notifications.post(RunNotifications.ID_ALERT, notifications.alert(getString(alert)))
        facade.publishState(RunState.IDLE)
        facade.publishLive(aggregator.snapshot.copy(state = RunState.IDLE))
        stopIfIdle()
    }

    private suspend fun recoverLocked(promoted: Boolean) {
        if (session != null) return
        val hb = heartbeat.read() ?: return stopIfIdle()
        val run = try { repository.unfinishedRun() } catch (e: CancellationException) { throw e } catch (t: Throwable) { null }
        if (run == null || run.runId != hb.runId) {
            heartbeat.clear()
            return stopIfIdle()
        }
        val settings = (hb.settingsJson ?: run.settingsJson)
            ?.let { runCatching { RunSettings.fromJson(it) }.getOrNull() } ?: RunSettings()
        val now = clock.now()
        val lastAliveUtc = hb.lastAliveUtcMs.takeIf { it > 0 }?.coerceAtMost(now.tUtcMs) ?: now.tUtcMs
        val rebooted = hb.lastAliveMonoNs <= 0 || hb.lastAliveMonoNs > now.tMonoNs
        val startedMono = if (!rebooted && hb.startedMonoNs > 0 && hb.startedMonoNs <= now.tMonoNs) hb.startedMonoNs
        else now.tMonoNs - (now.tUtcMs - run.startedAt).coerceAtLeast(0) * 1_000_000L

        val protocol = Protocol.byId(hb.protocolId ?: run.protocol)
        // A free run's length limit is not stored; recover it from the heartbeat's projected end
        // (written for every limited run): recorded time at the heartbeat + time that was left.
        val recoveredLimitMs = if (protocol == null && hb.protocolEndUtcMs > 0L && hb.lastAliveUtcMs > 0L) {
            val pausedAtHb = hb.pausedMsTotal + if (hb.paused && hb.pausedAtUtcMs > 0L) (hb.lastAliveUtcMs - hb.pausedAtUtcMs).coerceAtLeast(0L) else 0L
            val recordedAtHb = (hb.lastAliveUtcMs - run.startedAt - pausedAtHb).coerceAtLeast(0L)
            (recordedAtHb + (hb.protocolEndUtcMs - hb.lastAliveUtcMs).coerceAtLeast(0L)).takeIf { it > 0L }
        } else null
        val s = newSession(run, settings, hb.scenario?.takeIf { it in Scenarios.names }, startedMono, protocol, recoveredLimitMs)
        s.waypointSeq = runCatching { repository.counts(run.runId)[Table.WAYPOINT]?.toInt() ?: 0 }.getOrDefault(0)
        s.pausedMsTotal = hb.pausedMsTotal
        s.gapMsTotal = hb.gapMsTotal
        if (hb.paused) {
            // the pause already covers the dead interval; it is accounted for at resume/stop
            s.state = RunState.PAUSED
            s.pausedAtUtcMs = hb.pausedAtUtcMs
            s.pausedAtMonoNs = if (rebooted) 0L else hb.pausedAtMonoNs
        } else {
            val gap = now.tUtcMs - lastAliveUtc
            s.pausedMsTotal += gap
            s.gapMsTotal += gap
        }

        if (!hasFineLocation()) {
            // Location was revoked (Android kills the process on revocation) → close the run cleanly.
            session = s
            sampleLock.withLock { aggregator.reset(run.runId, s.state, startedMono) }
            facade.publishRunId(run.runId)
            onSample(s, GapSample(run.runId, now, lastAliveUtc, now.tUtcMs, "process_restart"))
            onSample(s, GapSample(run.runId, now, now.tUtcMs, now.tUtcMs, "permission_revoked"))
            notifications.post(RunNotifications.ID_ALERT, notifications.alert(getString(R.string.alert_permission_revoked)))
            stopLocked()
            return
        }
        if (!promoted) {
            // Not allowed to go foreground right now (background restart) — keep the heartbeat so
            // opening the app resumes the run.
            notifications.post(RunNotifications.ID_ALERT, notifications.alert(getString(R.string.alert_interrupted)))
            return stopIfIdle()
        }
        beginSession(s)
        onSample(s, GapSample(run.runId, now, lastAliveUtc, now.tUtcMs, "process_restart"))
        launchNtp(s)
    }

    private suspend fun pauseLocked() {
        val s = session ?: return stopIfIdle()
        if (s.state != RunState.RECORDING) return
        signals.paused.value = true
        cancelSources(s)
        val now = clock.now()
        s.pausedAtUtcMs = now.tUtcMs
        s.pausedAtMonoNs = now.tMonoNs
        s.state = RunState.PAUSED
        sampleLock.withLock {
            runCatching { sink.flush() }.onFailure { Log.w(TAG, "flush on pause failed", it) }
            aggregator.setState(RunState.PAUSED)
            facade.publishLive(aggregator.snapshot)
        }
        facade.publishState(RunState.PAUSED)
        writeHeartbeat(s)
        updateNotification()
    }

    private suspend fun resumeLocked() {
        val s = session ?: return stopIfIdle()
        if (s.state != RunState.PAUSED) return
        val now = clock.now()
        val d = pauseDurationMs(s, now)
        s.pausedMsTotal += d
        s.gapMsTotal += d
        s.state = RunState.RECORDING
        signals.paused.value = false
        sampleLock.withLock { aggregator.setState(RunState.RECORDING) }
        onSample(s, GapSample(s.run.runId, now, s.pausedAtUtcMs, now.tUtcMs, "pause"))
        launchSources(s)
        facade.publishState(RunState.RECORDING)
        writeHeartbeat(s)
        updateNotification()
    }

    private suspend fun markLocked(text: String?, audioPath: String?) {
        val s = session ?: return stopIfIdle()
        onSample(s, AnnotationSample(s.run.runId, clock.now(), text, audioPath))
    }

    /**
     * Tap-to-mark: stamped with the tap time; position = the aggregator's latest fix (any provider)
     * at the moment the command is processed (milliseconds after the tap).
     */
    private suspend fun waypointLocked(label: String?, tappedAt: Stamp?) {
        val s = session ?: return stopIfIdle()
        if (s.state == RunState.STOPPING) return
        val loc = sampleLock.withLock { aggregator.snapshot.location }
        s.waypointSeq += 1
        onSample(
            s,
            WaypointSample(
                runId = s.run.runId,
                stamp = tappedAt ?: clock.now(),
                seq = s.waypointSeq,
                label = label?.trim()?.takeIf { it.isNotEmpty() },
                lat = loc?.lat,
                lon = loc?.lon,
                hAccM = loc?.hAccM,
                provider = loc?.provider,
            ),
        )
    }

    private suspend fun stopLocked() {
        val s = session ?: return stopIfIdle()
        val wasPaused = s.state == RunState.PAUSED
        s.state = RunState.STOPPING
        facade.publishState(RunState.STOPPING)
        s.loops.forEach { it.cancel() }
        s.loops.clear()
        cancelSources(s)
        sampleLock.withLock { aggregator.setState(RunState.STOPPING) }

        val now = clock.now()
        try {
            if (wasPaused) {
                val d = pauseDurationMs(s, now)
                s.pausedMsTotal += d
                s.gapMsTotal += d
                onSample(s, GapSample(s.run.runId, now, s.pausedAtUtcMs, now.tUtcMs, "pause"))
            }
            sampleLock.withLock {
                for (e in s.engine.finish()) { sink.emit(e); aggregator.onSample(e) }
                for (r in s.roams.finish()) { sink.emit(r); aggregator.onSample(r) }
                sink.flush()
            }
            finalizeRecord(s, now.tUtcMs)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "finalising run ${s.run.runId} failed; writing endedAt only", t)
            runCatching { repository.updateRun((repository.getRun(s.run.runId) ?: s.run).copy(endedAt = now.tUtcMs)) }
        }

        heartbeat.clear()
        session = null
        signals.paused.value = false
        signals.degraded.value = false
        releaseWakeLock()
        sampleLock.withLock {
            aggregator.setState(RunState.IDLE)
            facade.publishLive(aggregator.snapshot)
        }
        facade.publishState(RunState.IDLE) // runId intentionally kept → summary navigation
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (session != null) return
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    // ------------------------------------------------------------------ session plumbing

    private fun newSession(
        run: RunRecord, settings: RunSettings, scenario: String?, startedMonoNs: Long, protocol: Protocol?, durationLimitMs: Long? = null,
    ): Session {
        // Collectors receive the already-effective settings with batterySaver cleared, so calling
        // effective() again on their side is a no-op (never double-multiplied).
        return Session(
            run, settings, scenario, EventEngine(run.runId, settings.thresholds), startedMonoNs,
            collectorContext(run.runId, settings), protocol, durationLimitMs,
        )
    }

    private fun collectorContext(runId: String, settings: RunSettings): CollectorContext =
        CollectorContext(runId, settings.effective().copy(batterySaver = false), clock, signals, status)

    /**
     * Low battery (≤ 15 %, not charging): switch to battery-saver intervals for the rest of the run
     * and record it as an annotation on the timeline — never a modal.
     */
    private suspend fun applyLowBatterySaverLocked(s: Session, batteryPct: Int) {
        if (session !== s || s.lowBatterySaver || s.settings.batterySaver) return
        s.lowBatterySaver = true
        s.ctx = collectorContext(s.run.runId, s.settings.copy(batterySaver = true))
        onSample(s, AnnotationSample(s.run.runId, clock.now(), getString(R.string.annotation_low_battery, batteryPct)))
        if (s.state == RunState.RECORDING && s.scenario == null) {
            cancelSources(s)
            launchSources(s)
        }
    }

    private suspend fun beginSession(s: Session) {
        session = s
        status.reset()
        signals.rat.value = null
        signals.primaryInterface.value = null
        signals.degraded.value = false
        signals.paused.value = s.state == RunState.PAUSED
        sampleLock.withLock {
            aggregator.reset(s.run.runId, s.state, s.startedMonoNs)
            facade.publishLive(aggregator.snapshot)
        }
        facade.publishRunId(s.run.runId)
        facade.publishLimit(s.limitMs)
        facade.publishState(s.state)
        acquireWakeLock()
        if (s.state == RunState.RECORDING) launchSources(s)
        startLoops(s)
        writeHeartbeat(s)
        updateNotification()
    }

    private fun launchNtp(s: Session) {
        scope.launch {
            val offset = withContext(Dispatchers.IO) { runCatching { ntp.offsetMs() }.getOrNull() } ?: return@launch
            commandLock.withLock {
                if (session !== s) return@withLock
                s.ntpOffsetMs = offset
                runCatching {
                    val latest = repository.getRun(s.run.runId) ?: s.run
                    repository.updateRun(latest.copy(ntpOffsetMs = offset))
                }.onFailure { Log.w(TAG, "ntp offset update failed", it) }
            }
        }
    }

    private fun launchSources(s: Session) {
        if (s.scenario != null) launchSimulation(s) else collectors.forEach { launchCollector(s, it) }
    }

    private suspend fun cancelSources(s: Session) {
        val jobs = s.jobs.values.toList() + listOfNotNull(s.simJob)
        s.jobs.clear()
        s.simJob = null
        jobs.forEach { it.cancel() }
        // a collector that ignores cancellation must not wedge pause/stop
        withTimeoutOrNull(JOIN_TIMEOUT_MS) { jobs.joinAll() }
    }

    private fun launchCollector(s: Session, c: Collector) {
        s.jobs.remove(c)?.cancel()
        s.launchedAtMonoNs[c] = clock.now().tMonoNs
        s.jobs[c] = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    c.samples(s.ctx).collect { sample ->
                        // watchdog timestamp only — collectors themselves count samples / set health
                        val now = clock.now().tMonoNs
                        status.update(c.id) { it.copy(lastSampleMonoNs = now) }
                        onSample(s, sample)
                    }
                    return@launch // completed on its own (feature unsupported etc.) — do not respawn
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "collector ${c.id} failed; restarting in ${RESTART_BACKOFF_MS} ms", t)
                    status.update(c.id) {
                        it.copy(health = CollectorHealth.DEGRADED, message = t.message ?: t.javaClass.simpleName, restarts = it.restarts + 1)
                    }
                    delay(RESTART_BACKOFF_MS)
                }
            }
        }
    }

    /** Debug "Simulate run": paces a synthetic scenario in real time (1×), re-stamped to now. */
    private fun launchSimulation(s: Session) {
        val name = s.scenario ?: return
        val samples = s.simSamples
            ?: Scenarios.byName(name, SyntheticRun(runId = s.run.runId)).sortedBy { it.stamp.tMonoNs }.also { s.simSamples = it }
        s.simJob?.cancel()
        s.simJob = scope.launch(Dispatchers.Default) {
            if (s.simIndex >= samples.size) return@launch
            // re-anchor so playback continues from simIndex at "now" (also after a pause)
            val anchor = samples[s.simIndex].stamp
            val now = clock.now()
            val dUtc = now.tUtcMs - anchor.tUtcMs
            val dMono = now.tMonoNs - anchor.tMonoNs
            while (isActive && s.simIndex < samples.size) {
                val src = samples[s.simIndex]
                val targetMono = src.stamp.tMonoNs + dMono
                val waitMs = (targetMono - clock.now().tMonoNs) / 1_000_000L
                if (waitMs > 0) delay(waitMs)
                val stamp = Stamp(src.stamp.tUtcMs + dUtc, targetMono, src.stamp.gpsTimeMs?.plus(dUtc))
                onSample(s, src.restamp(s.run.runId, stamp))
                s.simIndex++
            }
        }
    }

    /**
     * Single writer path: sink → live aggregator → event engine (→ sink/aggregator) → roam tracker
     * (→ sink/aggregator) → signals. Real and simulated runs both come through here.
     */
    private suspend fun onSample(s: Session, sample: Sample) {
        sampleLock.withLock {
            if (session !== s) return
            sink.emit(sample)
            aggregator.onSample(sample)
            for (e in s.engine.onSample(sample)) {
                sink.emit(e)
                aggregator.onSample(e)
            }
            for (r in s.roams.onSample(sample)) {
                sink.emit(r)
                aggregator.onSample(r)
            }
            updateSignals(sample)
            if (sample is DeviceSample) checkLowBattery(s, sample)
        }
    }

    /**
     * Persists collector health transitions (unavailable / degraded / restarted / recovered) as
     * x_collector_log rows (collector, health, message, restarts) — kept out of the user's
     * annotations — so an exported bundle explains *why* a table is empty or sparse (permission
     * missing, GNSS engine stopped, scan throttling).
     */
    private suspend fun recordCollectorHealth(s: Session) {
        val last = HashMap<String, Triple<CollectorHealth, String?, Int>>()
        status.state.collect { board ->
            if (session !== s) return@collect
            for ((id, st) in board) {
                val now = Triple(st.health, st.message, st.restarts)
                val prev = last[id]
                if (prev == now) continue
                last[id] = now
                val changedHealth = prev == null || prev.first != st.health || prev.second != st.message
                val restarted = prev != null && st.restarts > prev.third
                if (prev == null && (st.health == CollectorHealth.OK || st.health == CollectorHealth.DISABLED)) continue // normal start / user-disabled, not news
                if (!changedHealth && !restarted) continue
                onSample(s, CollectorLogSample(s.run.runId, clock.now(), id, st.health.name.lowercase(), st.message, st.restarts))
            }
        }
    }

    private fun checkLowBattery(s: Session, d: DeviceSample) {
        val level = d.battery ?: return
        if (s.lowBatterySaver || s.settings.batterySaver || d.charging == true || level > LOW_BATTERY_FRACTION) return
        val pct = (level * 100).toInt()
        command { applyLowBatterySaverLocked(s, pct) }
    }

    private fun updateSignals(sample: Sample) {
        when (sample) {
            is RadioSample -> signals.rat.value = sample.rat
            is NetworkPathSample -> signals.primaryInterface.value = sample.primaryInterface
            is ProbeSample -> if (sample.probeType == "latency") {
                val snap = aggregator.snapshot
                val lossy = (snap.lossPct ?: 0.0) > DEGRADED_LOSS_PCT
                val latency = snap.latencyMs
                val median = snap.medianLatencyMs
                val spiking = latency != null && median != null && median > 0 && latency > DEGRADED_LATENCY_FACTOR * median
                signals.degraded.value = lossy || spiking
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ periodic loops (main thread)

    private fun startLoops(s: Session) {
        s.loops += scope.launch { while (isActive) { delay(TICK_MS); tick(s) } }
        s.loops += scope.launch { while (isActive) { delay(NOTIFICATION_MS); updateNotification() } }
        s.loops += scope.launch { while (isActive) { delay(WATCHDOG_MS); watchdog(s) } }
        s.loops += scope.launch { while (isActive) { delay(HEARTBEAT_MS); writeHeartbeat(s) } }
        s.loops += scope.launch { while (isActive) { checkStorage(s); delay(STORAGE_CHECK_MS) } }
        s.loops += scope.launch { while (isActive) { delay(PERMISSION_CHECK_MS); checkPermission(s) } }
        s.loops += scope.launch { while (isActive) { delay(WAKELOCK_RENEW_MS); acquireWakeLock() } }
        s.loops += scope.launch { recordCollectorHealth(s) }
    }

    private suspend fun tick(s: Session) {
        val now = clock.now()
        val pausedMs = s.pausedMsTotal + if (s.state == RunState.PAUSED) pauseDurationMs(s, now) else 0L
        sampleLock.withLock {
            if (session !== s) return
            val loc = aggregator.snapshot.location
            val gpsOffset = loc?.let { l -> (l.gpsTimeMs ?: l.stamp.gpsTimeMs)?.minus(l.stamp.tUtcMs) }
            aggregator.tick(now.tMonoNs, pausedMs, gpsOffset)
            facade.publishLive(aggregator.snapshot)
        }
        val remaining = protocolRemainingMs(s, now) ?: return
        if (remaining <= 0L && s.state == RunState.RECORDING && !s.autoStopQueued) {
            s.autoStopQueued = true
            Log.i(TAG, "${s.protocol?.id ?: "length limit"} reached — stopping run ${s.run.runId}")
            command { if (session === s && s.state == RunState.RECORDING) stopLocked() }
        }
    }

    /** Recorded (un-paused, gap-free) time of [s]. */
    private fun recordedMs(s: Session, now: Stamp): Long {
        val pausedMs = s.pausedMsTotal + if (s.state == RunState.PAUSED) pauseDurationMs(s, now) else 0L
        return ((now.tMonoNs - s.startedMonoNs) / 1_000_000L - pausedMs).coerceAtLeast(0L)
    }

    /** Time left of the protocol / length limit (recorded time; pauses and restarts do not count), or null for an open-ended run. */
    private fun protocolRemainingMs(s: Session, now: Stamp): Long? {
        val limit = s.limitMs ?: return null
        return limit - recordedMs(s, now)
    }

    private fun watchdog(s: Session) {
        if (s.state != RunState.RECORDING || s.scenario != null) return
        val now = clock.now().tMonoNs
        val board = status.state.value
        for (c in collectors) {
            val limitMs = c.watchdogMs ?: continue
            if (s.jobs[c]?.isActive != true) continue // finished on its own (or not launched)
            val last = maxOf(board[c.id]?.lastSampleMonoNs ?: 0L, s.launchedAtMonoNs[c] ?: 0L)
            if ((now - last) / 1_000_000L > limitMs) {
                Log.w(TAG, "watchdog: ${c.id} silent > $limitMs ms, restarting")
                status.update(c.id) { it.copy(restarts = it.restarts + 1) }
                launchCollector(s, c) // cancels the old job first
            }
        }
    }

    private fun checkStorage(s: Session) {
        val free = runCatching { deviceInfo.freeStorageBytes() }.getOrNull() ?: return
        if (free < s.settings.minFreeStorageMb * 1024L * 1024L) abort(s, "storage_low", R.string.alert_storage_low)
    }

    private fun checkPermission(s: Session) {
        if (!hasFineLocation()) abort(s, "permission_revoked", R.string.alert_permission_revoked)
    }

    /** Stops the run from a background check: gap row + alert + normal stop. */
    private fun abort(s: Session, reason: String, @StringRes alert: Int) {
        command {
            if (session !== s || s.state == RunState.STOPPING) return@command
            val now = clock.now()
            onSample(s, GapSample(s.run.runId, now, now.tUtcMs, now.tUtcMs, reason))
            notifications.post(RunNotifications.ID_ALERT, notifications.alert(getString(alert)))
            stopLocked()
        }
    }

    private fun writeHeartbeat(s: Session) {
        if (session !== s) return
        val now = clock.now()
        heartbeat.write(
            RunHeartbeat.Snapshot(
                runId = s.run.runId,
                settingsJson = s.settings.toStorageJson(),
                scenario = s.scenario,
                lastAliveUtcMs = now.tUtcMs,
                lastAliveMonoNs = now.tMonoNs,
                startedMonoNs = s.startedMonoNs,
                paused = s.state == RunState.PAUSED,
                pausedAtUtcMs = s.pausedAtUtcMs,
                pausedAtMonoNs = s.pausedAtMonoNs,
                pausedMsTotal = s.pausedMsTotal,
                gapMsTotal = s.gapMsTotal,
                protocolId = s.protocol?.id,
                protocolEndUtcMs = protocolRemainingMs(s, now)?.let { now.tUtcMs + it.coerceAtLeast(0L) } ?: 0L,
            ),
        )
    }

    private fun updateNotification() {
        val s = session ?: return
        if (!foreground) return
        val remaining = protocolRemainingMs(s, clock.now())
        val protocol = remaining?.let { r -> (s.protocol?.id ?: s.run.name) to r.coerceAtLeast(0L) }
        notifications.post(
            RunNotifications.ID_RUNNING,
            notifications.running(facade.live.value, paused = s.state == RunState.PAUSED, protocol = protocol),
        )
    }

    // ------------------------------------------------------------------ finalisation

    /**
     * Completeness + GPS offsets. Bounded memory for multi-hour runs: tables are streamed page by
     * page ([RunRepository.stream]) and folded into counters; only the first/last
     * [GPS_OFFSET_FIXES] GNSS-timed fixes are kept for the offset medians.
     */
    private suspend fun finalizeRecord(s: Session, endUtcMs: Long) {
        val runId = s.run.runId
        val counts = repository.counts(runId)

        val locationSeconds = HashSet<Long>() // ≤ one entry per recorded second
        val firstGps = ArrayList<LocationSample>(GPS_OFFSET_FIXES)
        val lastGps = ArrayDeque<LocationSample>(GPS_OFFSET_FIXES + 1)
        repository.stream(runId, Table.LOCATION).collect { l ->
            if (l !is LocationSample) return@collect
            locationSeconds += l.stamp.tMonoNs / 1_000_000_000L
            if ((l.gpsTimeMs ?: l.stamp.gpsTimeMs) != null) {
                if (firstGps.size < GPS_OFFSET_FIXES) firstGps += l
                lastGps.addLast(l)
                if (lastGps.size > GPS_OFFSET_FIXES) lastGps.removeFirst()
            }
        }
        val scanIds = HashSet<Long>() // one entry per scan (~2/min)
        if ((counts[Table.WIFI_SCAN] ?: 0L) > 0L) {
            repository.stream(runId, Table.WIFI_SCAN).collect { if (it is WifiScanSample) scanIds += it.scanId }
        }
        var latencyProbes = 0L
        if ((counts[Table.PROBE] ?: 0L) > 0L) {
            repository.stream(runId, Table.PROBE).collect { if (it is ProbeSample && it.probeType == "latency") latencyProbes++ }
        }
        val observed = mapOf(
            // positions from any provider (raw GPS or fused indoors); GNSS health is scored by gnss_epochs
            "location" to locationSeconds.size.toLong(),
            // ~10 satellites per epoch; avoids loading every satellite row of a long run
            "gnss_epochs" to (counts[Table.GNSS_SATELLITE] ?: 0L) / GNSS_ROWS_PER_EPOCH,
            "cell_serving_polls" to (counts[Table.CELL_SERVING] ?: 0L),
            "wifi_scans" to scanIds.size.toLong(),
            "latency_probes" to latencyProbes,
            "device" to (counts[Table.DEVICE] ?: 0L),
            "motion" to (counts[Table.MOTION] ?: 0L),
            "network_path" to (counts[Table.NETWORK_PATH] ?: 0L),
        )
        val activeSims = if (s.scenario != null) 1 else runCatching { deviceInfo.activeSims().size }.getOrDefault(1).coerceAtLeast(1)
        val completeness = Completeness.compute(endUtcMs - s.run.startedAt, s.gapMsTotal, observed, s.settings, activeSims)
        // re-read: PairingService may have set linked_run_id meanwhile
        val latest = repository.getRun(runId) ?: s.run
        repository.updateRun(
            latest.copy(
                endedAt = endUtcMs,
                gpsOffsetMsStart = ClockAlignment.gpsOffsetMs(firstGps, GPS_OFFSET_FIXES, fromEnd = false),
                gpsOffsetMsEnd = ClockAlignment.gpsOffsetMs(lastGps.toList(), GPS_OFFSET_FIXES, fromEnd = true),
                ntpOffsetMs = s.ntpOffsetMs ?: latest.ntpOffsetMs,
                completenessJson = completeness.toJson(),
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun pauseDurationMs(s: Session, now: Stamp): Long =
        if (s.pausedAtMonoNs in 1..now.tMonoNs) (now.tMonoNs - s.pausedAtMonoNs) / 1_000_000L
        else (now.tUtcMs - s.pausedAtUtcMs).coerceAtLeast(0L)

    private fun promoteForeground(): Boolean {
        if (foreground) return true
        return try {
            startForeground(
                RunNotifications.ID_RUNNING,
                notifications.running(facade.live.value, paused = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            foreground = true
            true
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException, SecurityException (no location perm)
            Log.w(TAG, "startForeground refused", e)
            false
        }
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        val wl = wakeLock ?: requireNotNull(getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .also { it.setReferenceCounted(false); wakeLock = it }
        wl.acquire(WAKELOCK_TIMEOUT_MS) // non-reference-counted: re-acquire just extends the timeout
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    companion object {
        private const val TAG = "RunService"
        const val ACTION_START = "app.tracer.run.START"
        const val ACTION_PAUSE = "app.tracer.run.PAUSE"
        const val ACTION_RESUME = "app.tracer.run.RESUME"
        const val ACTION_STOP = "app.tracer.run.STOP"
        const val ACTION_MARK = "app.tracer.run.MARK"
        const val ACTION_RESUME_AFTER_DEATH = "app.tracer.run.RESUME_AFTER_DEATH"
        const val ACTION_WAYPOINT = "app.tracer.run.WAYPOINT"
        const val EXTRA_NAME = "name"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SETTINGS_JSON = "settings_json"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_TEXT = "text"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_SITE = "site"
        const val EXTRA_PROTOCOL = "protocol_id"
        const val EXTRA_DURATION_LIMIT_MS = "duration_limit_ms"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TAP_UTC_MS = "tap_utc_ms"
        const val EXTRA_TAP_MONO_NS = "tap_mono_ns"

        private const val TICK_MS = 1_000L
        private const val NOTIFICATION_MS = 2_000L
        private const val WATCHDOG_MS = 5_000L
        private const val HEARTBEAT_MS = 5_000L
        private const val STORAGE_CHECK_MS = 60_000L
        private const val PERMISSION_CHECK_MS = 30_000L
        private const val RESTART_BACKOFF_MS = 2_000L
        private const val JOIN_TIMEOUT_MS = 5_000L
        private const val WAKELOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
        private const val WAKELOCK_RENEW_MS = 60L * 60 * 1000
        private const val WAKELOCK_TAG = "Tracer:run"
        private const val DEGRADED_LOSS_PCT = 30.0
        private const val DEGRADED_LATENCY_FACTOR = 3.0
        private const val GNSS_ROWS_PER_EPOCH = 10L
        private const val LOW_BATTERY_FRACTION = 0.15
        private const val GPS_OFFSET_FIXES = 30
        private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

        fun intent(context: Context, action: String): Intent = Intent(context, RunService::class.java).setAction(action)
    }
}
