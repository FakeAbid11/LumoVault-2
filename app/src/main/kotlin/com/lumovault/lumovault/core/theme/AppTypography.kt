package com.lumovault.lumovault.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * LumoVault typography scale.
 *
 * Ported from lib/core/theme/app_typography.dart. Roboto, re-weighted off the
 * stock Material 3 scale for stronger hierarchy: display/headline run heavier
 * and tighter, titles/labels step up to semibold, body stays w400 with slightly
 * tightened tracking. Line heights are unchanged from M3.
 *
 * Compose's [Typography] is a plain holder; the weights and tracking here are
 * the port of the Dart `TextStyle` constants.
 */
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 57.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.0).sp,
        lineHeight = (57 * 1.12f).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 45.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        lineHeight = (45 * 1.16f).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 36.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
        lineHeight = (36 * 1.22f).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
        lineHeight = (32 * 1.25f).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
        lineHeight = (28 * 1.29f).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        lineHeight = (24 * 1.33f).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        lineHeight = (22 * 1.27f).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
        lineHeight = (16 * 1.5f).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
        lineHeight = (14 * 1.43f).sp,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
        lineHeight = (14 * 1.43f).sp,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        lineHeight = (12 * 1.33f).sp,
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        lineHeight = (11 * 1.45f).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.15.sp,
        lineHeight = (16 * 1.5f).sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
        lineHeight = (14 * 1.43f).sp,
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.3.sp,
        lineHeight = (12 * 1.33f).sp,
    ),
)
