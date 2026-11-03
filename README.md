# Tracer — Android connectivity & RF field logger

Records a full journey on cellular and Wi-Fi with live radio data, detects connectivity events with likely causes, links to a companion iPhone run, and exports one analysis bundle per run. Reference device: **Samsung Galaxy S25** (dual-SIM, One UI 7/8).

- Kotlin · Jetpack Compose · Material 3 · Coroutines/Flow · Hilt · Room (WAL) · OkHttp EventListener · Google Maps Compose **or** MapLibre
- minSdk 31 · target/compile 36 · per-subscription `TelephonyManager`
- Docs: [architecture](docs/ARCHITECTURE.md) · [schema (shared with iOS)](docs/SCHEMA.md) · [Android UI spec + design references](docs/design/ANDROID_UI_SPEC.md)

## Build

```bash
# Android Studio Narwhal+ / JDK 17+
./gradlew :app:assembleDebug            # APK → app/build/outputs/apk/debug/
./gradlew test                          # all JVM + Robolectric unit tests
./gradlew :app:connectedDebugAndroidTest  # service-lifecycle instrumented test (device attached)
./gradlew lint
tools/verify-jvm.sh                     # :core + :events compile & tests with NO Android SDK / network
```

Optional `local.properties`:

```properties
MAPS_API_KEY=AIza...        # enables Google Maps; without it the app uses MapLibre + OpenFreeMap tiles
```

`gradle.properties › tracer.mapBackend=google|maplibre` forces a backend (Google still requires a key).
Dependency versions live in `gradle/libs.versions.toml`; run Android Studio's *AGP Upgrade Assistant* to move to newer stable releases.

## Modules

| module | type | contents |
|---|---|---|
| `:core` | pure Kotlin/JVM | sample model, **canonical schema** + codec, settings/thresholds JSON, quality scales, CellInfo normaliser + band tables, export bundle writer/reader (ZIP, CSV, GeoJSON, SHA-256 manifest), pairing payload + GPS-time alignment, completeness, worst segments, live aggregator, synthetic scenarios, cross-module contracts |
| `:events` | pure Kotlin/JVM | streaming `EventEngine` (14 rules) + `CauseEngine` (13 heuristics), identical live and in Replay |
| `:storage` | Android | Room DB (runs) + canonical DDL tables, single batching writer, repository |
| `:collectors` | Android | location, GNSS, cellular (dual-SIM), Wi-Fi connection/scan/RTT, network path, motion, device |
| `:probes` | Android | latency (per-phase timings), throughput, public IP/ASN, captive check, SNTP, OpenCelliD lookup |
| `:export` | Android | run → ZIP bundle, FileProvider share, HTTPS upload, import |
| `:pairing` | Android | QR generate (ZXing) / scan (CameraX + ML Kit), linked-run alignment, v2 live-link stub |
| `:ui` | Android | Compose screens |
| `:app` | Android app | `RunService` (foreground, `location`), DI graph, notification, simulate mode |

`:core` and `:events` have no Android dependency, so the rules, schema and bundle format can be ported to Swift line-for-line and tested on any JVM.

## Permissions and why

