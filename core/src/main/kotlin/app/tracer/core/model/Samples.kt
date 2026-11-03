package app.tracer.core.model

/**
 * Every persisted row carries three clocks:
 *  - [tUtcMs]    wall clock (System.currentTimeMillis) — human-readable, can jump on NITZ/NTP correction
 *  - [tMonoNs]   SystemClock.elapsedRealtimeNanos — monotonic, includes deep sleep; all windowed rules use this
 *  - [gpsTimeMs] GNSS-derived UTC when the row came from (or was aligned to) a GNSS fix; null otherwise
 */
data class Stamp(val tUtcMs: Long, val tMonoNs: Long, val gpsTimeMs: Long? = null) {
    val tMonoMs: Long get() = tMonoNs / 1_000_000L
}

/** Source of [Stamp]s. Android impl wraps System.currentTimeMillis + SystemClock.elapsedRealtimeNanos. */
fun interface TracerClock {
    fun now(): Stamp
}

/** Canonical table names — shared with the iOS app. */
enum class Table(val sqlName: String) {
    RUNS("runs"),
    LOCATION("location"),
    MOTION("motion"),
    NETWORK_PATH("network_path"),
    RADIO("radio"),
    WIFI_CONNECTION("wifi_connection"),
    CELL_SERVING("cell_serving"),
    CELL_NEIGHBOUR("cell_neighbour"),
    WIFI_SCAN("wifi_scan"),
    GNSS_SATELLITE("gnss_satellite"),
    PROBE("probe"),
    DEVICE("device"),
    EVENT("event"),
    ANNOTATION("annotation"),
    METRICKIT_PAYLOAD("metrickit_payload"),
    // v2 shared tables
    WAYPOINT("waypoint"),
    WIFI_ROAM("wifi_roam"),

    // Android-only extension tables (prefixed x_). The iOS importer ignores unknown tables;
    // the shared tables above are byte-for-byte identical in column set and order.
    X_ANDROID_NETWORK("x_android_network"),
    X_ANDROID_WIFI_LINK("x_android_wifi_link"),
    X_ANDROID_GNSS_RAW("x_android_gnss_raw"),
    X_ANDROID_WIFI_RTT("x_android_wifi_rtt"),
    X_EVENT_LABEL("x_event_label"),
    X_GAP("x_gap"),
    X_ANDROID_CARRIER("x_android_carrier"),
    X_COLLECTOR_LOG("x_collector_log");

    val isShared: Boolean get() = !sqlName.startsWith("x_")
    /** Produced only by the iOS app; Android bundles omit these files. */
    val iosOnly: Boolean get() = this == METRICKIT_PAYLOAD

    companion object {
        private val byName = entries.associateBy { it.sqlName }
        fun of(sqlName: String): Table? = byName[sqlName]
    }
}

/** A time-stamped row destined for one table. Collectors emit these; the single writer persists them. */
sealed interface Sample {
    val runId: String
    val stamp: Stamp
    val table: Table
}

data class LocationSample(
    override val runId: String,
    override val stamp: Stamp,
    val lat: Double,
    val lon: Double,
    val altM: Double? = null,
    val hAccM: Double? = null,
    val vAccM: Double? = null,
    val speedMps: Double? = null,
    val speedAcc: Double? = null,
    val courseDeg: Double? = null,
    val courseAcc: Double? = null,
    val floor: Int? = null,
    val gpsTimeMs: Long? = null,
    /** "gps" | "fused" | "network" | "core_location" (iOS) */
    val provider: String,
) : Sample { override val table get() = Table.LOCATION }

data class MotionSample(
    override val runId: String,
    override val stamp: Stamp,
    /** still | walking | running | on_bicycle | in_vehicle | tilting | unknown */
    val activity: String? = null,
    val confidence: Int? = null,
    val pressureKpa: Double? = null,
    val relAltM: Double? = null,
    val accelMean: Double? = null,
    val accelMax: Double? = null,
) : Sample { override val table get() = Table.MOTION }

