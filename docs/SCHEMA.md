# Tracer data schema — v3

Generated from `core/src/main/kotlin/app/tracer/core/schema/Schema.kt` by `tools/gen/SchemaDoc.kt`. Do not edit by hand.

Shared-schema fingerprint (SHA-256 of `Schema.sharedFingerprint()`): `f840069d7d9551be69443dbe2a5f37abe63e5c9554e20938493eb76d08d3f40a` — the iOS app computes the same string; bundles carry it as `schema_fingerprint_sha256` in `run.json`.

Conventions: snake_case; booleans are INTEGER 0/1; every row carries `run_id`, `t_utc_ms` (wall clock), `t_mono_ns` (elapsedRealtimeNanos / mach_continuous_time); `gps_time_ms` is the GNSS-derived UTC of a fix. Sample tables have `id INTEGER PRIMARY KEY AUTOINCREMENT` and an index on `(run_id, t_mono_ns)`. CSV export: RFC 4180, header = column list below, NULL = empty field.

## Shared tables (identical on Android and iOS)

### `runs`

| # | column | type | null |
|---|---|---|---|
| 0 | `run_id` | TEXT | no |
| 1 | `t_utc_ms` | INTEGER | no |
| 2 | `t_mono_ns` | INTEGER | no |
| 3 | `name` | TEXT | no |
| 4 | `mode` | TEXT | no |
| 5 | `device_platform` | TEXT | no |
| 6 | `device_model` | TEXT | no |
| 7 | `os_version` | TEXT | no |
| 8 | `app_version` | TEXT | no |
| 9 | `started_at` | INTEGER | no |
| 10 | `ended_at` | INTEGER | yes |
| 11 | `linked_run_id` | TEXT | yes |
| 12 | `settings_json` | TEXT | yes |
| 13 | `gps_offset_ms_start` | INTEGER | yes |
| 14 | `gps_offset_ms_end` | INTEGER | yes |
| 15 | `ntp_offset_ms` | INTEGER | yes |
| 16 | `completeness_json` | TEXT | yes |
| 17 | `site` | TEXT | yes |
| 18 | `test_number` | INTEGER | yes |
| 19 | `protocol` | TEXT | yes |
| 20 | `time_zone` | TEXT | yes |
| 21 | `method_fingerprint` | TEXT | yes |

```sql
CREATE TABLE runs (run_id TEXT NOT NULL PRIMARY KEY, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, name TEXT NOT NULL, mode TEXT NOT NULL, device_platform TEXT NOT NULL, device_model TEXT NOT NULL, os_version TEXT NOT NULL, app_version TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER, linked_run_id TEXT, settings_json TEXT, gps_offset_ms_start INTEGER, gps_offset_ms_end INTEGER, ntp_offset_ms INTEGER, completeness_json TEXT, site TEXT, test_number INTEGER, protocol TEXT, time_zone TEXT, method_fingerprint TEXT);
```

### `location`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `lat` | REAL | no |
| 5 | `lon` | REAL | no |
| 6 | `alt_m` | REAL | yes |
| 7 | `h_acc_m` | REAL | yes |
| 8 | `v_acc_m` | REAL | yes |
| 9 | `speed_mps` | REAL | yes |
| 10 | `speed_acc` | REAL | yes |
| 11 | `course_deg` | REAL | yes |
| 12 | `course_acc` | REAL | yes |
| 13 | `floor` | INTEGER | yes |
| 14 | `gps_time_ms` | INTEGER | yes |
| 15 | `provider` | TEXT | no |
| 16 | `is_mock` | INTEGER | yes |

```sql
CREATE TABLE location (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, alt_m REAL, h_acc_m REAL, v_acc_m REAL, speed_mps REAL, speed_acc REAL, course_deg REAL, course_acc REAL, floor INTEGER, gps_time_ms INTEGER, provider TEXT NOT NULL, is_mock INTEGER);
```

### `motion`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `activity` | TEXT | yes |
| 5 | `confidence` | INTEGER | yes |
| 6 | `pressure_kpa` | REAL | yes |
| 7 | `rel_alt_m` | REAL | yes |
| 8 | `accel_mean` | REAL | yes |
| 9 | `accel_max` | REAL | yes |

```sql
CREATE TABLE motion (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, activity TEXT, confidence INTEGER, pressure_kpa REAL, rel_alt_m REAL, accel_mean REAL, accel_max REAL);
```

### `network_path`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `status` | TEXT | no |
| 5 | `interfaces` | TEXT | yes |
| 6 | `primary_interface` | TEXT | yes |
| 7 | `is_expensive` | INTEGER | yes |
| 8 | `is_constrained` | INTEGER | yes |
| 9 | `ipv4` | TEXT | yes |
| 10 | `ipv6` | TEXT | yes |
| 11 | `link_quality` | TEXT | yes |
| 12 | `unsatisfied_reason` | TEXT | yes |

