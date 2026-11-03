# Tracer data schema — v1

Generated from `core/src/main/kotlin/app/tracer/core/schema/Schema.kt` by `tools/gen/SchemaDoc.kt`. Do not edit by hand.

Shared-schema fingerprint (SHA-256 of `Schema.sharedFingerprint()`): `9ec9725f8bf4245cc2542da2da35d14a40c785756af7d7709e42e1a749c2c8d2` — the iOS app computes the same string; bundles carry it as `schema_fingerprint_sha256` in `run.json`.

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

```sql
CREATE TABLE runs (run_id TEXT NOT NULL PRIMARY KEY, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, name TEXT NOT NULL, mode TEXT NOT NULL, device_platform TEXT NOT NULL, device_model TEXT NOT NULL, os_version TEXT NOT NULL, app_version TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER, linked_run_id TEXT, settings_json TEXT, gps_offset_ms_start INTEGER, gps_offset_ms_end INTEGER, ntp_offset_ms INTEGER, completeness_json TEXT);
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

```sql
CREATE TABLE location (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, alt_m REAL, h_acc_m REAL, v_acc_m REAL, speed_mps REAL, speed_acc REAL, course_deg REAL, course_acc REAL, floor INTEGER, gps_time_ms INTEGER, provider TEXT NOT NULL);
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

```sql
CREATE TABLE wifi_scan (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, scan_id INTEGER NOT NULL, ssid TEXT, bssid TEXT NOT NULL, rssi_dbm INTEGER NOT NULL, frequency_mhz INTEGER NOT NULL, channel_width TEXT, standard TEXT, capabilities TEXT);
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

```sql
CREATE TABLE probe (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, probe_type TEXT NOT NULL, endpoint TEXT NOT NULL, success INTEGER NOT NULL, error TEXT, dns_ms REAL, connect_ms REAL, tls_ms REAL, ttfb_ms REAL, total_ms REAL, bytes INTEGER, mbps REAL, interface TEXT, is_cellular INTEGER, public_ip TEXT, asn TEXT, asn_org TEXT);
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

