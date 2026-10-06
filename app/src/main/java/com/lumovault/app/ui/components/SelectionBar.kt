package com.lumovault.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs

/**
 * The strip every media selection acts from: one count, then the controls that act on it.
 *
 * The timeline and its album detail each carried their own copy of this surface — same pill, same
 * label style, but different paddings and two different ways to fit a narrow phone. This is the one
 * both draw now (and the one anything that grows the same way later reaches for), so "12 selected"
 * is the same object wherever a selection can happen.
 *
 * The row scrolls rather than compresses, which is the album bar's own reason carried up: Trash plus
 * the organisation marks plus a count and a clear do not fit a narrow phone, and squeezing them
 * shrinks every tap target at once. The count leads and takes no weight, so a scrolling row cannot
 * squeeze it to nothing either — it is the one number the bar exists to print.
 */
@Composable
internal fun SelectionBar(
    label: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = SpaceMd, vertical = SpaceSm)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = SpaceXs),
            )
            actions()
        }
    }
}

/**
 * A labelled icon button, shared by every selection strip.
 *
 * Every one of these actions is undescribed until its content description is read, and a heart with
 * no label leaves the user guessing whether it marks the selection or the cell — so one component
 * decides that once instead of each screen remembering to.
 */
@Composable
internal fun ActionIcon(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = stringResource(label))
    }
}
