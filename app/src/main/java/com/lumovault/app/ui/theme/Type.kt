package com.lumovault.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material typography, kept as one object so screens never declare their own text styles
 * (PRD section 45: Material typography, hierarchy over decoration).
 *
 * Only the roles this app actually draws on are given values; the rest stay Material's own, because a
 * photo library needs no display sizes and its prose is already at the readable end of the scale. What is
 * set here is the hierarchy, which is the part that was flat: `Typography()` left a top bar and an empty
 * state the same 22 sp regular weight as a paragraph, so nothing on a screen ranked above anything else.
 *
 * Every change is size, weight and tracking. Colour never comes from here — a role that carried a colour
 * could not be themed, which is the same rule `LumoVaultType` below already states for the gallery roles.
 */
internal val LumoVaultTypography = Typography(
    /** A screen's own heading when there is no list to head: an empty state, a setup title. */
    headlineSmall = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    /** Top app bars. Material's default is 22 sp regular, which reads as content rather than as chrome. */
    titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    /** A sheet's title, a card's heading. */
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    /** The emphasised line in a list row. */
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    /** Buttons, including the ones inside dialogs. */
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
    /** Navigation-bar labels, which have to be legible at a glance under four icons. */
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
    /** Counts, timestamps and badges — the quietest text that still has to be read. */
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * The roles a photo gallery needs and the Material scale does not name.
 *
 * A day header is not a `titleMedium`: at 16 sp regular it is larger than the photos under it and reads as
 * content rather than as a label over content, which is why every section of the timeline looked about as
 * important as the pictures. Colour stays with the scheme — these carry size, weight and tracking only, so a
 * theme change cannot mute a heading and a screen cannot invent a fifth heading size.
 */
internal object LumoVaultType {
    /**
     * A day in the timeline, a section in Settings, the count line above the cloud grid.
     *
     * One step above [itemTitle] and no longer the same 15 sp: a heading and the row beneath it at the same
     * size is what made every section of the timeline look about as important as the pictures in it, which
     * is the opposite of the rule this file exists for.
     */
    val sectionHeader: TextStyle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
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

    /**
     * A setup screen's own title.
     *
     * One size above [sectionHeader] because it heads a form rather than a list: `headlineSmall` (21 sp)
     * sat close enough to the body copy that the six onboarding screens read as one flat block of text,
     * which is the same failure the gallery roles above exist to avoid.
     */
    val onboardingTitle: TextStyle = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    )

    /** The welcome tagline — the largest text the app draws, on the one screen that is a picture. */
    val onboardingHero: TextStyle = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    )
}
