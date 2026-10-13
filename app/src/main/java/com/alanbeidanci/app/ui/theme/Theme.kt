package com.alanbeidanci.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography

private val LightColors = lightColorScheme(
    primary = AlanPrimary,
    onPrimary = Color.White,
    primaryContainer = AlanPrimarySoft,
    onPrimaryContainer = AlanPrimaryDeep,
    secondary = AlanAmberDeep,
    secondaryContainer = AlanAmberSoft,
    onSecondaryContainer = AlanText,
    background = AlanCanvas,
    onBackground = AlanText,
    surface = AlanSurface,
    onSurface = AlanText,
    surfaceVariant = AlanSurfaceSoft,
    onSurfaceVariant = AlanMuted,
    outline = AlanBorder,
    error = AlanError,
)

private val DarkColors = darkColorScheme(
    primary = AlanDarkPrimary,
    onPrimary = Color(0xFF082D28),
    primaryContainer = AlanDarkPrimarySoft,
    onPrimaryContainer = Color(0xFF91E6D9),
    secondary = AlanAmber,
    secondaryContainer = Color(0xFF3A3422),
    onSecondaryContainer = Color(0xFFF5D98E),
    background = AlanDarkCanvas,
    onBackground = AlanDarkText,
    surface = AlanDarkSurface,
    onSurface = AlanDarkText,
    surfaceVariant = AlanDarkSurfaceSoft,
    onSurfaceVariant = AlanDarkMuted,
    outline = AlanDarkBorder,
    error = AlanDarkError,
)

private val AlanTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
)

private val AlanShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun AlanVocabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AlanTypography,
        shapes = AlanShapes,
        content = content,
    )
}
