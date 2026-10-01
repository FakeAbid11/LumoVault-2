package com.lumovault.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.RingStrokeBold
import com.lumovault.app.ui.theme.RingWaiting
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceXl

// The body a screen draws while work it can name is running: the ring, the step, the count.
//
// The timeline's scan and the cloud's prepare/scan steps each held a private copy of this column —
// same three children, same spacing — and the copies had already drifted where drift goes
// unnoticed: the count line sat at `bodyMedium` on one screen and [LumoVaultType.sectionDetail] on
// the other, so the same kind of moment read slightly differently depending on the tab. One component
// now states the shape, and the type file's own words pick the styles: sectionHeader is "a day in the
// timeline, a section in Settings, the count line above the cloud grid", sectionDetail is the quiet
// second line under a heading — which is exactly what an item count is.
//
// The number is the reassuring part: a count that goes up proves work a ring alone cannot. A spinner
// cannot tell a first run from a stall, and a percentage would be a lie, because a media scan cannot
// know its total before it has finished — which is why the parameter is `detail`, a fact the caller
// actually holds, and not a progress value the component would have to invent.

/** The body of a screen while a named step runs: what it is doing, and how far it has come. */
@Composable
internal fun WorkingScreen(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceMd, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(RingWaiting),
            strokeWidth = RingStrokeBold,
        )
        Text(text = title, style = LumoVaultType.sectionHeader)
        Text(
            text = detail,
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
