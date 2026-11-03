package app.tracer.core.schema

import app.tracer.core.model.*

/**
 * Sample <-> positional row values in [TableDef.columnNames] order.
 * Booleans are stored as INTEGER 0/1. Values are Long / Double / String / null only.
 */
object SampleCodec {

    private fun b(v: Boolean?): Long? = v?.let { if (it) 1L else 0L }
    private fun l(v: Int?): Long? = v?.toLong()

    fun encode(s: Sample): List<Any?> {
        val head = listOf<Any?>(s.runId, s.stamp.tUtcMs, s.stamp.tMonoNs)
        val body: List<Any?> = when (s) {
            is LocationSample -> listOf(s.lat, s.lon, s.altM, s.hAccM, s.vAccM, s.speedMps, s.speedAcc, s.courseDeg, s.courseAcc, l(s.floor), s.gpsTimeMs ?: s.stamp.gpsTimeMs, s.provider)
            is MotionSample -> listOf(s.activity, l(s.confidence), s.pressureKpa, s.relAltM, s.accelMean, s.accelMax)
            is NetworkPathSample -> listOf(s.status, s.interfaces, s.primaryInterface, b(s.isExpensive), b(s.isConstrained), s.ipv4, s.ipv6, s.linkQuality, s.unsatisfiedReason)
            is RadioSample -> listOf(s.serviceId, s.rat, s.nrState, s.carrierAggregation)
            is WifiConnectionSample -> listOf(s.ssid, s.bssid, s.security, l(s.rssiDbm), l(s.linkSpeedMbps), l(s.frequencyMhz), s.channelWidth, s.standard)
            is CellSample -> listOf(s.rat, s.mcc, s.mnc, l(s.tac), s.cellId, l(s.pci), l(s.earfcnOrArfcn), s.band, s.bandwidthMhz, l(s.rsrp), l(s.rsrq), l(s.rssi), l(s.sinr), l(s.cqi), l(s.timingAdvance), s.cellinfoAgeMs, l(s.subscriptionId))
            is WifiScanSample -> listOf(s.scanId, s.ssid, s.bssid, s.rssiDbm.toLong(), s.frequencyMhz.toLong(), s.channelWidth, s.standard, s.capabilities)
            is GnssSatelliteSample -> listOf(s.svid.toLong(), s.constellation, s.cn0DbHz, s.elevation, s.azimuth, b(s.usedInFix), s.carrierFreqHz)
            is ProbeSample -> listOf(s.probeType, s.endpoint, b(s.success), s.error, s.dnsMs, s.connectMs, s.tlsMs, s.ttfbMs, s.totalMs, s.bytes, s.mbps, s.iface, b(s.isCellular), s.publicIp, s.asn, s.asnOrg)
            is DeviceSample -> listOf(s.battery, b(s.charging), b(s.lowPower), s.thermalState)
            is EventSample -> listOf(s.eventType, s.severity, s.beforeJson, s.afterJson, s.lat, s.lon, s.likelyCause, s.causeConfidence, s.evidenceJson)
            is AnnotationSample -> listOf(s.text, s.audioPath)
            is MetricKitPayloadSample -> listOf(s.payloadJson)
            is AndroidNetworkSample -> listOf(s.networkHandle, s.transports, b(s.validated), b(s.captivePortal), b(s.notMetered), l(s.downKbps), l(s.upKbps), s.iface, s.dnsServers, s.privateDns)
            is WifiLinkSample -> listOf(s.bssid, l(s.txLinkMbps), l(s.rxLinkMbps), l(s.maxTxMbps), l(s.maxRxMbps), s.mloLinksJson)
            is GnssRawSample -> listOf(s.measurementCount.toLong(), s.clockJson, s.rawJson)
            is WifiRttSample -> listOf(s.bssid, s.status.toLong(), l(s.distanceMm), l(s.distanceStdDevMm), l(s.rssiDbm), l(s.attempted), l(s.successful))
            is EventLabelSample -> listOf(s.eventId, s.label)
            is GapSample -> listOf(s.startUtcMs, s.endUtcMs, s.reason)
        }
        return head + body
    }

