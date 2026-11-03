package app.tracer.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import app.tracer.core.cell.CarrierSpec
import app.tracer.core.cell.Carriers
import app.tracer.core.cell.CellNormalizer
import app.tracer.core.model.CarrierSample
import app.tracer.core.model.CellSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.Sample
import app.tracer.core.runtime.Collector
import app.tracer.core.runtime.FixAction
import app.tracer.core.runtime.CollectorContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * [cellular] — per active subscription: serving + neighbour cells (CellSample via CellNormalizer)
 * from TelephonyCallback and periodic requestCellInfoUpdate, and RadioSample (RAT, NR state,
 * carrier aggregation — via [Carriers.derive]) on display-info / service-state change and every 5 s,
 * with CarrierSample rows (x_android_carrier) when the carrier list changes or every 30 s.
 * Re-registers on SIM changes.
 */
class CellularCollector @Inject constructor(
    @ApplicationContext private val context: Context,
) : Collector {
    override val id = "cellular"
    override val watchdogMs: Long = 15_000L

    @SuppressLint("MissingPermission")
    override fun samples(ctx: CollectorContext): Flow<Sample> = callbackFlow<Sample> {
        if (!context.hasAllPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)) {
            ctx.status.unavailable(id, "Phone and precise location permissions are required for cell logging.", FixAction.APP_PERMISSIONS)
            awaitClose { }
            return@callbackFlow
        }
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val baseTm = context.getSystemService(TelephonyManager::class.java)
        if (sm == null || baseTm == null) {
            ctx.status.unavailable(id, "This device has no cellular radio.")
            awaitClose { }
            return@callbackFlow
        }
        val settings = ctx.settings.effective()
        val worker = Worker(id)
        val out: SendChannel<Sample> = channel
        val sessions = ConcurrentHashMap<Int, SubscriptionSession>()

        fun emitCells(session: SubscriptionSession, cells: List<CellInfo>) {
            val subId = session.subId
            val stamp = ctx.clock.now()
            val nowMono = ctx.clock.now().tMonoNs
            // Primary serving cell first: the event engine treats the first serving row of a poll as
            // the PCell. Pixel modems also flag a second LTE cell as registered that carries the
            // PCell's cell id (seen on B20 next to a B3 PCell) — that is not a serving cell, so it is
            // demoted to a neighbour unless the modem marks it as a CA secondary.
            val rank = { c: CellInfo ->
                when {
                    c.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING -> 0
                    c.isRegistered -> 1
                    else -> 2
                }
            }
            val samples = cells.sortedBy(rank).mapNotNull { info ->
                info.toRawCell()?.let { raw -> info to CellNormalizer.normalize(raw, ctx.runId, stamp, subId, nowMono) }
            }
            val primary = samples.firstOrNull { it.second.serving }?.second
            val rows = samples.map { (info, sample) ->
                val bogusServing = sample.serving && sample !== primary && primary != null &&
                    sample.rat == primary.rat && sample.cellId != null && sample.cellId == primary.cellId &&
                    info.cellConnectionStatus != CellInfo.CONNECTION_SECONDARY_SERVING
                if (bogusServing) sample.copy(serving = false) else sample
            }
            // Last poll only (replaced, never accumulated): input to Carriers.derive for radio rows.
            session.latestCells = rows
            for (row in rows) {
                if (!out.emitFromCallback(ctx, id, row)) return
            }
        }

        /**
         * Emits a RadioSample with 5G state / carriers derived by [Carriers.derive] from the display
         * RAT, ServiceState.getCellBandwidths() and the latest normalized cells of this subscription,
         * plus one CarrierSample per component carrier — the latter only when the carrier list changed
         * since the last emission or [CARRIER_REFRESH_MS] passed (keeps multi-hour runs lean).
         * Runs on the worker thread (all callers do).
         */
        fun emitRadio(s: SubscriptionSession) {
            val display = s.displayInfo
            val networkType = display?.networkType
                ?: s.dataNetworkType.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
                ?: frameworkCall { s.tm.dataNetworkType }
                ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
            val displayRat = ratOf(display?.overrideNetworkType ?: TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, networkType)
            val bandwidths = s.serviceState?.let { st -> frameworkCall { st.cellBandwidths.toList() } }.orEmpty()
            val derived = Carriers.derive(displayRat, bandwidths, s.latestCells)
            val stamp = ctx.clock.now()
            val radio = RadioSample(
                runId = ctx.runId,
                stamp = stamp,
                serviceId = s.subId.toString(),
                rat = derived.rat,
                nrState = derived.nrState,
                carrierAggregation = derived.carrierAggregationJson,
            )
            if (!out.emitFromCallback(ctx, id, radio)) return
            val nowMs = SystemClock.elapsedRealtime()
            val changed = derived.carriers != s.lastCarriers
            if (!changed && nowMs - s.lastCarriersMonoMs < CARRIER_REFRESH_MS) return
            s.lastCarriers = derived.carriers
            s.lastCarriersMonoMs = nowMs
            for (c in derived.carriers) {
                val row = CarrierSample(
                    runId = ctx.runId,
                    stamp = stamp,
                    serviceId = s.subId.toString(),
                    index = c.index,
                    rat = c.rat,
                    band = c.band,
                    arfcn = c.arfcn,
                    pci = c.pci,
                    bandwidthMhz = c.bandwidthMhz,
                    primary = c.primary,
                    source = c.source,
                )
                if (!out.emitFromCallback(ctx, id, row)) {
                    s.lastCarriers = null // paused / closed mid-list: re-emit next time
                    return
                }
            }
        }

        fun requestUpdate(s: SubscriptionSession) {
            s.lastRequestMonoMs = SystemClock.elapsedRealtime()
            frameworkCall({ ctx.status.degraded(id, "Cell info refresh refused: ${it.message}") }) {
                s.tm.requestCellInfoUpdate(worker.executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) = emitCells(s, cellInfo)
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        // Modem busy / timeout: the next poll retries; cached CellInfoListener rows still flow.
                    }
                })
            }
        }

        val listeners = object : SessionListener {
            override fun onCells(session: SubscriptionSession, cells: List<CellInfo>) = emitCells(session, cells)
            override fun onRadioChanged(session: SubscriptionSession) = emitRadio(session)
            override fun onSignalChanged(session: SubscriptionSession) {
                // Signal moved: refresh cell measurements sooner than the poll if the last request is old.
                val half = settings.cellPollIntervalMs / 2
                if (!ctx.signals.paused.value && SystemClock.elapsedRealtime() - session.lastRequestMonoMs >= half) requestUpdate(session)
            }
        }

        fun register(subId: Int): SubscriptionSession? {
            val tm = baseTm.createForSubscriptionId(subId)
            val session = SubscriptionSession(subId, tm, listeners)
            val ok = frameworkCall({ ctx.status.degraded(id, "Telephony callback refused for SIM $subId: ${it.message}") }) {
                tm.registerTelephonyCallback(worker.executor, session)
                true
            } ?: false
            return if (ok) session else null
        }

        fun unregister(session: SubscriptionSession) {
            frameworkCall { session.tm.unregisterTelephonyCallback(session) }
        }

        /** Runs on the worker thread. */
        fun syncSubscriptions() {
            val active = frameworkCall { sm.activeSubscriptionInfoList }.orEmpty().map { it.subscriptionId }.toSet()
            sessions.keys.filter { it !in active }.forEach { subId -> sessions.remove(subId)?.let(::unregister) }
            active.filter { it !in sessions.keys }.forEach { subId -> register(subId)?.let { sessions[subId] = it } }
            if (active.isEmpty()) ctx.status.degraded(id, "No active SIM. Insert or enable a SIM to log cells.")
            else ctx.status.ok(id)
        }

        // The no-arg OnSubscriptionsChangedListener constructor binds a Handler to Looper.myLooper(),
        // so it must be created on the worker's looper thread.
        val subsListener: SubscriptionManager.OnSubscriptionsChangedListener = try {
            withContext(worker.handler.asCoroutineDispatcher()) {
                object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() = syncSubscriptions()
                }
            }
        } catch (e: CancellationException) {
            worker.quit()
            throw e
        }
        frameworkCall { sm.addOnSubscriptionsChangedListener(worker.executor, subsListener) }
        worker.handler.post { syncSubscriptions() }

        // Poll: cached CellInfo can be stale, so actively request fresh measurements.
        launch {
            while (isActive) {
                ctx.signals.awaitResumed()
                sessions.values.forEach { s -> worker.handler.post { requestUpdate(s) } }
                delay(settings.cellPollIntervalMs.coerceAtLeast(250L))
            }
        }
        // RadioSample heartbeat: at least every 5 s per subscription.
        launch {
            while (isActive) {
                delay(5_000L)
                ctx.signals.awaitResumed()
                sessions.values.forEach { s -> worker.handler.post { emitRadio(s) } }
            }
        }

        awaitClose {
            frameworkCall { sm.removeOnSubscriptionsChangedListener(subsListener) }
            sessions.values.forEach(::unregister)
            sessions.clear()
            worker.quit()
        }
    }.buffer(512)

    private companion object {
        /** CarrierSamples are re-emitted at least this often even when the carrier list is unchanged. */
        const val CARRIER_REFRESH_MS = 30_000L
    }
}

