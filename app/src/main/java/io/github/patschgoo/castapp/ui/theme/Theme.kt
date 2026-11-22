package io.github.patschgoo.castapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF00629D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE5FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF526070),
    secondaryContainer = Color(0xFFD5E4F7),
    tertiary = Color(0xFF00696E),
    tertiaryContainer = Color(0xFF9CF0F6),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF99CBFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF004A78),
    onPrimaryContainer = Color(0xFFCFE5FF),
    secondary = Color(0xFFB9C8DA),
    secondaryContainer = Color(0xFF3A4857),
    tertiary = Color(0xFF80D4DA),
    tertiaryContainer = Color(0xFF004F53),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
)

@Composable
fun CastAppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
