package app.tracer.core.export

import java.io.Reader
import java.io.Writer

/** RFC 4180 CSV. null → empty field; booleans already encoded as 0/1 by the codec. */
object Csv {
    fun field(v: Any?): String {
        if (v == null) return ""
        val s = when (v) {
            is Double -> if (v.isNaN() || v.isInfinite()) "" else stripZero(v)
            is Float -> stripZero(v.toDouble())
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun stripZero(d: Double): String {
        val s = d.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    fun writeRow(w: Writer, values: List<Any?>) {
        values.forEachIndexed { i, v -> if (i > 0) w.write(","); w.write(field(v)) }
        w.write("\r\n")
    }

    /** Streaming parser. Returns rows as lists of nullable strings (empty unquoted field → null). */
    fun parse(input: Reader): Sequence<List<String?>> = sequence {
        val r = if (input.markSupported()) input else input.buffered()
        val row = mutableListOf<String?>()
        val sb = StringBuilder()
        var inQuotes = false
        var quotedField = false
        var any = false
        fun endField() { row += if (sb.isEmpty() && !quotedField) null else sb.toString(); sb.setLength(0); quotedField = false }
        while (true) {
            val c = r.read()
            if (c == -1) break
            any = true
            val ch = c.toChar()
            if (inQuotes) {
                if (ch == '"') {
                    r.mark(1)
                    val n = r.read()
                    if (n == '"'.code) sb.append('"') else { inQuotes = false; if (n != -1) r.reset() }
                } else sb.append(ch)
            } else when (ch) {
                '"' -> { inQuotes = true; quotedField = true }
                ',' -> endField()
                '\r' -> {}
                '\n' -> { endField(); yield(row.toList()); row.clear(); any = false }
                else -> sb.append(ch)
            }
        }
        if (any || sb.isNotEmpty() || row.isNotEmpty()) { endField(); yield(row.toList()) }
    }
}