```sql
CREATE TABLE x_android_network (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, t_utc_ms INTEGER NOT NULL, t_mono_ns INTEGER NOT NULL, network_handle INTEGER, transports TEXT, validated INTEGER, captive_portal INTEGER, not_metered INTEGER, down_kbps INTEGER, up_kbps INTEGER, interface TEXT, dns_servers TEXT, private_dns TEXT);
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

## Value vocabularies

| column | values |
|---|---|
| `runs.mode` | `drive` · `walk` · `rail` · `static` · `simulated` |
| `runs.device_platform` | `android` · `ios` |
| `location.provider` | `gps` · `fused` · `network` · `core_location` |
| `network_path.status` | `satisfied` · `unsatisfied` · `requires_connection` |
| `network_path.unsatisfied_reason` | `not_validated` · `captive_portal` · `no_network` · `airplane_mode` · `blocked` |
| `network_path.primary_interface` | `wifi` · `cellular` · `ethernet` · `vpn` · `other` · `none` |
| `network_path.link_quality` | `good` (≥ 20 Mbps est.) · `moderate` (≥ 2) · `minimal` · `unknown` |
| `network_path.is_constrained` | Android: `!NET_CAPABILITY_NOT_CONGESTED`; iOS: `NWPath.isConstrained` (Low Data Mode) |
| `radio.rat` | `NONE` `GSM` `UMTS` `HSPA` `LTE` `LTE_CA` `LTE_ADV_PRO` `NR_NSA` `NR_NSA_MMWAVE` `NR_ADVANCED` `NR_SA` `IWLAN` `UNKNOWN` |
| `radio.nr_state` | `none` · `restricted` · `not_restricted` · `connected` (Android only; null on iOS) |
| `radio.carrier_aggregation` | JSON `{"active":bool,"cell_count":n,"aggregate_mhz":x,"bandwidths_khz":[…],"rat":"…"}` |
| `cell_*.rat` | `GSM` `WCDMA` `TDSCDMA` `LTE` `NR` `CDMA` |
| `cell_*.rsrp` | LTE RSRP · NR SS-RSRP (CSI-RSRP fallback) · WCDMA RSCP — dBm |
| `cell_*.rsrq` | LTE RSRQ · NR SS-RSRQ · WCDMA Ec/No — dB |
| `cell_*.sinr` | LTE RSSNR · NR SS-SINR (CSI fallback) — dB |
| `cell_*.timing_advance` | LTE TA steps (×78.12 m) · NR round-trip µs (×149.9 m) · GSM TA (×553.85 m) |
| `cell_*.band` | `B<n>` LTE · `n<n>` NR · `U<n>` UMTS (from reported bands, else derived from (E/NR/U)ARFCN) |
| `cell_*.cellinfo_age_ms` | now − CellInfo.timestampNanos; rows older than `cell_stale_ms` are ignored by event rules |
| `wifi_*.standard` | `legacy` `wifi4` `wifi5` `wifi6` `wifi6e` `wifi7` `wigig` `unknown` |
| `wifi_*.channel_width` | `20` `40` `80` `160` `80+80` `320` (MHz) |
| `probe.probe_type` | `latency` · `throughput` · `public_ip` · `captive` |
| `probe.error` | null · `timeout` · `dns` · `connect` · `tls` · `io` · `no_network` · `http_<code>` |
| `device.thermal_state` | Android `nominal light moderate severe critical emergency shutdown`; iOS `nominal fair serious critical` |
| `event.event_type` | `handover` `rat_change` `wifi_roam` `wifi_cell_switch` `drop` `reconnect` `captive_portal` `ip_asn_change` `gps_degraded` `latency_spike` `dns_spike` `throughput_drop` `signal_drop` `neighbour_overtake` |
| `event.severity` | `info` · `warning` · `critical` |
| `event.likely_cause` | `tunnel_underpass` `cell_edge` `late_failed_handover` `handover_churn` `congestion_backhaul` `interference` `wifi_weak_signal` `sticky_client` `channel_congestion` `wifi_uplink` `captive_portal` `dns_issue` `carrier_gateway_change` |
| `event.evidence_json` | `{"candidates":[{"cause","confidence","signals":{…}}…],"snapshot":{serving, rat, wifi, path_status, gnss_used, …}}` |
| `x_gap.reason` | `pause` · `process_restart` · `storage_low` · `permission_revoked` · `airplane_mode` · `watchdog` |

## Deviations from the original shared-schema brief (proposed for iOS adoption)

1. **`id` row key** on every sample table — needed for event labels and stable CSV diffs. Additive; iOS should add it.
2. **Android-only detail goes to `x_` tables** rather than widening shared tables: `x_android_network` (validated, captive, NOT_METERED, link bandwidth estimates, DNS), `x_android_wifi_link` (tx/rx link speed, MLO links), `x_android_gnss_raw`, `x_android_wifi_rtt`. Shared tables stay byte-identical.
3. **Event labels** (editable in Event Detail) live in `x_event_label(event_id, label)`; latest row per `event_id` wins.
4. **Recording gaps** (pause, process restart, storage stop) live in `x_gap` so completeness can exclude them.
5. `motion` has no gyro columns in the brief, so gyroscope samples are not persisted.

## Bundle layout (`<name>_<run8>.zip`)

| file | content |
|---|---|
| `run.json` | manifest: `schema_version`, `bundle_version`, `generator`, `generated_at_utc_ms`, `schema_fingerprint_sha256`, `run`, `device`, `settings`, `offsets`, `completeness`, `files[] {path, bytes, sha256, rows}` |
| `run.sqlite` | standalone DB with the full canonical DDL and only this run's rows |
| `<table>.csv` | one per table (all tables, header-only when empty) |
| `track.geojson` | RFC 7946 LineString + per-vertex `coord_times_utc_ms`, `gps_time_ms`, `rsrp`, `h_acc_m`, `speed_mps` arrays |
| `events.geojson` | Point per located event with type, severity, cause |

Import verifies every SHA-256 listed in `run.json`; unknown tables/columns are ignored (forward compatible).

