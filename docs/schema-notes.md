## Value vocabularies

| column | values |
|---|---|
| `runs.mode` | `walk` · `drive` (Car) · `rail` (Train) · `custom` · `static` · `simulated` |
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
| `event.likely_cause` | `tunnel_underpass` `cell_edge` `late_failed_handover` `handover_churn` `congestion_backhaul` `interference` `wifi_weak_signal` `sticky_client` `channel_congestion` `wifi_uplink` `wifi_roam_transition` `captive_portal` `dns_issue` `carrier_gateway_change` |
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
