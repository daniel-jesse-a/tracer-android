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
    val tile: Color = Color(0xFF1D2024),
    // map chrome
    val map: Color = Color(0xFF0F1216), val road: Color = Color(0xFF262B33), val road2: Color = Color(0xFF1A1E24),
    val water: Color = Color(0xFF0C1A27), val scrim: Color = Color(0xDB111318),
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

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFABC7FF), onPrimary = Color(0xFF0B305F),
    primaryContainer = Color(0xFF284777), onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFBEC6DC), onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0), onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C), onTertiaryContainer = Color(0xFFFAD8FD),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF282A2F), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
    inverseSurface = Color(0xFFE2E2E9), inverseOnSurface = Color(0xFF2E3036), inversePrimary = Color(0xFF415F91),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF415F91), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF284777),
    secondary = Color(0xFF565F71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFF705575), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FD), onTertiaryContainer = Color(0xFF3F2844),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE7E8EE), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4), surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2E3036), inverseOnSurface = Color(0xFFF0F0F7), inversePrimary = Color(0xFFABC7FF),
)

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

@Composable
fun TracerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTracerColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, shapes = TracerShapes, content = content)
    }
}
