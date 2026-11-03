package app.tracer.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.tracer.core.quality.QualityLevel
import app.tracer.ui.R
import app.tracer.ui.theme.GroupedShapes
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Mono
import app.tracer.ui.theme.Space
import app.tracer.ui.theme.UnitScale

// ---------------------------------------------------------------- value + unit

/** Mono value with its unit at [UnitScale]× size in onSurfaceVariant (design rule for every number). */
@Composable
fun ValueWithUnit(
    value: String,
    unit: String?,
    style: TextStyle = Mono.value,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant
    val unitSize: TextUnit = if (style.fontSize.isSpecified) style.fontSize * UnitScale else TextUnit.Unspecified
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(value) }
            if (!unit.isNullOrEmpty()) {
                withStyle(SpanStyle(color = unitColor, fontSize = unitSize)) { append(" "); append(unit) }
            }
        },
        style = style,
        maxLines = 1,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- metric tile

enum class TileState { NORMAL, WARN, STALE }

/**
 * Metric tile (12 dp radius, surfaceContainer): LABEL (tiny caps) / value coloured by [quality] +
 * unit / optional [sub] line. WARN = 1 dp warning outline + warning sub; STALE = value and sub in
 * onSurfaceVariant at 60 %. Quality is exposed to accessibility as text, never colour alone.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String?,
    quality: QualityLevel?,
    modifier: Modifier = Modifier,
    sub: String? = null,
    state: TileState = TileState.NORMAL,
) {
    val tc = LocalTracerColors.current
    val cs = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val stale = cs.onSurfaceVariant.copy(alpha = 0.6f)
    val valueColor = when {
        state == TileState.STALE -> stale
        quality != null && quality != QualityLevel.NONE -> tc.quality(quality)
        else -> cs.onSurface
    }
    val subColor = when (state) {
        TileState.WARN -> tc.warning
        TileState.STALE -> stale
        TileState.NORMAL -> cs.onSurfaceVariant
    }
    val border = if (state == TileState.WARN) Modifier.border(1.dp, tc.warning, shape) else Modifier
    Surface(
        modifier = modifier.then(border).semantics(mergeDescendants = true) {
            if (quality != null) contentDescription = "$label ${quality.label}"
        },
        shape = shape,
        color = cs.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = Space.m, vertical = Space.s + 2.dp)) {
            Text(
                label.uppercase(), style = Mono.tiny, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            ValueWithUnit(value, unit, Mono.value, valueColor, Modifier.padding(top = 2.dp))
            if (sub != null) {
                Text(sub, style = Mono.tiny, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- chips

/** Outlined pill with a coloured dot and text. Text always carries the meaning. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** Tonal badge, e.g. "8/8 READY" next to a large title. */
@Composable
fun TonalBadge(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = color) {
        Text(
            text, style = Mono.small, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// ---------------------------------------------------------------- readiness

enum class ReadinessStatus(@StringRes val labelRes: Int) {
    OK(R.string.readiness_ok),
    WARN(R.string.readiness_warn),
    BLOCKED(R.string.readiness_blocked),
    PENDING(R.string.readiness_pending),
}

@Composable
fun ReadinessStatus.color(): Color {
    val c = LocalTracerColors.current
    return when (this) {
        ReadinessStatus.OK -> c.ok
        ReadinessStatus.WARN -> c.warning
        ReadinessStatus.BLOCKED -> c.critical
        ReadinessStatus.PENDING -> c.noData
    }
}

@Composable
fun StatusChip(status: ReadinessStatus, modifier: Modifier = Modifier) =
    StatusChip(stringResource(status.labelRes), status.color(), modifier)

/**
 * Readiness item: status icon (check / triangle / ✕ / hourglass) + title + Mono sub-line.
 * [onFix] shows a "Fix" action when the status is not OK.
 */
@Composable
fun ReadinessItem(
    title: String,
    sub: String,
    status: ReadinessStatus,
    modifier: Modifier = Modifier,
    onFix: (() -> Unit)? = null,
) {
    val color = status.color()
    val icon = when (status) {
        ReadinessStatus.OK -> Icons.Outlined.Check
        ReadinessStatus.WARN -> Icons.Outlined.WarningAmber
        ReadinessStatus.BLOCKED -> Icons.Outlined.Close
        ReadinessStatus.PENDING -> Icons.Outlined.HourglassEmpty
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(start = Space.m, end = Space.xs, top = Space.s, bottom = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = stringResource(status.labelRes), tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    sub, style = Mono.small,
                    color = if (status == ReadinessStatus.OK) MaterialTheme.colorScheme.onSurfaceVariant else color,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (onFix != null && status != ReadinessStatus.OK) {
                TextButton(onClick = onFix) { Text(stringResource(R.string.action_fix)) }
            }
        }
    }
}

// ---------------------------------------------------------------- completeness

enum class BadgeState { CAPTURED, PENDING, MISSING, NOT_APPLICABLE }

/** Tonal Mono chip "GPS ✓"; pending "…" gets a dashed outline; missing "✕"; n/a. */
@Composable
fun CompletenessBadge(label: String, state: BadgeState, modifier: Modifier = Modifier) {
    val tc = LocalTracerColors.current
    val cs = MaterialTheme.colorScheme
    val (mark, fg, bg) = when (state) {
        BadgeState.CAPTURED -> Triple("✓", tc.ok, tc.ok.copy(alpha = 0.14f))
        BadgeState.PENDING -> Triple("…", tc.warning, Color.Transparent)
        BadgeState.MISSING -> Triple("✕", tc.critical, tc.critical.copy(alpha = 0.14f))
        BadgeState.NOT_APPLICABLE -> Triple("n/a", cs.onSurfaceVariant, cs.surfaceContainerHigh)
    }
    val shape = RoundedCornerShape(6.dp)
    val dashed = if (state == BadgeState.PENDING) {
        Modifier.drawBehind {
            drawRoundRect(
                color = fg,
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
    } else {
        Modifier
    }
    Text(
        "$label $mark",
        style = Mono.tiny,
        color = fg,
        modifier = modifier
            .then(dashed)
            .background(bg, shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
fun CompletenessBadgeRow(badges: List<Pair<String, BadgeState>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.forEach { (label, state) -> CompletenessBadge(label, state) }
    }
}

// ---------------------------------------------------------------- grouped list

/**
 * One row of an M3 Expressive grouped list: surfaceContainer, 16 dp outer / 4 dp inner corners.
 * Place rows in a Column/LazyColumn with `Arrangement.spacedBy(Space.rowGap)`.
 */
@Composable
fun GroupedRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = GroupedShapes.of(index, count)
    val body: @Composable () -> Unit = {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, content = body)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, content = body)
    }
}

/** Section title with optional trailing content (e.g. a button). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (trailing != null) trailing()
    }
}

// ---------------------------------------------------------------- text

/** Text that ViewModels can produce without a Context. */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> text
}

fun UiText.asString(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Raw -> text
}
