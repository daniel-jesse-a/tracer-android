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
    val causeLookbackS: Double = 15.0,
) {
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

        fun fromJson(o: JsonObject): Thresholds {
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
                dnsMinMs = dbl("dns_min_ms", d.dnsMinMs).let { if (it == 100.0) d.dnsMinMs else it }, throughputDropFraction = dbl("throughput_drop_fraction", d.throughputDropFraction),
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
                churnMinSpeedMps = dbl("churn_min_speed_mps", d.churnMinSpeedMps), causeLookbackS = dbl("cause_lookback_s", d.causeLookbackS),
            )
        }
    }
}

data class ProbeEndpoint(val url: String, val label: String, val regional: Boolean = false)

/** User-tunable run settings. Persisted to DataStore as JSON and snapshotted into runs.settings_json. */
data class RunSettings(
    val locationIntervalMs: Long = 1_000,
    /** Fused (Wi-Fi/cell-assisted) positions alongside raw GPS — the only position source indoors. */
    val useFusedComparison: Boolean = true,
    val gnssRawMeasurements: Boolean = false,
    val cellPollIntervalMs: Long = 1_000,
    val wifiScanIntervalMs: Long = 30_000,
    val wifiRttEnabled: Boolean = false,
    val latencyIntervalMs: Long = 2_000,
    val adaptiveLatency: Boolean = true,
    val throughputIntervalMs: Long = 120_000,
    /** Upper bound per test; the download also stops after [throughputMaxMs]. 1 MB never leaves TCP slow start. */
    // Data budget: default path ≤ 10 MB / 2 min (≤ 300 MB/h worst case); the parallel cellular path
    // (dual-path, while on Wi-Fi) runs every 3rd cycle (≤ 100 MB/h). Latency probes are a few kB.
    val throughputBytes: Long = 10_000_000,
    val throughputMaxMs: Long = 4_000,
    /** Also probe the mobile-data network while Wi-Fi is the default (uses a little mobile data). */
    val dualPathProbing: Boolean = true,
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

    fun toJson(): JsonObject = buildJsonObject {
        put("settings_version", SETTINGS_VERSION)
        put("location_interval_ms", locationIntervalMs); put("use_fused_comparison", useFusedComparison)
        put("gnss_raw_measurements", gnssRawMeasurements); put("cell_poll_interval_ms", cellPollIntervalMs)
        put("wifi_scan_interval_ms", wifiScanIntervalMs); put("wifi_rtt_enabled", wifiRttEnabled)
        put("latency_interval_ms", latencyIntervalMs); put("adaptive_latency", adaptiveLatency)
        put("throughput_interval_ms", throughputIntervalMs); put("throughput_bytes", throughputBytes)
        put("throughput_max_ms", throughputMaxMs); put("dual_path_probing", dualPathProbing)
        put("public_ip_interval_ms", publicIpIntervalMs); put("device_interval_ms", deviceIntervalMs)
        put("motion_hz", motionHz); put("battery_saver", batterySaver)
        putJsonArray("endpoints") {
            endpoints.forEach { e -> addJsonObject { put("url", e.url); put("label", e.label); put("regional", e.regional) } }
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
        const val SETTINGS_VERSION = 2

        val DEFAULT_ENDPOINTS = listOf(
            ProbeEndpoint("https://cp.cloudflare.com/generate_204", "Cloudflare"),
            ProbeEndpoint("https://www.google.com/generate_204", "Google"),
            ProbeEndpoint("https://captive.apple.com/hotspot-detect.html", "Apple"),
            ProbeEndpoint("https://www.etisalat.ae/", "Regional (e&)", regional = true),
        )

        /** v1.0.8 defaults that redirect (HTTP 302) → replacements; applied when loading stored settings. */
        private val LEGACY_ENDPOINTS = mapOf(
            "https://1.1.1.1/cdn-cgi/trace" to "https://cp.cloudflare.com/generate_204",
            "https://dns.google/" to "https://www.google.com/generate_204",
            "https://www.apple.com/library/test/success.html" to "https://captive.apple.com/hotspot-detect.html",
        )

        fun fromJson(text: String): RunSettings {
            val o = Json.parseToJsonElement(text).jsonObject
            val d = RunSettings()
            val legacy = (o["settings_version"]?.jsonPrimitive?.intOrNull ?: 1) < SETTINGS_VERSION
            fun lng(k: String, def: Long) = o[k]?.jsonPrimitive?.doubleOrNull?.toLong() ?: def
            fun int(k: String, def: Int) = o[k]?.jsonPrimitive?.doubleOrNull?.toInt() ?: def
            fun bool(k: String, def: Boolean) = o[k]?.jsonPrimitive?.booleanOrNull ?: def
            fun str(k: String, def: String?) = o[k]?.jsonPrimitive?.contentOrNull ?: def
            val eps = o["endpoints"]?.jsonArray?.mapNotNull { e ->
                val eo = e.jsonObject
                val url = eo["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ProbeEndpoint(LEGACY_ENDPOINTS[url] ?: url, eo["label"]?.jsonPrimitive?.contentOrNull ?: url, eo["regional"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
            return RunSettings(
                locationIntervalMs = lng("location_interval_ms", d.locationIntervalMs),
                // pre-v2 stores had fused off by default; v2 turns it on as the indoor position source
                useFusedComparison = if (legacy) true else bool("use_fused_comparison", d.useFusedComparison),
                gnssRawMeasurements = bool("gnss_raw_measurements", d.gnssRawMeasurements),
                cellPollIntervalMs = lng("cell_poll_interval_ms", d.cellPollIntervalMs),
                wifiScanIntervalMs = lng("wifi_scan_interval_ms", d.wifiScanIntervalMs),
                wifiRttEnabled = bool("wifi_rtt_enabled", d.wifiRttEnabled),
                latencyIntervalMs = lng("latency_interval_ms", d.latencyIntervalMs),
                adaptiveLatency = bool("adaptive_latency", d.adaptiveLatency),
                throughputIntervalMs = lng("throughput_interval_ms", d.throughputIntervalMs).let { if (it == 60_000L) d.throughputIntervalMs else it },
                throughputBytes = lng("throughput_bytes", d.throughputBytes).let { if (it == 1_000_000L) d.throughputBytes else it },
                throughputMaxMs = lng("throughput_max_ms", d.throughputMaxMs),
                dualPathProbing = bool("dual_path_probing", d.dualPathProbing),
                publicIpIntervalMs = lng("public_ip_interval_ms", d.publicIpIntervalMs),
                deviceIntervalMs = lng("device_interval_ms", d.deviceIntervalMs),
                motionHz = int("motion_hz", d.motionHz), batterySaver = bool("battery_saver", d.batterySaver),
                endpoints = if (eps.isNullOrEmpty()) d.endpoints else eps,
                throughputUrl = str("throughput_url", d.throughputUrl)!!, publicIpUrl = str("public_ip_url", d.publicIpUrl)!!,
                captiveUrl = str("captive_url", d.captiveUrl)!!, openCellIdKey = str("opencellid_key", null),
                uploadUrl = str("upload_url", null), retentionDays = int("retention_days", d.retentionDays),
                minFreeStorageMb = lng("min_free_storage_mb", d.minFreeStorageMb),
                thresholds = o["thresholds"]?.jsonObject?.let { Thresholds.fromJson(it) } ?: d.thresholds,
            )
        }
    }
}