```sql
CREATE TABLE network_path (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, status TEXT NOT NULL, interfaces TEXT, primary_interface TEXT, is_expensive INTEGER, is_constrained INTEGER, ipv4 TEXT, ipv6 TEXT, link_quality TEXT, unsatisfied_reason TEXT);
```

### `radio`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `service_id` | TEXT | no |
| 5 | `rat` | TEXT | no |
| 6 | `nr_state` | TEXT | yes |
| 7 | `carrier_aggregation` | TEXT | yes |

```sql
CREATE TABLE radio (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, service_id TEXT NOT NULL, rat TEXT NOT NULL, nr_state TEXT, carrier_aggregation TEXT);
```

### `wifi_connection`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `ssid` | TEXT | yes |
| 5 | `bssid` | TEXT | yes |
| 6 | `security` | TEXT | yes |
| 7 | `rssi_dbm` | INTEGER | yes |
| 8 | `link_speed_mbps` | INTEGER | yes |
| 9 | `frequency_mhz` | INTEGER | yes |
| 10 | `channel_width` | TEXT | yes |
| 11 | `standard` | TEXT | yes |

```sql
CREATE TABLE wifi_connection (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, ssid TEXT, bssid TEXT, security TEXT, rssi_dbm INTEGER, link_speed_mbps INTEGER, frequency_mhz INTEGER, channel_width TEXT, standard TEXT);
```

### `cell_serving`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `rat` | TEXT | no |
| 5 | `mcc` | TEXT | yes |
| 6 | `mnc` | TEXT | yes |
| 7 | `tac` | INTEGER | yes |
| 8 | `cell_id` | INTEGER | yes |
| 9 | `pci` | INTEGER | yes |
| 10 | `earfcn_or_arfcn` | INTEGER | yes |
| 11 | `band` | TEXT | yes |
| 12 | `bandwidth_mhz` | REAL | yes |
| 13 | `rsrp` | INTEGER | yes |
| 14 | `rsrq` | INTEGER | yes |
| 15 | `rssi` | INTEGER | yes |
| 16 | `sinr` | INTEGER | yes |
| 17 | `cqi` | INTEGER | yes |
| 18 | `timing_advance` | INTEGER | yes |
| 19 | `cellinfo_age_ms` | INTEGER | yes |
| 20 | `subscription_id` | INTEGER | yes |

```sql
CREATE TABLE cell_serving (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, rat TEXT NOT NULL, mcc TEXT, mnc TEXT, tac INTEGER, cell_id INTEGER, pci INTEGER, earfcn_or_arfcn INTEGER, band TEXT, bandwidth_mhz REAL, rsrp INTEGER, rsrq INTEGER, rssi INTEGER, sinr INTEGER, cqi INTEGER, timing_advance INTEGER, cellinfo_age_ms INTEGER, subscription_id INTEGER);
```

### `cell_neighbour`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `rat` | TEXT | no |
| 5 | `mcc` | TEXT | yes |
| 6 | `mnc` | TEXT | yes |
| 7 | `tac` | INTEGER | yes |
| 8 | `cell_id` | INTEGER | yes |
| 9 | `pci` | INTEGER | yes |
| 10 | `earfcn_or_arfcn` | INTEGER | yes |
| 11 | `band` | TEXT | yes |
| 12 | `bandwidth_mhz` | REAL | yes |
| 13 | `rsrp` | INTEGER | yes |
| 14 | `rsrq` | INTEGER | yes |
| 15 | `rssi` | INTEGER | yes |
| 16 | `sinr` | INTEGER | yes |
| 17 | `cqi` | INTEGER | yes |
| 18 | `timing_advance` | INTEGER | yes |
| 19 | `cellinfo_age_ms` | INTEGER | yes |
| 20 | `subscription_id` | INTEGER | yes |

```sql
CREATE TABLE cell_neighbour (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, rat TEXT NOT NULL, mcc TEXT, mnc TEXT, tac INTEGER, cell_id INTEGER, pci INTEGER, earfcn_or_arfcn INTEGER, band TEXT, bandwidth_mhz REAL, rsrp INTEGER, rsrq INTEGER, rssi INTEGER, sinr INTEGER, cqi INTEGER, timing_advance INTEGER, cellinfo_age_ms INTEGER, subscription_id INTEGER);
```

### `wifi_scan`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `scan_id` | INTEGER | no |
| 5 | `ssid` | TEXT | yes |
| 6 | `bssid` | TEXT | no |
| 7 | `rssi_dbm` | INTEGER | no |
| 8 | `frequency_mhz` | INTEGER | no |
| 9 | `channel_width` | TEXT | yes |
| 10 | `standard` | TEXT | yes |
| 11 | `capabilities` | TEXT | yes |
| 12 | `features` | TEXT | yes |

