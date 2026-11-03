package app.tracer.run

import android.content.Context
import android.content.Intent
import android.util.Log
import app.tracer.core.config.RunSettings
import app.tracer.core.model.RunState
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.runtime.RunController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide [RunController] injected into ViewModels. Commands are delivered to [RunService] as
 * intents (so they work whether or not anything is bound); the service mirrors its state back into
 * the StateFlows below.
 *
 * [runId] semantics: set to the new run's id once it is RECORDING, cleared to null at [start], and
 * deliberately KEPT after the run ends (state = IDLE) so the UI can navigate to that run's summary.
 * "Is a run active" must be decided from [state], not from [runId].
 */
@Singleton
class RunControllerFacade @Inject constructor(
    @ApplicationContext private val context: Context,
) : RunController {

    private val _state = MutableStateFlow(RunState.IDLE)
    private val _live = MutableStateFlow(LiveSnapshot())
    private val _runId = MutableStateFlow<String?>(null)

    override val state: StateFlow<RunState> = _state.asStateFlow()
    override val live: StateFlow<LiveSnapshot> = _live.asStateFlow()
    override val runId: StateFlow<String?> = _runId.asStateFlow()

    override fun start(name: String, mode: String, settings: RunSettings, simulateScenario: String?) {
        if (_state.value != RunState.IDLE) return
        _runId.value = null
        _live.value = LiveSnapshot(state = RunState.PREFLIGHT)
        _state.value = RunState.PREFLIGHT
        val intent = RunService.intent(context, RunService.ACTION_START)
            .putExtra(RunService.EXTRA_NAME, name)
            .putExtra(RunService.EXTRA_MODE, mode)
            .putExtra(RunService.EXTRA_SETTINGS_JSON, settings.toStorageJson())
            .putExtra(RunService.EXTRA_SCENARIO, simulateScenario)
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException (app in background) / SecurityException
            Log.e(TAG, "cannot start RunService", e)
            _state.value = RunState.IDLE
            _live.value = LiveSnapshot()
        }
    }

    override fun pause() = send(RunService.ACTION_PAUSE)
    override fun resume() = send(RunService.ACTION_RESUME)
    override fun stop() = send(RunService.ACTION_STOP)

    override fun markMoment(text: String?, audioPath: String?) {
        send(RunService.ACTION_MARK) {
            putExtra(RunService.EXTRA_TEXT, text)
            putExtra(RunService.EXTRA_AUDIO_PATH, audioPath)
        }
    }

    /**
     * Called from MainActivity (foreground): if the heartbeat shows a run that was recording when the
     * process died and the service is not running, restart the service to resume that run.
     */
    fun recoverIfNeeded() {
        if (_state.value != RunState.IDLE) return
        if (!RunHeartbeat(context).hasActiveRun()) return
        try {
            context.startForegroundService(RunService.intent(context, RunService.ACTION_RESUME_AFTER_DEATH))
        } catch (e: Exception) {
            Log.w(TAG, "recovery start refused", e)
        }
    }

    private inline fun send(action: String, extras: Intent.() -> Unit = {}) {
        if (_state.value == RunState.IDLE) return
        try {
            context.startService(RunService.intent(context, action).apply(extras))
        } catch (e: Exception) { // IllegalStateException when background-start is not allowed
            Log.w(TAG, "cannot deliver $action", e)
        }
    }

    // ---- written by RunService (same module) ----
    internal fun publishState(state: RunState) { _state.value = state }
    internal fun publishRunId(runId: String?) { _runId.value = runId }
    internal fun publishLive(snapshot: LiveSnapshot) { _live.value = snapshot }

    private companion object { const val TAG = "RunControllerFacade" }
}