    fun encodeRun(r: RunRecord): List<Any?> = listOf(
        r.runId, r.tUtcMs, r.tMonoNs, r.name, r.mode, r.devicePlatform, r.deviceModel, r.osVersion, r.appVersion,
        r.startedAt, r.endedAt, r.linkedRunId, r.settingsJson, r.gpsOffsetMsStart, r.gpsOffsetMsEnd, r.ntpOffsetMs, r.completenessJson,
    )

    /** Row accessor used by [decode]; implementations wrap a Cursor, a CSV record or a Map. */
    interface Row {
        fun has(col: String): Boolean
        fun str(col: String): String?
        fun long(col: String): Long?
        fun dbl(col: String): Double?
        fun int(col: String): Int? = long(col)?.toInt()
        fun bool(col: String): Boolean? = long(col)?.let { it != 0L }
    }

    class MapRow(private val m: Map<String, Any?>) : Row {
        override fun has(col: String) = m.containsKey(col)
        override fun str(col: String) = m[col]?.toString()
        override fun long(col: String): Long? = when (val v = m[col]) {
            null -> null; is Number -> v.toLong(); is Boolean -> if (v) 1 else 0
            is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: when (v.lowercase()) { "true" -> 1; "false" -> 0; else -> null }
            else -> null
        }
        override fun dbl(col: String): Double? = when (val v = m[col]) {
            null -> null; is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
        }
    }

    fun decodeRun(row: Row): RunRecord = RunRecord(
        runId = row.str("run_id")!!, name = row.str("name") ?: "", mode = row.str("mode") ?: "",
        devicePlatform = row.str("device_platform") ?: "", deviceModel = row.str("device_model") ?: "",
        osVersion = row.str("os_version") ?: "", appVersion = row.str("app_version") ?: "",
        startedAt = row.long("started_at") ?: 0, endedAt = row.long("ended_at"), linkedRunId = row.str("linked_run_id"),
        settingsJson = row.str("settings_json"), gpsOffsetMsStart = row.long("gps_offset_ms_start"),
        gpsOffsetMsEnd = row.long("gps_offset_ms_end"), ntpOffsetMs = row.long("ntp_offset_ms"),
        completenessJson = row.str("completeness_json"), tUtcMs = row.long("t_utc_ms") ?: 0, tMonoNs = row.long("t_mono_ns") ?: 0,
    )