```sql
CREATE TABLE wifi_scan (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, scan_id INTEGER NOT NULL, ssid TEXT, bssid TEXT NOT NULL, rssi_dbm INTEGER NOT NULL, frequency_mhz INTEGER NOT NULL, channel_width TEXT, standard TEXT, capabilities TEXT, features TEXT);
```

### `gnss_satellite`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `svid` | INTEGER | no |
| 5 | `constellation` | TEXT | no |
| 6 | `cn0_dbhz` | REAL | no |
| 7 | `elevation` | REAL | no |
| 8 | `azimuth` | REAL | no |
| 9 | `used_in_fix` | INTEGER | no |
| 10 | `carrier_freq_hz` | REAL | yes |

```sql
CREATE TABLE gnss_satellite (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, svid INTEGER NOT NULL, constellation TEXT NOT NULL, cn0_dbhz REAL NOT NULL, elevation REAL NOT NULL, azimuth REAL NOT NULL, used_in_fix INTEGER NOT NULL, carrier_freq_hz REAL);
```

### `probe`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `probe_type` | TEXT | no |
| 5 | `endpoint` | TEXT | no |
| 6 | `success` | INTEGER | no |
| 7 | `error` | TEXT | yes |
| 8 | `dns_ms` | REAL | yes |
| 9 | `connect_ms` | REAL | yes |
| 10 | `tls_ms` | REAL | yes |
| 11 | `ttfb_ms` | REAL | yes |
| 12 | `total_ms` | REAL | yes |
| 13 | `bytes` | INTEGER | yes |
| 14 | `mbps` | REAL | yes |
| 15 | `interface` | TEXT | yes |
| 16 | `is_cellular` | INTEGER | yes |
| 17 | `public_ip` | TEXT | yes |
| 18 | `asn` | TEXT | yes |
| 19 | `asn_org` | TEXT | yes |
| 20 | `http_status` | INTEGER | yes |
| 21 | `redirect_count` | INTEGER | yes |
| 22 | `final_url` | TEXT | yes |
| 23 | `expected_response` | INTEGER | yes |
| 24 | `remote_ip` | TEXT | yes |
| 25 | `ip_version` | INTEGER | yes |
| 26 | `edge_pop` | TEXT | yes |
| 27 | `edge_headers` | TEXT | yes |
| 28 | `tls_cert_sha256` | TEXT | yes |

```sql
CREATE TABLE probe (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, probe_type TEXT NOT NULL, endpoint TEXT NOT NULL, success INTEGER NOT NULL, error TEXT, dns_ms REAL, connect_ms REAL, tls_ms REAL, ttfb_ms REAL, total_ms REAL, bytes INTEGER, mbps REAL, interface TEXT, is_cellular INTEGER, public_ip TEXT, asn TEXT, asn_org TEXT, http_status INTEGER, redirect_count INTEGER, final_url TEXT, expected_response INTEGER, remote_ip TEXT, ip_version INTEGER, edge_pop TEXT, edge_headers TEXT, tls_cert_sha256 TEXT);
```

### `device`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `battery` | REAL | yes |
| 5 | `charging` | INTEGER | yes |
| 6 | `low_power` | INTEGER | yes |
| 7 | `thermal_state` | TEXT | yes |

```sql
CREATE TABLE device (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, battery REAL, charging INTEGER, low_power INTEGER, thermal_state TEXT);
```

### `event`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `event_type` | TEXT | no |
| 5 | `severity` | TEXT | no |
| 6 | `before_json` | TEXT | yes |
| 7 | `after_json` | TEXT | yes |
| 8 | `lat` | REAL | yes |
| 9 | `lon` | REAL | yes |
| 10 | `likely_cause` | TEXT | yes |
| 11 | `cause_confidence` | REAL | yes |
| 12 | `evidence_json` | TEXT | yes |

```sql
CREATE TABLE event (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, event_type TEXT NOT NULL, severity TEXT NOT NULL, before_json TEXT, after_json TEXT, lat REAL, lon REAL, likely_cause TEXT, cause_confidence REAL, evidence_json TEXT);
```

### `annotation`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `text` | TEXT | yes |
| 5 | `audio_path` | TEXT | yes |

```sql
CREATE TABLE annotation (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, text TEXT, audio_path TEXT);
```

### `metrickit_payload`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `payload_json` | TEXT | no |

```sql
CREATE TABLE metrickit_payload (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, payload_json TEXT NOT NULL);
```

### `waypoint`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `seq` | INTEGER | no |
| 5 | `label` | TEXT | yes |
| 6 | `lat` | REAL | yes |
| 7 | `lon` | REAL | yes |
| 8 | `h_acc_m` | REAL | yes |
| 9 | `provider` | TEXT | yes |

