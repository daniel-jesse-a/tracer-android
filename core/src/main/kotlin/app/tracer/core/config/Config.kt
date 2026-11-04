package app.tracer.core.config

import kotlinx.serialization.json.*

/**
 * Event-rule thresholds. JSON keys are snake_case and identical to the iOS app's
 * `event_thresholds.json`, so one file configures both platforms.
 */
data class Thresholds(
    val gpsHAccM: Double = 25.0,
    val gpsJumpFactor: Double = 3.0,
    val gpsJumpMinM: Double = 30.0,
    val gpsMinUsedSats: Int = 5,
    val gpsRecoverS: Double = 10.0,
    /** No GNSS fix for this long while recording → gps_degraded(no_fix). */
    val gpsNoFixS: Double = 45.0,
    val latencyFactor: Double = 3.0,
    val latencyMinMs: Double = 150.0,
    val latencyWindow: Int = 30,
    val dnsFactor: Double = 3.0,
    val dnsMinMs: Double = 400.0,
    val throughputDropFraction: Double = 0.60,
    val throughputMinSamples: Int = 3,
    val signalDropDb: Int = 10,
    val signalDropWindowS: Double = 5.0,
    val overtakeDb: Int = 6,
    val overtakeHoldS: Double = 3.0,
    val dropConsecutiveFailures: Int = 3,
    val probeTimeoutMs: Long = 3_000,
    val cellStaleMs: Long = 10_000,
    val minEventSpacingS: Double = 5.0,
    // cause heuristics
    val cellEdgeRsrp: Int = -110,
    val cellEdgeTimingAdvance: Int = 25,
    val goodRsrp: Int = -95,
    val goodSinr: Int = 10,
    val interferenceSinr: Int = 0,
    val wifiWeakRssi: Int = -75,
    val wifiGoodRssi: Int = -65,
    val stickyDeltaDb: Int = 10,
    val channelCongestionAps: Int = 10,
    val tunnelCn0DropDb: Double = 8.0,
    val tunnelMaxUsedSats: Int = 4,
    val churnHandovers: Int = 3,
    val churnWindowS: Double = 60.0,
    val churnMinSpeedMps: Double = 15.0,
    /**
     * Cause-evidence window: periodic signals are read as the median within T ± this many seconds,
     * and diagnosis waits until the stream reaches T + this. (Was a never-used 15 s before v4 settings;
     * the engine always used ±2 s, which is now the default and what is actually applied.)
     */
    val causeLookbackS: Double = 2.0,
) {
    /** [causeLookbackS] in ms as applied by the cause engine (clamped to 0.5…15 s). */
    val causeWindowMs: Long get() = (causeLookbackS.coerceIn(0.5, 15.0) * 1000).toLong()

    fun toJson(): JsonObject = buildJsonObject {
        put("gps_h_acc_m", gpsHAccM); put("gps_jump_factor", gpsJumpFactor); put("gps_jump_min_m", gpsJumpMinM)
        put("gps_min_used_sats", gpsMinUsedSats); put("gps_recover_s", gpsRecoverS); put("gps_no_fix_s", gpsNoFixS)
        put("latency_factor", latencyFactor); put("latency_min_ms", latencyMinMs); put("latency_window", latencyWindow)
        put("dns_factor", dnsFactor); put("dns_min_ms", dnsMinMs)
        put("throughput_drop_fraction", throughputDropFraction); put("throughput_min_samples", throughputMinSamples)
        put("signal_drop_db", signalDropDb); put("signal_drop_window_s", signalDropWindowS)
        put("overtake_db", overtakeDb); put("overtake_hold_s", overtakeHoldS)
        put("drop_consecutive_failures", dropConsecutiveFailures); put("probe_timeout_ms", probeTimeoutMs)
        put("cell_stale_ms", cellStaleMs); put("min_event_spacing_s", minEventSpacingS)
        put("cell_edge_rsrp", cellEdgeRsrp); put("cell_edge_timing_advance", cellEdgeTimingAdvance)
        put("good_rsrp", goodRsrp); put("good_sinr", goodSinr); put("interference_sinr", interferenceSinr)
        put("wifi_weak_rssi", wifiWeakRssi); put("wifi_good_rssi", wifiGoodRssi); put("sticky_delta_db", stickyDeltaDb)
        put("channel_congestion_aps", channelCongestionAps); put("tunnel_cn0_drop_db", tunnelCn0DropDb)
        put("tunnel_max_used_sats", tunnelMaxUsedSats); put("churn_handovers", churnHandovers)
        put("churn_window_s", churnWindowS); put("churn_min_speed_mps", churnMinSpeedMps); put("cause_lookback_s", causeLookbackS)
    }

    companion object {
        fun fromJson(text: String): Thresholds = fromJson(Json.parseToJsonElement(text).jsonObject)

        fun fromJson(o: JsonObject, migrate: Boolean = true): Thresholds {
            val d = Thresholds()
            fun dbl(k: String, def: Double) = o[k]?.jsonPrimitive?.doubleOrNull ?: def
            fun int(k: String, def: Int) = o[k]?.jsonPrimitive?.doubleOrNull?.toInt() ?: def
            fun lng(k: String, def: Long) = o[k]?.jsonPrimitive?.doubleOrNull?.toLong() ?: def
            return Thresholds(
                gpsHAccM = dbl("gps_h_acc_m", d.gpsHAccM), gpsJumpFactor = dbl("gps_jump_factor", d.gpsJumpFactor),
                gpsJumpMinM = dbl("gps_jump_min_m", d.gpsJumpMinM), gpsMinUsedSats = int("gps_min_used_sats", d.gpsMinUsedSats),
                gpsRecoverS = dbl("gps_recover_s", d.gpsRecoverS), gpsNoFixS = dbl("gps_no_fix_s", d.gpsNoFixS),
                latencyFactor = dbl("latency_factor", d.latencyFactor), latencyMinMs = dbl("latency_min_ms", d.latencyMinMs),
                latencyWindow = int("latency_window", d.latencyWindow), dnsFactor = dbl("dns_factor", d.dnsFactor),
                dnsMinMs = dbl("dns_min_ms", d.dnsMinMs).let { if (migrate && it == 100.0) d.dnsMinMs else it }, throughputDropFraction = dbl("throughput_drop_fraction", d.throughputDropFraction),
                throughputMinSamples = int("throughput_min_samples", d.throughputMinSamples), signalDropDb = int("signal_drop_db", d.signalDropDb),
                signalDropWindowS = dbl("signal_drop_window_s", d.signalDropWindowS), overtakeDb = int("overtake_db", d.overtakeDb),
                overtakeHoldS = dbl("overtake_hold_s", d.overtakeHoldS), dropConsecutiveFailures = int("drop_consecutive_failures", d.dropConsecutiveFailures),
                probeTimeoutMs = lng("probe_timeout_ms", d.probeTimeoutMs), cellStaleMs = lng("cell_stale_ms", d.cellStaleMs),
                minEventSpacingS = dbl("min_event_spacing_s", d.minEventSpacingS),
                cellEdgeRsrp = int("cell_edge_rsrp", d.cellEdgeRsrp), cellEdgeTimingAdvance = int("cell_edge_timing_advance", d.cellEdgeTimingAdvance),
                goodRsrp = int("good_rsrp", d.goodRsrp), goodSinr = int("good_sinr", d.goodSinr), interferenceSinr = int("interference_sinr", d.interferenceSinr),
                wifiWeakRssi = int("wifi_weak_rssi", d.wifiWeakRssi), wifiGoodRssi = int("wifi_good_rssi", d.wifiGoodRssi),
                stickyDeltaDb = int("sticky_delta_db", d.stickyDeltaDb), channelCongestionAps = int("channel_congestion_aps", d.channelCongestionAps),
                tunnelCn0DropDb = dbl("tunnel_cn0_drop_db", d.tunnelCn0DropDb), tunnelMaxUsedSats = int("tunnel_max_used_sats", d.tunnelMaxUsedSats),
                churnHandovers = int("churn_handovers", d.churnHandovers), churnWindowS = dbl("churn_window_s", d.churnWindowS),
                churnMinSpeedMps = dbl("churn_min_speed_mps", d.churnMinSpeedMps), causeLookbackS = dbl("cause_lookback_s", d.causeLookbackS).let { if (migrate && it == 15.0) d.causeLookbackS else it },
            )
        }
    }
}

