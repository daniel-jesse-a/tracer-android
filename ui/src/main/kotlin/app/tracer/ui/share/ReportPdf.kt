package app.tracer.ui.share

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.toArgb
import app.tracer.core.model.RunRecord
import app.tracer.core.report.Carrier
import app.tracer.core.report.TestReport
import app.tracer.core.report.Verdict
import app.tracer.core.report.fmtClock
import app.tracer.core.report.fmtDuration
import app.tracer.ui.summary.boundaryTicks
import app.tracer.ui.summary.positionsFraction
import app.tracer.ui.theme.LightT
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * One- to two-page A4 PDF of a [TestReport] (Share › Report). Plain [PdfDocument] + Canvas, white
 * background and the light verdict palette so it prints well.
 */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private const val CW = W - 2 * M

    /** "Office_test3_report.pdf", else "<runId>_report.pdf". */
    fun fileName(run: RunRecord?, runId: String): String = "${baseName(run, runId)}_report.pdf"

    fun baseName(run: RunRecord?, runId: String): String {
        val site = run?.site?.trim()?.takeIf { it.isNotEmpty() }
        val n = run?.testNumber
        return if (site != null && n != null) "${site.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').ifEmpty { "site" }}_test$n"
        else runId.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }

    fun write(r: TestReport, run: RunRecord?, out: File) {
        val doc = PdfDocument()
        try {
            Writer(doc, r, run).draw()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    // ------------------------------------------------------------------------------ colours

    private val C = LightT
    private val BLACK = C.text.toArgb()
    private val TEXT2 = C.text2.toArgb()
    private val TEXT3 = C.text3.toArgb()
    private val SEP = C.sep.toArgb()
    private val TRACK = C.card2.toArgb()

    private fun fg(v: Verdict): Int = when (v) {
        Verdict.BAD -> C.bad; Verdict.BELOW_AVG -> C.belowAvg; Verdict.AVERAGE -> C.average
        Verdict.ABOVE_AVG -> C.aboveAvg; Verdict.VERY_GOOD -> C.veryGood; Verdict.NONE -> C.text3
    }.toArgb()

    private fun bg(v: Verdict): Int = when (v) {
        Verdict.BAD -> C.badBg; Verdict.BELOW_AVG -> C.belowAvgBg; Verdict.AVERAGE -> C.averageBg
        Verdict.ABOVE_AVG -> C.aboveAvgBg; Verdict.VERY_GOOD -> C.veryGoodBg; Verdict.NONE -> C.card2
    }.toArgb()

    private fun carrier(c: Carrier): Int = when (c) { Carrier.WIFI -> C.wifi; Carrier.MOBILE -> C.mobile; Carrier.NONE -> C.offline }.toArgb()

    private fun text(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 0 && p.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in s.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) <= maxW || cur.isEmpty()) cur = next else { lines += cur; cur = word }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    // ------------------------------------------------------------------------------ writer

    private class Writer(val doc: PdfDocument, val r: TestReport, val run: RunRecord?) {
        var pageNo = 0
        var page: PdfDocument.Page? = null
        lateinit var cv: Canvas
        var y = M

        fun newPage() {
            finishPage()
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page = p; cv = p.canvas; y = M
            cv.drawColor(android.graphics.Color.WHITE)
        }

        fun finishPage() {
            val p = page ?: return
            val foot = text(8f, TEXT3)
            cv.drawText("Tracer ${r.appVersion} · ${r.runId}", M, H - 22f, foot)
            cv.drawText("Page $pageNo", W - M, H - 22f, text(8f, TEXT3, align = Paint.Align.RIGHT))
            doc.finishPage(p)
            page = null
        }

        fun need(h: Float) { if (y + h > H - M - 16) newPage() }

        fun draw() {
            newPage()
            header()
            tiles()
            timeline()
            wifiChart()
            mobileChart()
            responseChart()
            moments()
            quality()
            details()
            finishPage()
        }

        private fun header() {
            cv.drawText("TRACER TEST REPORT", M, y + 9, text(9f, TEXT3, bold = true)); y += 16
            cv.drawText(ellipsize(r.title, text(22f, BLACK, true), CW), M, y + 22, text(22f, BLACK, true)); y += 30
            val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            run?.timeZone?.let { fmt.timeZone = TimeZone.getTimeZone(it) }
            val meta = listOf(fmt.format(Date(r.startedAt)), "Duration ${fmtDuration(r.durationS)}", r.deviceModel)
                .filter { it.isNotBlank() }.joinToString(" · ")
            cv.drawText(ellipsize(meta, text(10f, TEXT3), CW), M, y + 10, text(10f, TEXT3)); y += 26

            val pct = r.onlinePct
            val big = text(26f, BLACK, true)
            cv.drawText(ellipsize(r.headline, big, CW - 120), M, y + 26, big)
            if (pct != null) {
                cv.drawText("${pct.roundToInt()}%", W - M, y + 24, text(24f, fg(r.onlineVerdict), true, Paint.Align.RIGHT))
                cv.drawText("online", W - M, y + 38, text(9f, TEXT3, align = Paint.Align.RIGHT))
            }
            y += 36
            val sub = text(13f, TEXT2)
            for (line in wrap(r.subline, sub, CW - 120)) { cv.drawText(line, M, y + 13, sub); y += 17 }
            y += 14
        }

        private fun tiles() {
            data class T(val label: String, val value: String, val unit: String?, val v: Verdict, val word: String? = null)
            val ws = r.wifiSpeedMedian ?: r.wifiSpeedMax
            val ms = r.mobileSpeedMedian ?: r.mobileSpeedMax
            val rt = r.responseMs
            val list = listOf(
                T("Wi-Fi speed", ws?.takeIf { r.wifiTested }?.let { "${it.roundToInt()}" } ?: "–", ws?.takeIf { r.wifiTested }?.let { "Mbps" },
                    if (r.wifiTested) r.wifiSpeedVerdict else Verdict.NONE, if (r.wifiTested) null else "Not tested"),
                T("Mobile speed", ms?.takeIf { r.mobileTested }?.let { "${it.roundToInt()}" } ?: "–", ms?.takeIf { r.mobileTested }?.let { "Mbps" },
                    if (r.mobileTested) r.mobileSpeedVerdict else Verdict.NONE, if (r.mobileTested) null else "Not tested"),
                T("Response", rt?.let { "≤${it.roundToInt()}" } ?: "–", rt?.let { "ms" }, r.responseVerdict),
                T("Stability", "${r.switches}", if (r.switches == 1) "switch" else "switches", r.stabilityVerdict),
            )
            val gap = 8f
            val w = (CW - gap * 3) / 4
            val h = 78f
            need(h + 16)
            list.forEachIndexed { i, t ->
                val x = M + i * (w + gap)
                cv.drawRoundRect(RectF(x, y, x + w, y + h), 10f, 10f, fill(bg(t.v)))
                cv.drawText(t.label, x + 10, y + 17, text(9.5f, fg(t.v), true))
                val vp = text(19f, BLACK, true)
                cv.drawText(t.value, x + 10, y + 41, vp)
                if (t.unit != null) cv.drawText(t.unit, x + 10 + vp.measureText(t.value) + 3, y + 41, text(9f, TEXT2))
                // 5-segment meter
                val segW = (w - 20 - 4 * 3) / 5
                for (s in 0 until 5) {
                    val sx = x + 10 + s * (segW + 3)
                    cv.drawRoundRect(RectF(sx, y + 50, sx + segW, y + 54), 2f, 2f, fill(if (s < t.v.segments) fg(t.v) else 0x1F000000))
                }
                cv.drawText(t.word ?: t.v.label, x + 10, y + 69, text(10f, fg(t.v), true))
            }
            y += h + 20
        }

        private fun sectionTitle(t: String, h: Float) {
            need(h + 22)
            cv.drawText(t, M, y + 13, text(13f, BLACK, true)); y += 22
        }

        private fun timeline() {
            if (r.segments.isEmpty() || r.durationS <= 0) return
            sectionTitle("Timeline", 46f)
            // legend
            val lg = text(9f, TEXT3)
            var lx = W - M - 90
            listOf(Carrier.WIFI to "Wi-Fi", Carrier.MOBILE to "Mobile", Carrier.NONE to "Offline").forEach { (c, l) ->
                cv.drawRoundRect(RectF(lx, y - 19, lx + 7, y - 12), 1.5f, 1.5f, fill(carrier(c)))
                cv.drawText(l, lx + 10, y - 12, lg); lx += 10 + lg.measureText(l) + 10
            }
            val h = 16f
            cv.save()
            val clip = Path().apply { addRoundRect(RectF(M, y, M + CW, y + h), 6f, 6f, Path.Direction.CW) }
            cv.clipPath(clip)
            cv.drawRect(M, y, M + CW, y + h, fill(TRACK))
            r.segments.forEach { s ->
                val x0 = M + (s.fromS / r.durationS * CW).toFloat()
                val x1 = M + (s.toS / r.durationS * CW).toFloat()
                if (x1 > x0) cv.drawRect(x0, y, x1 - 1f, y + h, fill(carrier(s.carrier)))
            }
            cv.restore()
            y += h + 12
            val ticks = boundaryTicks(r)
            ticks.forEachIndexed { i, t ->
                val align = when (i) { 0 -> Paint.Align.LEFT; ticks.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                cv.drawText(fmtClock(t), M + (t / r.durationS * CW).toFloat(), y, text(8.5f, TEXT3, align = align))
            }
            y += 18
        }

        private data class Band(val from: Double, val to: Double, val fill: Int, val label: String?, val labelColor: Int)
        private data class Series(val pts: List<app.tracer.core.report.Pt>, val color: Int, val dashed: Boolean = false)

        /**
         * One chart frame across the page: bands, optional dashed reference line, series, switch markers.
         * An empty frame carries [emptyReason] so a missing graph always says why.
         */
        private fun chart(
            title: String, series: List<Series>, top: Double, bottom: Double, bands: List<Band>,
            ref: Pair<Double, String>? = null, emptyReason: String?, caption: String?, note: String? = null,
            rings: List<Pair<Double, Double>> = emptyList(), legend: List<Triple<String, Int, Boolean>> = emptyList(),
        ) {
            if (r.durationS <= 0) return
            val h = 92f
            sectionTitle(title, h + 30)
            val x = { t: Double -> M + (t / r.durationS * CW).toFloat() }
            val yy = { v: Double -> y + ((top - v.coerceIn(bottom, top)) / (top - bottom) * h).toFloat() }
            val empty = emptyReason != null
            for (b in bands) {
                val y0 = yy(maxOf(b.from, b.to)); val y1 = yy(minOf(b.from, b.to))
                if (y1 - y0 < 1f) continue
                cv.drawRect(M, y0, M + CW, y1, fill(b.fill).apply { if (empty) alpha = 110 })
                if (!empty && b.label != null && y1 - y0 >= 11f) cv.drawText(b.label, M + 5, y0 + 10, text(8f, b.labelColor, true))
            }
            if (ref != null) {
                val ry = yy(ref.first)
                cv.drawLine(M, ry, M + CW, ry, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = C.veryGood.toArgb(); strokeWidth = 1f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
                })
                if (!empty) cv.drawText(ref.second, M + 5, ry - 3, text(8f, C.veryGood.toArgb(), true))
            }
            if (empty) {
                cv.drawText(emptyReason ?: "", M + CW / 2, y + h / 2 + 4, text(10f, TEXT3, align = Paint.Align.CENTER))
            } else {
                val dotted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = TEXT3; strokeWidth = 0.8f; pathEffect = android.graphics.DashPathEffect(floatArrayOf(1.5f, 2f), 0f)
                }
                for (t in app.tracer.core.report.ChartText.switchTimes(r)) cv.drawLine(x(t), y, x(t), y + h, dotted)
                val maxGap = maxOf(15.0, r.durationS / 30)
                for (s in series) {
                    if (s.pts.isEmpty()) continue
                    val path = Path()
                    s.pts.forEachIndexed { i, p ->
                        if (i == 0 || p.tS - s.pts[i - 1].tS > maxGap) path.moveTo(x(p.tS), yy(p.v)) else path.lineTo(x(p.tS), yy(p.v))
                    }
                    cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = s.color; style = Paint.Style.STROKE; strokeWidth = 1.6f
                        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                        if (s.dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
                    })
                }
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLACK; style = Paint.Style.STROKE; strokeWidth = 1.2f }
                for ((t, v) in rings) {
                    cv.drawCircle(x(t), yy(v), 3.5f, fill(android.graphics.Color.WHITE))
                    cv.drawCircle(x(t), yy(v), 3.5f, ring)
                }
            }
            y += h + 11
            cv.drawText("0:00", M, y, text(8.5f, TEXT3))
            val mid = listOfNotNull(caption, note).joinToString(" · ")
            if (mid.isNotEmpty()) cv.drawText(mid, M + CW / 2, y, text(8.5f, TEXT3, align = Paint.Align.CENTER))
            cv.drawText(fmtClock(r.durationS), M + CW, y, text(8.5f, TEXT3, align = Paint.Align.RIGHT))
            if (legend.isNotEmpty() && !empty) {
                y += 12
                var lx = M
                val lp = text(8.5f, TEXT3)
                for ((label, color, dashed) in legend) {
                    cv.drawLine(lx, y - 3, lx + 12, y - 3, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color; strokeWidth = 1.6f
                        if (dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 2f), 0f)
                    })
                    cv.drawText(label, lx + 16, y, lp); lx += 16 + lp.measureText(label) + 14
                }
            }
            y += 18
        }

        private fun wifiChart() {
            chart(
                "Wi-Fi strength",
                listOf(Series(r.wifiRssi, C.wifi.toArgb())), -40.0, -95.0,
                listOf(
                    Band(-65.0, -40.0, C.veryGoodBg.toArgb(), "Strong", C.veryGood.toArgb()),
                    Band(-80.0, -65.0, C.averageBg.toArgb(), "OK", C.average.toArgb()),
                    Band(-95.0, -80.0, C.badBg.toArgb(), "Weak", C.bad.toArgb()),
                ),
                emptyReason = app.tracer.core.report.ChartText.wifi(r),
                caption = "○ = new access point · dotted = network switch",
                rings = r.newApAtS.mapNotNull { t -> r.wifiRssi.minByOrNull { kotlin.math.abs(it.tS - t) }?.let { t to it.v } },
            )
        }

        private fun mobileChart() {
            val hasNr = r.nrRsrpSeries.isNotEmpty()
            chart(
                "Mobile signal" + (r.mobileTech?.let { " · $it" } ?: ""),
                listOf(Series(r.rsrpSeries, C.mobile.toArgb()), Series(r.nrRsrpSeries, C.mobile.toArgb(), dashed = true)),
                -70.0, -130.0,
                listOf(
                    Band(-90.0, -70.0, C.veryGoodBg.toArgb(), "Strong", C.veryGood.toArgb()),
                    Band(-110.0, -90.0, C.averageBg.toArgb(), "OK", C.average.toArgb()),
                    Band(-130.0, -110.0, C.badBg.toArgb(), "Weak", C.bad.toArgb()),
                ),
                emptyReason = app.tracer.core.report.ChartText.mobile(r),
                caption = "RSRP dBm, higher is better",
                note = app.tracer.core.report.ChartText.nr(r),
                legend = if (hasNr || r.nrSeen) listOf(Triple("4G anchor", C.mobile.toArgb(), false), Triple("5G (NR leg)", C.mobile.toArgb(), true)) else emptyList(),
            )
        }

        private fun responseChart() {
            val all = r.responseWifi + r.responseMobile
            val top = maxOf(400.0, (all.maxOfOrNull { it.v } ?: 0.0) * 1.15)
            chart(
                "Response time",
                listOf(Series(r.responseWifi, C.wifi.toArgb()), Series(r.responseMobile, C.mobile.toArgb())),
                top, 0.0,
                listOf(
                    Band(0.0, 150.0, C.veryGoodBg.toArgb(), null, 0),
                    Band(300.0, top, C.badBg.toArgb(), "Slow", C.bad.toArgb()),
                ),
                ref = 150.0 to "Good below 150 ms",
                emptyReason = app.tracer.core.report.ChartText.response(r),
                caption = "ms, lower is better",
                legend = listOf(Triple("Wi-Fi", C.wifi.toArgb(), false), Triple("Mobile", C.mobile.toArgb(), false)),
            )
        }

        private fun moments() {
            sectionTitle("Moments", 40f)
            if (r.moments.isEmpty()) {
                cv.drawText("Nothing unusual happened.", M, y + 11, text(10.5f, TEXT2)); y += 24
                return
            }
            val cols = floatArrayOf(M, M + 46, M + 230, M + 290)
            fun headerRow() {
                val hp = text(8.5f, TEXT3, true)
                listOf("TIME", "MOMENT", "LASTED", "LIKELY CAUSE").forEachIndexed { i, s -> cv.drawText(s, cols[i], y + 9, hp) }
                y += 14
                cv.drawLine(M, y, M + CW, y, Paint().apply { color = SEP; strokeWidth = 0.8f })
                y += 4
            }
            headerRow()
            val rowP = text(10f, BLACK)
            val greyP = text(10f, TEXT2)
            r.moments.forEach { m ->
                if (y + 17 > H - M - 16) { newPage(); headerRow() }
                cv.drawText(fmtClock(m.tS), cols[0], y + 11, greyP)
                cv.drawText(ellipsize(m.title, rowP, cols[2] - cols[1] - 8), cols[1], y + 11, rowP)
                cv.drawText(m.lastedS?.takeIf { it >= 1 }?.let { fmtDuration(it) } ?: "–", cols[2], y + 11, greyP)
                val cause = m.causeTitle ?: "–"
                cv.drawText(ellipsize(cause, greyP, M + CW - cols[3]), cols[3], y + 11, greyP)
                y += 16
                cv.drawLine(M, y, M + CW, y, Paint().apply { color = SEP; strokeWidth = 0.5f })
                y += 2
            }
            y += 12
        }

        private fun quality() {
            sectionTitle("Test quality", 70f)
            val v = r.qualityVerdict
            val cap = text(10f, fg(v), true)
            val capW = cap.measureText(v.label) + 16
            cv.drawRoundRect(RectF(W - M - capW, y - 22, W - M, y - 6), 8f, 8f, fill(bg(v)))
            cv.drawText(v.label, W - M - capW / 2, y - 10.5f, text(10f, fg(v), true, Paint.Align.CENTER))
            val checks = r.checksPassedPct
            bar("Checks passed", checks?.let { "${it.roundToInt()}%" } ?: "–", ((checks ?: 0.0) / 100).toFloat(), fg(Verdict.checks(checks)))
            val pf = positionsFraction(r)
            bar("Positions", "${r.positions}", pf.toFloat(), fg(Verdict.checks(pf * 100)))
            r.onSchedulePct?.let { bar("On schedule", "${it.roundToInt()}%", (it / 100).toFloat(), fg(Verdict.checks(it))) }
            y += 8
        }

        private fun bar(label: String, value: String, frac: Float, color: Int) {
            need(26f)
            cv.drawText(label, M, y + 10, text(10f, TEXT2))
            cv.drawText(value, M + CW, y + 10, text(10f, BLACK, true, Paint.Align.RIGHT))
            y += 15
            cv.drawRoundRect(RectF(M, y, M + CW, y + 4), 2f, 2f, fill(TRACK))
            cv.drawRoundRect(RectF(M, y, M + CW * frac.coerceIn(0f, 1f), y + 4), 2f, 2f, fill(color))
            y += 12
        }

        private fun details() {
            fun d(v: Double?, unit: String) = v?.let { "${it.roundToInt().toString().replace('-', '−')} $unit" } ?: "–"
            val rows = listOf(
                "Online" to (r.onlinePct?.let { "${it.roundToInt()}% · ${r.onlineVerdict.label}" } ?: "–"),
                "Wi-Fi signal (median)" to "${d(r.wifiRssiMedian, "dBm")} · ${r.wifiVerdict.label}",
                "Mobile signal (median)" to "${d(r.rsrpMedian, "dBm")} · ${r.mobileVerdict.label}",
                "Mobile network" to listOfNotNull(r.mobileTech, r.mobileBand?.let { "band $it" }).joinToString(" · ").ifEmpty { "–" },
                "Response Wi-Fi / mobile" to "${d(r.responseWifiMs, "ms")} / ${d(r.responseMobileMs, "ms")}",
                "Position" to (r.positionAccM?.let { "±${it.roundToInt()} m" + (r.positionSource?.let { s -> " · $s" } ?: "") } ?: "–"),
                "Checks" to "${r.checks} · false drops ${r.falseDrops}",
                "App" to "Tracer ${r.appVersion}",
            )
            sectionTitle("Details", 24f)
            val k = text(9.5f, TEXT3)
            val v = text(9.5f, BLACK)
            val colW = CW / 2
            rows.chunked(2).forEach { pair ->
                need(16f)
                pair.forEachIndexed { i, (key, value) ->
                    val x = M + i * colW
                    cv.drawText(key, x, y + 10, k)
                    cv.drawText(ellipsize(value, v, colW - 120), x + 112, y + 10, v)
                }
                y += 15
            }
        }
    }
}
