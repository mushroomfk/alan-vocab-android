package com.alanbeidanci.app.ui.theme

import androidx.compose.ui.graphics.Color

val AlanCanvas = Color(0xFFF2F1EC)
val AlanSidebar = Color(0xFFEBEAE4)
val AlanSurface = Color(0xFFFFFEFA)
val AlanSurfaceSoft = Color(0xFFF5F5F0)
val AlanText = Color(0xFF22302B)
val AlanMuted = Color(0xFF6F7B76)
val AlanBorder = Color(0xFFDDE1DC)
val AlanPrimary = Color(0xFF148B80)
val AlanPrimaryDeep = Color(0xFF0A6D65)
val AlanPrimarySoft = Color(0xFFDFF2ED)
val AlanAmber = Color(0xFFF3C75B)
val AlanAmberDeep = Color(0xFF9A6900)
val AlanAmberSoft = Color(0xFFFFF2CB)
val AlanHero = Color(0xFF17463F)

val AlanDarkCanvas = Color(0xFF101917)
val AlanDarkSurface = Color(0xFF192622)
val AlanDarkSurfaceSoft = Color(0xFF202F2B)
val AlanDarkText = Color(0xFFEDF5F1)
val AlanDarkMuted = Color(0xFF9AA9A3)
val AlanDarkBorder = Color(0xFF2A3B36)
val AlanDarkPrimary = Color(0xFF46BAAA)
val AlanDarkPrimarySoft = Color(0xFF1D3D37)

val AlanError = Color(0xFFE5484D)
val AlanDarkError = Color(0xFFFF8A80)
val AlanSuccess = Color(0xFF689F38)
val AlanDarkSuccess = Color(0xFF9CCC65)
val AlanSuccessBg = Color(0xFFEAF5DC)
val AlanDarkSuccessBg = Color(0xFF24301A)

val AlanMeterLow = Color(0xFF9AA8A2)
val AlanMeterMid = Color(0xFF9A6900)
val AlanMeterHigh = Color(0xFF0B8379)
val AlanDarkMeterLow = Color(0xFF5F6F69)
val AlanDarkMeterMid = Color(0xFFF3C75B)
val AlanDarkMeterHigh = Color(0xFF46BAAA)

fun progressMeterColor(ratio: Float, dark: Boolean): Color {
    val level = when {
        ratio >= 0.7f -> 2
        ratio >= 0.4f -> 1
        else -> 0
    }
    return when {
        dark && level == 2 -> AlanDarkMeterHigh
        dark && level == 1 -> AlanDarkMeterMid
        dark -> AlanDarkMeterLow
        level == 2 -> AlanMeterHigh
        level == 1 -> AlanMeterMid
        else -> AlanMeterLow
    }
}