/**
 * @param expectStatus the HTTP status this endpoint should end on (after redirects); null = any 2xx.
 *        A response with another status is still *reachable* — it just isn't the *expected response*.
 */
data class ProbeEndpoint(val url: String, val label: String, val regional: Boolean = false, val expectStatus: Int? = null) {
    fun isExpected(finalStatus: Int?): Boolean = finalStatus != null && (expectStatus?.let { finalStatus == it } ?: (finalStatus in 200..299))
}

/** Which networks probes run on. */
enum class ProbePaths(val key: String) {
    /** default network only */
    DEFAULT("default"),
    /** default network + mobile data in parallel while Wi-Fi is the default */
    DUAL("dual"),
    /** Wi-Fi network only (never falls back to cellular) */
    WIFI_ONLY("wifi_only"),
    /** mobile-data network only (requested even when Wi-Fi is connected) */
    CELLULAR_ONLY("cellular_only");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

/** User-tunable run settings. Persisted to DataStore as JSON and snapshotted into runs.settings_json. */
data class RunSettings(
    val locationIntervalMs: Long = 1_000,
    /** Fused (Wi-Fi/cell-assisted) positions alongside raw GPS — the only position source indoors. */
    val useFusedComparison: Boolean = true,
    val gnssRawMeasurements: Boolean = false,
    val cellPollIntervalMs: Long = 1_000,
    val wifiScanIntervalMs: Long = 30_000,
    /** Ranges to APs that advertise 802.11mc/az responder support (indoor position aid). */
    val wifiRttEnabled: Boolean = true,
    val latencyIntervalMs: Long = 2_000,
    val adaptiveLatency: Boolean = true,
    val throughputIntervalMs: Long = 120_000,
    /**
     * Upper bound per test; the download is time-boxed by [throughputMaxMs] (the actual duration is
     * recorded per probe). 1 MB never leaves TCP slow start; 10 MB finished in < 1 s on fast links.
     */
    // Data budget: default path ≤ 25 MB / 2 min (≤ 750 MB/h worst case, usually far less because of
    // the 5 s time box); the parallel cellular path (dual-path, while on Wi-Fi) runs every 3rd cycle
    // and stops at [cellularDataBudgetMb]. Latency probes are a few kB.
    val throughputBytes: Long = 25_000_000,
    val throughputMaxMs: Long = 5_000,
    /** Also probe the mobile-data network while Wi-Fi is the default (uses a little mobile data). */
    val dualPathProbing: Boolean = true,
    /** Overrides [dualPathProbing] when set (protocol runs pin the path). */
    val probePaths: ProbePaths? = null,
    /** Per-run cap on throughput-test bytes over cellular; once reached, cellular throughput tests pause (latency continues). */
    val cellularDataBudgetMb: Long = 1_000,
    /** Internal probes: ICMP to the default gateway + UDP DNS query to each DHCP DNS server. */
    val internalProbes: Boolean = true,
    val publicIpIntervalMs: Long = 300_000,
    val deviceIntervalMs: Long = 30_000,
    val motionHz: Int = 10,
    val batterySaver: Boolean = false,
    val endpoints: List<ProbeEndpoint> = DEFAULT_ENDPOINTS,
    val throughputUrl: String = "https://speed.cloudflare.com/__down?bytes={bytes}",
    val publicIpUrl: String = "https://ipinfo.io/json",
    val captiveUrl: String = "https://connectivitycheck.gstatic.com/generate_204",
    val openCellIdKey: String? = null,
    val uploadUrl: String? = null,
    val retentionDays: Int = 90,
    val minFreeStorageMb: Long = 500,
    val thresholds: Thresholds = Thresholds(),
) {
    /** Battery-saver multiplies polling intervals; it never disables a collector outright. */
    fun effective(): RunSettings = if (!batterySaver) this else copy(
        cellPollIntervalMs = cellPollIntervalMs * 3,
        wifiScanIntervalMs = maxOf(wifiScanIntervalMs * 2, 120_000),
        latencyIntervalMs = latencyIntervalMs * 3,
        throughputIntervalMs = throughputIntervalMs * 5,
        motionHz = minOf(motionHz, 2),
        wifiRttEnabled = false,
        gnssRawMeasurements = false,
    )

    /** Effective probe-path mode. */
    val paths: ProbePaths get() = probePaths ?: if (dualPathProbing) ProbePaths.DUAL else ProbePaths.DEFAULT

    fun toJson(): JsonObject = buildJsonObject {
        put("settings_version", SETTINGS_VERSION)
        put("location_interval_ms", locationIntervalMs); put("use_fused_comparison", useFusedComparison)
        put("gnss_raw_measurements", gnssRawMeasurements); put("cell_poll_interval_ms", cellPollIntervalMs)
        put("wifi_scan_interval_ms", wifiScanIntervalMs); put("wifi_rtt_enabled", wifiRttEnabled)
        put("latency_interval_ms", latencyIntervalMs); put("adaptive_latency", adaptiveLatency)
        put("throughput_interval_ms", throughputIntervalMs); put("throughput_bytes", throughputBytes)
        put("throughput_max_ms", throughputMaxMs); put("dual_path_probing", dualPathProbing)
        probePaths?.let { put("probe_paths", it.key) }
        put("cellular_data_budget_mb", cellularDataBudgetMb); put("internal_probes", internalProbes)
        put("public_ip_interval_ms", publicIpIntervalMs); put("device_interval_ms", deviceIntervalMs)
        put("motion_hz", motionHz); put("battery_saver", batterySaver)
        putJsonArray("endpoints") {
            endpoints.forEach { e -> addJsonObject { put("url", e.url); put("label", e.label); put("regional", e.regional); e.expectStatus?.let { put("expect_status", it) } } }
        }
        put("throughput_url", throughputUrl); put("public_ip_url", publicIpUrl); put("captive_url", captiveUrl)
        openCellIdKey?.let { put("opencellid_key_set", true) } // never export the key itself
        uploadUrl?.let { put("upload_url", it) }
        put("retention_days", retentionDays); put("min_free_storage_mb", minFreeStorageMb)
        put("thresholds", thresholds.toJson())
    }

    /** Full serialisation including secrets — for local DataStore only. */
    fun toStorageJson(): String = JsonObject(toJson() + buildJsonObject {
        openCellIdKey?.let { put("opencellid_key", it) }
    }).toString()

    companion object {
        /** Non-redirecting, tiny-response anycast endpoints (204/200). Redirects inflate TTFB and used to read as failures. */
        /** v4: 25 MB / 5 s speed test, 1 GB cellular budget, cause_lookback_s 2 (the window actually used). */
        const val SETTINGS_VERSION = 4

        /** v3 defaults that v4 replaces when a stored value still equals them (user edits are kept). */
        private const val V3_THROUGHPUT_BYTES = 10_000_000L
        private const val V3_THROUGHPUT_MAX_MS = 4_000L
        private const val V3_CELLULAR_BUDGET_MB = 500L

        /** Defaults of builds ≤ 1.0.8 — used for keys missing from an un-migrated (as-recorded) settings JSON. */
        private val LEGACY_V1_DEFAULTS by lazy {
            RunSettings(throughputBytes = 1_000_000, throughputIntervalMs = 60_000, throughputMaxMs = Long.MAX_VALUE,
                dualPathProbing = false, wifiRttEnabled = false, useFusedComparison = false, internalProbes = false)
        }

        val DEFAULT_ENDPOINTS = listOf(
            ProbeEndpoint("https://cp.cloudflare.com/generate_204", "Cloudflare", expectStatus = 204),
            ProbeEndpoint("https://www.google.com/generate_204", "Google", expectStatus = 204),
            ProbeEndpoint("https://captive.apple.com/hotspot-detect.html", "Apple", expectStatus = 200),
            ProbeEndpoint("https://www.etisalat.ae/", "Regional (e&)", regional = true),
        )

        /** v1.0.8 defaults that redirect (HTTP 302) → replacements; applied when loading stored settings. */
        private val LEGACY_ENDPOINTS = mapOf(
            "https://1.1.1.1/cdn-cgi/trace" to "https://cp.cloudflare.com/generate_204",
            "https://dns.google/" to "https://www.google.com/generate_204",
            "https://www.apple.com/library/test/success.html" to "https://captive.apple.com/hotspot-detect.html",
        )

        /**
         * @param migrate apply upgrades of older stored settings (new defaults, legacy endpoints).
         *        Pass false to read back *exactly what a run used* (method fingerprint of old runs).
         */
        fun fromJson(text: String, migrate: Boolean = true): RunSettings {
            val o = Json.parseToJsonElement(text).jsonObject
            val d = if (migrate) RunSettings() else LEGACY_V1_DEFAULTS
            val version = if (migrate) o["settings_version"]?.jsonPrimitive?.intOrNull ?: 1 else SETTINGS_VERSION
            val legacy = version < 2
            fun lng(k: String, def: Long) = o[k]?.jsonPrimitive?.doubleOrNull?.toLong() ?: def
            fun int(k: String, def: Int) = o[k]?.jsonPrimitive?.doubleOrNull?.toInt() ?: def
            fun bool(k: String, def: Boolean) = o[k]?.jsonPrimitive?.booleanOrNull ?: def
            fun str(k: String, def: String?) = o[k]?.jsonPrimitive?.contentOrNull ?: def
            val eps = o["endpoints"]?.jsonArray?.mapNotNull { e ->
                val eo = e.jsonObject
                val url = eo["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val u = if (migrate) LEGACY_ENDPOINTS[url] ?: url else url
                ProbeEndpoint(u, eo["label"]?.jsonPrimitive?.contentOrNull ?: url, eo["regional"]?.jsonPrimitive?.booleanOrNull ?: false,
                    eo["expect_status"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ENDPOINTS.firstOrNull { it.url == u }?.expectStatus)
            }
            return RunSettings(
                locationIntervalMs = lng("location_interval_ms", d.locationIntervalMs),
                // pre-v2 stores had fused off by default; v2 turns it on as the indoor position source
                useFusedComparison = if (legacy) true else bool("use_fused_comparison", d.useFusedComparison),
                gnssRawMeasurements = bool("gnss_raw_measurements", d.gnssRawMeasurements),
                cellPollIntervalMs = lng("cell_poll_interval_ms", d.cellPollIntervalMs),
                wifiScanIntervalMs = lng("wifi_scan_interval_ms", d.wifiScanIntervalMs),
                // v3 turns RTT on (it only ranges to APs that support it)
                wifiRttEnabled = if (version < 3) true else bool("wifi_rtt_enabled", d.wifiRttEnabled),
                latencyIntervalMs = lng("latency_interval_ms", d.latencyIntervalMs),
                adaptiveLatency = bool("adaptive_latency", d.adaptiveLatency),
                throughputIntervalMs = lng("throughput_interval_ms", d.throughputIntervalMs).let { if (migrate && it == 60_000L) d.throughputIntervalMs else it },
                throughputBytes = lng("throughput_bytes", d.throughputBytes).let {
                    if (migrate && (it == 1_000_000L || (version < 4 && it == V3_THROUGHPUT_BYTES))) d.throughputBytes else it },
                throughputMaxMs = lng("throughput_max_ms", d.throughputMaxMs).let { if (migrate && version < 4 && it == V3_THROUGHPUT_MAX_MS) d.throughputMaxMs else it },
                dualPathProbing = bool("dual_path_probing", d.dualPathProbing),
                probePaths = ProbePaths.of(o["probe_paths"]?.jsonPrimitive?.contentOrNull),
                cellularDataBudgetMb = lng("cellular_data_budget_mb", d.cellularDataBudgetMb)
                    .let { if (migrate && version < 4 && it == V3_CELLULAR_BUDGET_MB) d.cellularDataBudgetMb else it },
                internalProbes = bool("internal_probes", d.internalProbes),
                publicIpIntervalMs = lng("public_ip_interval_ms", d.publicIpIntervalMs),
                deviceIntervalMs = lng("device_interval_ms", d.deviceIntervalMs),
                motionHz = int("motion_hz", d.motionHz), batterySaver = bool("battery_saver", d.batterySaver),
                endpoints = if (eps.isNullOrEmpty()) d.endpoints else eps,
                throughputUrl = str("throughput_url", d.throughputUrl)!!, publicIpUrl = str("public_ip_url", d.publicIpUrl)!!,
                captiveUrl = str("captive_url", d.captiveUrl)!!, openCellIdKey = str("opencellid_key", null),
                uploadUrl = str("upload_url", null), retentionDays = int("retention_days", d.retentionDays),
                minFreeStorageMb = lng("min_free_storage_mb", d.minFreeStorageMb),
                thresholds = o["thresholds"]?.jsonObject?.let { Thresholds.fromJson(it, migrate) } ?: d.thresholds,
            )
        }
    }
}
