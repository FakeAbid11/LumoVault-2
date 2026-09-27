package com.lumovault.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The corner ramp, so a card, a sheet and a menu agree without each screen deciding for itself.
 *
 * Before this, [androidx.compose.material3.MaterialTheme] was handed a colour scheme and a type scale only,
 * so every component fell back to Material 3's own defaults while the screens that cared passed an explicit
 * `RoundedCornerShape` — six of them a literal number, others the named values in `Dimensions.kt`. The
 * result was two corner radii for one idea of "a panel", which is the kind of inconsistency that reads as
 * unpolished long before anyone can say why.
 *
 * The ramp is anchored on what the app already drew: 4 dp for a thumbnail or a chip, 12 dp for the cards
 * (`GroupCardCorner`), and the steps either side for the larger surfaces — so no screen's existing geometry
 * moves. What changes is the default a component gets when nobody thinks about it.
 *
 * Material 3's own names are used rather than the app's, because that is what a component reads: a
 * `Card` asks for `shapes.medium`, and a menu for `extraSmall`.
 */
internal val LumoVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(GroupCardCorner),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
