package app.tracer.ui.preflight

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.config.RunSettings
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.runtime.DeviceInfoProvider
import app.tracer.core.runtime.RunController
import app.tracer.ui.R
import app.tracer.ui.common.ReadinessStatus
import app.tracer.ui.common.UiText
import app.tracer.ui.common.formatBytes
import app.tracer.ui.common.formatDateTime
import app.tracer.ui.common.formatMs
import app.tracer.ui.companion.CompanionLink
import app.tracer.ui.onboarding.Permissions
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

enum class FixAction { PERMISSIONS, LOCATION_SETTINGS, BATTERY, DEV_OPTIONS, RETRY_ENDPOINTS, COMPANION, STORAGE }

data class ReadinessUi(val title: UiText, val sub: UiText, val status: ReadinessStatus, val fix: FixAction? = null)

data class RunSetup(
    val name: String,
    /** walk | drive | rail | custom */
    val mode: String = "drive",
    val batterySaver: Boolean = false,
    val simulate: Boolean = false,
)

data class PreflightUi(
    val items: List<ReadinessUi>,
    val ready: Int,
    /** Start disabled only for hard blockers: location not precise + all-the-time, storage below minimum. */
    val hardBlocked: Boolean,
    val partner: PairingPayload?,
    val deltaMs: Long?,
)

enum class IntervalField { SAMPLING, PROBES, WIFI_SCAN }

