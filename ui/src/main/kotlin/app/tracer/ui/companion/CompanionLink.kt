package app.tracer.ui.companion

import android.util.Log
import app.tracer.core.model.RunState
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.runtime.PairingService
import app.tracer.core.runtime.RunController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide pairing state shared by Pre-flight and Companion: a scanned iPhone QR is kept here
 * and applied with PairingService.acceptScanned as soon as a run is RECORDING (the screen that
 * scanned it may be gone by then).
 */
@Singleton
class CompanionLink @Inject constructor(
    private val controller: RunController,
    private val pairing: PairingService,
) {
    data class Linked(val runId: String, val payload: PairingPayload, val result: Result<String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _scanned = MutableStateFlow<Pair<String, PairingPayload>?>(null)
    /** Raw QR text + parsed payload of the partner device, until applied. */
    val scanned: StateFlow<Pair<String, PairingPayload>?> = _scanned.asStateFlow()

    private val _linkOnStart = MutableStateFlow(true)
    /** "Link to iPhone run" switch in Pre-flight. */
    val linkOnStart: StateFlow<Boolean> = _linkOnStart.asStateFlow()

    private val _lastLinked = MutableStateFlow<Linked?>(null)
    val lastLinked: StateFlow<Linked?> = _lastLinked.asStateFlow()

    init {
        scope.launch {
            combine(controller.state, controller.runId, _scanned, _linkOnStart) { st, id, sc, on -> Quad(st, id, sc, on) }
                .collect { (st, id, sc, on) ->
                    if (st == RunState.RECORDING && id != null && sc != null && on) apply(id, sc)
                }
        }
    }

    /** @return false when [qrText] is not a Tracer pairing QR. */
    fun onScanned(qrText: String): Boolean {
        val p = PairingPayload.parse(qrText) ?: return false
        _scanned.value = qrText to p
        return true
    }

    fun setLinkOnStart(on: Boolean) { _linkOnStart.value = on }

    fun clear() { _scanned.value = null }

    private suspend fun apply(runId: String, scanned: Pair<String, PairingPayload>) {
        if (_scanned.value != scanned) return // already applied / replaced
        _scanned.value = null
        val result = runCatching { pairing.acceptScanned(runId, scanned.first) }.getOrElse { Result.failure(it) }
        result.onFailure { Log.w(TAG, "acceptScanned failed", it) }
        _lastLinked.value = Linked(runId, scanned.second, result)
    }

    private data class Quad(val st: RunState, val id: String?, val sc: Pair<String, PairingPayload>?, val on: Boolean)

    private companion object { const val TAG = "CompanionLink" }
}
