package app.tracer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import app.tracer.core.quality.ConnType
import app.tracer.core.quality.QualityLevel

/*
 * Tracer design system — "Android · Material 3 Expressive" (Tracer — iOS & Android design file).
 * Tonal scheme from one blue seed; data hues (quality ramp, connection type) are shared with iOS
 * and darkened in light mode for 4.5:1 on white. Dark-first, light supported.
 *
 * Type: Roboto (UI, platform default) · Roboto Flex (display) · Roboto Mono (every number).
 * Roboto Mono: drop RobotoMono-Regular/-Medium/-Bold.ttf (Apache-2.0, fonts.google.com) into
 * ui/src/main/res/font as roboto_mono_regular.ttf, roboto_mono_medium.ttf, roboto_mono_bold.ttf and
 * replace MonoFamily with
 *   FontFamily(Font(R.font.roboto_mono_regular), Font(R.font.roboto_mono_medium, FontWeight.Medium),
 *              Font(R.font.roboto_mono_bold, FontWeight.Bold))
 * Until then the platform monospace (fixed-width, tabular) is used.
 */
val MonoFamily: FontFamily = FontFamily.Monospace

/** Numbers are always monospaced; units render at [UnitScale]× in onSurfaceVariant. */
object Mono {
    val hero = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 34.sp, letterSpacing = (-0.5).sp)
    val big = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 28.sp, letterSpacing = (-0.5).sp)
    val value = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp)
    val body = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp)
    val small = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp)
    val tiny = TextStyle(fontFamily = MonoFamily, fontSize = 10.sp, letterSpacing = 0.4.sp)
}

/** Unit text = 0.65× value size. Timestamps HH:MM:SS.mmm in detail views, HH:MM:SS in lists. Minus = U+2212. */
const val UnitScale = 0.65f
const val MINUS = '−'

/** Dark swatch (non-composable; safe inside DrawScope). Prefer `LocalTracerColors.current.quality(q)` for theme-aware colour. */
fun QualityLevel.color(): Color = Color(argb.toInt())
fun ConnType.color(): Color = Color(argb.toInt())

@Immutable
data class TracerExtraColors(
    val dark: Boolean = true,
    // quality ramp q1..q5 + none ("No service"/timeout always also gets a dotted stroke / hatch)
    val excellent: Color = Color(0xFF39C5BB), val good: Color = Color(0xFF3D8BFD), val fair: Color = Color(0xFFF5C542),
    val poor: Color = Color(0xFFF28B30), val bad: Color = Color(0xFFE5487A), val noData: Color = Color(0xFF5A5A60),
    // connection type · Okabe–Ito
    val nrSa: Color = Color(0xFF56B4E9), val nrNsa: Color = Color(0xFFCC79A7), val lte: Color = Color(0xFFE69F00),
    val g3: Color = Color(0xFFF0E442), val wifi: Color = Color(0xFF1FBF8F), val connNone: Color = Color(0xFF5A5A60),
    // semantic
    val ok: Color = Color(0xFF7DDC9A), val warning: Color = Color(0xFFFFB870), val critical: Color = Color(0xFFE5487A),
    val info: Color = Color(0xFFABC7FF),
    /** overlay colour for the paired iPhone run in Replay */
    val linkedRun: Color = Color(0xFFCC79A7),
    /** metric tile background */
    val tile: Color = Color(0xFF1C1C1E),
    // map chrome
    val map: Color = Color(0xFF1C1C1E), val road: Color = Color(0xFF3A3A3C), val road2: Color = Color(0xFF2C2C2E),
    val water: Color = Color(0xFF0A2A4D), val scrim: Color = Color(0xEB000000),
) {
    fun quality(q: QualityLevel): Color = when (q) {
        QualityLevel.EXCELLENT -> excellent; QualityLevel.GOOD -> good; QualityLevel.FAIR -> fair
        QualityLevel.POOR -> poor; QualityLevel.BAD -> bad; QualityLevel.NONE -> noData
    }
    fun conn(c: ConnType): Color = when (c) {
        ConnType.NR_SA -> nrSa; ConnType.NR_NSA -> nrNsa; ConnType.LTE -> lte
        ConnType.G3 -> g3; ConnType.WIFI -> wifi; ConnType.NONE -> connNone
    }
}

private val DarkExtra = TracerExtraColors()

