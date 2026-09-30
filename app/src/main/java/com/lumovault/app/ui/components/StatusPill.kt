package com.lumovault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXxs

/**
 * The app's one status chip, shared by the setup flow and every screen that reports a state.
 *
 * It began life in onboarding, where a checklist had to distinguish "done" from "skipped" from "not
 * available" at a glance — PRD section 48's rule that internal states are mapped into simple
 * user-facing ones, and section 53's requirement that "backed up" and "exists remotely, unverified"
 * are never the same mark. The screens that used to say these states as raw words (diagnostics
 * values, health counts, cloud notices) draw the same chip now, so one word never means two things
 * depending on which screen it appeared on.
 *
 * Colour carries half the meaning, so the label and any icon do not have to do it alone — and a tone
 * is only ever assigned from a fact the state already holds; a pill never claims more than the text
 * it replaces did.
 */

/** How a [StatusPill] is coloured, so a skipped step never wears the same chip as a completed one. */
internal enum class PillTone { Done, Skipped, Missing, Unavailable, Neutral }

/**
 * The one-line answer a card or a checklist row gives about itself: "Allowed", "Not set", "Unrestricted".
 */
@Composable
internal fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val colors = MaterialTheme.colorScheme
    val container = when (tone) {
        PillTone.Done -> colors.primaryContainer
        PillTone.Skipped, PillTone.Neutral -> colors.surfaceContainerHighest
        PillTone.Missing -> Color.Transparent
        PillTone.Unavailable -> colors.errorContainer
    }
    val content = when (tone) {
        PillTone.Done -> colors.onPrimaryContainer
        PillTone.Skipped, PillTone.Neutral, PillTone.Missing -> colors.onSurfaceVariant
        PillTone.Unavailable -> colors.onErrorContainer
    }
    Box(
        modifier = modifier
            .background(container, shape)
            .then(
                if (tone == PillTone.Missing) {
                    Modifier.border(1.dp, colors.outlineVariant, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = SpaceSm, vertical = SpaceXxs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}
