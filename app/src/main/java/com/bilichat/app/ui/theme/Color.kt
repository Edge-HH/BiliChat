package com.bilichat.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 无法动态取色（Android 12 以下或已关闭）时使用的配色，以 B 站粉 #FB7299 为种子色。

internal val BiliLightColors = lightColorScheme(
    primary = Color(0xFFA0335B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3F001A),
    secondary = Color(0xFF75565E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E1),
    onSecondaryContainer = Color(0xFF2B151B),
    tertiary = Color(0xFF7B5733),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF22191B),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF22191B),
    surfaceVariant = Color(0xFFF3DDE1),
    onSurfaceVariant = Color(0xFF524346),
    outline = Color(0xFF847376),
    outlineVariant = Color(0xFFD6C2C5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F2),
    surfaceContainer = Color(0xFFFBEAEC),
    surfaceContainerHigh = Color(0xFFF5E4E6),
    surfaceContainerHighest = Color(0xFFEFDFE1),
)

internal val BiliDarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C5),
    onPrimary = Color(0xFF620F2F),
    primaryContainer = Color(0xFF811D44),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFE3BDC4),
    onSecondary = Color(0xFF43292F),
    secondaryContainer = Color(0xFF5B3F45),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFFEEBD92),
    onTertiary = Color(0xFF472A0A),
    tertiaryContainer = Color(0xFF61401E),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF191113),
    onBackground = Color(0xFFEFDFE1),
    surface = Color(0xFF191113),
    onSurface = Color(0xFFEFDFE1),
    surfaceVariant = Color(0xFF524346),
    onSurfaceVariant = Color(0xFFD6C2C5),
    outline = Color(0xFF9F8C8F),
    outlineVariant = Color(0xFF524346),
    surfaceContainerLowest = Color(0xFF140C0E),
    surfaceContainerLow = Color(0xFF22191B),
    surfaceContainer = Color(0xFF261D1F),
    surfaceContainerHigh = Color(0xFF312829),
    surfaceContainerHighest = Color(0xFF3C3234),
)
