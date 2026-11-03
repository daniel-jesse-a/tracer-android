package app.tracer.core

import app.tracer.core.model.*
import app.tracer.core.schema.SampleCodec
import app.tracer.core.schema.Schema
import app.tracer.core.sim.Scenarios
import org.junit.Assert.*
import org.junit.Test

class SchemaTest {

    /** Locks the shared contract with iOS. Changing it requires bumping Schema.VERSION + a migration. */
    @Test fun sharedSchemaMatchesSpec() {
        val expect = mapOf(
            "runs" to "run_id,t_utc_ms,t_mono_ns,name,mode,device_platform,device_model,os_version,app_version,started_at,ended_at,linked_run_id,settings_json,gps_offset_ms_start,gps_offset_ms_end,ntp_offset_ms,completeness_json",
            "location" to "lat,lon,alt_m,h_acc_m,v_acc_m,speed_mps,speed_acc,course_deg,course_acc,floor,gps_time_ms,provider",
            "motion" to "activity,confidence,pressure_kpa,rel_alt_m,accel_mean,accel_max",
            "network_path" to "status,interfaces,primary_interface,is_expensive,is_constrained,ipv4,ipv6,link_quality,unsatisfied_reason",
            "radio" to "service_id,rat,nr_state,carrier_aggregation",
            "wifi_connection" to "ssid,bssid,security,rssi_dbm,link_speed_mbps,frequency_mhz,channel_width,standard",
            "cell_serving" to "rat,mcc,mnc,tac,cell_id,pci,earfcn_or_arfcn,band,bandwidth_mhz,rsrp,rsrq,rssi,sinr,cqi,timing_advance,cellinfo_age_ms,subscription_id",
            "cell_neighbour" to "rat,mcc,mnc,tac,cell_id,pci,earfcn_or_arfcn,band,bandwidth_mhz,rsrp,rsrq,rssi,sinr,cqi,timing_advance,cellinfo_age_ms,subscription_id",
            "wifi_scan" to "scan_id,ssid,bssid,rssi_dbm,frequency_mhz,channel_width,standard,capabilities",
            "gnss_satellite" to "svid,constellation,cn0_dbhz,elevation,azimuth,used_in_fix,carrier_freq_hz",
            "probe" to "probe_type,endpoint,success,error,dns_ms,connect_ms,tls_ms,ttfb_ms,total_ms,bytes,mbps,interface,is_cellular,public_ip,asn,asn_org",
            "device" to "battery,charging,low_power,thermal_state",
            "event" to "event_type,severity,before_json,after_json,lat,lon,likely_cause,cause_confidence,evidence_json",
            "annotation" to "text,audio_path",
            "metrickit_payload" to "payload_json",
        )
        assertEquals(expect.keys, Schema.shared.map { it.name }.toSet())
        for (td in Schema.shared) {
            val cols = td.columnNames
            if (td.name == "runs") assertEquals(expect["runs"], cols.joinToString(","))
            else {
                assertEquals("${td.name} common prefix", listOf("run_id", "t_utc_ms", "t_mono_ns"), cols.take(3))
                assertEquals(td.name, expect[td.name], cols.drop(3).joinToString(","))
            }
        }
    }

    @Test fun ddlIsWellFormed() {
        Schema.createAllSql().forEach { assertTrue(it, it.startsWith("CREATE ")) }
        assertTrue(Schema.RUNS.createSql().contains("run_id TEXT NOT NULL PRIMARY KEY"))
        assertTrue(Schema.LOCATION.createSql().contains("id INTEGER PRIMARY KEY AUTOINCREMENT"))
        assertEquals(Schema.LOCATION.columnNames.size, Schema.LOCATION.insertSql().count { it == '?' })
    }

    @Test fun codecArityMatchesEveryTable() {
        val samples = Scenarios.byName("sticky_wifi") + Scenarios.byName("carrier_gateway_change") + extensionSamples()
        val seen = mutableSetOf<Table>()
        for (s in samples) {
            val td = Schema.of(s.table)
            assertEquals("arity ${td.name}", td.columnNames.size, SampleCodec.encode(s).size)
            seen += s.table
        }
        assertTrue(seen.containsAll(listOf(Table.LOCATION, Table.GNSS_SATELLITE, Table.CELL_SERVING, Table.WIFI_SCAN, Table.PROBE, Table.WIFI_CONNECTION)))
    }

    @Test fun codecRoundTrip() {
        val samples = Scenarios.byName("sticky_wifi").take(300) + Scenarios.byName("carrier_gateway_change").filterIsInstance<ProbeSample>() + extensionSamples()
        for (s in samples) {
            val td = Schema.of(s.table)
            val row = SampleCodec.MapRow(td.columnNames.zip(SampleCodec.encode(s)).toMap())
            val back = SampleCodec.decode(s.table, row)
            val norm = if (s is LocationSample) s.copy(stamp = s.stamp.copy(gpsTimeMs = s.gpsTimeMs)) else s
            assertEquals(norm, back)
        }
        val run = RunRecord("r1", "Test", "drive", "android", "SM-S931B", "16", "1.0.0", 1000, 2000, "ios-run", "{}", 350, 360, -12, "{}", 1000, 5)
        val rowRun = SampleCodec.MapRow(Schema.RUNS.columnNames.zip(SampleCodec.encodeRun(run)).toMap())
        assertEquals(run, SampleCodec.decodeRun(rowRun))
    }

    private fun extensionSamples(): List<Sample> {
        val st = Stamp(10, 20)
        return listOf(
            MotionSample("r", st, "in_vehicle", 80, 101.2, 1.5, 0.2, 1.4),
            DeviceSample("r", st, 0.81, true, false, "light"),
            RadioSample("r", st, "1", "NR_NSA", "connected", "{\"active\":true}"),
            NetworkPathSample("r", st, "satisfied", "cellular", "cellular", true, false, "10.0.0.2", null, "good", null),
            EventSample("r", st, "drop", "critical", "{}", "{}", 25.0, 55.0, "tunnel_underpass", 0.85, "{}"),
            AnnotationSample("r", st, "bridge", null),
            MetricKitPayloadSample("r", st, "{}"),
            AndroidNetworkSample("r", st, 123L, "CELLULAR", true, false, false, 50000, 10000, "rmnet0", "8.8.8.8", null),
            WifiLinkSample("r", st, "aa", 866, 780, 1201, 1201, "[]"),
            GnssRawSample("r", st, 31, "{}", null),
            WifiRttSample("r", st, "aa", 0, 4200, 300, -55, 8, 8),
            EventLabelSample("r", st, 7, "known dead zone"),
            GapSample("r", st, 1, 2, "process_restart"),
        )
    }
}
