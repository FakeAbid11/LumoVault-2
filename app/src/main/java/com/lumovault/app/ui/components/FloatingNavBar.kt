package com.lumovault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumovault.app.ui.navigation.LumoVaultDestination
import com.lumovault.app.ui.navigation.icon
import com.lumovault.app.ui.navigation.label
import com.lumovault.app.ui.theme.NavCapsuleCorner
import com.lumovault.app.ui.theme.NavHeight
import com.lumovault.app.ui.theme.NavIconSize
import com.lumovault.app.ui.theme.NavMargin
import com.lumovault.app.ui.theme.SpaceXs

/**
 * The floating navigation capsule from the reference app's shell (`app_shell.dart`).
 *
 * It is a `Surface` with a 28 dp corner and a 16 dp margin on three sides, holding a 64 dp row — not a
 * Material `NavigationBar`, because that composable fixes its own height at 80 dp and its own window insets,
 * and the reference's proportions (a capsule, not a bar pinned to the glass) are the thing being matched.
 * Writing the row is forty lines and buys the exact shape, the exact height and the exact selected state.
 *
 * Two details carry most of the resemblance. Only the **selected** item shows its label: five labels in a
 * 64 dp capsule is a wall of text, and the reference hides all but one, which makes the pill read as a
 * pointer rather than a legend. And the indicator is a **stadium** in `secondaryContainer` behind the icon —
 * not a circle, and not the theme's `primary`, because a filled accent shape at the bottom of the screen
 * competes with the accent everywhere else on it.
 */
@Composable
fun FloatingNavBar(
    selected: LumoVaultDestination,
    onSelect: (LumoVaultDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NavMargin)
            .padding(bottom = NavMargin),
        shape = RoundedCornerShape(NavCapsuleCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = NavShadow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NavHeight)
                .padding(horizontal = SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LumoVaultDestination.entries.forEach { destination ->
                NavItem(
                    destination = destination,
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    destination: LumoVaultDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val label = destination.label()

    Column(
        modifier = modifier
            .widthIn(min = MinItemWidth)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
            )
            .padding(vertical = SpaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceXs, Alignment.CenterVertically),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) {
                // The stadium is drawn behind the icon rather than around the whole item, which is what keeps
                // a four-item capsule from looking like four buttons.
                Box(
                    modifier = Modifier
                        .size(IndicatorSize)
                        .background(scheme.secondaryContainer, RoundedCornerShape(IndicatorSize)),
                )
            }
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                modifier = Modifier.size(NavIconSize),
                tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
        }

        // Unselected items reserve nothing for their label: the row is 64 dp tall and the icon is centred, so
        // an empty line here would push the selected item's icon out of alignment with its neighbours.
        if (selected) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val NavShadow = 8.dp
private val IndicatorSize = 56.dp
private val MinItemWidth = 48.dp
