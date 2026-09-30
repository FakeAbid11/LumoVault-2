package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs
import com.lumovault.app.ui.theme.SpaceXxs

/**
 * The pieces every setup screen shares, so the six of them cannot drift apart on the details that make
 * a flow read as one designed thing: the backdrop behind them, the step indicator above the form, the
 * status chip, and the disc a leading icon sits in.
 */

/** Opaque top-to-bottom brand tint: scheme colours only, so it holds in both themes and hides no text. */
@Composable
internal fun onboardingBackdrop(): Brush = Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.background,
    ),
)

private val SegmentHeight = 4.dp
private val SegmentCorner = 2.dp

/**
 * One segment per step, filled up to the current one.
 *
 * A fraction of a hairline bar says "somewhere between a third and a half" — six chips say *which* step,
 * and a finished flow shows a full row, which is what the reader of a progress indicator is actually
 * looking for. The count stays beside it for anyone the bar alone does not reach.
 */
@Composable
internal fun SegmentedProgress(
    step: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(SegmentHeight)
                    .background(
                        color = if (index < step) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = RoundedCornerShape(SegmentCorner),
                    ),
            )
        }
        Text(
            text = stringResource(R.string.step_progress, step, totalSteps),
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How a [StatusPill] is coloured, so a skipped step never wears the same chip as a completed one. */
internal enum class PillTone { Done, Skipped, Missing, Unavailable, Neutral }

/**
 * The one-line answer a card or a checklist row gives about itself: "Allowed", "Not set", "Unrestricted".
 *
 * Colour carries half the meaning — the done chip is the scheme's own accent container, an unavailable
 * step the error one — so the icon and the label no longer have to do it alone.
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

/** The disc a leading icon sits in, in the scheme's own container pair. */
@Composable
internal fun IconCircle(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = IconCircleSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(size / 2f),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private val IconCircleSize = 40.dp
