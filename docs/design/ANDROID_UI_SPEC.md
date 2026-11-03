# Android UI spec (from "Tracer — iOS & Android" design file)

Screens: `android-screens.png` (Pre-flight · Live · Cells · Runs), `components.png` (data language + components), `flow.png` (core flow). Text dump: `android-screens.txt`.
Tokens are implemented in `ui/.../theme/Theme.kt` (MaterialTheme colour scheme, `LocalTracerColors`, `Mono`, `GroupedShapes`, `Space`) and `core/.../quality/Quality.kt` (`QualityLevel`, `ConnType`).

## Global
- Material 3 Expressive, dark-first (light supported via the same tokens). Roboto UI, Roboto Mono for all numbers; units 0.65× in onSurfaceVariant; minus sign U+2212; timestamps HH:MM:SS.mmm in detail rows.
- **Bottom NavigationBar, 80 dp, pill indicator**, 4 destinations: **Pre-flight** (home icon) · **Runs** (list icon) · **Companion** (two-device icon) · **Settings** (gear). Live Run is full-screen (no bottom nav) and reachable from Pre-flight Start / notification.
- Grouped lists: rows in surfaceContainer, 16 dp outer corners, 4 dp inner corners, 2 dp gaps (`GroupedShapes.of(i, n)`). No coloured vertical left stripe anywhere.
- Metric tile (12 dp radius, surfaceContainer): LABEL (tiny caps, onSurfaceVariant) / value (Mono.value, coloured by quality) + unit / sub-line (tiny, e.g. "good", "p95 121", "22 s ago"). States: **normal**; **warn** = 1 dp outline in `warning` colour + sub-line in warning; **stale** = value and sub in onSurfaceVariant at 60 % with "cached 6.2 s"/"stale 4 m".
- Readiness item: check (ok colour) / warning triangle (warning) / ✕ (critical) icon + title + Mono sub-line. Blocked rows show a "Fix" action.
- Completeness badges (tonal chips, Mono tiny): `GPS ✓ NET ✓ PRB ✓ RF ✓ GNSS ✓`; pending `…` with dashed outline; missing `✕`; `n/a`.
- Quality ramp: Excellent #39C5BB · Good #3D8BFD · Fair #F5C542 · Poor #F28B30 · No service #E5487A (+ dotted) · None #5A5A60. Latency reuses the ramp; timeout = hatched band.
- Connection type: 5G SA #56B4E9 · 5G NSA #CC79A7 · LTE #E69F00 · 3G #F0E442 · Wi-Fi #1FBF8F · None #5A5A60 + dots.
- Event icons (24 dp, 2 dp stroke, colour = severity): Handover ⇄ (SwapHoriz), RAT change (bars+arrow / NetworkCell), Wi-Fi roam (Wifi+arrow), Wi-Fi↔cell (Wifi + SignalCellular), Drop (Block, critical), Reconnect (Refresh/Autorenew, ok colour), Captive portal (Web/Login), IP/ASN change (Public/Language), GPS degraded (GpsOff/LocationDisabled), Latency spike (ShowChart), DNS spike (Dns), Manual mark (Bookmark), Signal drop (SignalCellularConnectedNoInternet / TrendingDown), Neighbour overtake (CompareArrows), Throughput drop (Speed).
- Event row: icon · title "Handover PCI 87 → 214" · Mono sub "RSRP −109 → −94 · A3 · 38 ms gap" · trailing Mono timestamp.
- Map: dark map chrome (`map/road/water` tokens). Track coloured by quality with accuracy halo; current position = blue dot with halo; serving tower = outlined triangle with "PCI 214" label; TA ring = dashed circle labelled "TA 12 · 937 m". Event pins ≥ 16 dp with 2.5 dp ground-colour halo, 44 dp tap target: info white, warn yellow, drop pink, selected = ring, cluster = numbered circle. Quality legend overlay "Quality ■Exc ■Good ■Fair ■Poor ┅None".
- Timeline track: 30 dp rows — TYPE band (connection-type colours), RSRP line (+ dotted threshold), EVENTS dots; all tracks share one time axis; cursor vertical line in primary; pinch scales x only.

