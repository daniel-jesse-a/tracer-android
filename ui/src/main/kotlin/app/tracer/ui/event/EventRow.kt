package app.tracer.ui.event

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tracer.core.model.EventSample
import app.tracer.ui.common.EventIcon
import app.tracer.ui.common.GroupedRow
import app.tracer.ui.common.eventSubtitle
import app.tracer.ui.common.eventTitle
import app.tracer.ui.live.Fmt
import app.tracer.ui.theme.Mono

/** Grouped event row: icon · title · Mono subtitle · trailing HH:MM:SS.mmm (design "Event row"). */
@Composable
fun EventListRow(e: EventSample, index: Int, count: Int, onClick: () -> Unit, label: String? = null) {
    GroupedRow(index = index, count = count, onClick = onClick) {
        EventIcon(e.eventType, e.severity)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(eventTitle(e), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                eventSubtitle(e) + (label?.let { " · “$it”" } ?: ""),
                style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Fmt.timestampMs(e.stamp.tUtcMs), style = Mono.small, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp))
    }
}