```sql
CREATE TABLE waypoint (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, seq INTEGER NOT NULL, label TEXT, lat REAL, lon REAL, h_acc_m REAL, provider TEXT);
```

### `wifi_roam`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `ssid` | TEXT | yes |
| 5 | `from_bssid` | TEXT | yes |
| 6 | `to_bssid` | TEXT | yes |
| 7 | `from_band` | TEXT | yes |
| 8 | `to_band` | TEXT | yes |
| 9 | `from_rssi_dbm` | INTEGER | yes |
| 10 | `to_rssi_dbm` | INTEGER | yes |
| 11 | `link_down_ms` | INTEGER | yes |
| 12 | `latency_penalty_ms` | REAL | yes |
| 13 | `rtt_before_ms` | REAL | yes |
| 14 | `rtt_after_ms` | REAL | yes |
| 15 | `target_11k` | INTEGER | yes |
| 16 | `target_11v` | INTEGER | yes |
| 17 | `target_11r` | INTEGER | yes |

```sql
CREATE TABLE wifi_roam (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, ssid TEXT, from_bssid TEXT, to_bssid TEXT, from_band TEXT, to_band TEXT, from_rssi_dbm INTEGER, to_rssi_dbm INTEGER, link_down_ms INTEGER, latency_penalty_ms REAL, rtt_before_ms REAL, rtt_after_ms REAL, target_11k INTEGER, target_11v INTEGER, target_11r INTEGER);
```

### `integrity`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `gps_time_ms` | INTEGER | yes |
| 5 | `kind` | TEXT | no |
| 6 | `path` | TEXT | yes |
| 7 | `target` | TEXT | no |
| 8 | `ok` | INTEGER | yes |
| 9 | `value` | TEXT | yes |
| 10 | `detail_json` | TEXT | yes |

```sql
CREATE TABLE integrity (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, gps_time_ms INTEGER, kind TEXT NOT NULL, path TEXT, target TEXT NOT NULL, ok INTEGER, value TEXT, detail_json TEXT);
```

## Android extension tables (`x_` prefix — iOS importer ignores them)

### `x_android_network`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `network_handle` | INTEGER | yes |
| 5 | `transports` | TEXT | yes |
| 6 | `validated` | INTEGER | yes |
| 7 | `captive_portal` | INTEGER | yes |
| 8 | `not_metered` | INTEGER | yes |
| 9 | `down_kbps` | INTEGER | yes |
| 10 | `up_kbps` | INTEGER | yes |
| 11 | `interface` | TEXT | yes |
| 12 | `dns_servers` | TEXT | yes |
| 13 | `private_dns` | TEXT | yes |
| 14 | `ifname` | TEXT | yes |

```sql
CREATE TABLE x_android_network (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, network_handle INTEGER, transports TEXT, validated INTEGER, captive_portal INTEGER, not_metered INTEGER, down_kbps INTEGER, up_kbps INTEGER, interface TEXT, dns_servers TEXT, private_dns TEXT, ifname TEXT);
```

### `x_android_wifi_link`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `bssid` | TEXT | yes |
| 5 | `tx_link_mbps` | INTEGER | yes |
| 6 | `rx_link_mbps` | INTEGER | yes |
| 7 | `max_tx_mbps` | INTEGER | yes |
| 8 | `max_rx_mbps` | INTEGER | yes |
| 9 | `mlo_links_json` | TEXT | yes |

```sql
CREATE TABLE x_android_wifi_link (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, bssid TEXT, tx_link_mbps INTEGER, rx_link_mbps INTEGER, max_tx_mbps INTEGER, max_rx_mbps INTEGER, mlo_links_json TEXT);
```

### `x_android_gnss_raw`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `measurement_count` | INTEGER | no |
| 5 | `clock_json` | TEXT | yes |
| 6 | `raw_json` | TEXT | yes |

```sql
CREATE TABLE x_android_gnss_raw (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, measurement_count INTEGER NOT NULL, clock_json TEXT, raw_json TEXT);
```

### `x_android_wifi_rtt`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `bssid` | TEXT | no |
| 5 | `status` | INTEGER | no |
| 6 | `distance_mm` | INTEGER | yes |
| 7 | `distance_std_dev_mm` | INTEGER | yes |
| 8 | `rssi_dbm` | INTEGER | yes |
| 9 | `attempted` | INTEGER | yes |
| 10 | `successful` | INTEGER | yes |

```sql
CREATE TABLE x_android_wifi_rtt (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, bssid TEXT NOT NULL, status INTEGER NOT NULL, distance_mm INTEGER, distance_std_dev_mm INTEGER, rssi_dbm INTEGER, attempted INTEGER, successful INTEGER);
```

