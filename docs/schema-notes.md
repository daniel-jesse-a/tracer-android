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
| `probe.error` | null · `timeout` · `dns` · `connect` · `tls` · `io` · `no_network` · `http_<code>` |
| `device.thermal_state` | Android `nominal light moderate severe critical emergency shutdown`; iOS `nominal fair serious critical` |
| `event.event_type` | `handover` `rat_change` `wifi_roam` `wifi_cell_switch` `drop` `reconnect` `captive_portal` `ip_asn_change` `gps_degraded` `latency_spike` `dns_spike` `throughput_drop` `signal_drop` `neighbour_overtake` |
| `event.severity` | `info` · `warning` · `critical` |
| `event.likely_cause` | `tunnel_underpass` `cell_edge` `late_failed_handover` `handover_churn` `congestion_backhaul` `interference` `wifi_weak_signal` `sticky_client` `channel_congestion` `wifi_uplink` `wifi_roam_transition` `wifi_link_delay` `upstream_delay` `captive_portal` `dns_issue` `carrier_gateway_change` |
| `event.evidence_json` | `{"candidates":[{"cause","confidence","signals":{…}}…],"snapshot":{serving, rat, wifi, path_status, gnss_used, …}}` |
| `waypoint` | tap-to-mark: `seq`, optional `label`, best position at tap time |
| `wifi_roam` | one row per roam, written ~10 s after it: from/to BSSID, band, RSSI, `link_down_ms` (disconnect → re-association, 0 = seamless), `latency_penalty_ms` (median Wi-Fi RTT 10 s after − 30 s before), target AP `11k`/`11v`/`11r` |
| `x_collector_log.health` | `ok` · `degraded` · `unavailable` · `stopped` · `disabled` (collector status — kept apart from user waypoints/annotations) |
| `runs.method_fingerprint` | 16 hex chars of SHA-256 over endpoints (+expected status), probe paths, throughput size/cap/interval, latency interval, internal probes, thresholds, settings_version, protocol. Same fingerprint ⇒ same method |
| `x_gap.reason` | `pause` · `process_restart` · `storage_low` · `permission_revoked` · `airplane_mode` · `watchdog` |

## Deviations from the original shared-schema brief (proposed for iOS adoption)

1. **`id` row key** on every sample table — needed for event labels and stable CSV diffs. Additive; iOS should add it.
2. **Android-only detail goes to `x_` tables** rather than widening shared tables: `x_android_network` (validated, captive, NOT_METERED, link bandwidth estimates, DNS), `x_android_wifi_link` (tx/rx link speed, MLO links), `x_android_gnss_raw`, `x_android_wifi_rtt`. Shared tables stay byte-identical.
3. **Event labels** (editable in Event Detail) live in `x_event_label(event_id, label)`; latest row per `event_id` wins.
4. **Recording gaps** (pause, process restart, storage stop) live in `x_gap` so completeness can exclude them.
5. `motion` has no gyro columns in the brief, so gyroscope samples are not persisted.

## Cause attribution rules (v2)

Diagnosis runs once the stream has reached event time T + 2 s. Periodic signals (serving-cell rows, GNSS epochs, probes) use the **median within T ± 2 s**. Change-reported state (Wi-Fi link, path, Android validation flags, RAT, scans ≤ 30 s old) uses the **last report at or before T + 2 s**; for roams and Wi-Fi→cell switches, the AP that was left. Precursor *events* keep their own windows: overtake ≤ 15 s before, roam ≤ 10 s before, handover rate over 60 s, and a tunnel baseline at T−45…T−15 s. Cellular-RF events and events on the cellular path never get Wi-Fi causes. `captive_portal` requires Android's captive flag, or Android reporting the network unvalidated **and** probes getting reachable-but-unexpected answers.

## Bundle layout (v2, `<site>_t<NN>_<yyyyMMdd-HHmm>_<run8>.zip`)

| file | content |
|---|---|
| `run.json` | manifest: `schema_version`, `bundle_version` (2), `generator`, `generated_at_utc_ms`, `time_zone`, `method_fingerprint`, `tables_omitted`, `csv_extra_columns`, `run` (incl. site/test/protocol), `device`, `settings`, `offsets`, `completeness`, `files[] {path, bytes, sha256, rows}` |
| `summary.json` | per path (`wifi`/`cellular`): reachable and expected-response rates overall and per endpoint (with error counts); latency median/p95 for total, DNS, connect, TLS and TTFB; throughput median/max/bytes; internal gateway and DHCP-DNS RTT, plus link vs upstream RTT split. Radio: Wi-Fi RSSI median/worst, RSRP and SINR medians, NR share. Events by type, severity and type×severity. Wi-Fi mobility: roams, penalty, link-down, time and episodes on cellular fallback. Position: fixes by provider, waypoints. Method fingerprint + canonical method JSON. Streamed, so it stays bounded on multi-hour runs |
| `run.sqlite` | canonical DDL with this run's rows, plus views `v_<table>` adding `t_run_s` and `t_local` |
| `<table>.csv` | **only tables with rows**; iOS-only tables are not written on Android. Every sample CSV ends with `t_run_s` (seconds into run, monotonic) and `t_local` (ISO-8601 local time with offset) |
| `track.geojson` / `events.geojson` | as v1 |

Import verifies every SHA-256 listed in `run.json`; missing CSVs mean empty tables; unknown tables and columns are ignored (v1 bundles import unchanged).

## Protocol runs

| id | duration | probe paths |
|---|---|---|
| `walk10-dual` | 10 min | default + mobile data in parallel |
| `walk10-wifi` | 10 min | Wi-Fi network only |
| `walk10-cell` | 10 min | mobile data only (even with Wi-Fi connected) |
| `drive30-dual` | 30 min | dual |
| `soak120-dual` | 2 h | dual, 500 MB mobile-data budget |

Protocols freeze every method setting (`Protocol.settings()`), so the same protocol always has the same method fingerprint. Repeat a protocol at the same site and time of day for directly comparable runs.
