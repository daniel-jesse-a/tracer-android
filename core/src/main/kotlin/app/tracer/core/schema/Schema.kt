package app.tracer.core.schema

import app.tracer.core.model.Table

enum class ColType(val sql: String) { INTEGER("INTEGER"), REAL("REAL"), TEXT("TEXT") }

data class Column(val name: String, val type: ColType, val notNull: Boolean = false)

data class TableDef(val table: Table, val columns: List<Column>, val hasRowId: Boolean = true) {
    val name: String get() = table.sqlName
    /** Insertable columns (excludes the autoincrement id). */
    val columnNames: List<String> get() = columns.map { it.name }
    /** Columns as exported (CSV header / SELECT list), id first when present. */
    val exportColumns: List<String> get() = (if (hasRowId) listOf("id") else emptyList()) + columnNames

    fun createSql(ifNotExists: Boolean = true): String = buildString {
        append("CREATE TABLE ")
        if (ifNotExists) append("IF NOT EXISTS ")
        append(name).append(" (")
        val parts = mutableListOf<String>()
        if (hasRowId) parts += "id INTEGER PRIMARY KEY AUTOINCREMENT"
        columns.forEach { c ->
            val pk = if (!hasRowId && c.name == "run_id") " PRIMARY KEY" else ""
            parts += "${c.name} ${c.type.sql}${if (c.notNull) " NOT NULL" else ""}$pk"
        }
        append(parts.joinToString(", "))
        append(")")
    }

    fun indexSql(): List<String> =
        if (table == Table.RUNS) emptyList()
        else listOf("CREATE INDEX IF NOT EXISTS idx_${name}_run_time ON $name (run_id, t_mono_ns)")

    fun insertSql(): String =
        "INSERT INTO $name (${columnNames.joinToString(", ")}) VALUES (${columnNames.joinToString(", ") { "?" }})"
}

/**
 * Canonical Tracer schema. This file is the single source of truth shared with the iOS app
 * (docs/SCHEMA.md is generated from it — see SchemaDoc). Column order matters: CSV headers
 * and positional INSERTs follow it.
 */
object Schema {
    /**
     * v2 (additive): runs.site/test_number/protocol/time_zone/method_fingerprint; probe.http_status/
     * redirect_count/final_url/expected_response; wifi_scan.features; tables waypoint, wifi_roam,
     * x_android_carrier, x_collector_log; x_android_network.ifname. v1 bundles still import.
     */
    const val VERSION = 2

    private fun i(n: String, nn: Boolean = false) = Column(n, ColType.INTEGER, nn)
    private fun r(n: String, nn: Boolean = false) = Column(n, ColType.REAL, nn)
    private fun t(n: String, nn: Boolean = false) = Column(n, ColType.TEXT, nn)

    private val common = listOf(t("run_id", true), i("t_utc_ms", true), i("t_mono_ns", true))

    private fun def(table: Table, vararg cols: Column) = TableDef(table, common + cols.toList())

    private val cellCols = arrayOf(
        t("rat", true), t("mcc"), t("mnc"), i("tac"), i("cell_id"), i("pci"), i("earfcn_or_arfcn"),
        t("band"), r("bandwidth_mhz"), i("rsrp"), i("rsrq"), i("rssi"), i("sinr"), i("cqi"),
        i("timing_advance"), i("cellinfo_age_ms"), i("subscription_id"),
    )

    val RUNS = TableDef(
        Table.RUNS,
        listOf(
            t("run_id", true), i("t_utc_ms", true), i("t_mono_ns", true),
            t("name", true), t("mode", true), t("device_platform", true), t("device_model", true),
            t("os_version", true), t("app_version", true), i("started_at", true), i("ended_at"),
            t("linked_run_id"), t("settings_json"), i("gps_offset_ms_start"), i("gps_offset_ms_end"),
            i("ntp_offset_ms"), t("completeness_json"),
            t("site"), i("test_number"), t("protocol"), t("time_zone"), t("method_fingerprint"),
        ),
        hasRowId = false,
    )

