package com.lumovault.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The reference app's corner radii, hung on Material 3's five shape slots.
 *
 * They map onto the slots exactly, which is why this file is three lines of numbers rather than a new
 * vocabulary: 8 for a chip on a photo, 12 for the tile itself, 16 for an album cover or a text field, 24 for
 * a card, 28 for the navigation capsule, a dialog and a bottom sheet. Screens should read
 * `MaterialTheme.shapes.small` rather than name a radius, so that a component drawn on two screens cannot end
 * up with two corners.
 */
internal val LumoVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
)

/** A circular backing for a single glyph — the scrubber handle aside, this is the only non-rounded shape. */
internal val GlyphCircle = CircleShape
