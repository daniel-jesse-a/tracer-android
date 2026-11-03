package app.tracer.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tracer.events.Cause
import app.tracer.events.EventType
import app.tracer.events.Severity
import app.tracer.ui.R
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.severityColor

@Composable
fun eventTypeLabel(type: String): String = when (type) {
    EventType.HANDOVER -> stringResource(R.string.live_ev_handover)
    EventType.RAT_CHANGE -> stringResource(R.string.live_ev_rat_change)
    EventType.WIFI_ROAM -> stringResource(R.string.live_ev_wifi_roam)
    EventType.WIFI_CELL_SWITCH -> stringResource(R.string.live_ev_wifi_cell_switch)
    EventType.DROP -> stringResource(R.string.live_ev_drop)
    EventType.RECONNECT -> stringResource(R.string.live_ev_reconnect)
    EventType.CAPTIVE_PORTAL -> stringResource(R.string.live_ev_captive_portal)
    EventType.IP_ASN_CHANGE -> stringResource(R.string.live_ev_ip_asn_change)
    EventType.GPS_DEGRADED -> stringResource(R.string.live_ev_gps_degraded)
    EventType.LATENCY_SPIKE -> stringResource(R.string.live_ev_latency_spike)
    EventType.DNS_SPIKE -> stringResource(R.string.live_ev_dns_spike)
    EventType.THROUGHPUT_DROP -> stringResource(R.string.live_ev_throughput_drop)
    EventType.SIGNAL_DROP -> stringResource(R.string.live_ev_signal_drop)
    EventType.NEIGHBOUR_OVERTAKE -> stringResource(R.string.live_ev_neighbour_overtake)
    else -> type
}

@Composable
fun causeLabel(cause: String): String = when (cause) {
    Cause.TUNNEL -> stringResource(R.string.live_cause_tunnel)
    Cause.CELL_EDGE -> stringResource(R.string.live_cause_cell_edge)
    Cause.LATE_HANDOVER -> stringResource(R.string.live_cause_late_handover)
    Cause.HANDOVER_CHURN -> stringResource(R.string.live_cause_handover_churn)
    Cause.CONGESTION -> stringResource(R.string.live_cause_congestion)
    Cause.INTERFERENCE -> stringResource(R.string.live_cause_interference)
    Cause.WIFI_WEAK -> stringResource(R.string.live_cause_wifi_weak)
    Cause.STICKY_CLIENT -> stringResource(R.string.live_cause_sticky_client)
    Cause.CHANNEL_CONGESTION -> stringResource(R.string.live_cause_channel_congestion)
    Cause.WIFI_UPLINK -> stringResource(R.string.live_cause_wifi_uplink)
    Cause.CAPTIVE -> stringResource(R.string.live_cause_captive)
    Cause.DNS -> stringResource(R.string.live_cause_dns)
    Cause.GATEWAY -> stringResource(R.string.live_cause_gateway)
    else -> cause
}

@Composable
fun severityLabel(severity: String): String = when (severity) {
    Severity.CRITICAL -> stringResource(R.string.live_sev_critical)
    Severity.WARNING -> stringResource(R.string.live_sev_warning)
    else -> stringResource(R.string.live_sev_info)
}

/** Leading severity dot + text label (never colour alone, never a left-edge stripe). */
@Composable
fun SeverityBadge(severity: String, modifier: Modifier = Modifier) {
    val c = severityColor(severity, LocalTracerColors.current)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(severityLabel(severity), style = MaterialTheme.typography.labelSmall, color = c)
    }
}