private val LightExtra = TracerExtraColors(
    dark = false,
    excellent = Color(0xFF0F9488), good = Color(0xFF1F6FE0), fair = Color(0xFFC99A00), poor = Color(0xFFD9650F),
    bad = Color(0xFFC8195A), noData = Color(0xFFA1A1A6),
    nrSa = Color(0xFF0072B2), nrNsa = Color(0xFFA8558A), lte = Color(0xFFC27C00), g3 = Color(0xFF9E9300),
    wifi = Color(0xFF00845F), connNone = Color(0xFFA1A1A6),
    ok = Color(0xFF1E6B3A), warning = Color(0xFF8A4B00), critical = Color(0xFFC8195A), info = Color(0xFF415F91),
    linkedRun = Color(0xFFA8558A), tile = Color(0xFFEDEDF4),
    map = Color(0xFFE7E9EC), road = Color(0xFFFFFFFF), road2 = Color(0xFFD3D7DD), water = Color(0xFFC7DCEF),
    scrim = Color(0xE6F9F9FF),
)

val LocalTracerColors = staticCompositionLocalOf { DarkExtra }

fun severityColor(severity: String, c: TracerExtraColors): Color = when (severity) {
    "critical" -> c.critical; "warning" -> c.warning; else -> c.info
}

/*
 * Design v2 ("Tracer — iOS & Android", consumer screens): true-black background, grouped cards,
 * system-blue links, white primary pills, verdict-tinted tiles. The M3 schemes below are mapped to
 * that palette so legacy (engineer-view) screens sit in the same chrome.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF0A84FF), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0A2A4D), onPrimaryContainer = Color(0xFF9CC9FF),
    secondary = Color(0xFF98989D), onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2C2C2E), onSecondaryContainer = Color(0xFFF5F5F7),
    tertiary = Color(0xFFFF9F0A), onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF33200C), onTertiaryContainer = Color(0xFFFF9F43),
    error = Color(0xFFFF6961), onError = Color(0xFF000000),
    errorContainer = Color(0xFF3A1517), onErrorContainer = Color(0xFFFF6961),
    background = Color(0xFF000000), onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF000000), onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1C1E), onSurfaceVariant = Color(0xFF98989D),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF48484A), outlineVariant = Color(0xFF38383A),
    inverseSurface = Color(0xFFF5F5F7), inverseOnSurface = Color(0xFF000000), inversePrimary = Color(0xFF0A84FF),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF007AFF), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EBFF), onPrimaryContainer = Color(0xFF004A99),
    secondary = Color(0xFF6C6C70), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFFC93400), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE8D6), onTertiaryContainer = Color(0xFFC93400),
    error = Color(0xFFD70015), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE1E0), onErrorContainer = Color(0xFFD70015),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF000000),
    surface = Color(0xFFF2F2F7), onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color(0xFFD1D1D6),
    outline = Color(0xFFC7C7CC), outlineVariant = Color(0xFFD1D1D6),
    inverseSurface = Color(0xFF1C1C1E), inverseOnSurface = Color(0xFFF5F5F7), inversePrimary = Color(0xFF0A84FF),
)

/**
 * Design v2 palette. Verdict colours: fg = text/meter, bg = tile tint.
 * Dark values are the design's exact swatches (iOS system colours on black).
 */
@Immutable
data class TColors(
    val dark: Boolean,
    val bg: Color, val card: Color, val card2: Color, val card3: Color, val sep: Color,
    val text: Color, val text2: Color, val text3: Color, val text4: Color,
    val link: Color, val linkBg: Color,
    val wifi: Color, val mobile: Color, val offline: Color,
    val primaryBtn: Color, val onPrimaryBtn: Color,
    val switchOn: Color,
    val bad: Color, val badBg: Color,
    val belowAvg: Color, val belowAvgBg: Color,
    val average: Color, val averageBg: Color,
    val aboveAvg: Color, val aboveAvgBg: Color,
    val veryGood: Color, val veryGoodBg: Color,
    val meterOff: Color,
    val chartBad: Color,
    val map: Color, val mapBlock: Color,
)

