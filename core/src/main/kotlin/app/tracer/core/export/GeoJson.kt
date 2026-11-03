package app.tracer.core.export

import app.tracer.core.model.CellSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.LocationSample
import kotlinx.serialization.json.*

object GeoJson {
    /**
     * track.geojson: one LineString (RFC 7946, [lon,lat,alt]) plus parallel per-vertex arrays in
     * properties (`coord_times_utc_ms`, `rsrp`, `h_acc_m`, `speed_mps`) — the de-facto convention
     * GPX tooling and QGIS "coordTimes" importers understand. Serving RSRP is the nearest
     * serving-cell row within ±2 s.
     */
    fun track(runId: String, locations: List<LocationSample>, serving: List<CellSample> = emptyList()): JsonObject {
        val sortedCells = serving.sortedBy { it.stamp.tMonoNs }
        fun nearestRsrp(tMono: Long): Int? {
            if (sortedCells.isEmpty()) return null
            var lo = 0; var hi = sortedCells.size - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (sortedCells[mid].stamp.tMonoNs < tMono) lo = mid + 1 else hi = mid }
            val cands = listOfNotNull(sortedCells.getOrNull(lo), sortedCells.getOrNull(lo - 1))
            val best = cands.minByOrNull { kotlin.math.abs(it.stamp.tMonoNs - tMono) } ?: return null
            return if (kotlin.math.abs(best.stamp.tMonoNs - tMono) <= 2_000_000_000L) best.rsrp else null
        }
        val locs = locations.sortedBy { it.stamp.tMonoNs }
        return buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            locs.forEach { l -> addJsonArray { add(l.lon); add(l.lat); l.altM?.let { add(it) } } }
                        }
                    }
                    putJsonObject("properties") {
                        put("run_id", runId)
                        putJsonArray("coord_times_utc_ms") { locs.forEach { add(it.stamp.tUtcMs) } }
                        putJsonArray("gps_time_ms") { locs.forEach { add(it.gpsTimeMs) } }
                        putJsonArray("rsrp") { locs.forEach { add(nearestRsrp(it.stamp.tMonoNs)) } }
                        putJsonArray("h_acc_m") { locs.forEach { add(it.hAccM) } }
                        putJsonArray("speed_mps") { locs.forEach { add(it.speedMps) } }
                    }
                }
            }
        }
    }

    fun events(runId: String, events: List<EventSample>): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            events.filter { it.lat != null && it.lon != null }.forEach { e ->
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") { add(e.lon); add(e.lat) }
                    }
                    putJsonObject("properties") {
                        put("run_id", runId)
                        put("event_type", e.eventType)
                        put("severity", e.severity)
                        put("t_utc_ms", e.stamp.tUtcMs)
                        put("likely_cause", e.likelyCause)
                        put("cause_confidence", e.causeConfidence)
                    }
                }
            }
        }
    }
}