| permission | used for |
|---|---|
| `ACCESS_FINE_LOCATION` / `COARSE` | GPS track, GNSS status, **and** required by Android for CellInfo and Wi-Fi scan results |
| `ACCESS_BACKGROUND_LOCATION` ("Allow all the time") | keeps cell/Wi-Fi/GNSS data flowing with the screen off for multi-hour runs |
| `READ_PHONE_STATE` | per-SIM `TelephonyCallback` (display info → 5G NSA / NR advanced, service state, CA bandwidths) |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | connected AP info, `startScan()` |
| `NEARBY_WIFI_DEVICES` (no `neverForLocation`) | Android 13+ scan access; location-derived data is the point, so the flag is deliberately absent |
| `ACCESS_NETWORK_STATE` / `INTERNET` | network path callbacks, probes |
| `ACTIVITY_RECOGNITION` | still / walking / in-vehicle transitions |
| `POST_NOTIFICATIONS` | persistent run notification + alerts |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` / `WAKE_LOCK` | ≥ 3 h screen-off recording |
| `CAMERA` | scanning the iPhone pairing QR (requested on demand) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | exemption prompt shown in onboarding with an explanation |

Onboarding walks these in order, including the separate "Allow all the time" step, which Android routes through system Settings.

## Samsung Galaxy S25 notes

1. **Battery**: grant the exemption in onboarding, **and** in *Settings › Battery › Background usage limits* add Tracer to **Never sleeping apps**. Otherwise One UI's "Put unused apps to sleep" can kill the foreground service on long runs. The app restarts and records an `x_gap` row (`process_restart`), but data in the gap is lost.
2. **Wi-Fi scan throttling**: Android allows 4 foreground scans per 2 min (background: 1 per 30 min). For the default 30 s cadence enable Developer options (*About phone › Software information ›* tap *Build number* ×7) and turn **Wi-Fi scan throttling** off. Pre-flight and Live Run show a banner when `startScan()` starts failing.
3. **Service mode `*#0011#`**: dial it for Samsung's ServiceMode screen to cross-check what Tracer logs: serving band, EARFCN/NR-ARFCN, PCI, RSRP/RSRQ/SINR, NR state. Values should agree within ±1–2 dB at the same moment. Some carriers disable the code.
4. **Cached CellInfo**: One UI often returns stale cell lists from `getAllCellInfo()`. Tracer polls `requestCellInfoUpdate()` every second and stores `cellinfo_age_ms`; rows older than `cell_stale_ms` (10 s) are ignored by the event rules.
5. **NR state**: `nr_state` comes from `ServiceState.toString()`, which Samsung populates. Values can be null on other OEMs.
6. **Dual SIM**: every cell/radio row carries `subscription_id`/`service_id`. Rules evaluate per subscription.

## Install on a phone

**Direct download (always the newest build):** open
`https://github.com/daniel-jesse-a/tracer-android/releases/download/latest-debug/tracer-debug.apk`
on the phone, then open the file and allow "Install unknown apps" for the browser when prompted. While the repo is private, sign in to GitHub in that browser first. Over USB: `adb install -r tracer-debug.apk`.

Each CI build gets a higher `versionCode` and the same signing key, so new builds install over old ones and keep your runs.

