# Tracer report v1 (the only export)

The share sheet, Download, Files, Server and the per-test email all deliver **one PDF**: the
"Tracer report v1" network test report. No HTML, ZIP or summary files leave the phone. The raw run
stays in the on-device database, so the report can be rebuilt at any time.

| Piece | Where |
|---|---|
| Template (unchanged, self-contained) | `ui/src/main/assets/report/tracer-report-template.html` |
| Contract / reference script | `docs/report-v1/REPORT_DATA_CONTRACT.md`, `docs/report-v1/build_report_data.py` |
| ReportData builder (Kotlin port) | `core/src/main/kotlin/app/tracer/core/report/v1/ReportData.kt` |
| ReportData builder (TypeScript port, Expo) | `report-v1/ts/src/buildReportData.ts` |
| Android export | `ui/report/ReportV1Exporter.kt` → `ReportPdfRenderer.kt` (off-screen WebView → `createPrintDocumentAdapter` → file, `android/print/PdfFileWriter.java`) |
| Optional basemap | `ui/report/Basemap.kt` (Settings › Report › Report map tiles; attribution always printed) |
| Rail overlay | `ui/src/main/assets/report/rail_ergt.json`: **internal ERGT data, git-ignored.** CI writes it from the `RAIL_ERGT_B64` secret when set. |

Android-only additions to the reference rules: the Wi-Fi radio layer is filled from WifiInfo (RSSI median/worst, link speed, band, channel; Good ≥ −67, Fair ≥ −75, Poor ≥ −82 dBm, else Failing); RAT keys come from TelephonyDisplayInfo/CellInfo (`NR_NSA` from the override type); stations far from the track are marked `in_view: false` so a Dubai test isn't zoomed out to Abu Dhabi; the destination is the New Test pick, else the station nearest the last fix.

Tests: `events/src/test/kotlin/app/tracer/events/ReportV1Test.kt` (synthetic full / no Wi-Fi / no location / under 2 min, and the 95596452 acceptance values with `REPORT_V1_FIXTURE=<unzipped bundle>`), `report-v1/ts/test/report.test.ts` (TS must equal the Kotlin output on the synthetic run). The Kotlin port was cross-checked against `build_report_data.py` on the synthetic run: every number matches; the only differences are the Android radio layer, quality notes and extra rule-based recommendations.

CI job "Report v1 sample PDFs" prints the samples with headless Chrome, checks page counts (exactly 5 for 95596452), and uploads them as the `report-v1-sample-pdfs` artifact.
