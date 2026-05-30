package uz.imagesearch.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    secondary = Color(0xFF8C8C8C),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    secondary = Color(0xFFB0B0B0),
    background = Color(0xFF101114),
    surface = Color(0xFF17181C),
)

@Composable
fun ImageSearchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}