@HiltViewModel
class PreflightViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: RunController,
    private val settingsRepository: SettingsRepository,
    private val deviceInfo: DeviceInfoProvider,
    private val companion: CompanionLink,
) : ViewModel() {

    private data class Statics(
        val fine: Boolean, val background: Boolean, val granted: Int, val total: Int,
        val batteryPct: Int?, val batteryExempt: Boolean, val freeBytes: Long?,
        val sims: Int?, val throttled: Boolean?,
    )

    private data class Endpoints(val ok: Int, val total: Int, val medianMs: Double?)

    val isDebugBuild: Boolean = 0 != (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)

    val settings: StateFlow<RunSettings?> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val simScenario: StateFlow<String?> = settingsRepository.debugSimScenario.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val linkOnStart: StateFlow<Boolean> = companion.linkOnStart

    private val _setup = MutableStateFlow(RunSetup(name = context.getString(R.string.preflight_default_name, formatDateTime(System.currentTimeMillis()))))
    val setup: StateFlow<RunSetup> = _setup.asStateFlow()

    private val statics = MutableStateFlow(readStatics())
    private val endpoints = MutableStateFlow<Endpoints?>(null)
    private val gnss = gnssStateFlow(context).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnssState(permission = false, providerEnabled = false))

    private val inputs = combine(statics, endpoints, gnss) { s, e, g -> Triple(s, e, g) }

    val ui: StateFlow<PreflightUi?> = combine(inputs, settings, companion.scanned) { (s, e, g), st, scanned ->
        st?.let { build(s, e, g, it, scanned?.second) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            // seed battery-saver toggle from the stored default once
            val first = settingsRepository.settings.first()
            _setup.update { it.copy(batterySaver = first.batterySaver) }
        }
        checkEndpoints()
    }

    fun refresh() {
        statics.value = readStatics()
    }

    fun checkEndpoints() {
        viewModelScope.launch {
            val list = settingsRepository.settings.first().endpoints
            endpoints.value = null
            val times = withContext(Dispatchers.IO) { list.map { async { head(it.url) } }.awaitAll() }
            val ok = times.filterNotNull().sorted()
            endpoints.value = Endpoints(ok.size, list.size, ok.getOrNull(ok.size / 2)?.toDouble())
        }
    }

    fun setName(v: String) = _setup.update { it.copy(name = v) }
    fun setMode(v: String) = _setup.update { it.copy(mode = v) }
    fun setBatterySaver(v: Boolean) = _setup.update { it.copy(batterySaver = v) }
    fun setSimulate(v: Boolean) = _setup.update { it.copy(simulate = v) }
    fun setLinkOnStart(v: Boolean) = companion.setLinkOnStart(v)

    fun setInterval(field: IntervalField, ms: Long) {
        viewModelScope.launch {
            settingsRepository.update { s ->
                when (field) {
                    IntervalField.SAMPLING -> s.copy(locationIntervalMs = ms)
                    IntervalField.PROBES -> s.copy(latencyIntervalMs = ms)
                    IntervalField.WIFI_SCAN -> s.copy(wifiScanIntervalMs = ms)
                }
            }
        }
    }

    /** Synchronous so it completes even though navigation clears this ViewModel right after. */
    fun start() {
        val s = _setup.value
        val base = settings.value ?: RunSettings()
        val scenario = if (isDebugBuild && s.simulate) simScenario.value ?: "commute" else null
        val name = s.name.ifBlank { context.getString(R.string.preflight_default_name, formatDateTime(System.currentTimeMillis())) }
        controller.start(name, s.mode, base.copy(batterySaver = s.batterySaver), scenario)
    }

    // ------------------------------------------------------------------ checks

    private fun readStatics(): Statics {
        val (granted, total) = Permissions.runtimeSummary(context)
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        return Statics(
            fine = Permissions.fineLocation(context),
            background = Permissions.backgroundLocation(context),
            granted = granted, total = total,
            batteryPct = battery,
            batteryExempt = runCatching { deviceInfo.isIgnoringBatteryOptimizations() }.getOrElse { Permissions.batteryExempt(context) },
            freeBytes = runCatching { deviceInfo.freeStorageBytes() }.getOrNull(),
            sims = runCatching { deviceInfo.activeSims().size }.getOrNull(),
            throttled = runCatching { deviceInfo.wifiScanThrottled() }.getOrNull(),
        )
    }

    private fun build(s: Statics, e: Endpoints?, g: GnssState, st: RunSettings, partner: PairingPayload?): PreflightUi {
        val items = ArrayList<ReadinessUi>(8)

        // Permissions
        val locOk = s.fine && s.background
        items += ReadinessUi(
            UiText.Res(R.string.pf_permissions),
            UiText.Res(if (s.background) R.string.pf_permissions_sub_always else R.string.pf_permissions_sub_while, listOf(s.granted, s.total)),
            when { !locOk -> ReadinessStatus.BLOCKED; s.granted < s.total -> ReadinessStatus.WARN; else -> ReadinessStatus.OK },
            FixAction.PERMISSIONS,
        )

        // GPS lock
        val acc = g.hAccM?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
        items += when {
            !g.permission -> ReadinessUi(UiText.Res(R.string.pf_gps), UiText.Res(R.string.pf_gps_no_permission), ReadinessStatus.BLOCKED, FixAction.PERMISSIONS)
            !g.providerEnabled -> ReadinessUi(UiText.Res(R.string.pf_gps), UiText.Res(R.string.pf_gps_off), ReadinessStatus.BLOCKED, FixAction.LOCATION_SETTINGS)
            g.hasFix -> ReadinessUi(
                UiText.Res(R.string.pf_gps), UiText.Res(R.string.pf_gps_sub, listOf(acc, g.used)),
                if ((g.hAccM ?: 99f) <= 10f) ReadinessStatus.OK else ReadinessStatus.WARN,
            )
            else -> ReadinessUi(UiText.Res(R.string.pf_gps), UiText.Res(R.string.pf_gps_searching, listOf(g.visible)), ReadinessStatus.PENDING)
        }

        // Battery
        val pct = s.batteryPct?.toString() ?: "—"
        items += ReadinessUi(
            UiText.Res(R.string.pf_battery),
            UiText.Res(if (s.batteryExempt) R.string.pf_battery_sub_unrestricted else R.string.pf_battery_sub_optimised, listOf(pct)),
            if (s.batteryExempt && (s.batteryPct ?: 100) > 20) ReadinessStatus.OK else ReadinessStatus.WARN,
            FixAction.BATTERY,
        )

        // Storage
        val minBytes = st.minFreeStorageMb * 1024L * 1024L
        val storageLow = s.freeBytes != null && s.freeBytes < minBytes
        items += ReadinessUi(
            UiText.Res(R.string.pf_storage),
            s.freeBytes?.let { UiText.Res(R.string.pf_storage_sub, listOf(formatBytes(it))) } ?: UiText.Res(R.string.pf_unknown),
            when { storageLow -> ReadinessStatus.BLOCKED; s.freeBytes != null && s.freeBytes < 2 * minBytes -> ReadinessStatus.WARN; s.freeBytes == null -> ReadinessStatus.WARN; else -> ReadinessStatus.OK },
            FixAction.STORAGE,
        )

        // Probe endpoints
        items += if (e == null) {
            ReadinessUi(UiText.Res(R.string.pf_endpoints), UiText.Res(R.string.pf_checking), ReadinessStatus.PENDING)
        } else {
            ReadinessUi(
                UiText.Res(R.string.pf_endpoints),
                UiText.Res(R.string.pf_endpoints_sub, listOf(e.ok, e.total, formatMs(e.medianMs))),
                when { e.ok == e.total -> ReadinessStatus.OK; e.ok > 0 -> ReadinessStatus.WARN; else -> ReadinessStatus.BLOCKED },
                FixAction.RETRY_ENDPOINTS,
            )
        }

        // Companion
        val delta = deltaMs(g, partner)
        items += if (partner == null) {
            ReadinessUi(UiText.Res(R.string.pf_companion), UiText.Res(R.string.pf_companion_none), ReadinessStatus.WARN, FixAction.COMPANION)
        } else {
            ReadinessUi(
                UiText.Raw(partner.model.ifBlank { partner.platform }),
                if (delta != null) UiText.Res(R.string.pf_companion_delta, listOf(signed(delta))) else UiText.Res(R.string.pf_companion_no_gps),
                if (delta != null && abs(delta) < 50) ReadinessStatus.OK else ReadinessStatus.WARN,
                FixAction.COMPANION,
            )
        }

        // Cell info API
        val hz = rateHz(st.cellPollIntervalMs)
        items += ReadinessUi(
            UiText.Res(R.string.pf_cell),
            when (val n = s.sims) {
                null -> UiText.Res(R.string.pf_cell_sub_unknown, listOf(hz))
                0 -> UiText.Res(R.string.pf_cell_sub_nosim, listOf(hz))
                else -> UiText.Res(R.string.pf_cell_sub, listOf(hz, n))
            },
            if ((s.sims ?: 0) > 0) ReadinessStatus.OK else ReadinessStatus.WARN,
            if (s.sims == null) FixAction.PERMISSIONS else null,
        )

        // Wi-Fi throttling (+ raw GNSS)
        val raw = if (st.gnssRawMeasurements) "✓" else "off"
        items += ReadinessUi(
            UiText.Res(R.string.pf_wifi_throttle),
            when (s.throttled) {
                false -> UiText.Res(R.string.pf_wifi_throttle_off, listOf(raw))
                true -> UiText.Res(R.string.pf_wifi_throttle_on, listOf(raw))
                null -> UiText.Res(R.string.pf_wifi_throttle_unknown, listOf(raw))
            },
            if (s.throttled == false) ReadinessStatus.OK else ReadinessStatus.WARN,
            FixAction.DEV_OPTIONS,
        )

        return PreflightUi(
            items = items,
            ready = items.count { it.status == ReadinessStatus.OK },
            hardBlocked = !locOk || storageLow,
            partner = partner,
            deltaMs = delta,
        )
    }

    companion object {
        /** Partner clock − our clock, via both GNSS offsets (true time = wall + gpsOffset on each device). */
        fun deltaMs(g: GnssState, partner: PairingPayload?): Long? {
            val ours = g.gpsOffsetMs ?: return null
            val theirs = partner?.gpsOffsetMs ?: return null
            return ours - theirs
        }

        fun signed(v: Long): String = if (v < 0) "−${-v}" else "+$v"

        /** 1000 ms → "1"; 500 → "2"; 2000 → "0.5" (Hz). */
        fun rateHz(intervalMs: Long): String = trim(1000.0 / intervalMs.coerceAtLeast(1))

        /** 2000 ms → "2" (seconds). */
        fun seconds(intervalMs: Long): String = trim(intervalMs / 1000.0)

        private fun trim(v: Double): String =
            if (v == floor(v)) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)

        /** HEAD with 3 s connect/read timeouts; any HTTP response counts as reachable. Returns ms or null. */
        fun head(url: String): Long? = try {
            val t0 = SystemClock.elapsedRealtime()
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "HEAD"
                c.connectTimeout = 3_000
                c.readTimeout = 3_000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.responseCode
                SystemClock.elapsedRealtime() - t0
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
