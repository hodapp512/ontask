package dev.ontask.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.ontask.Level

enum class ThemeMode(val label: String) { SYSTEM("Match system"), LIGHT("Light"), DARK("Dark") }

private val Green = Color(0xFF2E9E5B)

val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F7A44), onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEFDB), onPrimaryContainer = Color(0xFF05301A),
    secondary = Color(0xFF4A6355), secondaryContainer = Color(0xFFCDE8D6), onSecondaryContainer = Color(0xFF082015),
    background = Color(0xFFF6F8F6), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFE6EBE6), onSurfaceVariant = Color(0xFF404943), outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9C1), error = Color(0xFFBA1A1A),
)

val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF6FDB9B), onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF00522C), onPrimaryContainer = Color(0xFFCFEFDB),
    secondary = Color(0xFFB1CCBA), secondaryContainer = Color(0xFF334B3C), onSecondaryContainer = Color(0xFFCDE8D6),
    background = Color(0xFF121513), surface = Color(0xFF1A1D1B), onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF2A2F2B), onSurfaceVariant = Color(0xFFBFC9C1), outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943), error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
)

@Composable
fun OnTaskTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, shapes = AppShapes, content = content)
}

fun levelColor(l: Level): Color = when (l) {
    Level.DEEP -> Green
    Level.MIXED -> Color(0xFFE0A526)
    Level.WASTED -> Color(0xFFD64545)
    Level.AWAY -> Color(0xFF9AA5B1)
}