## Pre-flight (Android)
Large title "Pre-flight" + tonal badge "8/8 READY". **2-column grid** of readiness items: Permissions "7/7 · all the time" · GPS lock "±2.9 m · 31 sats" · Battery "71 % · unrestricted" · Storage "88.4 GB free" · Probe endpoints "3/3 · 16 ms" · Companion "iPhone 17 Pro / Δt −12 ms · GPS" · Cell info API "1 Hz · 2 SIM slots" · Wi-Fi throttling "off · raw GNSS ✓".
"Run setup": OutlinedTextField "Run name"; connected SegmentedButton row **Walk / Car / Train / Custom** (→ mode walk/drive/rail/custom); a 3-cell tonal row Sampling "1 Hz" · Probes "every 5 s" · Wi-Fi scan "every 2 s" (tap to edit); "Link to iPhone run" row with Switch and Mono sub "iPhone 17 Pro · Δt −12 ms"; **Extended FAB "▶ Start run"** (primaryContainer) bottom-end. Start disabled only for hard blockers.

## Live Run (Android)
- Top ~40 %: map, follow mode. Floating pill (surface, 28 dp) top-start: red dot + Mono "00:42:17" + "Train · 61.4 km".
- TabRow: **Live · Cells · Wi-Fi · GNSS** (primary indicator).
- Live tab: **4-column tile grid** — TYPE (5G NSA in conn colour / "e& 424-02"), RSRP (−94 dBm / good), RSRQ (−11 dB / NR −10), SINR (8.5 dB / NR 11.2), PCI·CID (214 / 0x1A3F0C2), BAND (n78+B3 / ARFCN 1850), TAC·TA (4108 / TA 12 · 937 m), LATENCY (49 ms / p95 121), LOSS (0.2 % / jit 4.8), THRU ↓ (61.3 Mb/s / 22 s ago), GPS·SPD (±3 m / 96 km/h), WI-FI (−71 dBm / scan · 14 APs). Then a timeline strip card (TYPE band over RSRP line), then grouped event rows (newest first).
- Bottom action row: square tonal **Mark** (bookmark icon), square tonal **Pause**, wide **End run** pill in errorContainer with ■ icon.
## Cells tab
Header row: red dot + Mono elapsed · "SIM 1 · e& 424-02 · 1 Hz" (SIM switcher when dual). **Serving card in primaryContainer**: "Serving · LTE anchor" + "PCI 214"; big Mono RSRP/RSRQ/SINR with units; row B3/1850 · CID 0x… · TAC · TA 12/937 m; divider; "NR leg · n78 · 636666 — PCI 402 · SS-RSRP −97 · SINR 11.2". "Neighbours · 7 — sorted by RSRP": grouped rows "PCI 87  B3 · 1850   RSRQ −10   −92" with an RSRP bar beneath (colour by quality); overtaking neighbour row in **tertiaryContainer** with "▲ Overtaking serving by +2 dB for 1.4 s · HO likely".
## Runs (library)
Top: SearchBar "Search runs, PCI, SSID"; FilterChips **All · 52 / Train / Linked / This week / Has drops**. Run card (surfaceContainer, 16 dp): title "Rail · Seg 07 · Coach 4" + trailing date "Today 13:55"; Mono meta "TRAIN 1:07:42 98.6 km 41 ev ⇄ iPhone"; **quality bar** (thin segmented bar of the run's RSRP quality over time); completeness badges row. Long-press → multi-select: checkbox per card, selected card in primaryContainer, contextual top bar "✕ 2 selected · 44.1 MB" with Share (export) and Delete.
## Companion tab
Pair by QR (show own QR / scan iPhone), live clock offset vs GPS (target < 50 ms), linked runs list, "Start on iPhone too?" prompt text.
## End Run (Android, ≈5 s)
One screen: summary (duration, distance, events, drops), completeness badges, name/notes editable, **Save**. Companion run auto-linked if paired.
## Interrupts
No GPS, no service, airplane mode, backgrounded, low battery (auto battery-saver at 15 %), companion disconnected → each becomes an event/banner on the timeline, never a modal.