### `x_event_label`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `event_id` | INTEGER | no |
| 5 | `label` | TEXT | no |

```sql
CREATE TABLE x_event_label (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, event_id INTEGER NOT NULL, label TEXT NOT NULL);
```

### `x_gap`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `start_utc_ms` | INTEGER | no |
| 5 | `end_utc_ms` | INTEGER | no |
| 6 | `reason` | TEXT | no |

```sql
CREATE TABLE x_gap (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, start_utc_ms INTEGER NOT NULL, end_utc_ms INTEGER NOT NULL, reason TEXT NOT NULL);
```

### `x_android_carrier`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `service_id` | TEXT | no |
| 5 | `idx` | INTEGER | no |
| 6 | `rat` | TEXT | no |
| 7 | `band` | TEXT | yes |
| 8 | `arfcn` | INTEGER | yes |
| 9 | `pci` | INTEGER | yes |
| 10 | `bandwidth_mhz` | REAL | yes |
| 11 | `is_primary` | INTEGER | no |
| 12 | `source` | TEXT | no |

```sql
CREATE TABLE x_android_carrier (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, service_id TEXT NOT NULL, idx INTEGER NOT NULL, rat TEXT NOT NULL, band TEXT, arfcn INTEGER, pci INTEGER, bandwidth_mhz REAL, is_primary INTEGER NOT NULL, source TEXT NOT NULL);
```

### `x_collector_log`

| # | column | type | null |
|---|---|---|---|
| 0 | `id` | INTEGER PK | no |
| 1 | `run_id` | TEXT | no |
| 2 | `t_utc_ms` | INTEGER | no |
| 3 | `t_mono_ns` | INTEGER | no |
| 4 | `collector` | TEXT | no |
| 5 | `health` | TEXT | no |
| 6 | `message` | TEXT | yes |
| 7 | `restarts` | INTEGER | yes |

```sql
CREATE TABLE x_collector_log (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, collector TEXT NOT NULL, health TEXT NOT NULL, message TEXT, restarts INTEGER);
```

## Value vocabularies

