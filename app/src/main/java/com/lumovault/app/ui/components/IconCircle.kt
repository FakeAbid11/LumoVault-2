package com.lumovault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's one "glyph in a tinted disc" device, shared wherever a mark needs an anchor: the setup
 * flow's permission cards, an empty state's halo, a system album's badge.
 *
 * It existed three times over before this, in three palettes — the setup cards' `primaryContainer`
 * coin, the empty-state halo's neutral `surfaceContainer`, the album badge's `surfaceContainerHigh` —
 * each claiming in a comment to be the same device as the others while wearing a different colour.
 * One component with one palette settles that: the scheme's own container pair, which is also the
 * only combination PRD section 44 guarantees clears contrast in both themes.
 *
 * [glyph] defaults to half the disc; the empty-state halo passes a smaller glyph deliberately,
 * because at 88 dp a half-size icon reads as a poster rather than a mark — single-surface geometry,
 * in the sense PRD section 46 allows when the reason is said.
 */
@Composable
internal fun IconCircle(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = IconCircleSize,
    glyph: Dp = size / 2f,
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
            modifier = Modifier.size(glyph),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** A row's or card's own mark, at the size a row can spare. */
private val IconCircleSize = 40.dp
