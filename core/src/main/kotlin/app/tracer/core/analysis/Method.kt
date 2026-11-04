package app.tracer.core.analysis

import app.tracer.core.config.ProbePaths
import app.tracer.core.config.RunSettings
import app.tracer.core.export.Bundle
import kotlinx.serialization.json.*

/**
 * Method fingerprint: SHA-256 (first 16 hex chars) over a canonical JSON of every setting that
 * changes *what* is measured — endpoints (+ expected status), throughput size/cap/interval, probe
 * paths (dual/wifi/cellular), latency interval, thresholds and settings_version. Two runs with the
 * same fingerprint were measured the same way; a different fingerprint means their numbers are not
 * directly comparable.
 */
object Method {
    fun canonical(settings: RunSettings, protocol: String? = null, settingsVersion: Int = RunSettings.SETTINGS_VERSION): JsonObject = buildJsonObject {
        put("settings_version", settingsVersion)
        put("protocol", protocol)
        putJsonArray("endpoints") {
            settings.endpoints.sortedBy { it.url }.forEach { e -> addJsonObject { put("url", e.url); put("expect_status", e.expectStatus) } }
        }
        put("probe_paths", settings.paths.key)
        put("latency_interval_ms", settings.latencyIntervalMs)
        put("adaptive_latency", settings.adaptiveLatency)
        put("throughput_bytes", settings.throughputBytes)
        put("throughput_max_ms", settings.throughputMaxMs)
        put("throughput_interval_ms", settings.throughputIntervalMs)
        put("internal_probes", settings.internalProbes)
        put("battery_saver", settings.batterySaver)
        // the cause-evidence window actually applied (± seconds around each event)
        put("cause_window_s", settings.thresholds.causeWindowMs / 1000.0)
        // thresholds: keys sorted so field order never changes the hash
        putJsonObject("thresholds") { settings.thresholds.toJson().entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) } }
    }

    fun fingerprint(settings: RunSettings, protocol: String? = null, settingsVersion: Int = RunSettings.SETTINGS_VERSION): String =
        Bundle.sha256Hex(canonical(settings, protocol, settingsVersion).toString().toByteArray()).take(16)

    /** Fingerprint of a stored run's settings JSON exactly as recorded (no migration). */
    fun fingerprintOfRecorded(settingsJson: String, protocol: String? = null): String? = runCatching {
        val v = Json.parseToJsonElement(settingsJson).jsonObject["settings_version"]?.jsonPrimitive?.intOrNull ?: 1
        fingerprint(RunSettings.fromJson(settingsJson, migrate = false), protocol, v)
    }.getOrNull()

    /** Which inputs differ between two canonical method JSONs (for the "measured differently" flag). */
    fun diff(a: JsonObject, b: JsonObject): List<String> = (a.keys + b.keys).filter { a[it] != b[it] }.sorted()
}

/** A fixed, repeatable test recipe. Settings are fixed (user settings are ignored) so runs compare directly. */
data class Protocol(
    val id: String,
    val title: String,
    val durationS: Int,
    val paths: ProbePaths,
    val description: String,
) {
    /** The frozen settings every run of this protocol uses. */
    fun settings(): RunSettings = RunSettings(
        locationIntervalMs = 1_000, useFusedComparison = true, gnssRawMeasurements = false,
        cellPollIntervalMs = 1_000, wifiScanIntervalMs = 30_000, wifiRttEnabled = true,
        latencyIntervalMs = 2_000, adaptiveLatency = false,
        throughputIntervalMs = 120_000, throughputBytes = 10_000_000, throughputMaxMs = 4_000,
        publicIpIntervalMs = 300_000, deviceIntervalMs = 30_000, motionHz = 10,
        batterySaver = false, dualPathProbing = paths == ProbePaths.DUAL, probePaths = paths,
        cellularDataBudgetMb = 500, internalProbes = true,
    )

    companion object {
        val ALL = listOf(
            Protocol("walk10-dual", "Walk 10 min · dual path", 600, ProbePaths.DUAL,
                "Wi-Fi (default) and mobile data probed in parallel. The standard comparison walk."),
            Protocol("walk10-wifi", "Walk 10 min · Wi-Fi only", 600, ProbePaths.WIFI_ONLY,
                "Probes only on the Wi-Fi network. Isolates Wi-Fi coverage and roaming."),
            Protocol("walk10-cell", "Walk 10 min · cellular only", 600, ProbePaths.CELLULAR_ONLY,
                "Probes only on mobile data, even with Wi-Fi connected. Isolates the radio network."),
            Protocol("drive30-dual", "Drive 30 min · dual path", 1_800, ProbePaths.DUAL, "Longer route; same method as walk10-dual."),
            Protocol("soak120-dual", "Soak 2 h · dual path", 7_200, ProbePaths.DUAL,
                "Long static/rail soak. Mobile-data budget applies (500 MB)."),
        )
        fun byId(id: String?): Protocol? = ALL.firstOrNull { it.id == id }
    }
}