    val LOCATION = def(
        Table.LOCATION, r("lat", true), r("lon", true), r("alt_m"), r("h_acc_m"), r("v_acc_m"),
        r("speed_mps"), r("speed_acc"), r("course_deg"), r("course_acc"), i("floor"), i("gps_time_ms"), t("provider", true),
    )
    val MOTION = def(Table.MOTION, t("activity"), i("confidence"), r("pressure_kpa"), r("rel_alt_m"), r("accel_mean"), r("accel_max"))
    val NETWORK_PATH = def(
        Table.NETWORK_PATH, t("status", true), t("interfaces"), t("primary_interface"), i("is_expensive"),
        i("is_constrained"), t("ipv4"), t("ipv6"), t("link_quality"), t("unsatisfied_reason"),
    )
    val RADIO = def(Table.RADIO, t("service_id", true), t("rat", true), t("nr_state"), t("carrier_aggregation"))
    val WIFI_CONNECTION = def(
        Table.WIFI_CONNECTION, t("ssid"), t("bssid"), t("security"), i("rssi_dbm"), i("link_speed_mbps"),
        i("frequency_mhz"), t("channel_width"), t("standard"),
    )
    val CELL_SERVING = def(Table.CELL_SERVING, *cellCols)
    val CELL_NEIGHBOUR = def(Table.CELL_NEIGHBOUR, *cellCols)
    val WIFI_SCAN = def(
        Table.WIFI_SCAN, i("scan_id", true), t("ssid"), t("bssid", true), i("rssi_dbm", true), i("frequency_mhz", true),
        t("channel_width"), t("standard"), t("capabilities"), t("features"),
    )
    val GNSS_SATELLITE = def(
        Table.GNSS_SATELLITE, i("svid", true), t("constellation", true), r("cn0_dbhz", true), r("elevation", true),
        r("azimuth", true), i("used_in_fix", true), r("carrier_freq_hz"),
    )
    val PROBE = def(
        Table.PROBE, t("probe_type", true), t("endpoint", true), i("success", true), t("error"), r("dns_ms"),
        r("connect_ms"), r("tls_ms"), r("ttfb_ms"), r("total_ms"), i("bytes"), r("mbps"), t("interface"),
        i("is_cellular"), t("public_ip"), t("asn"), t("asn_org"),
        i("http_status"), i("redirect_count"), t("final_url"), i("expected_response"),
    )
    val DEVICE = def(Table.DEVICE, r("battery"), i("charging"), i("low_power"), t("thermal_state"))
    val EVENT = def(
        Table.EVENT, t("event_type", true), t("severity", true), t("before_json"), t("after_json"), r("lat"), r("lon"),
        t("likely_cause"), r("cause_confidence"), t("evidence_json"),
    )
    val ANNOTATION = def(Table.ANNOTATION, t("text"), t("audio_path"))
    val METRICKIT_PAYLOAD = def(Table.METRICKIT_PAYLOAD, t("payload_json", true))
    val WAYPOINT = def(Table.WAYPOINT, i("seq", true), t("label"), r("lat"), r("lon"), r("h_acc_m"), t("provider"))
    val WIFI_ROAM = def(
        Table.WIFI_ROAM, t("ssid"), t("from_bssid"), t("to_bssid"), t("from_band"), t("to_band"), i("from_rssi_dbm"),
        i("to_rssi_dbm"), i("link_down_ms"), r("latency_penalty_ms"), r("rtt_before_ms"), r("rtt_after_ms"),
        i("target_11k"), i("target_11v"), i("target_11r"),
    )

