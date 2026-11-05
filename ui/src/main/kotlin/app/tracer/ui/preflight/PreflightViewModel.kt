package app.tracer.ui.preflight

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tracer.core.analysis.Method
import app.tracer.core.config.ProbePaths
import app.tracer.core.config.RunSettings
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.report.Verdict
import app.tracer.core.runtime.DeviceInfoProvider
import app.tracer.core.runtime.RunController
import app.tracer.core.runtime.RunListItem
import app.tracer.core.runtime.RunRepository
import app.tracer.ui.R
import app.tracer.ui.common.formatDateTime
import app.tracer.ui.companion.CompanionLink
import app.tracer.ui.onboarding.Permissions
import app.tracer.ui.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Where tapping a readiness row takes the user. */
enum class FixAction { PERMISSIONS, LOCATION_SETTINGS, BATTERY, RETRY_ENDPOINTS, COMPANION, STORAGE }

/** Dot colour of a readiness row: green / yellow (note) / red (must fix) / grey (checking). */
enum class CheckStatus { OK, NOTE, BLOCKED, PENDING }

/**
 * One readiness row: "● Location · Precise · all the time".
 * @param note short reason for the header sub-line ("indoors") when [status] is NOTE/BLOCKED
 */
data class CheckRow(val name: String, val value: String, val status: CheckStatus, val fix: FixAction? = null, val note: String? = null)

data class Readiness(
    val rows: List<CheckRow>,
    val passed: Int,
    /** Start disabled only for hard blockers: location not precise + all the time, storage below minimum. */
    val hardBlocked: Boolean,
    /** Linked companions (the scanned partner while "link on start" is on). */
    val companions: List<PairingPayload>,
) {
    val total: Int get() = rows.size
    val title: String get() = if (hardBlocked) "Not ready yet" else "Ready to go"
    /** "1 note · indoors" / "1 to fix · location" / "All checks passed" / "Checking…" */
    val sub: String get() {
        val blocked = rows.filter { it.status == CheckStatus.BLOCKED }
        val notes = rows.filter { it.status == CheckStatus.NOTE }
        return when {
            blocked.isNotEmpty() -> "${blocked.size} to fix" + (blocked.first().note?.let { " · $it" } ?: "")
            notes.isNotEmpty() -> "${notes.size} ${if (notes.size == 1) "note" else "notes"}" + (notes.first().note?.let { " · $it" } ?: "")
            rows.any { it.status == CheckStatus.PENDING } -> "Checking…"
            else -> "All checks passed"
        }
    }
}

/** walk | drive | rail | static */
data class NewTestSetup(
    val site: String = "",
    val mode: String = "walk",
    /** "dual" | "wifi" | "cellular" */
    val networks: String = "dual",
    /** 0 = no limit */
    val lengthMin: Int = 10,
    val simulate: Boolean = false,
    /** Destination station for the report's route map ("" = auto: nearest station to where the run ends) */
    val destination: String = "",
) {
    val siteOrNull: String? get() = site.trim().takeIf { it.isNotEmpty() }
    val paths: ProbePaths get() = when (networks) { "wifi" -> ProbePaths.WIFI_ONLY; "cellular" -> ProbePaths.CELLULAR_ONLY; else -> ProbePaths.DUAL }
}

/** "Comparable with Office tests 1–3" etc. */
data class Comparability(val text: String, val comparable: Boolean)