    fun decode(table: Table, row: Row): Sample {
        val run = row.str("run_id")!!
        val st = Stamp(row.long("t_utc_ms") ?: 0, row.long("t_mono_ns") ?: 0,
            if (table == Table.LOCATION) row.long("gps_time_ms") else null)
        return when (table) {
            Table.LOCATION -> LocationSample(run, st, row.dbl("lat")!!, row.dbl("lon")!!, row.dbl("alt_m"), row.dbl("h_acc_m"), row.dbl("v_acc_m"),
                row.dbl("speed_mps"), row.dbl("speed_acc"), row.dbl("course_deg"), row.dbl("course_acc"), row.int("floor"), row.long("gps_time_ms"), row.str("provider") ?: "unknown")
            Table.MOTION -> MotionSample(run, st, row.str("activity"), row.int("confidence"), row.dbl("pressure_kpa"), row.dbl("rel_alt_m"), row.dbl("accel_mean"), row.dbl("accel_max"))
            Table.NETWORK_PATH -> NetworkPathSample(run, st, row.str("status") ?: "unsatisfied", row.str("interfaces"), row.str("primary_interface"),
                row.bool("is_expensive"), row.bool("is_constrained"), row.str("ipv4"), row.str("ipv6"), row.str("link_quality"), row.str("unsatisfied_reason"))
            Table.RADIO -> RadioSample(run, st, row.str("service_id") ?: "", row.str("rat") ?: "UNKNOWN", row.str("nr_state"), row.str("carrier_aggregation"))
            Table.WIFI_CONNECTION -> WifiConnectionSample(run, st, row.str("ssid"), row.str("bssid"), row.str("security"), row.int("rssi_dbm"),
                row.int("link_speed_mbps"), row.int("frequency_mhz"), row.str("channel_width"), row.str("standard"))
            Table.CELL_SERVING, Table.CELL_NEIGHBOUR -> CellSample(run, st, table == Table.CELL_SERVING, row.str("rat") ?: "UNKNOWN",
                row.str("mcc"), row.str("mnc"), row.int("tac"), row.long("cell_id"), row.int("pci"), row.int("earfcn_or_arfcn"), row.str("band"),
                row.dbl("bandwidth_mhz"), row.int("rsrp"), row.int("rsrq"), row.int("rssi"), row.int("sinr"), row.int("cqi"), row.int("timing_advance"),
                row.long("cellinfo_age_ms"), row.int("subscription_id"))
            Table.WIFI_SCAN -> WifiScanSample(run, st, row.long("scan_id") ?: 0, row.str("ssid"), row.str("bssid") ?: "", row.int("rssi_dbm") ?: -127,
                row.int("frequency_mhz") ?: 0, row.str("channel_width"), row.str("standard"), row.str("capabilities"))
            Table.GNSS_SATELLITE -> GnssSatelliteSample(run, st, row.int("svid") ?: 0, row.str("constellation") ?: "UNKNOWN", row.dbl("cn0_dbhz") ?: 0.0,
                row.dbl("elevation") ?: 0.0, row.dbl("azimuth") ?: 0.0, row.bool("used_in_fix") ?: false, row.dbl("carrier_freq_hz"))
            Table.PROBE -> ProbeSample(run, st, row.str("probe_type") ?: "", row.str("endpoint") ?: "", row.bool("success") ?: false, row.str("error"),
                row.dbl("dns_ms"), row.dbl("connect_ms"), row.dbl("tls_ms"), row.dbl("ttfb_ms"), row.dbl("total_ms"), row.long("bytes"), row.dbl("mbps"),
                row.str("interface"), row.bool("is_cellular"), row.str("public_ip"), row.str("asn"), row.str("asn_org"))
            Table.DEVICE -> DeviceSample(run, st, row.dbl("battery"), row.bool("charging"), row.bool("low_power"), row.str("thermal_state"))
            Table.EVENT -> EventSample(run, st, row.str("event_type") ?: "", row.str("severity") ?: "info", row.str("before_json"), row.str("after_json"),
                row.dbl("lat"), row.dbl("lon"), row.str("likely_cause"), row.dbl("cause_confidence"), row.str("evidence_json"))
            Table.ANNOTATION -> AnnotationSample(run, st, row.str("text"), row.str("audio_path"))
            Table.METRICKIT_PAYLOAD -> MetricKitPayloadSample(run, st, row.str("payload_json") ?: "{}")
            Table.X_ANDROID_NETWORK -> AndroidNetworkSample(run, st, row.long("network_handle"), row.str("transports"), row.bool("validated"),
                row.bool("captive_portal"), row.bool("not_metered"), row.int("down_kbps"), row.int("up_kbps"), row.str("interface"), row.str("dns_servers"), row.str("private_dns"))
            Table.X_ANDROID_WIFI_LINK -> WifiLinkSample(run, st, row.str("bssid"), row.int("tx_link_mbps"), row.int("rx_link_mbps"), row.int("max_tx_mbps"), row.int("max_rx_mbps"), row.str("mlo_links_json"))
            Table.X_ANDROID_GNSS_RAW -> GnssRawSample(run, st, row.int("measurement_count") ?: 0, row.str("clock_json"), row.str("raw_json"))
            Table.X_ANDROID_WIFI_RTT -> WifiRttSample(run, st, row.str("bssid") ?: "", row.int("status") ?: -1, row.int("distance_mm"), row.int("distance_std_dev_mm"),
                row.int("rssi_dbm"), row.int("attempted"), row.int("successful"))
            Table.X_EVENT_LABEL -> EventLabelSample(run, st, row.long("event_id") ?: 0, row.str("label") ?: "")
            Table.X_GAP -> GapSample(run, st, row.long("start_utc_ms") ?: 0, row.long("end_utc_ms") ?: 0, row.str("reason") ?: "")
            Table.RUNS -> error("runs is decoded with decodeRun")
        }
    }
}