private interface SessionListener {
    fun onCells(session: SubscriptionSession, cells: List<CellInfo>)
    fun onRadioChanged(session: SubscriptionSession)
    fun onSignalChanged(session: SubscriptionSession)
}

/** One subscription's TelephonyCallback plus its latest radio state. */
private class SubscriptionSession(
    val subId: Int,
    val tm: TelephonyManager,
    private val listener: SessionListener,
) : TelephonyCallback(),
    TelephonyCallback.CellInfoListener,
    TelephonyCallback.SignalStrengthsListener,
    TelephonyCallback.DisplayInfoListener,
    TelephonyCallback.ServiceStateListener,
    TelephonyCallback.DataConnectionStateListener {

    @Volatile var displayInfo: TelephonyDisplayInfo? = null
    @Volatile var serviceState: ServiceState? = null
    @Volatile var dataNetworkType: Int = TelephonyManager.NETWORK_TYPE_UNKNOWN
    @Volatile var lastRequestMonoMs: Long = 0L
    /** Normalized cells of the last poll (replaced each poll — bounded). */
    @Volatile var latestCells: List<CellSample> = emptyList()
    /** Carrier list of the last CarrierSample emission (worker thread only). */
    var lastCarriers: List<CarrierSpec>? = null
    var lastCarriersMonoMs: Long = 0L

    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = listener.onCells(this, cellInfo)

    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = listener.onSignalChanged(this)

    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
        displayInfo = telephonyDisplayInfo
        listener.onRadioChanged(this)
    }

    override fun onServiceStateChanged(serviceState: ServiceState) {
        this.serviceState = serviceState
        listener.onRadioChanged(this)
    }

    override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
        dataNetworkType = networkType
        listener.onRadioChanged(this)
    }
}
