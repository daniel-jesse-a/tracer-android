package app.tracer.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tracer.core.report.Carrier
import app.tracer.core.report.Segment
import app.tracer.core.report.Verdict
import app.tracer.core.report.fmtClock
import app.tracer.ui.theme.LocalT
import app.tracer.ui.theme.TColors
import app.tracer.ui.theme.TType

/*
 * Design-v2 component kit. Every consumer screen is built from these so spacing, radii and colours
 * stay identical to the mock: screen padding 20 dp, cards 22 dp radius, tiles 20 dp, pills 18 dp,
 * rows 56 dp, 5-segment meters (4 dp tall, 2 dp radius, 4 dp gaps).
 */

object K {
    val screenPad = 20.dp
    val cardRadius = 22.dp
    val tileRadius = 20.dp
    val pillRadius = 18.dp
    val gap = 12.dp
}

/** Design-v2 palette of the current theme. */
val tc: TColors @Composable @ReadOnlyComposable get() = LocalT.current

fun TColors.fg(v: Verdict): Color = when (v) {
    Verdict.BAD -> bad; Verdict.BELOW_AVG -> belowAvg; Verdict.AVERAGE -> average
    Verdict.ABOVE_AVG -> aboveAvg; Verdict.VERY_GOOD -> veryGood; Verdict.NONE -> text3
}
fun TColors.bgOf(v: Verdict): Color = when (v) {
    Verdict.BAD -> badBg; Verdict.BELOW_AVG -> belowAvgBg; Verdict.AVERAGE -> averageBg
    Verdict.ABOVE_AVG -> aboveAvgBg; Verdict.VERY_GOOD -> veryGoodBg; Verdict.NONE -> card
}
fun TColors.of(c: Carrier): Color = when (c) { Carrier.WIFI -> wifi; Carrier.MOBILE -> mobile; Carrier.NONE -> offline }

// ------------------------------------------------------------------------------------- scaffolding

/** Black screen with the standard horizontal padding; content scrolls inside a Column the caller provides. */
@Composable
fun Screen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().background(tc.bg).statusBarsPadding().padding(horizontal = K.screenPad),
        content = content,
    )
}

/** "‹ Tests" back link, optional centred title, optional trailing blue action ("Share"). */
@Composable
fun TopLinks(
    back: String? = null, onBack: (() -> Unit)? = null,
    title: String? = null,
    action: String? = null, onAction: (() -> Unit)? = null,
    trailingText: String? = null,
    backIsCancel: Boolean = false,
) {
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        if (back != null && onBack != null) {
            Text(if (backIsCancel) back else "‹ $back", style = TType.body.copy(fontSize = 17.sp), color = tc.link,
                modifier = Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(vertical = 8.dp))
        }
        if (title != null) Text(title, style = TType.body.copy(fontWeight = FontWeight.SemiBold), color = tc.text, modifier = Modifier.align(Alignment.Center))
        if (action != null && onAction != null) {
            Text(action, style = TType.body, color = tc.link,
                modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(vertical = 8.dp))
        } else if (trailingText != null) {
            Text(trailingText, style = TType.sub.copy(fontSize = 16.sp), color = tc.text3, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Small grey eyebrow over a large title ("Office · Test 3 · 3:12" / "Stayed online"). */
@Composable
fun TitleBlock(eyebrow: String?, title: String, trailing: (@Composable () -> Unit)? = null, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp)) {
        if (eyebrow != null) Text(eyebrow, style = TType.sub.copy(fontSize = 16.sp), color = tc.text3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = TType.largeTitle, color = tc.text, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        if (sub != null) Text(sub, style = TType.sub.copy(fontSize = 17.sp), color = tc.text2, modifier = Modifier.padding(top = 4.dp))
    }
}

/** "TODAY · OFFICE" section caption. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = TType.caps, color = tc.text3, modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

/** Plain grouped card. */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = tc.card, padding: PaddingValues = PaddingValues(18.dp),
         onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(K.cardRadius)).background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
        content = content,
    )
}

/** Card holding rows separated by hairlines (inset by [inset]). */
@Composable
fun GroupCard(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>, inset: Dp = 0.dp) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(K.cardRadius)).background(tc.card)) {
        rows.forEachIndexed { i, r ->
            r()
            if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = inset), thickness = 0.5.dp, color = tc.sep)
        }
    }
}

