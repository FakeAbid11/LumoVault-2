package com.lumovault.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.ui.theme.LumoVaultTheme
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXl

// Stand-in body for a screen with nothing to show yet.
//
// It used to wrap itself in a `Card`, which is the wrong instrument here: a card says "one item of content,
// tappable, part of a set", and there is no item — the screen is empty, and the only thing worth drawing is
// the reason and the way out. So it is a column on the background now, with the glyph in [IconCircle] to give
// the eye one anchor — the same disc the setup flow's cards and a system album's badge use, because an empty
// state that looks like a different application's empty state is one — and an optional action, because an
// empty state that explains itself without offering a next step is a dead end with better typography.
//
// Every empty, permission and failure state in the app goes through this one component so the same three
// sentences look the same on the timeline, in the cloud and on the map. A message that sits *inside* a live
// screen — under a section heading that already offers the action, or between a list's controls — is not a
// screen's body and does not borrow this framing; it stays the quiet line it is.

/** A screen's whole body: why there is nothing here, and what to do about it. */
@Composable
fun PlaceholderScreen(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconCircle(
            imageVector = icon,
            size = HaloSize,
            glyph = HaloGlyphSize,
        )
        Spacer(Modifier.height(SpaceXl))
        Text(
            text = title,
            // A screen's heading, not a list row's: this is the only text on the screen, and it has to
            // carry the weight of explaining why there is nothing else.
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(SpaceSm))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(SpaceXl))
            action()
        }
        // The column is centred, and a centred block of text sits exactly where the thumb is not. This lifts
        // the whole thing by a header's height so the heading lands in the upper third, which is also where
        // the eye starts a screen.
        Spacer(Modifier.height(SpaceMd))
    }
}

private val HaloSize = 88.dp
// Half the disc at 88 dp would be a 44 dp poster; the halo anchors the screen, it is not the subject.
private val HaloGlyphSize = 40.dp

@Preview(name = "Dark")
@Composable
private fun PlaceholderScreenDarkPreview() {
    LumoVaultTheme(mode = ThemeMode.Dark) {
        PlaceholderScreen(
            title = "No photos yet",
            description = "Photos you take with your camera will appear here.",
            icon = Icons.Filled.Photo,
            action = { Button(onClick = {}) { Text("Continue") } },
        )
    }
}

@Preview(name = "Light")
@Composable
private fun PlaceholderScreenLightPreview() {
    LumoVaultTheme(mode = ThemeMode.Light) {
        PlaceholderScreen(
            title = "Cloud library",
            description = "Back up your photos to your own Telegram channel.",
            icon = Icons.Filled.Cloud,
        )
    }
}
