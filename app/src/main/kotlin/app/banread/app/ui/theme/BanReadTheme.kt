package app.banread.app.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.model.ReaderTheme

@Immutable
data class BanReadPalette(
    val readerTheme: ReaderTheme,
    val labelZh: String,
    val paper: Color,
    val paperReading: Color,
    val paperDeep: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val lineStrong: Color,
    val cinnabar: Color,
    val cinnabarSoft: Color,
    val pine: Color,
    val pineSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val indigo: Color,
    val danger: Color,
    val dangerSoft: Color,
    val isDark: Boolean,
    val isEink: Boolean,
    val animationDurationMs: Int,
    val borderWidth: Dp,
    val cardElevation: Dp,
) {
    fun <T> tweenSpec(): FiniteAnimationSpec<T> = tween(durationMillis = animationDurationMs)
}

object BanReadPalettes {
    val PAPER_LIGHT = BanReadPalette(
        readerTheme = ReaderTheme.PAPER_LIGHT,
        labelZh = "素白",
        paper = Color(0xFFF7F4ED),
        paperReading = Color(0xFFFBF9F5),
        paperDeep = Color(0xFFEFEAE0),
        surface = Color(0xFFFDFBF7),
        surfaceSubtle = Color(0xFFF2EFE7),
        ink = Color(0xFF1E1E1E),
        ink2 = Color(0xFF4A4D52),
        ink3 = Color(0xFF666A70),
        line = Color(0xFFDCD9D0),
        lineStrong = Color(0xFFC2BEB2),
        cinnabar = Color(0xFFA8442F),
        cinnabarSoft = Color(0x1CA8442F),
        pine = Color(0xFF2F6A52),
        pineSoft = Color(0x1C2F6A52),
        amber = Color(0xFF87500E),
        amberSoft = Color(0x2487500E),
        indigo = Color(0xFF2C4056),
        danger = Color(0xFFB42318),
        dangerSoft = Color(0x1CB42318),
        isDark = false,
        isEink = false,
        animationDurationMs = 180,
        borderWidth = 1.dp,
        cardElevation = 1.dp,
    )

    val SEPIA_WARM = BanReadPalette(
        readerTheme = ReaderTheme.SEPIA_WARM,
        labelZh = "暖纸",
        paper = Color(0xFFF4F1E8),
        paperReading = Color(0xFFF4ECD8),
        paperDeep = Color(0xFFEAE4D6),
        surface = Color(0xFFFCF9F2),
        surfaceSubtle = Color(0xFFEFEAE0),
        ink = Color(0xFF181817),
        ink2 = Color(0xFF4E4A42),
        ink3 = Color(0xFF686258),
        line = Color(0xFFD8D1C2),
        lineStrong = Color(0xFFBEB6A4),
        cinnabar = Color(0xFFA8442F),
        cinnabarSoft = Color(0x1CA8442F),
        pine = Color(0xFF2F6A52),
        pineSoft = Color(0x1C2F6A52),
        amber = Color(0xFF87500E),
        amberSoft = Color(0x2487500E),
        indigo = Color(0xFF2C4056),
        danger = Color(0xFFB42318),
        dangerSoft = Color(0x1CB42318),
        isDark = false,
        isEink = false,
        animationDurationMs = 180,
        borderWidth = 1.dp,
        cardElevation = 1.dp,
    )

    val SLATE_NIGHT = BanReadPalette(
        readerTheme = ReaderTheme.SLATE_NIGHT,
        labelZh = "夜墨",
        paper = Color(0xFF131720),
        paperReading = Color(0xFF1A1F29),
        paperDeep = Color(0xFF10141C),
        surface = Color(0xFF232A37),
        surfaceSubtle = Color(0xFF2B3343),
        ink = Color(0xFFD8DEE9),
        ink2 = Color(0xFFB0B9C8),
        ink3 = Color(0xFF8590A3),
        line = Color(0xFF313A4B),
        lineStrong = Color(0xFF465268),
        cinnabar = Color(0xFFDF745D),
        cinnabarSoft = Color(0x2EDF745D),
        pine = Color(0xFF6CB8A6),
        pineSoft = Color(0x296CB8A6),
        amber = Color(0xFFE4A84E),
        amberSoft = Color(0x29E4A84E),
        indigo = Color(0xFF7EA5CE),
        danger = Color(0xFFF07167),
        dangerSoft = Color(0x2EF07167),
        isDark = true,
        isEink = false,
        animationDurationMs = 180,
        borderWidth = 1.dp,
        cardElevation = 2.dp,
    )