| column | values |
|---|---|
| `runs.mode` | `walk` · `drive` (Car) · `rail` (Train) · `custom` · `static` · `simulated` |
| `runs.device_platform` | `android` · `ios` |
| `location.provider` | `gps` · `fused` · `network` · `core_location` |
| `location.is_mock` | v3: Android `Location.isMock`, iOS `sourceInformation.isSimulatedBySoftware`; 1 = spoofed fix (ignored by movement/bbox) |
| `network_path.status` | `satisfied` · `unsatisfied` · `requires_connection` |
| `network_path.unsatisfied_reason` | `not_validated` · `captive_portal` · `no_network` · `airplane_mode` · `blocked` |
| `network_path.primary_interface` | `wifi` · `cellular` · `ethernet` · `vpn` · `other` · `none` |
| `network_path.link_quality` | `good` (≥ 20 Mbps est.) · `moderate` (≥ 2) · `minimal` · `unknown` |
| `network_path.is_constrained` | Android: `!NET_CAPABILITY_NOT_CONGESTED`; iOS: `NWPath.isConstrained` (Low Data Mode) |
| `radio.rat` | `NONE` `GSM` `UMTS` `HSPA` `LTE` `LTE_CA` `LTE_ADV_PRO` `NR_NSA` `NR_NSA_MMWAVE` `NR_ADVANCED` `NR_SA` `IWLAN` `UNKNOWN` |
| `radio.nr_state` | v2: `none` · `nsa` · `nsa_advanced` · `sa`, derived from TelephonyDisplayInfo override type (NR_NSA / NR_ADVANCED) and network type NR (SA), else `nsa` if an aggregated carrier is NR (Android only; null on iOS) |
| `radio.carrier_aggregation` | JSON `{"active":bool,"cell_count":n,"aggregate_mhz":x,"bandwidths_khz":[…],"rat":"…","carriers":[{rat,band,arfcn,pci,bandwidth_mhz,primary}]}`. A carrier > 20 MHz is always NR (LTE max is 20 MHz); per-carrier rows also in `x_android_carrier` |
| `cell_*.rat` | `GSM` `WCDMA` `TDSCDMA` `LTE` `NR` `CDMA` |
| `cell_*.rsrp` | LTE RSRP · NR SS-RSRP (CSI-RSRP fallback) · WCDMA RSCP — dBm |
| `cell_*.rsrq` | LTE RSRQ · NR SS-RSRQ · WCDMA Ec/No — dB |
| `cell_*.sinr` | LTE RSSNR · NR SS-SINR (CSI fallback) — dB |
| `cell_*.timing_advance` | LTE TA steps (×78.12 m) · NR round-trip µs (×149.9 m) · GSM TA (×553.85 m) |
| `cell_*.band` | `B<n>` LTE · `n<n>` NR · `U<n>` UMTS (from reported bands, else derived from (E/NR/U)ARFCN) |
| `cell_*.cellinfo_age_ms` | now − CellInfo.timestampNanos; rows older than `cell_stale_ms` are ignored by event rules |
| `wifi_*.standard` | `legacy` `wifi4` `wifi5` `wifi6` `wifi6e` `wifi7` `wigig` `unknown` |
| `wifi_*.channel_width` | `20` `40` `80` `160` `80+80` `320` (MHz) |
| `probe.probe_type` | `latency` · `throughput` · `public_ip` · `captive` · `gateway` (ICMP to the default gateway) · `dns_server` (UDP query to a DHCP DNS server) |
| `probe.success` | v2: **reachable** — an HTTP response / DNS answer / ping reply arrived (redirects and 4xx/5xx are reachable) |
| `probe.expected_response` | v2: the final status matched the endpoint's expectation (e.g. 204 for `generate_204`) |
| `probe.http_status` / `redirect_count` / `final_url` | v2: status of the first (timed) hop; redirects followed separately (max 5) for count and final URL |
| `probe.interface` | v2: transport name `wifi` · `cellular` · `ethernet` · `vpn` (kernel names only in `x_android_network.ifname`) |
| `wifi_scan.features` | v2: `11k` (RM enabled caps IE 70) · `11v` (BSS transition, ext caps IE 127 bit 19) · `11r` (mobility domain IE 54 or FT AKM) |
| `probe.remote_ip` / `ip_version` | v3: address of the connection that served the request; `4` or `6` |
| `probe.edge_pop` | v3: edge / PoP that answered: Cloudflare `cf-ray` suffix (e.g. `DXB`), `x-amz-cf-pop`, `x-served-by`, Apple edge / `via` node |
| `probe.edge_headers` | v3: JSON object of the raw edge headers present (`cf-ray`, `server`, `via`, `x-cache`, `x-served-by`, `x-amz-cf-pop`, `alt-svc`, …) |
| `probe.tls_cert_sha256` | v3: SHA-256 (hex) of the leaf TLS certificate |
| `integrity.kind` | v3: `dns_doh` (system resolver vs DNS-over-HTTPS answers for `target`) · `tls_cert` (leaf fingerprint seen for `target`) · `first_hop` (first public hop on `path`) |
| `integrity.ok` | v3: 1 = consistent / found · 0 = mismatch / failed · NULL = could not test; `value` = short result, `detail_json` = full detail. `gps_time_ms` follows the common triple (GNSS-aligned UTC when known) |
| `probe.error` | null · `timeout` · `dns` · `connect` · `tls` · `io` · `no_network` · `http_<code>` |
| `device.thermal_state` | Android `nominal light moderate severe critical emergency shutdown`; iOS `nominal fair serious critical` |
| `event.event_type` | `handover` `rat_change` `wifi_roam` `wifi_cell_switch` `drop` `reconnect` `captive_portal` `ip_asn_change` `gps_degraded` `latency_spike` `dns_spike` `throughput_drop` `signal_drop` `neighbour_overtake`; v3: `rat_flicker` (a RAT change that reverted within 2 s, info) · `gps_recovered` (end of a `gps_degraded` episode, info) |
| `event.severity` | `info` · `warning` · `critical` |
| `event.likely_cause` | `tunnel_underpass` `cell_edge` `late_failed_handover` `handover_churn` `congestion_backhaul` `interference` `wifi_weak_signal` `sticky_client` `channel_congestion` `wifi_uplink` `wifi_roam_transition` `wifi_link_delay` `upstream_delay` `captive_portal` `dns_issue` `carrier_gateway_change`; v3: `dns_retry_timer` |
| `event.evidence_json` | `{"window_ms","window_s","candidates":[{"cause","confidence","signals":{…}}…],"snapshot":{serving, rat, wifi, path_status, gnss_used, …}}`. v3: every event (diagnosed or not) carries `window_s` (the cause window actually used); events in a resume window add `"after_resume":true` and `resume_gap_s`; DNS retry-timer spikes add `"dns_retry_timer":true` |
| `waypoint` | tap-to-mark: `seq`, optional `label`, best position at tap time |
| `wifi_roam` | one row per roam, written ~10 s after it: from/to BSSID, band, RSSI, `link_down_ms` (disconnect → re-association, 0 = seamless), `latency_penalty_ms` (median Wi-Fi RTT 10 s after − 30 s before), target AP `11k`/`11v`/`11r` |
| `x_collector_log.health` | `ok` · `degraded` · `unavailable` · `stopped` · `disabled` (collector status — kept apart from user waypoints/annotations) |
| `runs.method_fingerprint` | 16 hex chars of SHA-256 over endpoints (+expected status), probe paths, throughput size/cap/interval, latency interval, internal probes, thresholds, settings_version, protocol. Same fingerprint ⇒ same method |
| `x_gap.reason` | `pause` · `process_restart` · `storage_low` · `permission_revoked` · `airplane_mode` · `watchdog`; v3: `stall` (service heartbeat saw no samples) |

