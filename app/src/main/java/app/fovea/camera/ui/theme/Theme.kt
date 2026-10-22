package app.fovea.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Fovea {
    val Black = Color(0xFF0B0B0C)
    val Graphite = Color(0xFF151618)
    val Surface2 = Color(0xFF1F2124)
    val Line = Color(0xFF2C2F33)
    val Text = Color(0xFFF2F2F0)
    val Muted = Color(0xFF8A8F96)
    val Accent = Color(0xFFB6F23C)
    val Danger = Color(0xFFE5484D)

    val ScreenPadding = 16.dp
    val TouchTarget = 48.dp
    val ShutterSize = 78.dp
}

private val FoveaType = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun FoveaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fovea.Accent, onPrimary = Fovea.Black,
            background = Fovea.Black, onBackground = Fovea.Text,
            surface = Fovea.Graphite, onSurface = Fovea.Text,
            surfaceVariant = Fovea.Surface2, onSurfaceVariant = Fovea.Muted,
            outline = Fovea.Line, error = Fovea.Danger
        ),
        typography = FoveaType,
        content = content
    )
}