    val AMOLED_BLACK = BanReadPalette(
        readerTheme = ReaderTheme.AMOLED_BLACK,
        labelZh = "纯黑",
        paper = Color(0xFF000000),
        paperReading = Color(0xFF000000),
        paperDeep = Color(0xFF07080A),
        surface = Color(0xFF0D0F12),
        surfaceSubtle = Color(0xFF15181E),
        ink = Color(0xFFE5E7EB),
        ink2 = Color(0xFFB3B9C4),
        ink3 = Color(0xFF838A96),
        line = Color(0xFF22262E),
        lineStrong = Color(0xFF353B47),
        cinnabar = Color(0xFFDF745D),
        cinnabarSoft = Color(0x2EDF745D),
        pine = Color(0xFF6CB8A6),
        pineSoft = Color(0x296CB8A6),
        amber = Color(0xFFE4A84E),
        amberSoft = Color(0x29E4A84E),
        indigo = Color(0xFF7EA5CE),
        danger = Color(0xFFF07167),
        dangerSoft = Color(0x2EF07167),
        isDark = true,
        isEink = false,
        animationDurationMs = 180,
        borderWidth = 1.dp,
        cardElevation = 0.dp,
    )

    val EINK_HIGH_CONTRAST = BanReadPalette(
        readerTheme = ReaderTheme.EINK_HIGH_CONTRAST,
        labelZh = "墨水屏",
        paper = Color(0xFFFFFFFF),
        paperReading = Color(0xFFFFFFFF),
        paperDeep = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFF2F2F2),
        ink = Color(0xFF000000),
        ink2 = Color(0xFF000000),
        ink3 = Color(0xFF222222),
        line = Color(0xFF000000),
        lineStrong = Color(0xFF000000),
        cinnabar = Color(0xFF000000),
        cinnabarSoft = Color(0x14000000),
        pine = Color(0xFF000000),
        pineSoft = Color(0x14000000),
        amber = Color(0xFF111111),
        amberSoft = Color(0x14000000),
        indigo = Color(0xFF000000),
        danger = Color(0xFF000000),
        dangerSoft = Color(0x1A000000),
        isDark = false,
        isEink = true,
        animationDurationMs = 0,
        borderWidth = 1.dp,
        cardElevation = 0.dp,
    )

    fun forTheme(theme: ReaderTheme): BanReadPalette = when (theme) {
        ReaderTheme.PAPER_LIGHT -> PAPER_LIGHT
        ReaderTheme.SEPIA_WARM -> SEPIA_WARM
        ReaderTheme.SLATE_NIGHT -> SLATE_NIGHT
        ReaderTheme.AMOLED_BLACK -> AMOLED_BLACK
        ReaderTheme.EINK_HIGH_CONTRAST -> EINK_HIGH_CONTRAST
    }
}

val LocalBanReadPalette = staticCompositionLocalOf { BanReadPalettes.SEPIA_WARM }

private val BanReadShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
)

private val BanReadTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun BanReadTheme(
    readerTheme: ReaderTheme,
    content: @Composable () -> Unit,
) {
    val palette = BanReadPalettes.forTheme(readerTheme)
    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.cinnabar,
            onPrimary = Color(0xFF10141C),
            secondary = palette.pine,
            onSecondary = Color(0xFF10141C),
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceSubtle,
            onSurfaceVariant = palette.ink2,
            outline = palette.lineStrong,
            outlineVariant = palette.line,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.cinnabar,
            onPrimary = Color.White,
            secondary = palette.pine,
            onSecondary = Color.White,
            background = palette.paper,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceSubtle,
            onSurfaceVariant = palette.ink2,
            outline = palette.lineStrong,
            outlineVariant = palette.line,
            error = palette.danger,
        )
    }

    CompositionLocalProvider(LocalBanReadPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = BanReadShapes,
            typography = BanReadTypography,
            content = content,
        )
    }
}

object BanReadTheme {
    val palette: BanReadPalette
        @Composable
        get() = LocalBanReadPalette.current
}

/**
 * Parses a `#RRGGBB` or `#AARRGGBB` hex string safely, falling back to a neutral cloth color.
 */
fun parseHexColorOrFallback(hex: String?, fallback: Color = Color(0xFF2C4056)): Color {
    val raw = hex?.trim()?.removePrefix("#") ?: return fallback
    return runCatching {
        when (raw.length) {
            6 -> Color((0xFF000000L or raw.toLong(16)).toInt())
            8 -> Color(raw.toLong(16).toInt())
            else -> fallback
        }
    }.getOrDefault(fallback)
}
