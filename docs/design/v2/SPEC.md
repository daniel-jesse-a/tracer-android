# Tracer design v2 — Android implementation spec

Source: `Tracer_iOS_Android_v2.html` (11 phone frames, 390 × 844 pt). Renders: `NN_<screen>.png` (2×), text dumps `NN_<screen>.txt`.

## Tokens (dark = design swatches; light = iOS light equivalents) — `ui/theme/Theme.kt` `TColors` / `LocalT`

| token | dark | use |
|---|---|---|
| bg | #000000 | screen |
| card / card2 / card3 | #1C1C1E / #2C2C2E / #3A3A3C | grouped cards / bar track, selected segment / hairline-strong |
| sep | #38383A | row hairlines |
| text / text2 / text3 / text4 | #F5F5F7 / #C7C7CC / #98989D / #48484A | primary / secondary / captions / disabled, chevrons |
| link | #0A84FF | back links, actions, selection outline, blue button |
| wifi / mobile | #409CFF / #FF9F0A | network timeline, charts |
| switchOn | #30D158 | toggles |
| verdict fg / bg | Bad #FF6961/#3A1517 · Below avg #FF9F43/#33200C · Average #FFD60A/#2C2606 · Above avg #A6E05A/#1C2A10 · Very good #30D158/#0F2A18 | tiles, meters, verdict words |
| primary button | #F5F5F7 on #000 | Continue / Finish / Full diagnostics |

Type (Inter in the mock → platform sans): large title 34/800, tile value 30/700, row 17/400–500, label 15–16/600, caption 13/600 caps, ticks 12 tabular.
Radii: cards 22, tiles 20, pills 18, icon wells 12, meters 3. Screen padding 20, gaps 12.

## Verdict scale — `core/report/Report.kt` `Verdict`

| metric | Very good | Above avg | Average | Below avg | Bad |
|---|---|---|---|---|---|
| Wi-Fi RSSI dBm | > −55 | > −65 | > −72 | > −80 | ≤ −80 |
| Mobile RSRP dBm | > −80 | > −90 | > −100 | > −110 | ≤ −110 |
| Response (RTT) ms | ≤ 50 | ≤ 150 | ≤ 300 | ≤ 600 | > 600 |
| Download Mbps | ≥ 100 | ≥ 25 | ≥ 10 | ≥ 2 | < 2 |
| Position ±m | ≤ 5 | ≤ 8 | ≤ 20 | ≤ 50 | > 50 |
| Online % | ≥ 99.5 | ≥ 98 | ≥ 95 | ≥ 90 | < 90 |
| Checks passed % | ≥ 98 | ≥ 90 | ≥ 75 | ≥ 40 | < 40 |
| Switches | 0 | 1 | ≤ 3 | ≤ 6 | > 6 |

Meter = verdict.segments lit of 5. Test quality = checks verdict, one step down when < 40 % of probe slots ran on schedule. Tests-list verdict = online verdict unless test quality is Below avg / Bad.

## Screens → code

| # | screen | composable |
|---|---|---|
| 0 | Welcome | `ui/welcome/WelcomeScreen` |
| 1 | New test | `ui/newtest/NewTestScreen` |
| 2 | Live | `ui/live/LiveRunRoute` |
| 3 | Signals | `ui/signals/SignalsScreen` |
| 4 | Test summary | `ui/summary/TestSummaryScreen` |
| 5 | Diagnostics (Simple / Detailed) | `ui/diagnostics/DiagnosticsScreen` |
| 6 | Moment detail | `ui/moment/MomentScreen` |
| 7 | Tests | `ui/tests/TestsScreen` |
| 8 | Companions | `ui/companion/CompanionsScreen` |
| 9 | Share | `ui/share/ShareScreen` |
| 10 | Settings | `ui/settings/SettingsScreen` |

Engineer view (Settings → Advanced) keeps the v1 detailed tools: run library with import, Replay with layers, worst segments, transfer/pairing, RF/Wi-Fi/GNSS panels.
