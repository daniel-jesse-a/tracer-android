package app.tracer.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tracer.core.quality.QualityLevel
import app.tracer.ui.theme.GroupedShapes
import app.tracer.ui.theme.LocalTracerColors
import app.tracer.ui.theme.Space

/** Grouped-list row (16 dp outer / 4 dp inner corners) with a configurable container colour. No side stripe. */
@Composable
internal fun PanelRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = GroupedShapes.of(index, count), color = color) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.m), content = content)
    }
}

/**
 * Horizontal bar of [value] on [min]..[max], coloured by quality. The numeric value is always shown
 * next to it by the caller, so the bar never carries meaning by colour alone; NONE/BAD are dotted.
 */
@Composable
internal fun LevelBar(value: Int?, min: Int, max: Int, quality: QualityLevel, modifier: Modifier = Modifier) {
    val colors = LocalTracerColors.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = colors.quality(quality)
    val dotted = quality.dotted
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (value != null) {
            val f = ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
            val w = size.width * f
            if (!dotted) {
                drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
            } else {
                var x = 0f
                val step = size.height * 2.5f
                while (x < w) {
                    drawCircle(fill, size.height / 2, Offset(x + size.height / 2, size.height / 2))
                    x += step
                }
            }
        }
    }
}