/** Settings-style row: title · value · chevron, or a trailing slot (switch). */
@Composable
fun ListRow(
    title: String, value: String? = null, onClick: (() -> Unit)? = null,
    sub: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = onClick != null && trailing == null, titleColor: Color = tc.text,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = TType.bodyRegular, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = TType.sub, color = tc.text3, maxLines = 2)
        }
        if (value != null) Text(value, style = TType.bodyRegular.merge(TType.num), color = tc.text3, maxLines = 1)
        trailing?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tc.text4, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** iOS-like toggle (green track). */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = tc.switchOn, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = tc.card3, uncheckedBorderColor = Color.Transparent,
        ),
    )
}

// ------------------------------------------------------------------------------------- buttons

enum class PillKind { PRIMARY, SECONDARY, BLUE, SECONDARY_BLUE }

/** Full-width pill button: white primary ("Continue", "Finish"), dark secondary ("Mark"), blue ("Share 2 items"). */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: PillKind = PillKind.PRIMARY, enabled: Boolean = true, height: Dp = 56.dp) {
    val (bg, fg) = when (kind) {
        PillKind.PRIMARY -> tc.primaryBtn to tc.onPrimaryBtn
        PillKind.SECONDARY -> tc.card to tc.text
        PillKind.BLUE -> tc.link to Color.White
        PillKind.SECONDARY_BLUE -> tc.card to tc.link
    }
    Box(
        modifier.height(height).clip(RoundedCornerShape(K.pillRadius)).background(if (enabled) bg else tc.card2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TType.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp), color = if (enabled) fg else tc.text3) }
}

/** Small blue "Allow" chip. */
@Composable
fun Chip(text: String, onClick: () -> Unit, color: Color = tc.link) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(text, style = TType.label, color = Color.White)
    }
}

/** Status capsule with a dot: "● You stayed online". */
@Composable
fun StatusCapsule(text: String, color: Color, bg: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 8.dp); Spacer(Modifier.width(8.dp)); Text(text, style = TType.label.copy(fontSize = 16.sp), color = color)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 9.dp) { Box(Modifier.size(size).clip(CircleShape).background(color)) }

/** Light / Dark / Auto segmented control; also "Simple | Detailed". */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, height: Dp = 44.dp) {
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(tc.card).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp))
                    .background(if (i == selected) tc.card3 else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, style = TType.label.copy(fontSize = 16.sp), color = tc.text) }
        }
    }
}

// ------------------------------------------------------------------------------------- data marks

/** 5-segment verdict meter. */
@Composable
fun Meter(v: Verdict, modifier: Modifier = Modifier, height: Dp = 5.dp, gap: Dp = 5.dp, color: Color = tc.fg(v)) {
    val off = tc.meterOff
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(5) { i ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (i < v.segments) color else off))
        }
    }
}

/** Dashes meter for compact rows (Diagnostics / Companions): fixed 116 dp wide. */
@Composable
fun MiniMeter(v: Verdict, modifier: Modifier = Modifier) = Meter(v, modifier.width(58.dp), height = 5.dp, gap = 3.dp)

/** Verdict-tinted tile: label (verdict colour), big value + small unit, meter, verdict word. */
@Composable
fun VerdictTile(label: String, value: String, v: Verdict, modifier: Modifier = Modifier, unit: String? = null, onClick: (() -> Unit)? = null,
                verdictText: String = v.label) {
    Column(
        modifier.clip(RoundedCornerShape(K.tileRadius)).background(tc.bgOf(v))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(label, style = TType.label.copy(fontSize = 16.sp), color = tc.fg(v), maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(
            buildAnnotatedString {
                append(value)
                if (unit != null) withStyle(SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = tc.text2)) { append(" $unit") }
            },
            style = TType.tileValue.merge(TType.num), color = tc.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Meter(v, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(verdictText, style = TType.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = tc.fg(v))
    }
}

/** Two tiles side by side. */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(K.gap), content = content)
}

/** Rounded square icon well tinted by a verdict ("Online", "Wi-Fi signal" rows). */
@Composable
fun IconWell(icon: ImageVector, tint: Color, bg: Color, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Ring gauge: "100% online". */
@Composable
fun Ring(fraction: Float, color: Color, center: String, under: String?, size: Dp = 104.dp, stroke: Dp = 9.dp, track: Color = tc.card2) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx(); val inset = w / 2
            val sz = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, style = TType.headline.copy(fontSize = if (center.length > 4) 22.sp else 26.sp, fontWeight = FontWeight.ExtraBold).merge(TType.num), color = tc.text)
            if (under != null) Text(under, style = TType.footnote, color = tc.text3)
        }
    }
}

