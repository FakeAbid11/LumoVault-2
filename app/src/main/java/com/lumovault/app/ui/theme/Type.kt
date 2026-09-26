package com.lumovault.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Flutter app's `TextTheme`, transcribed.
 *
 * `lib/core/theme/app_typography.dart` builds every role by hand rather than accepting Material's defaults,
 * and the difference is the whole reason the two apps read differently: the reference sets **600** on every
 * title and label, gives each role a line height (1.43–1.5, not the platform default) and trims tracking on
 * the large sizes. Compose's stock `Typography()` is 400-weight on titles and 500 on labels, which is why the
 * native screens looked washed out beside it at the same point sizes.
 *
 * Font family is `Roboto`, declared in the Flutter theme without a bundled asset — so it resolves to the
 * platform face there, and to the platform face here. Naming it keeps the two honest about what they render.
 *
 * Colours are deliberately absent: a text style that carries a colour cannot follow the theme, and the roles
 * below are used on surfaces, on containers and over photographs.
 */
internal val LumoVaultTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * The three roles the gallery and the settings screens name for themselves, defined *from* the scale above
 * so a screen never picks a size.
 *
 * A day header is the reference app's `titleMedium` at weight 600 with a count pill beside it; a card title is
 * its `bodyMedium` at 600; a caption is its `bodySmall`. Those are the three the screens ask for, and writing
 * them once here is what stops a fourth screen inventing a fifth heading size — which is exactly what the
 * native app had done before this pass, with a day header at 16 sp regular on one screen and 15 sp medium on
 * another.
 */
internal object LumoVaultType {
    /** A day in the timeline, a section in Settings, the count line above the cloud grid. */
    val sectionHeader: TextStyle
        get() = LumoVaultTypography.titleMedium

    /** The quiet second line under a heading: item counts, "1.2 GB", "Updated a moment ago". */
    val sectionDetail: TextStyle
        get() = LumoVaultTypography.bodySmall

    /** One tapable thing in a list — an album, a folder, a preference. */
    val itemTitle: TextStyle
        get() = LumoVaultTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

    /** The count pill that rides beside a day header, and a badge on a thumbnail. */
    val pillLabel: TextStyle
        get() = LumoVaultTypography.labelSmall
}
