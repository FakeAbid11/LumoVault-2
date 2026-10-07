package com.lumovault.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXl

/**
 * The app's one overflow sheet: a title, the rows that do not fit on the surface that opened it, and a
 * divider where the rows change subject.
 *
 * It exists because two screens each needed exactly this and only one of them had it. The viewer's own
 * sheet started here — an overflow drawn as a `DropdownMenu` hangs off the top-right corner of a screen
 * held at arm's length, over the very content it acts on, with rows falling off the edge the thumb is not
 * on — and the album header's rename/delete pair needs the same arrival: where the hand already is,
 * full-width rows, one TalkBack node each. A third screen that grows an overflow should reach for this
 * rather than writing a fourth `ModalBottomSheet`.
 *
 * [title] is optional because the surface above a sheet often already names its subject; the viewer's
 * sheet sits over a photograph with nothing to say what it is, so it always passes one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes title: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SpaceXl),
        ) {
            if (title != null) {
                Text(
                    text = stringResource(title),
                    style = LumoVaultType.sectionHeader,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = SpaceXl, vertical = SpaceSm),
                )
            }
            content()
        }
    }
}

/**
 * One row of a [MoreSheet].
 *
 * The glyph carries no content description of its own: it sits beside the words that name the action, so
 * a description would make TalkBack say the row twice — and an icon *without* text beside it is the case
 * accessibility guidance is really about, which this row is not. The row itself is the target, sized to
 * the 48 dp floor rather than to its own contents, because a 24 dp glyph in an 8 dp pad is a tap that misses.
 */
@Composable
internal fun MoreSheetRow(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MoreSheetRowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = SpaceXl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = SpaceMd),
        )
    }
}

/**
 * The separation between a sheet's blocks.
 *
 * It *is* the grouping rather than a heading over each one: two rows that file a photograph and one that
 * hides it need to read as different subjects, and a heading above a two-row block would be taller than
 * the block it names.
 */
@Composable
internal fun MoreSheetDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = SpaceSm))
}

/** A row's own height floor, in dp: the Android touch-target minimum, not the glyph's preference. */
private val MoreSheetRowMinHeight = 48.dp
