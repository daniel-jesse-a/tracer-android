import app.tracer.core.schema.Schema
import app.tracer.core.export.Bundle

/** Generates docs/SCHEMA.md from the canonical Schema object (source of truth shared with iOS). */
fun main(args: Array<String>) {
    val sb = StringBuilder()
    sb.appendLine("# Tracer data schema — v${Schema.VERSION}")
    sb.appendLine()
    sb.appendLine("Generated from `core/src/main/kotlin/app/tracer/core/schema/Schema.kt` by `tools/gen/SchemaDoc.kt`. Do not edit by hand.")
    sb.appendLine()
    sb.appendLine("Shared-schema fingerprint (SHA-256 of `Schema.sharedFingerprint()`): `${Bundle.sha256Hex(Schema.sharedFingerprint().toByteArray())}` — the iOS app computes the same string; bundles carry it as `schema_fingerprint_sha256` in `run.json`.")
    sb.appendLine()
    sb.appendLine("Conventions: snake_case; booleans are INTEGER 0/1; every row carries `run_id`, `t_utc_ms` (wall clock), `t_mono_ns` (elapsedRealtimeNanos / mach_continuous_time); `gps_time_ms` is the GNSS-derived UTC of a fix. Sample tables have `id INTEGER PRIMARY KEY AUTOINCREMENT` and an index on `(run_id, t_mono_ns)`. CSV export: RFC 4180, header = column list below, NULL = empty field.")
    sb.appendLine()
    for (shared in listOf(true, false)) {
        sb.appendLine(if (shared) "## Shared tables (identical on Android and iOS)" else "## Android extension tables (`x_` prefix — iOS importer ignores them)")
        sb.appendLine()
        for (td in Schema.all.filter { it.table.isShared == shared }) {
            sb.appendLine("### `${td.name}`")
            sb.appendLine()
            sb.appendLine("| # | column | type | null |")
            sb.appendLine("|---|---|---|---|")
            var i = 0
            if (td.hasRowId) sb.appendLine("| ${i++} | `id` | INTEGER PK | no |")
            td.columns.forEach { c -> sb.appendLine("| ${i++} | `${c.name}` | ${c.type.sql} | ${if (c.notNull) "no" else "yes"} |") }
            sb.appendLine()
            sb.appendLine("```sql")
            sb.appendLine(td.createSql(ifNotExists = false) + ";")
            sb.appendLine("```")
            sb.appendLine()
        }
    }
    java.io.File(args[0]).readText().let { notes -> sb.appendLine(notes) }
    java.io.File(args[1]).writeText(sb.toString())
}