val DarkT = TColors(
    dark = true,
    bg = Color(0xFF000000), card = Color(0xFF1C1C1E), card2 = Color(0xFF2C2C2E), card3 = Color(0xFF3A3A3C), sep = Color(0xFF38383A),
    text = Color(0xFFF5F5F7), text2 = Color(0xFFC7C7CC), text3 = Color(0xFF98989D), text4 = Color(0xFF48484A),
    link = Color(0xFF0A84FF), linkBg = Color(0xFF0A2A4D),
    wifi = Color(0xFF409CFF), mobile = Color(0xFFFF9F0A), offline = Color(0xFF48484A),
    primaryBtn = Color(0xFFF5F5F7), onPrimaryBtn = Color(0xFF000000),
    switchOn = Color(0xFF30D158),
    bad = Color(0xFFFF6961), badBg = Color(0xFF3A1517),
    belowAvg = Color(0xFFFF9F43), belowAvgBg = Color(0xFF33200C),
    average = Color(0xFFFFD60A), averageBg = Color(0xFF2C2606),
    aboveAvg = Color(0xFFA6E05A), aboveAvgBg = Color(0xFF1C2A10),
    veryGood = Color(0xFF30D158), veryGoodBg = Color(0xFF0F2A18),
    meterOff = Color(0x1FFFFFFF),
    chartBad = Color(0xFF3A1517),
    map = Color(0xFF1C1C1E), mapBlock = Color(0xFF2C2C2E),
)

val LightT = TColors(
    dark = false,
    bg = Color(0xFFF2F2F7), card = Color(0xFFFFFFFF), card2 = Color(0xFFE5E5EA), card3 = Color(0xFFD1D1D6), sep = Color(0xFFE5E5EA),
    text = Color(0xFF000000), text2 = Color(0xFF3A3A3C), text3 = Color(0xFF6C6C70), text4 = Color(0xFFAEAEB2),
    link = Color(0xFF007AFF), linkBg = Color(0xFFD9EBFF),
    wifi = Color(0xFF007AFF), mobile = Color(0xFFE08600), offline = Color(0xFFAEAEB2),
    primaryBtn = Color(0xFF000000), onPrimaryBtn = Color(0xFFFFFFFF),
    switchOn = Color(0xFF34C759),
    bad = Color(0xFFD70015), badBg = Color(0xFFFFE5E3),
    belowAvg = Color(0xFFC93400), belowAvgBg = Color(0xFFFFEBDB),
    average = Color(0xFF9A6B00), averageBg = Color(0xFFFFF5CC),
    aboveAvg = Color(0xFF4A8A12), aboveAvgBg = Color(0xFFEAF6DA),
    veryGood = Color(0xFF248A3D), veryGoodBg = Color(0xFFDDF5E3),
    meterOff = Color(0x1F000000),
    chartBad = Color(0xFFFFE5E3),
    map = Color(0xFFE5E5EA), mapBlock = Color(0xFFD1D1D6),
)

val LocalT = staticCompositionLocalOf { DarkT }

/** Tiles 12 dp · cards 20 dp · pills 28 dp. */
val TracerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** M3 Expressive grouped list: 16 dp rounded outer ends, 4 dp inner corners, rows separated by [Space.rowGap]. */
object GroupedShapes {
    private val o = 16.dp
    private val i = 4.dp
    fun of(index: Int, count: Int): RoundedCornerShape = when {
        count <= 1 -> RoundedCornerShape(o)
        index == 0 -> RoundedCornerShape(topStart = o, topEnd = o, bottomStart = i, bottomEnd = i)
        index == count - 1 -> RoundedCornerShape(topStart = i, topEnd = i, bottomStart = o, bottomEnd = o)
        else -> RoundedCornerShape(i)
    }
}

/** 4 dp spacing grid. */
object Space { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val rowGap = 2.dp }

/** Type scale from the design (Inter in the mock; the platform sans is used — same metrics class). */
object TType {
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, lineHeight = 40.sp)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val tileValue = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val headline = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
    val bodyRegular = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal)
    val callout = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val sub = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val footnote = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val caps = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
    val tick = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")
    val num = TextStyle(fontFeatureSettings = "tnum")
}

private val TracerType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
        headlineLarge = TType.largeTitle, headlineMedium = TType.title.copy(fontSize = 28.sp), headlineSmall = TType.title,
        titleLarge = TType.title, titleMedium = TType.headline.copy(fontSize = 17.sp), titleSmall = TType.label,
        bodyLarge = TType.bodyRegular, bodyMedium = TType.sub, bodySmall = TType.footnote,
        labelLarge = TType.label, labelMedium = TType.footnote.copy(fontWeight = FontWeight.Medium), labelSmall = TType.tick,
    )
}

@Composable
fun TracerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTracerColors provides if (darkTheme) DarkExtra else LightExtra,
        LocalT provides if (darkTheme) DarkT else LightT,
    ) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, shapes = TracerShapes, typography = TracerType, content = content)
    }
}
