package app.tracer.ui.companion

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.RunState
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.runtime.PairingService
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunListItem
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.preflight.GnssState
import app.tracer.ui.preflight.gnssStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CompanionUi(
    /** QR text for the active run; null when no run is recording (QR appears once it starts). */
    val ownQr: String?,
    val runActive: Boolean,
    /** GNSS − wall clock on this device. */
    val ownGpsOffsetMs: Long?,
    val partner: PairingPayload?,
    /** partner clock − our clock via GNSS offsets; target |Δt| < 50 ms. */
    val deltaMs: Long?,
    val lastLinked: CompanionLink.Linked?,
)

@HiltViewModel
class CompanionViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val controller: RunController,
    private val pairing: PairingService,
    private val repository: RunRepository,
    private val link: CompanionLink,
) : ViewModel() {

    private val gnss: StateFlow<GnssState> =
        gnssStateFlow(context).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnssState(permission = false, providerEnabled = false))

    /** Pairing QR for the current run (re-generated when the run or its GPS offset changes). */
    private val ownQr = combine(controller.state, controller.runId, controller.live.map { it.gpsOffsetMs }.distinctUntilChanged()) { st, id, off ->
        Triple(st, id, off)
    }.map { (st, id, off) ->
        if (id == null || (st != RunState.RECORDING && st != RunState.PAUSED)) null
        else runCatching { repository.getRun(id)?.let { pairing.payloadFor(it, off) } }.getOrNull()
    }

    val ui: StateFlow<CompanionUi> = combine(ownQr, controller.state, gnss, link.scanned, link.lastLinked) { qr, st, g, sc, last ->
        val partner = sc?.second ?: last?.payload
        val ours = g.gpsOffsetMs
        val theirs = partner?.gpsOffsetMs
        val delta = if (ours != null && theirs != null) ours - theirs else null
        CompanionUi(
            ownQr = qr,
            runActive = st == RunState.RECORDING || st == RunState.PAUSED,
            ownGpsOffsetMs = g.gpsOffsetMs,
            partner = partner,
            deltaMs = delta,
            lastLinked = last,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompanionUi(null, false, null, null, null, null))

    val linkedRuns: StateFlow<List<RunListItem>> = repository.observeRuns()
        .map { list -> list.filter { it.run.linkedRunId != null || it.hasLinkedImport } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** @return false when the QR is not a Tracer pairing code. */
    fun onScanned(text: String): Boolean = link.onScanned(text)

    fun clearPartner() = link.clear()
}
