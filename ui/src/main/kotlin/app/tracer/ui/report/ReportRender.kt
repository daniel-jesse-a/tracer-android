package app.tracer.ui.report

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset

/** Template injection + file name for Tracer report v1 (same rules as iOS src/report/v1/render.ts). Pure Kotlin. */
object ReportRender {
    const val TOKEN = "__REPORT_DATA__"
    const val TEMPLATE_ASSET = "report/tracer-report-template.html"
    const val RAIL_ASSET = "report/rail_ergt.json"
    /** A4 landscape, PostScript points. */
    const val PAGE_W_PT = 842
    const val PAGE_H_PT = 595

    /** JSON safe inside <script type="application/json">: "</" escaped as "<\/" (non-finite numbers are already null). */
    fun reportJson(data: JsonObject): String = data.toString().replace("</", "<\\/")

    fun inject(template: String, data: JsonObject): String {
        val i = template.indexOf(TOKEN)
        require(i >= 0) { "report template has no $TOKEN token" }
        return template.substring(0, i) + reportJson(data) + template.substring(i + TOKEN.length)
    }

    /** Tracer_<site|mode>_<test#>_<yyyyMMdd-HHmm>_<runId8>.pdf (local time of the run start). */
    fun fileName(site: String?, mode: String, testNumber: Int?, startedAt: Long, runId: String, tzOffsetMin: Int): String {
        fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').ifEmpty { "test" }
        val d = Instant.ofEpochMilli(startedAt + tzOffsetMin * 60_000L).atOffset(ZoneOffset.UTC)
        val stamp = "%04d%02d%02d-%02d%02d".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute)
        return "Tracer_${clean(site?.trim()?.ifEmpty { null } ?: mode)}_${testNumber ?: 0}_${stamp}_${runId.take(8)}.pdf"
    }
}
