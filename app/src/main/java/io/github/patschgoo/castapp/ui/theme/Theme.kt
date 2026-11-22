package io.github.patschgoo.castapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF4C1D95),
    secondary = Color(0xFF52525B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F0F4),
    onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFF0F766E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFAFAFB),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF1F0F4),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFF8E8E99),
    outlineVariant = Color(0xFFE4E4E7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF4F3F7),
    surfaceContainerHigh = Color(0xFFF1F0F4),
    surfaceContainerHighest = Color(0xFFEBEAF0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1A1033),
    primaryContainer = Color(0xFF2A2440),
    onPrimaryContainer = Color(0xFFDDD6FE),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF262430),
    onSecondaryContainer = Color(0xFFF4F4F5),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0F0E13),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF0F0E13),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF262430),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF71717A),
    outlineVariant = Color(0xFF34313F),
    surfaceContainerLowest = Color(0xFF0B0A0E),
    surfaceContainerLow = Color(0xFF1B1A22),
    surfaceContainer = Color(0xFF1F1E27),
    surfaceContainerHigh = Color(0xFF262430),
    surfaceContainerHighest = Color(0xFF2E2C39),
)

/**
 * The colors of the main screen that Material's scheme has no slot for: the tinted
 * source tiles, and the dark bar that shows what is being cast.
 */
@Immutable
data class CastColors(
    /** Backgrounds of the source tiles, in order; text on them is the scheme's `onSurface`. */
    val tints: List<Color>,
    /** The small square behind a tile's icon. */
    val tileChip: Color,
    /** A plain card on the screen background (the "Cast to" row, round buttons). */
    val card: Color,
    val bar: Color,
    val onBar: Color,
    val barMuted: Color,
    val barButton: Color,
    val barAccent: Color,
    /** "Ready", "Casting". */
    val ok: Color,
)

private val LightCast = CastColors(
    tints = listOf(
        Color(0xFFEDE9FE), Color(0xFFDBEAFE), Color(0xFFFFE4D6), Color(0xFFD1FAE5),
        Color(0xFFFEF3C7), Color(0xFFFCE7F3), Color(0xFFE0F2FE), Color(0xFFE5E7EB),
    ),
    tileChip = Color.White,
    card = Color.White,
    bar = Color(0xFF18181B),
    onBar = Color.White,
    barMuted = Color(0xFFA1A1AA),
    barButton = Color(0xFF2E2A3D),
    barAccent = Color(0xFFC4B5FD),
    ok = Color(0xFF15803D),
)

private val DarkCast = CastColors(
    tints = listOf(
        Color(0xFF2A2440), Color(0xFF1C2A44), Color(0xFF3D261C), Color(0xFF163328),
        Color(0xFF3A3218), Color(0xFF3B1C2E), Color(0xFF16303F), Color(0xFF262B2E),
    ),
    tileChip = Color(0xFF0F0E13),
    card = Color(0xFF1B1A22),
    bar = Color(0xFF262433),
    onBar = Color(0xFFF4F4F5),
    barMuted = Color(0xFFA1A1AA),
    barButton = Color(0xFF34304A),
    barAccent = Color(0xFFC4B5FD),
    ok = Color(0xFF4ADE80),
)

val LocalCastColors = staticCompositionLocalOf { LightCast }

/** Plus Jakarta Sans (SIL Open Font License, see assets/licenses), bundled so the app looks the same on every phone. */
private val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

/** Material's type scale, unchanged except for the font. */
private val AppTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Jakarta),
        displayMedium = displayMedium.copy(fontFamily = Jakarta),
        displaySmall = displaySmall.copy(fontFamily = Jakarta),
        headlineLarge = headlineLarge.copy(fontFamily = Jakarta),
        headlineMedium = headlineMedium.copy(fontFamily = Jakarta),
        headlineSmall = headlineSmall.copy(fontFamily = Jakarta),
        titleLarge = titleLarge.copy(fontFamily = Jakarta),
        titleMedium = titleMedium.copy(fontFamily = Jakarta),
        titleSmall = titleSmall.copy(fontFamily = Jakarta),
        bodyLarge = bodyLarge.copy(fontFamily = Jakarta),
        bodyMedium = bodyMedium.copy(fontFamily = Jakarta),
        bodySmall = bodySmall.copy(fontFamily = Jakarta),
        labelLarge = labelLarge.copy(fontFamily = Jakarta),
        labelMedium = labelMedium.copy(fontFamily = Jakarta),
        labelSmall = labelSmall.copy(fontFamily = Jakarta),
    )
}

/** Whether the app should be dark for [mode] right now. */
@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * CastApp's own violet theme with its own font. It follows the system's light/dark setting
 * by default (or the choice in Settings); it looks the same on every Android version.
 */
@Composable
fun CastAppTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(mode)
    CompositionLocalProvider(LocalCastColors provides if (dark) DarkCast else LightCast) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}