/**
 * New-test (design frame 1) state: readiness checks, setup (site / movement / networks / length),
 * next test number, comparability with earlier tests at the site, mobile-data estimate, and start.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PreflightViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: RunController,
    private val repository: RunRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceInfo: DeviceInfoProvider,
    private val companion: CompanionLink,
    private val reportExporter: app.tracer.ui.report.ReportV1Exporter,
) : ViewModel() {

    /** Stations of the rail overlay (empty when the internal ERGT asset is not bundled). */
    val stations: List<String> by lazy { runCatching { reportExporter.stations() }.getOrDefault(emptyList()) }
    fun setDestination(v: String) = _setup.update { it.copy(destination = v) }

    private data class Statics(
        val fine: Boolean, val background: Boolean,
        val batteryPct: Int?, val batteryExempt: Boolean, val freeBytes: Long?,
        val sims: Int?, val dataRat: String?,
        /** recent non-GPS fix (fused / network): accuracy m */
        val netAccM: Double?,
    )

    private data class Endpoints(val ok: Int, val total: Int, val medianMs: Double?)

    val isDebugBuild: Boolean = 0 != (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)

    val settings: StateFlow<RunSettings?> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val simScenario: StateFlow<String?> = settingsRepository.debugSimScenario.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val startCompanions: StateFlow<Boolean> = settingsRepository.startCompanions.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _setup = MutableStateFlow(NewTestSetup())
    val setup: StateFlow<NewTestSetup> = _setup.asStateFlow()

    /** Next test number for the typed site (null while blank). */
    val testNumber: StateFlow<Int?> = _setup.map { it.siteOrNull }.distinctUntilChanged().debounce(250).mapLatest { site ->
        site?.let { runCatching { repository.nextTestNumber(it) }.getOrDefault(1) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val statics = MutableStateFlow(readStatics())
    private val endpoints = MutableStateFlow<Endpoints?>(null)
    private val gnss = gnssStateFlow(context).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnssState(permission = false, providerEnabled = false))

    private val partner: StateFlow<PairingPayload?> = combine(companion.scanned, companion.linkOnStart) { sc, on -> if (on) sc?.second else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val readiness: StateFlow<Readiness?> = combine(statics, endpoints, gnss, settings, partner) { s, e, g, st, p ->
        st?.let { build(s, e, g, it, p) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val runs: StateFlow<List<RunListItem>> = repository.observeRuns().catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Method of the run that would start now, compared with earlier tests at the same site. */
    val comparability: StateFlow<Comparability?> = combine(_setup, settings, runs) { setup, st, list ->
        val site = setup.siteOrNull
        if (st == null || site == null) null else {
            val fp = Method.fingerprint(runSettings(st, setup), null)
            val earlier = list.map { it.run }.filter { it.site.equals(site, ignoreCase = true) && it.testNumber != null }
            val same = earlier.filter { it.methodFingerprint == fp }.mapNotNull { it.testNumber }.distinct().sorted()
            when {
                earlier.isEmpty() -> Comparability("First test at $site", true)
                same.isNotEmpty() -> Comparability("Comparable with $site ${if (same.size == 1) "test" else "tests"} ${numbers(same)}", true)
                else -> Comparability("Settings differ from earlier $site tests", false)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val site = runCatching { settingsRepository.lastSite.first() }.getOrNull()
            val nets = runCatching { settingsRepository.defaultNetworks.first() }.getOrDefault("dual")
            val len = runCatching { settingsRepository.defaultLengthMin.first() }.getOrDefault(10)
            val move = runCatching { settingsRepository.defaultMovement.first() }.getOrDefault("walk")
            _setup.update {
                it.copy(
                    site = if (it.site.isEmpty()) site.orEmpty() else it.site,
                    networks = nets.takeIf { n -> n in NETWORKS } ?: "dual",
                    lengthMin = len.coerceAtLeast(0),
                    mode = move.takeIf { m -> m in MODES } ?: "walk",
                )
            }
        }
        checkEndpoints()
    }

    fun refresh() { statics.value = readStatics() }

    fun checkEndpoints() {
        viewModelScope.launch {
            val list = settingsRepository.settings.first().endpoints
            endpoints.value = null
            val times = withContext(Dispatchers.IO) { list.map { async { head(it.url) } }.awaitAll() }
            val ok = times.filterNotNull().sorted()
            endpoints.value = Endpoints(ok.size, list.size, ok.getOrNull(ok.size / 2)?.toDouble())
        }
    }

    fun setSite(v: String) = _setup.update { it.copy(site = v.take(MAX_SITE_LEN)) }
    fun setMode(v: String) = _setup.update { it.copy(mode = v) }
    fun setNetworks(v: String) = _setup.update { it.copy(networks = v) }
    fun setLengthMin(v: Int) = _setup.update { it.copy(lengthMin = v.coerceAtLeast(0)) }
    fun setSimulate(v: Boolean) = _setup.update { it.copy(simulate = v) }

    /** "≈ 20 MB" of mobile data one phone uses for the chosen networks + length; null = none (Wi-Fi only). */
    fun mobileDataLabel(st: RunSettings, setup: NewTestSetup): String? {
        val mb = estimateMobileMb(runSettings(st, setup), setup.paths, if (setup.lengthMin > 0) setup.lengthMin else 10) ?: return null
        return "≈ $mb MB mobile data" + if (setup.lengthMin == 0) " per 10 min" else ""
    }

    /** Free runs still record their recipe as the protocol, e.g. "free:walk-dual-10m" (Protocol.byId → null, no fixed settings). */
    private fun freeProtocolId(s: NewTestSetup): String = "free:${s.mode}-${s.networks}-${if (s.lengthMin > 0) "${s.lengthMin}m" else "open"}"

    /** Synchronous so it completes even though navigation clears this ViewModel right after. */
    fun start() {
        val s = _setup.value
        val base = settings.value ?: RunSettings()
        val scenario = if (isDebugBuild && s.simulate) simScenario.value ?: "commute" else null
        val name = context.getString(R.string.preflight_default_name, formatDateTime(System.currentTimeMillis()))
        val site = s.siteOrNull
        controller.start(
            name, s.mode, runSettings(base, s), scenario, site = site, protocolId = freeProtocolId(s),
            durationLimitMs = if (s.lengthMin > 0) s.lengthMin * 60_000L else null,
        )
        // outlives this ViewModel (navigation clears it right after start)
        persistScope.launch { runCatching { settingsRepository.setLastSite(site) } }
        val dest = s.destination
        if (dest.isNotBlank()) persistScope.launch {
            runCatching {
                val id = kotlinx.coroutines.withTimeout(15_000) { controller.runId.filterNotNull().first() }
                settingsRepository.setDestination(id, dest)
            }
        }
    }

    // ------------------------------------------------------------------ checks

    private fun readStatics(): Statics {
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        return Statics(
            fine = Permissions.fineLocation(context),
            background = Permissions.backgroundLocation(context),
            batteryPct = battery,
            batteryExempt = runCatching { deviceInfo.isIgnoringBatteryOptimizations() }.getOrElse { Permissions.batteryExempt(context) },
            freeBytes = runCatching { deviceInfo.freeStorageBytes() }.getOrNull(),
            sims = runCatching { deviceInfo.activeSims().size }.getOrNull(),
            dataRat = dataRat(),
            netAccM = networkFixAccuracy(),
        )
    }

    /** "4G" / "5G" / "3G" from the data network type, null if unknown or not permitted. */
    @SuppressLint("MissingPermission") // checked via Permissions.phoneState
    private fun dataRat(): String? {
        if (!Permissions.phoneState(context)) return null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val t = runCatching { tm.dataNetworkType }.getOrNull() ?: return null
        return when (t) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            else -> null
        }
    }

    /** Accuracy of a recent (≤ 2 min) fused/network fix — the indoor position source. */
    @SuppressLint("MissingPermission") // checked via Permissions.fineLocation
    private fun networkFixAccuracy(): Double? {
        if (!Permissions.fineLocation(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        val now = SystemClock.elapsedRealtimeNanos()
        return providers.firstNotNullOfOrNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                ?.takeIf { it.hasAccuracy() && now - it.elapsedRealtimeNanos < 120_000_000_000L }
                ?.accuracy?.toDouble()
        }
    }

    private fun build(s: Statics, e: Endpoints?, g: GnssState, st: RunSettings, partner: PairingPayload?): Readiness {
        val rows = ArrayList<CheckRow>(8)
        val locOk = s.fine && s.background

        rows += when {
            !s.fine -> CheckRow("Location", "Not allowed", CheckStatus.BLOCKED, FixAction.PERMISSIONS, "location")
            !s.background -> CheckRow("Location", "Precise · only while open", CheckStatus.BLOCKED, FixAction.PERMISSIONS, "location")
            else -> CheckRow("Location", "Precise · all the time", CheckStatus.OK)
        }

        val gpsAcc = g.hAccM?.toDouble()
        rows += when {
            !g.permission -> CheckRow("Position", "No permission", CheckStatus.BLOCKED, FixAction.PERMISSIONS, "location")
            !g.providerEnabled && s.netAccM == null -> CheckRow("Position", "Location is off", CheckStatus.BLOCKED, FixAction.LOCATION_SETTINGS, "location off")
            g.hasFix && gpsAcc != null -> {
                val v = Verdict.position(gpsAcc)
                CheckRow("Position", "±${gpsAcc.roundToInt()} m · GPS",
                    if (v == Verdict.VERY_GOOD || v == Verdict.ABOVE_AVG) CheckStatus.OK else CheckStatus.NOTE, null, "rough position")
            }
            s.netAccM != null -> CheckRow("Position", "±${s.netAccM.roundToInt()} m · Wi-Fi", CheckStatus.NOTE, null, "indoors")
            else -> CheckRow("Position", "Searching · ${g.visible} satellites", CheckStatus.PENDING)
        }

        val pct = s.batteryPct
        rows += CheckRow(
            "Battery",
            (pct?.let { "$it %" } ?: "–") + if (s.batteryExempt) "" else " · may be paused",
            if (s.batteryExempt && (pct ?: 100) > 20) CheckStatus.OK else CheckStatus.NOTE,
            FixAction.BATTERY, if (!s.batteryExempt) "battery" else "low battery",
        )

        val minBytes = st.minFreeStorageMb * 1024L * 1024L
        val free = s.freeBytes
        val storageLow = free != null && free < minBytes
        rows += CheckRow(
            "Storage",
            free?.let { "${gb(it)} free" } ?: "Unknown",
            when { storageLow -> CheckStatus.BLOCKED; free == null || free < 2 * minBytes -> CheckStatus.NOTE; else -> CheckStatus.OK },
            FixAction.STORAGE, "storage",
        )

        rows += if (e == null) CheckRow("Test servers", "Checking…", CheckStatus.PENDING)
        else CheckRow(
            "Test servers", "${e.ok} of ${e.total} reachable",
            when { e.ok == e.total -> CheckStatus.OK; e.ok > 0 -> CheckStatus.NOTE; else -> CheckStatus.BLOCKED },
            FixAction.RETRY_ENDPOINTS, "test servers",
        )

        rows += when (s.sims) {
            null -> CheckRow("Mobile data", "Unknown", CheckStatus.NOTE, FixAction.PERMISSIONS, "mobile data")
            0 -> CheckRow("Mobile data", "No SIM", CheckStatus.NOTE, null, "no SIM")
            else -> CheckRow("Mobile data", listOfNotNull("Ready", s.dataRat).joinToString(" · "), CheckStatus.OK)
        }

        val companions = listOfNotNull(partner)
        for (p in companions) {
            val delta = deltaMs(g, p)
            val ms = delta?.let { abs(it) }
            val v = Verdict.link(ms?.toDouble())
            rows += CheckRow(
                p.model.ifBlank { p.platform },
                if (ms != null) "Linked · $ms ms" else "Linked",
                if (ms == null || v == Verdict.VERY_GOOD || v == Verdict.ABOVE_AVG) CheckStatus.OK else CheckStatus.NOTE,
                FixAction.COMPANION, "clock gap",
            )
        }

        return Readiness(rows, rows.count { it.status == CheckStatus.OK }, hardBlocked = !locOk || storageLow, companions = companions)
    }

    companion object {
        private const val MAX_SITE_LEN = 40
        val MODES = listOf("walk", "drive", "rail", "static")
        val NETWORKS = listOf("dual", "wifi", "cellular")
        val LENGTHS = listOf(5, 10, 15, 0)

        /** Process-lifetime scope for small writes that must survive the ViewModel (last site). */
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The user's settings with the chosen probe paths. */
        fun runSettings(base: RunSettings, setup: NewTestSetup): RunSettings =
            base.copy(probePaths = setup.paths, dualPathProbing = setup.paths == ProbePaths.DUAL)

        /**
         * Mobile data one phone uses in [minutes]: throughput tests that run over mobile data
         * (all of them on mobile-only; every 3rd cycle on the parallel path for Wi-Fi + mobile) plus
         * ~2 kB per latency probe on that path, capped by the cellular budget. Rounded to 5 MB above 10 MB.
         * null for Wi-Fi only.
         */
        fun estimateMobileMb(settings: RunSettings, paths: ProbePaths, minutes: Int): Long? {
            if (paths == ProbePaths.WIFI_ONLY) return null
            val st = settings.effective()
            val s = minutes * 60.0
            val tests = ceil(s * 1000.0 / st.throughputIntervalMs.coerceAtLeast(1)).toLong()
            val mobileTests = if (paths == ProbePaths.CELLULAR_ONLY) tests else (tests + 2) / 3
            val probes = (s * 1000.0 / st.latencyIntervalMs.coerceAtLeast(1)).toLong()
            val bytes = (mobileTests * st.throughputBytes + probes * 2_000L).coerceAtMost(st.cellularDataBudgetMb * 1_000_000L)
            val mb = bytes / 1_000_000.0
            return when {
                mb < 1 -> 1L
                mb < 10 -> mb.roundToLong()
                else -> (mb / 5).roundToLong() * 5
            }
        }

        /** [1,2,3] → "1–3"; [1,3] → "1, 3"; [2] → "2". */
        fun numbers(n: List<Int>): String {
            if (n.size >= 2 && n.last() - n.first() == n.size - 1) return "${n.first()}–${n.last()}"
            return n.joinToString(", ")
        }

        /** 88 GB / 4.2 GB / 900 MB */
        fun gb(bytes: Long): String {
            val g = bytes / 1_073_741_824.0
            return when {
                g >= 10 -> "${g.roundToInt()} GB"
                g >= 1 -> String.format(Locale.ROOT, "%.1f GB", g)
                else -> "${(bytes / 1_048_576.0).roundToInt()} MB"
            }
        }

        /** Partner clock − our clock, via both GNSS offsets (true time = wall + gpsOffset on each device). */
        fun deltaMs(g: GnssState, partner: PairingPayload?): Long? {
            val ours = g.gpsOffsetMs ?: return null
            val theirs = partner?.gpsOffsetMs ?: return null
            return ours - theirs
        }

        fun signed(v: Long): String = if (v < 0) "−${-v}" else "+$v"

        /** 1000 ms → "1"; 500 → "2"; 2000 → "0.5" (Hz). */
        fun rateHz(intervalMs: Long): String = trim(1000.0 / intervalMs.coerceAtLeast(1))

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
