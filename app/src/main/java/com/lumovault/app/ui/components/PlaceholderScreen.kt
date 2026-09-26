package com.lumovault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
// the reason and the way out. So it is a column on the background now, with the glyph in a tonal disc to give
// the eye one anchor, and an optional action, because an empty state that explains itself without offering a
// next step is a dead end with better typography.
//
// Every empty, permission and failure state in the app goes through this one component so the same three
// sentences look the same on the timeline, in the cloud and on the map.

/** A screen's whole body: why there is nothing here, and what to do about it. */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(HaloSize)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(HaloIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(SpaceXl))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(SpaceSm))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
private val HaloIconSize = 40.dp

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