data class NetworkPathSample(
    override val runId: String,
    override val stamp: Stamp,
    /** satisfied | unsatisfied | requires_connection */
    val status: String,
    /** comma-separated transport list, e.g. "wifi,cellular" */
    val interfaces: String? = null,
    /** wifi | cellular | ethernet | vpn | other | none */
    val primaryInterface: String? = null,
    val isExpensive: Boolean? = null,
    val isConstrained: Boolean? = null,
    val ipv4: String? = null,
    val ipv6: String? = null,
    /** good | moderate | minimal | unknown */
    val linkQuality: String? = null,
    /** not_validated | captive_portal | no_network | airplane_mode | blocked | null */
    val unsatisfiedReason: String? = null,
) : Sample { override val table get() = Table.NETWORK_PATH }

data class RadioSample(
    override val runId: String,
    override val stamp: Stamp,
    /** subscription id (Android) / service identifier (iOS) */
    val serviceId: String,
    /** NONE | GSM | UMTS | HSPA | LTE | LTE_CA | LTE_ADV_PRO | NR_NSA | NR_NSA_MMWAVE | NR_ADVANCED | NR_SA | UNKNOWN */
    val rat: String,
    /** v2: derived from TelephonyDisplayInfo override type + carriers: none | nsa | nsa_advanced | sa */
    val nrState: String? = null,
    /** JSON: {"active":bool,"cell_count":n,"aggregate_mhz":x,"bandwidths_khz":[..],"carriers":[{"rat","band","arfcn","bandwidth_mhz","primary"}]} */
    val carrierAggregation: String? = null,
) : Sample { override val table get() = Table.RADIO }

data class WifiConnectionSample(
    override val runId: String,
    override val stamp: Stamp,
    val ssid: String? = null,
    val bssid: String? = null,
    val security: String? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    /** 20 | 40 | 80 | 160 | 320 | 80+80 (MHz, as text) */
    val channelWidth: String? = null,
    /** wifi4 | wifi5 | wifi6 | wifi6e | wifi7 | legacy | unknown */
    val standard: String? = null,
) : Sample { override val table get() = Table.WIFI_CONNECTION }

/** Shared shape for cell_serving and cell_neighbour. */
data class CellSample(
    override val runId: String,
    override val stamp: Stamp,
    val serving: Boolean,
    /** GSM | WCDMA | LTE | NR | TDSCDMA | CDMA */
    val rat: String,
    val mcc: String? = null,
    val mnc: String? = null,
    val tac: Int? = null,
    val cellId: Long? = null,
    val pci: Int? = null,
    val earfcnOrArfcn: Int? = null,
    val band: String? = null,
    val bandwidthMhz: Double? = null,
    /** LTE RSRP / NR SS-RSRP / WCDMA RSCP (dBm) */
    val rsrp: Int? = null,
    /** LTE RSRQ / NR SS-RSRQ / WCDMA Ec/No (dB) */
    val rsrq: Int? = null,
    val rssi: Int? = null,
    /** LTE RSSNR / NR SS-SINR (dB) */
    val sinr: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val cellinfoAgeMs: Long? = null,
    val subscriptionId: Int? = null,
) : Sample {
    override val table get() = if (serving) Table.CELL_SERVING else Table.CELL_NEIGHBOUR
    /** Identity used for handover detection: prefer global cell id, fall back to PCI+ARFCN. */
    val identity: String get() = if (cellId != null) "$rat:$cellId" else "$rat:pci$pci@$earfcnOrArfcn"
}

data class WifiScanSample(
    override val runId: String,
    override val stamp: Stamp,
    val scanId: Long,
    val ssid: String? = null,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val channelWidth: String? = null,
    val standard: String? = null,
    val capabilities: String? = null,
    /** v2: comma list from the AP's IEs/AKMs: "11k" (RM enabled caps), "11v" (BSS transition), "11r" (FT AKM / mobility domain) */
    val features: String? = null,
) : Sample { override val table get() = Table.WIFI_SCAN }

