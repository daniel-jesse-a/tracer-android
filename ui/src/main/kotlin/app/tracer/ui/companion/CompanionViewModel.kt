package app.tracer.ui.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.model.RunState
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.runtime.PairingService
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/** One row of the Companions list. */
data class CompanionRow(
    val key: String,
    val name: String,
    val platform: String,
    /** "Linked · recording" / "Links when the test starts" / "Not nearby" */
    val status: String,
    val live: Boolean,
    /** clock gap to this phone (|GPS offset here − there|), ms; null = unknown */
    val gapMs: Long?,
    val openRunId: String?,
)

data class CompanionsUi(
    val rows: List<CompanionRow>,
    val liveCount: Int,
    /** QR text for the active run; null when no run is recording (QR appears once it starts). */
    val ownQr: String?,
    val runActive: Boolean,
    /** last link attempt failed: message to show */
    val linkError: String?,
    /** a scanned partner is waiting for the run to start */
    val pending: Boolean,
)

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val controller: RunController,
    private val pairing: PairingService,
    private val repository: RunRepository,
    private val link: CompanionLink,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val gpsOffset = controller.live.map { it.gpsOffsetMs }.distinctUntilChanged()

    private val runActive = controller.state.map { it == RunState.RECORDING || it == RunState.PAUSED }.distinctUntilChanged()

    /** Pairing QR for the current run (re-generated when the run or its GPS offset changes). */
    private val ownQr = combine(controller.state, controller.runId, gpsOffset) { st, id, off -> Triple(st, id, off) }
        .map { (st, id, off) ->
            if (id == null || (st != RunState.RECORDING && st != RunState.PAUSED)) null
            else runCatching { repository.getRun(id)?.let { pairing.payloadFor(it, off) } }.getOrNull()
        }

    private data class Partner(val payload: PairingPayload?, val linkedOk: Boolean, val error: String?, val pending: Boolean)

    private val partner = combine(link.scanned, link.lastLinked, controller.runId) { sc, last, id ->
        val lastForRun = last?.takeIf { it.runId == id }
        Partner(
            payload = sc?.second ?: lastForRun?.payload,
            linkedOk = lastForRun?.result?.isSuccess == true,
            error = lastForRun?.result?.exceptionOrNull()?.let { it.message ?: "Could not link" },
            pending = sc != null,
        )
    }

    private val history = repository.observeRuns().map { list -> CompanionDevices.from(list.map { it.run }) }

    private val status = combine(runActive, gpsOffset, pairing.live.connected) { a, off, conn -> Triple(a, off, conn) }

    val ui: StateFlow<CompanionsUi> = combine(history, partner, status, ownQr) { hist, p, st, qr ->
        val (active, ourOffset, connected) = st
        val rows = mutableListOf<CompanionRow>()
        val payload = p.payload
        var partnerKey: String? = null
        if (payload != null) {
            val name = payload.model.ifBlank { if (payload.platform.equals("ios", true)) "iPhone" else "Phone" }
            partnerKey = name.lowercase()
            val live = active && (p.linkedOk || connected)
            val theirs = payload.gpsOffsetMs
            val gap = if (ourOffset != null && theirs != null) abs(ourOffset - theirs) else null
            rows += CompanionRow(
                key = "partner|$partnerKey", name = name, platform = payload.platform.lowercase(),
                status = when {
                    live -> "Linked · recording"
                    p.pending && !active -> "Links when the test starts"
                    p.pending -> "Linking…"
                    p.error != null -> "Link failed"
                    else -> "Not nearby"
                },
                live = live, gapMs = if (live) gap else null,
                openRunId = hist.firstOrNull { it.name.equals(name, true) }?.openRunId,
            )
        }
        hist.filter { it.name.lowercase() != partnerKey }.forEach { d ->
            rows += CompanionRow(d.key, d.name, d.platform, "Not nearby", live = false, gapMs = null, openRunId = d.openRunId)
        }
        CompanionsUi(
            rows = rows, liveCount = rows.count { it.live }, ownQr = qr, runActive = active,
            linkError = p.error, pending = p.pending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompanionsUi(emptyList(), 0, null, false, null, false))

    val startCompanions: StateFlow<Boolean> = settings.startCompanions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setStartCompanions(on: Boolean) { viewModelScope.launch { settings.setStartCompanions(on) } }

    /** @return false when the QR is not a Tracer pairing code. */
    fun onScanned(text: String): Boolean = link.onScanned(text)

    fun clearPartner() = link.clear()
}
