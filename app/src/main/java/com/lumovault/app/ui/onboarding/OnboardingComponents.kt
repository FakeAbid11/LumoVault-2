package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceXs

/**
 * The pieces every setup screen shares, so the six of them cannot drift apart on the details that make
 * a flow read as one designed thing: the backdrop behind them and the step indicator above the form.
 *
 * The status chip and the icon disc started here too and have moved to `ui/components`: the rest of
 * the app reports states and anchors glyphs the same way, and a vocabulary only works when it is one
 * vocabulary.
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