data class GnssSatelliteSample(
    override val runId: String,
    override val stamp: Stamp,
    val svid: Int,
    /** GPS | GLONASS | GALILEO | BEIDOU | QZSS | SBAS | IRNSS | UNKNOWN */
    val constellation: String,
    val cn0DbHz: Double,
    val elevation: Double,
    val azimuth: Double,
    val usedInFix: Boolean,
    val carrierFreqHz: Double? = null,
) : Sample { override val table get() = Table.GNSS_SATELLITE }

data class ProbeSample(
    override val runId: String,
    override val stamp: Stamp,
    /** latency | throughput | public_ip | captive | gateway (ICMP to default gateway) | dns_server (UDP query to a DHCP DNS server) */
    val probeType: String,
    val endpoint: String,
    val success: Boolean,
    /** null | timeout | dns | connect | tls | http_<code> | io | no_network */
    val error: String? = null,
    val dnsMs: Double? = null,
    val connectMs: Double? = null,
    val tlsMs: Double? = null,
    val ttfbMs: Double? = null,
    val totalMs: Double? = null,
    val bytes: Long? = null,
    val mbps: Double? = null,
    /** v2: transport name wifi | cellular | ethernet | vpn (never wlan0/rmnet1) */
    val iface: String? = null,
    val isCellular: Boolean? = null,
    val publicIp: String? = null,
    val asn: String? = null,
    val asnOrg: String? = null,
    // v2: [success] means "reachable" (any HTTP response / DNS answer / ping reply). Whether the
    // response was the one the endpoint should return is [expectedResponse]. A redirect is reachable.
    val httpStatus: Int? = null,
    val redirectCount: Int? = null,
    val finalUrl: String? = null,
    val expectedResponse: Boolean? = null,
) : Sample {
    override val table get() = Table.PROBE
    val reachable: Boolean get() = success
}

data class DeviceSample(
    override val runId: String,
    override val stamp: Stamp,
    /** 0.0–1.0 */
    val battery: Double? = null,
    val charging: Boolean? = null,
    val lowPower: Boolean? = null,
    /** nominal | light | moderate | severe | critical | emergency | shutdown (Android) ; nominal|fair|serious|critical (iOS) */
    val thermalState: String? = null,
) : Sample { override val table get() = Table.DEVICE }

data class EventSample(
    override val runId: String,
    override val stamp: Stamp,
    val eventType: String,
    /** info | warning | critical */
    val severity: String,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val likelyCause: String? = null,
    val causeConfidence: Double? = null,
    val evidenceJson: String? = null,
) : Sample { override val table get() = Table.EVENT }

data class AnnotationSample(
    override val runId: String,
    override val stamp: Stamp,
    val text: String? = null,
    val audioPath: String? = null,
) : Sample { override val table get() = Table.ANNOTATION }

data class MetricKitPayloadSample(
    override val runId: String,
    override val stamp: Stamp,
    val payloadJson: String,
) : Sample { override val table get() = Table.METRICKIT_PAYLOAD }

// ---------- Android-only extension rows ----------

data class AndroidNetworkSample(
    override val runId: String,
    override val stamp: Stamp,
    val networkHandle: Long? = null,
    val transports: String? = null,
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    val notMetered: Boolean? = null,
    val downKbps: Int? = null,
    val upKbps: Int? = null,
    val iface: String? = null,
    val dnsServers: String? = null,
    val privateDns: String? = null,
    /** kernel interface name (wlan0, rmnet1, …); [iface] holds the transport name (wifi | cellular) */
    val ifname: String? = null,
) : Sample { override val table get() = Table.X_ANDROID_NETWORK }

data class WifiLinkSample(
    override val runId: String,
    override val stamp: Stamp,
    val bssid: String? = null,
    val txLinkMbps: Int? = null,
    val rxLinkMbps: Int? = null,
    val maxTxMbps: Int? = null,
    val maxRxMbps: Int? = null,
    /** JSON array of MLO links: [{"link_id":0,"bssid":"..","band":"5","rssi":-60,"freq":5180}] */
    val mloLinksJson: String? = null,
) : Sample { override val table get() = Table.X_ANDROID_WIFI_LINK }