## Deviations from the original shared-schema brief (proposed for iOS adoption)

1. **`id` row key** on every sample table — needed for event labels and stable CSV diffs. Additive; iOS should add it.
2. **Android-only detail goes to `x_` tables** rather than widening shared tables: `x_android_network` (validated, captive, NOT_METERED, link bandwidth estimates, DNS), `x_android_wifi_link` (tx/rx link speed, MLO links), `x_android_gnss_raw`, `x_android_wifi_rtt`. Shared tables stay byte-identical.
3. **Event labels** (editable in Event Detail) live in `x_event_label(event_id, label)`; latest row per `event_id` wins.
4. **Recording gaps** (pause, process restart, storage stop) live in `x_gap` so completeness can exclude them.
5. `motion` has no gyro columns in the brief, so gyroscope samples are not persisted.

## Cause attribution rules (v2)

Diagnosis runs once the stream has reached event time T + 2 s. Periodic signals (serving-cell rows, GNSS epochs, probes) use the **median within T ± 2 s**. Change-reported state (Wi-Fi link, path, Android validation flags, RAT, scans ≤ 30 s old) uses the **last report at or before T + 2 s**; for roams and Wi-Fi→cell switches, the AP that was left. Precursor *events* keep their own windows: overtake ≤ 15 s before, roam ≤ 10 s before, handover rate over 60 s, and a tunnel baseline at T−45…T−15 s. Cellular-RF events and events on the cellular path never get Wi-Fi causes. `captive_portal` requires Android's captive flag, or Android reporting the network unvalidated **and** probes getting reachable-but-unexpected answers.

## Cause attribution and wake artefacts (v3)

The cause window is `thresholds.cause_lookback_s` (default **2 s**, the value the engine always used; stored settings still holding the never-applied 15 s migrate to 2). It is recorded in every event's evidence (`window_s`), in `method.cause_window_s` (run.json, summary.json, and the method fingerprint) and in `summary.json` `event_rules`.

- **Resume window.** After any gap of ≥ 10 s (an `x_gap` row of any reason, or ≥ 10 s with no data sample of any table) the next **30 s** are a resume window. Rolling latency/DNS medians, per-endpoint latency windows, serving-cell (signal) history and GPS step baselines restart. `dns_spike`, `latency_spike`, `rat_change` and `rat_flicker` inside the window are `info` with `"after_resume":true`, and never get `tunnel_underpass`. A recording gap is not a GPS no-fix: the no-fix timer restarts at the resume.
- **DNS retry timer.** A DNS spike whose `dns_ms` is 1,900–2,200 or 4,900–5,200 ms on the first real lookup (`dns_ms` > 1) after ≥ 20 s without one on that path gets `likely_cause = dns_retry_timer` (first query lost while the radio woke), severity `info`. Such answers and cached (≤ 1 ms) answers stay out of the DNS baseline.
- **RAT debounce.** A RAT change is held 2 s; if it reverts within that time (5G→LTE→5G) it is one `rat_flicker` (info, not diagnosed). Changes that stand are `rat_change`; downgrades are diagnosed at T + 5.5 s. `tunnel_underpass` requires a GNSS collapse plus a signal (RSRP) drop plus, for `rat_change`, a downgrade held > 5 s (`signals.rat_downgrade_held_ms`); other events need the GNSS collapse plus a signal drop or a sustained downgrade.
- **Latency spikes** use a baseline per (path, endpoint): the endpoint's last `latency_window` RTTs, after ≥ 10 samples, spike only above max(p90 × 1.5, p90 + `latency_min_ms`), so bimodal endpoints (edge vs origin, 5G vs LTE) and slow-but-steady endpoints do not fire.
- **GPS episodes.** `gps_degraded` fires once on entering the degraded state (h_acc > `gps_h_acc_m`, a position jump, too few satellites, or no fix for `gps_no_fix_s`) and `gps_recovered` once after `gps_recover_s` without a bad fix; nothing re-fires while it stays degraded. Evidence carries `baseline_h_acc_m` = median h_acc of **all** raw GPS fixes in the last 60 s (never frozen at a good value).

## Coverage and gaps (v3)