| device | Android | notes |
|---|---|---|
| Galaxy S25 | 15 / 16 (One UI 7/8) | see Samsung notes below; `*#0011#` ServiceMode for cross-checks |
| Pixel 6 Pro | 13–16 (Tensor G1, Exynos 5123 modem) | dual-SIM (pSIM + eSIM). Cross-check with `*#*#4636#*#*` › Phone information. `nr_state` may be null (Pixel's `ServiceState.toString` omits it); the RAT still comes from display info |
| Pixel 10 Pro | 16 (Tensor G5) | APK is verified **16 KB page-size compatible** in CI (Android 16 can run with 16 KB pages); same cross-check code as Pixel 6 Pro |

Pixel battery: *Settings › Apps › Tracer › App battery usage › Unrestricted*. Wi-Fi scan throttling: *Settings › System › Developer options › Wi-Fi scan throttling* off (enable Developer options by tapping *About phone › Build number* ×7). The APK contains arm64-v8a (all three phones) and x86_64 (emulator) native libraries.

## Event rules and causes

Thresholds are JSON (`core/src/main/resources/event_thresholds.default.json`, keys identical on iOS) and editable in Settings. Replay can re-run all rules with edited thresholds.

| event | rule |
|---|---|
| `handover` | serving cell id (or PCI@ARFCN) change per subscription + RAT |
| `rat_change` | display RAT change; downgrade = warning |
| `wifi_roam` | BSSID change within the same SSID |
| `wifi_cell_switch` | default network primary transport wifi ↔ cellular |
| `drop` / `reconnect` | default network loses validation, **or** 3 consecutive probe failures; reconnect carries `outage_ms` |
| `captive_portal` | NET_CAPABILITY_CAPTIVE_PORTAL or non-204 captive check |
| `ip_asn_change` | public IP or ASN changes (ASN change = warning) |
| `gps_degraded` | h_acc > 25 m, jump > 3× median step (and > 30 m), or < 5 satellites used in fix; 10 s recovery hysteresis |
| `latency_spike` / `dns_spike` | > 3× rolling median and > 150 ms (DNS: > 100 ms) |
| `throughput_drop` | > 60 % below the run median (≥ 3 samples) |
| `signal_drop` | serving RSRP falls > 10 dB within 5 s |
| `neighbour_overtake` | neighbour RSRP > serving + 6 dB for 3 s with no handover |

Each diagnosed event carries `likely_cause`, `cause_confidence` and `evidence_json` listing **every** candidate with its signals, so you can see why the winner won. Causes: `tunnel_underpass`, `cell_edge`, `late_failed_handover`, `handover_churn`, `congestion_backhaul`, `interference`, `wifi_weak_signal`, `sticky_client`, `channel_congestion`, `wifi_uplink`, `captive_portal`, `dns_issue`, `carrier_gateway_change`. The rules and their tests (`events/src/test`) run against `core/sim/Synthetic.kt` scenarios, one per cause.

## Companion iPhone run

1. Pre-flight › *Companion*: show this run's QR on Android **or** scan the iPhone's QR. Payload: `tracer://pair?d=<base64url JSON {run_id, platform, model, os, app, started_at, gps_offset_ms}>`. `linked_run_id` is stored on both runs.
2. v1 is offline: record on both devices, export the iPhone bundle, then *Library › Import*. Runs are aligned on **GNSS time**: each device's `median(gps_time_ms − t_utc_ms)` is its clock offset, and the iPhone timeline shifts by `offset_ios − offset_android`. Replay overlays the iPhone track and lanes.
3. v2 (`LiveLink`, stub): live status over the local network via NSD `_tracer._tcp`.

Live Run shows the device's wall-clock offset vs GPS. `runs` stores it at start and end (drift), plus an optional SNTP offset.

## Export bundle

`Library › ⋮ › Export` → Android Sharesheet (or HTTPS upload if configured). ZIP contents: `run.sqlite`, one CSV per table, `track.geojson`, `events.geojson`, and `run.json` (schema version, device, settings, offsets, completeness, SHA-256 of every file). Import verifies every checksum. Details: [docs/SCHEMA.md](docs/SCHEMA.md#bundle-layout-name_run8zip).

## Reliability

- `RunService` is a `location`-type foreground service holding a partial wake lock, `START_STICKY`, with a 5 s heartbeat. After process death it resumes the unfinished run and writes an `x_gap` row.
- Collector supervision: each collector runs in its own supervised job with backoff. A watchdog restarts any collector silent longer than its `watchdogMs`.
- Storage guard stops the run below 500 MB free (configurable). Battery saver multiplies cell-poll, scan, probe and motion intervals.
- SIM swap re-registers per-subscription callbacks. Permission revocation mid-run raises an alert and ends the run cleanly. Airplane mode is recorded as `network_path.unsatisfied_reason = airplane_mode`.

## Debug: Simulate run

Debug builds expose *Settings › Simulate run* and a Pre-flight toggle. The service then replays a synthetic scenario (`tunnel`, `late_handover`, `sticky_wifi`, `commute`, …) in real time through the real writer, event engine, notification and UI, with no radios needed.

## Status

| | state |
|---|---|
| Full Android build (`:app:assembleDebug`) | **green on GitHub Actions** — 0 compiler errors, 0 warnings |
| Unit tests (all 9 modules, incl. Robolectric storage/export) + `lintDebug` (7 Android modules) | **passing** |
| Offline check without the Android SDK | `tools/verify-jvm.sh` — compiles `:core` and `:events` as separate modules + Android-free helpers, 74 tests |

Every push builds a debug APK: *Actions › latest "Android build" run › Artifacts › `tracer-debug-apk`*. It is signed with the committed debug key (`app/debug.keystore`, debug-only), so new builds install over old ones.

Still to do on a device:
- Commit the Room schema JSON that KSP generates on the first local build (`storage/schemas/…/1.json`), which the migration test needs.
- Run `./gradlew :app:connectedDebugAndroidTest` with the phone attached (service-lifecycle test).
- Optional: add the Roboto Mono TTFs (see `ui/.../theme/Theme.kt`).

Known limitations: the MapLibre default style (OpenFreeMap *liberty*) is light, so the dark map chrome from the design applies only on Google Maps; Live Run notes on End Run are not persisted (name only); library search matches name, mode and run ID (not PCI/SSID).