data class GnssRawSample(
    override val runId: String,
    override val stamp: Stamp,
    val measurementCount: Int,
    val clockJson: String? = null,
    val rawJson: String? = null,
) : Sample { override val table get() = Table.X_ANDROID_GNSS_RAW }

data class WifiRttSample(
    override val runId: String,
    override val stamp: Stamp,
    val bssid: String,
    val status: Int,
    val distanceMm: Int? = null,
    val distanceStdDevMm: Int? = null,
    val rssiDbm: Int? = null,
    val attempted: Int? = null,
    val successful: Int? = null,
) : Sample { override val table get() = Table.X_ANDROID_WIFI_RTT }

data class EventLabelSample(
    override val runId: String,
    override val stamp: Stamp,
    /** rowid of the event in the event table */
    val eventId: Long,
    val label: String,
) : Sample { override val table get() = Table.X_EVENT_LABEL }

/** A recording gap: process death, service restart, pause, storage stop. */
data class GapSample(
    override val runId: String,
    override val stamp: Stamp,
    val startUtcMs: Long,
    val endUtcMs: Long,
    /** pause | process_restart | storage_low | permission_revoked | airplane_mode | watchdog */
    val reason: String,
) : Sample { override val table get() = Table.X_GAP }

// ---------- v2 shared tables ----------

/** User-marked point (tap-to-mark), optionally labelled. Position = best fix at tap time. */
data class WaypointSample(
    override val runId: String,
    override val stamp: Stamp,
    val seq: Int,
    val label: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val hAccM: Double? = null,
    val provider: String? = null,
) : Sample { override val table get() = Table.WAYPOINT }

/** One Wi-Fi roam (BSSID change within an SSID), written ~10 s after the roam once the penalty is known. */
data class WifiRoamSample(
    override val runId: String,
    override val stamp: Stamp,
    val ssid: String? = null,
    val fromBssid: String? = null,
    val toBssid: String? = null,
    /** "2.4" | "5" | "6" */
    val fromBand: String? = null,
    val toBand: String? = null,
    val fromRssiDbm: Int? = null,
    val toRssiDbm: Int? = null,
    /** Disconnect → re-association, from the Wi-Fi link callbacks; 0 = seamless (no disconnect observed) */
    val linkDownMs: Long? = null,
    /** Median Wi-Fi-path RTT in the 10 s after the roam minus the median in the 30 s before. */
    val latencyPenaltyMs: Double? = null,
    val rttBeforeMs: Double? = null,
    val rttAfterMs: Double? = null,
    /** From the target AP's scan IEs; null = target not seen in a recent scan */
    val target11k: Boolean? = null,
    val target11v: Boolean? = null,
    val target11r: Boolean? = null,
) : Sample { override val table get() = Table.WIFI_ROAM }

// ---------- v2 Android extension tables ----------

/** One component carrier of a (possibly aggregated / EN-DC) connection, per radio update. */
data class CarrierSample(
    override val runId: String,
    override val stamp: Stamp,
    val serviceId: String,
    /** 0 = primary (PCell) */
    val index: Int,
    /** LTE | NR */
    val rat: String,
    val band: String? = null,
    val arfcn: Int? = null,
    val pci: Int? = null,
    val bandwidthMhz: Double? = null,
    val primary: Boolean,
    /** bandwidth+cellinfo (matched) | bandwidth (RAT inferred from width) */
    val source: String,
) : Sample { override val table get() = Table.X_ANDROID_CARRIER }

/** Collector health transition (kept apart from user waypoints/annotations). */
data class CollectorLogSample(
    override val runId: String,
    override val stamp: Stamp,
    val collector: String,
    /** ok | degraded | unavailable | stopped | disabled */
    val health: String,
    val message: String? = null,
    val restarts: Int? = null,
) : Sample { override val table get() = Table.X_COLLECTOR_LOG }
