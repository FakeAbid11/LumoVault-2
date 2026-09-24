package com.lumovault.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Card
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

/**
 * Stand-in body for the Phase 1 destinations. Real library content replaces this per screen in
 * Phases 3, 4, 7 and 8; the shell above it is the part that stays.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(name = "Dark")
@Composable
private fun PlaceholderScreenDarkPreview() {
    LumoVaultTheme(mode = ThemeMode.Dark) {
        PlaceholderScreen(
            title = "Photos",
            description = "Local photo library will appear here.",
            icon = Icons.Filled.Photo,
        )
    }
}

@Preview(name = "Light")
@Composable
private fun PlaceholderScreenLightPreview() {
    LumoVaultTheme(mode = ThemeMode.Light) {
        PlaceholderScreen(
            title = "Cloud",
            description = "Cloud library will appear here.",
            icon = Icons.Filled.Cloud,
        )
    }
}
