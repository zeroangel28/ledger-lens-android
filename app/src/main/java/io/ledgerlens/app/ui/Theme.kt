package io.ledgerlens.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val dark = darkColorScheme(primary = Color(0xFF4ED9B1), onPrimary = Color(0xFF07251C), primaryContainer = Color(0xFF173D34), onPrimaryContainer = Color(0xFFD3F9ED), secondary = Color(0xFFB6C9E5), background = Color(0xFF0B1420), onBackground = Color(0xFFF3F6FA), surface = Color(0xFF111F2E), onSurface = Color(0xFFF3F6FA), surfaceVariant = Color(0xFF1A2B3E), onSurfaceVariant = Color(0xFFB7C5D6), outline = Color(0xFF60758E), error = Color(0xFFFFB4AB))
private val light = lightColorScheme(primary = Color(0xFF006B52), onPrimary = Color.White, primaryContainer = Color(0xFFD5F4E8), onPrimaryContainer = Color(0xFF06362A), secondary = Color(0xFF365A80), background = Color(0xFFF5F7FA), onBackground = Color(0xFF111F2E), surface = Color.White, onSurface = Color(0xFF111F2E), surfaceVariant = Color(0xFFE8EEF4), onSurfaceVariant = Color(0xFF42566D), outline = Color(0xFF6A7F96), error = Color(0xFFBA1A1A))
@Composable fun LensTheme(mode: String, content: @Composable () -> Unit) {
    val isDark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val base = if (isDark) dark else light
    val colors = base.copy(
        secondaryContainer = if (isDark) Color(0xFF264D43) else Color(0xFFCCEDE1),
        onSecondaryContainer = if (isDark) Color(0xFFD3F9ED) else Color(0xFF06362A),
        surfaceContainer = if (isDark) Color(0xFF142334) else Color(0xFFE8EEF4),
        surfaceContainerHigh = if (isDark) Color(0xFF1A2B3E) else Color(0xFFE2E9F0)
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp),
        titleLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    ), content = content)
}
