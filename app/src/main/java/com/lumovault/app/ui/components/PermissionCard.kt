package com.lumovault.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm

/**
 * One permission, as the app tells it in both of the places it is told: the setup screen that asks, and
 * the settings screen that reports.
 *
 * It was private to onboarding, and the settings screen's first draft grew its own version — a second
 * card with the same three lines in the same order, free to drift into saying something different about
 * the same grant. This is that card, so the two screens cannot disagree about what a permission is
 * called, what colour its answer wears, or where its button goes.
 *
 * The header carries the answer rather than the body, because the answer is what the user came to read:
 * mark, name, and the one word that says whether Android is letting LumoVault in.
 */
@Composable
internal fun PermissionCard(
    title: String,
    description: String,
    icon: ImageVector,
    status: String,
    tone: PillTone,
    actions: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconCircle(imageVector = icon)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = status, tone = tone)
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(SpaceSm)) { actions() }
        }
    }
}

/** A permission card's button: tonal rather than filled, because the card above it already has weight. */
@Composable
internal fun CardAction(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) { Text(label) }
}