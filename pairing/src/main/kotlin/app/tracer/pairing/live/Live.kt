package app.tracer.pairing.live

import app.tracer.core.runtime.LiveLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** v1 [LiveLink]: never connects; advertise/stop are no-ops. */
@Singleton
class NoopLiveLink @Inject constructor() : LiveLink {
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
}

/** One live status update exchanged between paired devices (v2). */
data class LiveStatusMessage(
    val runId: String,
    val tUtcMs: Long,
    val rat: String?,
    val rsrp: Int?,
    val latencyMs: Double?,
    val lat: Double?,
    val lon: Double?,
)

/**
 * v2 transport for live status between two paired phones on the same local network. Stub only.
 *
 * Planned design:
 *  - Discovery: each recording device registers an NSD (DNS-SD) service of type `_tracer._tcp`
 *    via `NsdManager.registerService`, with TXT records `run_id`, `platform` and `v=1`; the peer
 *    browses `_tracer._tcp` and only resolves the service whose `run_id` equals the linked run id
 *    learnt from the pairing QR (so strangers on the same Wi-Fi are ignored).
 *  - Session: a plain TCP socket on the advertised port carrying newline-delimited JSON
 *    [LiveStatusMessage]s at ≤ 1 Hz in each direction; the QR payload's run id doubles as a
 *    pre-shared token checked in the first line. No retries beyond reconnect-on-resolve.
 *  - iOS speaks the same protocol through Network.framework (NWBrowser for `_tracer._tcp`).
 */
interface LiveStatusTransport {
    /** Messages received from the peer while connected. */
    val incoming: Flow<LiveStatusMessage>

    /** Advertise [runId] and look for the peer recording [peerRunId]. */
    suspend fun start(runId: String, peerRunId: String)

    suspend fun send(message: LiveStatusMessage)

    suspend fun stop()
}
