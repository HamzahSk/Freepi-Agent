package rofil.movie.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF090B11)
val Surface = Color(0xFF121621)
val SurfaceElevated = Color(0xFF1B2130)
val SurfaceGlass = Color(0xCC1B2130)
val Primary = Color(0xFFE11D48)
val PrimarySoft = Color(0xFF7F1D3A)
val Accent = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF6F6F8)
val TextSecondary = Color(0xFFA0A0B0)
val Outline = Color(0xFF343B4C)
val Success = Color(0xFF22C55E)

private val DarkScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimarySoft,
    onPrimaryContainer = Color.White,
    secondary = Accent,
    onSecondary = Color.Black,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = Color(0xFFFF6B6B),
)

@Composable
fun FreepiTheme(content: @Composable () -> Unit) {
    // The app is a cinema-style dark experience in both system modes.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}