`run.json` and `summary.json` carry a `coverage` block computed from the data, plus top-level `captured_s` and `coverage_pct` (`summary.json` also `gaps`; run.json's recorded `completeness` gains `gaps`, `gap_s`, `captured_s`, `coverage_pct`):

`{"duration_s","paused_s","pauses","captured_s","coverage_pct","stall_threshold_s":15,"gaps":{"count","total_s","longest_s","by_reason":{"<reason>":{"count","total_s"}},"list":[{"t_run_s","duration_s","reason"}]}}`

Gaps are the union of `x_gap` rows (reason ≠ `pause`) and **stalls**: any stretch while recording (pauses excluded) longer than 15 s with no row of any table (events, labels and gap rows are not evidence of recording). Overlapping intervals are merged, so a heartbeat `stall` row and the silence it describes count once; a merged gap takes the reason of the `x_gap` row overlapping it most, else `stall`. `coverage_pct` = captured ÷ (duration − paused) × 100. x_gap wall-clock bounds are mapped onto the monotonic timeline with the row's own (t_utc_ms, t_mono_ns) pair.

`summary.json` (summary_version 2) also adds per endpoint `remote_ips`, `ip_versions`, `edge_pops` (counts; the same for throughput under `throughput_mbps`, with `duration_ms_median`), an `integrity` block (`checks`, `ok`, `failed`, `untested`, `by_kind`, `latest[]` per kind + path + target), a `movement` block (`bbox_m` [east-west, north-south] from the 1st–99th percentile of fixes with h_acc ≤ 50 m, `path_m` anchor-based so jitter adds nothing, `stationary` = bbox diagonal < 150 m) and `position.mock_fixes`.

## Settings v4

Speed test 25 MB, time-boxed at 5 s (`throughput_max_ms`; the actual duration is in each throughput row's `total_ms`), cellular data budget 1,000 MB, `cause_lookback_s` 2. Stored v3 settings still holding the old defaults (10 MB, 4 s, 500 MB, 15 s) are migrated; values the user changed are kept. `settings_version` and `cause_window_s` are part of the method, so every v4 run has a new method fingerprint. Protocol runs keep their frozen settings.

## Bundle layout (v3; v2 differed only in the additions marked v3 above, `<site>_t<NN>_<yyyyMMdd-HHmm>_<run8>.zip`)

| file | content |
|---|---|
| `run.json` | manifest: `schema_version` (3), `bundle_version` (3), `coverage`, `captured_s`, `coverage_pct`, `method {fingerprint, cause_window_s, stall_threshold_s}`, `generator`, `generated_at_utc_ms`, `time_zone`, `method_fingerprint`, `tables_omitted`, `csv_extra_columns`, `run` (incl. site/test/protocol), `device`, `settings`, `offsets`, `completeness`, `files[] {path, bytes, sha256, rows}` |
| `summary.json` | per path (`wifi`/`cellular`): reachable and expected-response rates overall and per endpoint (with error counts); latency median/p95 for total, DNS, connect, TLS and TTFB; throughput median/max/bytes; internal gateway and DHCP-DNS RTT, plus link vs upstream RTT split. Radio: Wi-Fi RSSI median/worst, RSRP and SINR medians, NR share. Events by type, severity and type×severity. Wi-Fi mobility: roams, penalty, link-down, time and episodes on cellular fallback. Position: fixes by provider, waypoints. Method fingerprint + canonical method JSON. Streamed, so it stays bounded on multi-hour runs |
| `run.sqlite` | canonical DDL with this run's rows, plus views `v_<table>` adding `t_run_s` and `t_local` |
| `<table>.csv` | **only tables with rows**; iOS-only tables are not written on Android. Every sample CSV ends with `t_run_s` (seconds into run, monotonic) and `t_local` (ISO-8601 local time with offset) |
| `track.geojson` / `events.geojson` | as v1 |

Import verifies every SHA-256 listed in `run.json`; missing CSVs mean empty tables; unknown tables and columns are ignored; missing columns read as NULL (v1 and v2 bundles import unchanged).

## Database upgrades

Room owns only `runs` (version 3; `MIGRATION_1_2`, `MIGRATION_2_3`, the latter has no `runs` columns). Sample tables follow `tracer_meta.schema_version`: `Schema.migrationSql(2)` adds `location.is_mock`, the five `probe` columns and creates `integrity` (+ index); every ALTER is skipped when the column already exists.

## Protocol runs

| id | duration | probe paths |
|---|---|---|
| `walk10-dual` | 10 min | default + mobile data in parallel |
| `walk10-wifi` | 10 min | Wi-Fi network only |
| `walk10-cell` | 10 min | mobile data only (even with Wi-Fi connected) |
| `drive30-dual` | 30 min | dual |
| `soak120-dual` | 2 h | dual, 500 MB mobile-data budget |

Protocols freeze every method setting (`Protocol.settings()`), so the same protocol always has the same method fingerprint. Repeat a protocol at the same site and time of day for directly comparable runs.

