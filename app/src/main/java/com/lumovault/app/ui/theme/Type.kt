package com.lumovault.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material typography, kept as one object so screens never declare their own text styles
 * (PRD section 45: Material typography, hierarchy over decoration).
 */
internal val LumoVaultTypography = Typography()

/**
 * The roles a photo gallery needs and the Material scale does not name.
 *
 * A day header is not a `titleMedium`: at 16 sp regular it is larger than the photos under it and reads as
 * content rather than as a label over content, which is why every section of the timeline looked about as
 * important as the pictures. Colour stays with the scheme — these carry size, weight and tracking only, so a
 * theme change cannot mute a heading and a screen cannot invent a fifth heading size.
 */
internal object LumoVaultType {
    /** A day in the timeline, a section in Settings, the count line above the cloud grid. */
    val sectionHeader: TextStyle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    )

    /** The quiet second line under a heading: item counts, "1.2 GB", "Updated a moment ago". */
    val sectionDetail: TextStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
    )

    /** One tapable thing in a list — an album, a folder, a preference. */
    val itemTitle: TextStyle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    )
}
