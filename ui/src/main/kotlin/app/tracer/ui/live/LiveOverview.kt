package app.tracer.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tracer.core.cell.CellNormalizer
import app.tracer.core.model.EventSample
import app.tracer.core.quality.ConnType
import app.tracer.core.quality.QualityLevel
import app.tracer.core.quality.QualityScale
import app.tracer.core.runtime.LiveSnapshot
import app.tracer.core.runtime.TrackPoint
import app.tracer.ui.R
import app.tracer.ui.common.MetricTile
import app.tracer.ui.common.TileState
import app.tracer.ui.components.chart.ChartSeries
import app.tracer.ui.components.chart.SeriesArrays
import app.tracer.ui.components.chart.SeriesFactory
import app.tracer.ui.components.chart.TimelineChart
import app.tracer.ui.event.EventListRow
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space

private const val STRIP_NS = 10L * 60 * 1_000_000_000L
private const val STALE_CELL_MS = 5_000L
private const val STALE_THROUGHPUT_MS = 300_000L

/** Track-derived lanes (TYPE, RSRP and optionally SINR, latency, GNSS used). Rebuilt only when the track changes. */
@Composable
internal fun rememberTrackSeries(track: List<TrackPoint>, full: Boolean = true): List<ChartSeries> {
    val colors = LocalTracerColors.current
    val lType = stringResource(R.string.live_lane_type)
    val lRsrp = stringResource(R.string.live_lane_rsrp)
    val lSinr = stringResource(R.string.live_lane_sinr)
    val lLat = stringResource(R.string.live_lane_latency)
    val lGnss = stringResource(R.string.live_lane_gnss)
    val noData = stringResource(R.string.live_no_data)
    return remember(track, full, colors) {
        val xs = SeriesArrays.longs(track) { it.tMonoNs }
        val type = SeriesFactory.connType(lType, xs, Array(track.size) { ConnType.of(track[it].rat) }, colors, noData)
        val rsrp = SeriesFactory.rsrp(lRsrp, xs, SeriesArrays.floats(track) { it.rsrp }, colors, noData)
        if (!full) listOf(type, rsrp)
        else listOf(
            type, rsrp,
            SeriesFactory.sinr(lSinr, xs, SeriesArrays.floats(track) { it.sinr }, colors, noData),
            SeriesFactory.latency(lLat, xs, SeriesArrays.floats(track) { it.latencyMs }, BooleanArray(track.size) { track[it].failed }, colors, noData),
            SeriesFactory.satellites(lGnss, xs, SeriesArrays.floats(track) { it.usedSats }, colors, noData),
        )
    }
}

private fun warnIf(q: QualityLevel): TileState = if (q == QualityLevel.POOR || q == QualityLevel.BAD) TileState.WARN else TileState.NORMAL

/** Live tab: 4-column tile grid, timeline strip card, grouped event rows (newest first). */
@Composable
internal fun LiveOverview(snap: LiveSnapshot, stats: LiveStats, onEventClick: (EventSample) -> Unit) {
    val strip = rememberTrackSeries(snap.track, full = false)
    val nowNs = (snap.startedMonoNs ?: snap.track.lastOrNull()?.tMonoNs ?: 0L) + (snap.elapsedMs + snap.pausedMs) * 1_000_000L
    val stripStart = maxOf(snap.startedMonoNs ?: (nowNs - STRIP_NS), nowNs - STRIP_NS)
    val events = snap.recentEvents
    val ticks = remember(events) { events.map { it.stamp.tMonoNs to it.severity } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.rowGap),
    ) {
        item(key = "tiles") { TileGrid(snap, stats) }
        item(key = "strip") {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = Space.s),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                TimelineChart(
                    series = strip,
                    startNs = stripStart,
                    endNs = nowNs.coerceAtLeast(stripStart + 1),
                    cursorNs = null,
                    events = ticks,
                    modifier = Modifier.padding(Space.m),
                    onEventTap = { i -> events.getOrNull(i)?.let(onEventClick) },
                    originNs = snap.startedMonoNs ?: stripStart,
                )
            }
        }
        if (events.isEmpty()) {
            item(key = "noevents") {
                Text(
                    stringResource(R.string.live_no_events),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.m),
                )
            }
        }
        itemsIndexed(events, key = { i, e -> "${e.stamp.tMonoNs}:${e.eventType}:$i" }) { i, e ->
            EventListRow(e, i, events.size, onClick = { onEventClick(e) })
        }
        item(key = "bottom") { Spacer(Modifier.height(Space.l)) }
    }
}

private data class Tile(val label: String, val value: String, val unit: String?, val quality: QualityLevel?, val state: TileState, val sub: String?)

