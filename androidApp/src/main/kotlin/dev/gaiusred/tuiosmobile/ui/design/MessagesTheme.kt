package dev.gaiusred.tuiosmobile.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val light = lightColorScheme(
    primary = Color(0xFF007AFF), onPrimary = Color.White,
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF111114),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFF2F2F7), onSurfaceVariant = Color(0xFF64646D),
    outlineVariant = Color(0xFFE5E5EA), secondaryContainer = Color(0xFFE9E9EB),
    onSecondaryContainer = Color(0xFF161619),
)
private val dark = darkColorScheme(
    primary = Color(0xFF409CFF), onPrimary = Color.White,
    background = Color(0xFF000000), onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF000000), onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1C1E), onSurfaceVariant = Color(0xFFB0B0B8),
    outlineVariant = Color(0xFF38383A), secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFF5F5F7),
)

@Composable
fun MessagesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) dark else light,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp,
                lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
        ), content = content)
}