    val X_ANDROID_NETWORK = def(
        Table.X_ANDROID_NETWORK, i("network_handle"), t("transports"), i("validated"), i("captive_portal"),
        i("not_metered"), i("down_kbps"), i("up_kbps"), t("interface"), t("dns_servers"), t("private_dns"), t("ifname"),
    )
    val X_ANDROID_WIFI_LINK = def(
        Table.X_ANDROID_WIFI_LINK, t("bssid"), i("tx_link_mbps"), i("rx_link_mbps"), i("max_tx_mbps"),
        i("max_rx_mbps"), t("mlo_links_json"),
    )
    val X_ANDROID_GNSS_RAW = def(Table.X_ANDROID_GNSS_RAW, i("measurement_count", true), t("clock_json"), t("raw_json"))
    val X_ANDROID_WIFI_RTT = def(
        Table.X_ANDROID_WIFI_RTT, t("bssid", true), i("status", true), i("distance_mm"), i("distance_std_dev_mm"),
        i("rssi_dbm"), i("attempted"), i("successful"),
    )
    val X_EVENT_LABEL = def(Table.X_EVENT_LABEL, i("event_id", true), t("label", true))
    val X_GAP = def(Table.X_GAP, i("start_utc_ms", true), i("end_utc_ms", true), t("reason", true))
    val X_ANDROID_CARRIER = def(
        Table.X_ANDROID_CARRIER, t("service_id", true), i("idx", true), t("rat", true), t("band"), i("arfcn"), i("pci"),
        r("bandwidth_mhz"), i("is_primary", true), t("source", true),
    )
    val X_COLLECTOR_LOG = def(Table.X_COLLECTOR_LOG, t("collector", true), t("health", true), t("message"), i("restarts"))

    val all: List<TableDef> = listOf(
        RUNS, LOCATION, MOTION, NETWORK_PATH, RADIO, WIFI_CONNECTION, CELL_SERVING, CELL_NEIGHBOUR, WIFI_SCAN,
        GNSS_SATELLITE, PROBE, DEVICE, EVENT, ANNOTATION, METRICKIT_PAYLOAD, WAYPOINT, WIFI_ROAM,
        X_ANDROID_NETWORK, X_ANDROID_WIFI_LINK, X_ANDROID_GNSS_RAW, X_ANDROID_WIFI_RTT, X_EVENT_LABEL, X_GAP,
        X_ANDROID_CARRIER, X_COLLECTOR_LOG,
    )
    val shared: List<TableDef> = all.filter { it.table.isShared }
    val sampleTables: List<TableDef> = all.filter { it.table != Table.RUNS }

    private val byTable = all.associateBy { it.table }
    fun of(table: Table): TableDef = byTable.getValue(table)

    fun createAllSql(): List<String> = all.flatMap { listOf(it.createSql()) + it.indexSql() }

    /**
     * Upgrade SQL from [from] to [from]+1 for databases created with the canonical DDL. New tables
     * come from createAllSql() (IF NOT EXISTS); this adds the new columns to existing tables.
     * `runs` is listed too — Room-managed databases apply it through a Room Migration instead.
     */
    fun migrationSql(from: Int): List<String> = when (from) {
        1 -> listOf(
            "ALTER TABLE runs ADD COLUMN site TEXT", "ALTER TABLE runs ADD COLUMN test_number INTEGER",
            "ALTER TABLE runs ADD COLUMN protocol TEXT", "ALTER TABLE runs ADD COLUMN time_zone TEXT",
            "ALTER TABLE runs ADD COLUMN method_fingerprint TEXT",
            "ALTER TABLE probe ADD COLUMN http_status INTEGER", "ALTER TABLE probe ADD COLUMN redirect_count INTEGER",
            "ALTER TABLE probe ADD COLUMN final_url TEXT", "ALTER TABLE probe ADD COLUMN expected_response INTEGER",
            "ALTER TABLE wifi_scan ADD COLUMN features TEXT",
            "ALTER TABLE x_android_network ADD COLUMN ifname TEXT",
        )
        else -> emptyList()
    }

    /** Stable fingerprint of the shared schema; iOS computes the same string and compares. */
    fun sharedFingerprint(): String =
        shared.joinToString("\n") { td -> td.name + "(" + td.exportColumns.joinToString(",") + ")" }
}