@Composable
private fun TileGrid(snap: LiveSnapshot, stats: LiveStats) {
    val colors = LocalTracerColors.current
    val primary = snap.primaryServing
    val sub = primary?.subscriptionId ?: 0
    val cells = snap.servingBySub[sub].orEmpty()
    val lte = cells.firstOrNull { it.rat == "LTE" }
    val nr = cells.firstOrNull { it.rat == "NR" }
    val leg = if (primary?.rat == "LTE") nr else null
    val cellAge = primary?.cellinfoAgeMs
    val cellStale = cellAge != null && cellAge > STALE_CELL_MS
    val cachedSub = if (cellStale) stringResource(R.string.live_tile_cached, Fmt.d1(cellAge!! / 1000.0)) else null
    fun cellState(q: QualityLevel): TileState = if (cellStale) TileState.STALE else warnIf(q)

    val rsrpQ = QualityScale.rsrp(primary?.rsrp)
    val sinrQ = QualityScale.sinr(primary?.sinr)
    val latQ = QualityScale.latency(snap.latencyMs)
    val wifiQ = QualityScale.wifiRssi(snap.wifi?.rssiDbm)
    val ta = primary?.timingAdvance
    val taM = primary?.let { CellNormalizer.timingAdvanceDistanceM(it.rat, ta) }
    val bands = listOfNotNull(nr?.band, lte?.band).distinct().joinToString("+").ifEmpty { primary?.band ?: Fmt.DASH }
    val mbpsAge = stats.mbpsAgeMs
    val loc = snap.location
    val hAcc = loc?.hAccM
    val speedKmh = loc?.speedMps?.let { it * 3.6 }

    val tiles = listOf(
        Tile(stringResource(R.string.live_tile_rsrp), Fmt.int(primary?.rsrp), "dBm", rsrpQ, cellState(rsrpQ),
            cachedSub ?: rsrpQ.label.lowercase()),
        Tile(stringResource(R.string.live_tile_rsrq), Fmt.int(primary?.rsrq), "dB", null, if (cellStale) TileState.STALE else TileState.NORMAL,
            cachedSub ?: leg?.rsrq?.let { "NR ${Fmt.int(it)}" }),
        Tile(stringResource(R.string.live_tile_sinr), Fmt.int(primary?.sinr), "dB", sinrQ, cellState(sinrQ),
            cachedSub ?: leg?.sinr?.let { "NR ${Fmt.int(it)}" } ?: sinrQ.label.lowercase()),
        Tile(stringResource(R.string.live_tile_pci_cid), Fmt.int(primary?.pci), null, null, if (cellStale) TileState.STALE else TileState.NORMAL,
            Fmt.hex(primary?.cellId)),
        Tile(stringResource(R.string.live_tile_band), bands, null, null, TileState.NORMAL,
            primary?.earfcnOrArfcn?.let { "ARFCN $it" }),
        Tile(stringResource(R.string.live_tile_tac_ta), Fmt.int(primary?.tac), null, null, TileState.NORMAL,
            if (ta != null && taM != null) "TA $ta · ${Fmt.d0(taM)} m" else null),
        Tile(stringResource(R.string.live_tile_latency), Fmt.d0(snap.latencyMs), "ms", latQ, warnIf(latQ),
            stats.p95LatencyMs?.let { "p95 ${Fmt.d0(it)}" }),
        Tile(stringResource(R.string.live_tile_loss), Fmt.d1(snap.lossPct), "%", null,
            if ((snap.lossPct ?: 0.0) >= 2.0) TileState.WARN else TileState.NORMAL,
            snap.jitterMs?.let { "jit ${Fmt.d1(it)}" }),
        Tile(stringResource(R.string.live_tile_thru), Fmt.d1(snap.lastMbps), "Mb/s", null,
            if (mbpsAge != null && mbpsAge > STALE_THROUGHPUT_MS) TileState.STALE else TileState.NORMAL,
            mbpsAge?.let {
                if (it > STALE_THROUGHPUT_MS) stringResource(R.string.live_tile_stale, Fmt.ageSeconds(it))
                else stringResource(R.string.live_tile_ago, Fmt.ageSeconds(it))
            }),
        Tile(stringResource(R.string.live_tile_gps_spd), hAcc?.let { "±${Fmt.d0(it)}" } ?: Fmt.DASH, "m", null,
            if ((hAcc ?: 0.0) > 25.0) TileState.WARN else TileState.NORMAL,
            speedKmh?.let { "${Fmt.d0(it)} km/h" }),
        Tile(stringResource(R.string.live_tile_wifi), Fmt.int(snap.wifi?.rssiDbm), "dBm", if (snap.wifi != null) wifiQ else null,
            if (snap.wifi != null) warnIf(wifiQ) else TileState.NORMAL,
            stringResource(R.string.live_tile_scan_aps, snap.scan.size)),
    )

    val type = ConnType.of(snap.rat, snap.transport)
    val plmn = primary?.let { c -> if (c.mcc != null && c.mnc != null) "${c.mcc}-${c.mnc}" else null }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // first row: TYPE + RSRP, RSRQ, SINR; then 4 per row
        (0..tiles.size).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { idx ->
                    if (idx == 0) {
                        TypeTile(type, plmn, colors.conn(type), Modifier.weight(1f))
                    } else {
                        val t = tiles[idx - 1]
                        MetricTile(t.label, t.value, t.unit, t.quality, Modifier.weight(1f), state = t.state, sub = t.sub)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** TYPE tile: value in its connection-type colour (and named, never colour alone). */
@Composable
private fun TypeTile(type: ConnType, plmn: String?, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.live_tile_type), style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(type.label, style = Mono.value, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(plmn ?: Fmt.DASH, style = Mono.tiny, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