/** Network timeline bar (Wi-Fi blue / Mobile orange / offline grey) with optional time ticks. */
@Composable
fun NetworkBar(segments: List<Segment>, durationS: Double, modifier: Modifier = Modifier, height: Dp = 30.dp, ticks: List<Double>? = null, radius: Dp = 10.dp) {
    val colors = tc
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius)).background(colors.card2)) {
            if (durationS <= 0) return@Canvas
            val gapPx = 2.dp.toPx()
            segments.forEachIndexed { i, s ->
                val x0 = (s.fromS / durationS * size.width).toFloat()
                val x1 = (s.toS / durationS * size.width).toFloat() - if (i < segments.lastIndex) gapPx else 0f
                if (x1 > x0) drawRect(colors.of(s.carrier), Offset(x0, 0f), Size(x1 - x0, size.height))
            }
        }
        if (ticks != null && durationS > 0) {
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
                val w = maxWidth
                ticks.forEachIndexed { i, tk ->
                    val frac = (tk / durationS).toFloat().coerceIn(0f, 1f)
                    val align = when (i) { 0 -> 0f; ticks.lastIndex -> 1f; else -> 0.5f }
                    Text(fmtClock(tk), style = TType.tick, color = colors.text3,
                        modifier = Modifier.offset(x = (w * frac) - (40.dp * align)).width(40.dp),
                        textAlign = when (i) { 0 -> TextAlign.Start; ticks.lastIndex -> TextAlign.End; else -> TextAlign.Center })
                }
            }
        }
    }
}

/** Slim one-line timeline used on Live ("So far") and in the Tests list rows. */
@Composable
fun SlimBar(segments: List<Segment>, durationS: Double, modifier: Modifier = Modifier, height: Dp = 6.dp, total: Double = durationS) =
    NetworkBar(segments, total, modifier, height, null, radius = 3.dp)

/** "Wi-Fi ■  Mobile ■" legend. */
@Composable
fun CarrierLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(tc.wifi)); Spacer(Modifier.width(6.dp))
        Text("Wi-Fi", style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3); Spacer(Modifier.width(14.dp))
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(tc.mobile)); Spacer(Modifier.width(6.dp))
        Text("Mobile", style = TType.footnote.copy(fontSize = 14.sp), color = tc.text3)
    }
}

/** Labelled progress line ("Checks passed 97%"). */
@Composable
fun ProgressLine(label: String, value: String, fraction: Float, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row { Text(label, style = TType.sub.copy(fontSize = 16.sp), color = tc.text2, modifier = Modifier.weight(1f))
            Text(value, style = TType.label.copy(fontSize = 16.sp).merge(TType.num), color = tc.text) }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(tc.card2)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

/** Small verdict capsule ("Above avg" in the Test-quality header). */
@Composable
fun VerdictCapsule(v: Verdict) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(tc.bgOf(v)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(v.label, style = TType.label, color = tc.fg(v))
    }
}

/** Outline-selectable card (Share "What to send", New-test movement tiles). */
@Composable
fun SelectCard(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, radius: Dp = K.cardRadius, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(tc.card)
            .then(if (selected) Modifier.border(2.dp, tc.link, RoundedCornerShape(radius)) else Modifier)
            .clickable(onClick = onClick),
        content = content,
    )
}

/** Round check (selected) / empty circle. */
@Composable
fun CheckCircle(checked: Boolean, size: Dp = 28.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).then(if (checked) Modifier.background(tc.link) else Modifier.border(2.dp, tc.text4, CircleShape)),
        contentAlignment = Alignment.Center,
    ) { if (checked) Text("✓", color = Color.White, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)) }
}

/** Radio dot for "What to test". */
@Composable
fun RadioDot(selected: Boolean, size: Dp = 24.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).border(2.dp, if (selected) tc.link else tc.text4, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (selected) Box(Modifier.size(size * 0.5f).clip(CircleShape).background(tc.link)) }
}

/** Number + small unit inline, e.g. "333 Mbps". */
fun valueUnit(value: String, unit: String?, unitSize: TextUnit = 16.sp) = buildAnnotatedString {
    append(value); if (unit != null) withStyle(SpanStyle(fontSize = unitSize, fontWeight = FontWeight.Medium)) { append(" $unit") }
}

/** Thin rounded rect helper for charts. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.band(color: Color, top: Float, bottom: Float, r: Float = 6f) =
    drawRoundRect(color, Offset(0f, top), Size(size.width, bottom - top), CornerRadius(r, r))

/** U+2212 minus for negative numbers. */
fun Number.signed(): String = toString().replace('-', '−')
fun Double.dbm(): String = Math.round(this).toString().replace('-', '−')
