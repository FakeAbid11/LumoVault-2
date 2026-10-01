package com.lumovault.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lumovault.app.ui.theme.SpaceMd

// One small sentence on a raised strip, beside the content it qualifies.
//
// It used to be drawn twice, privately: the album detail carried a "deletion is not supported here"
// strip over its grid, and the map carried another for its three top notices — the same anatomy, a
// medium-shaped surface with one body-small line in it, that had drifted apart in colour and padding,
// so the same *kind* of sentence sat at two different quietnesses depending on the screen (one tier
// apart in the light ramp, invisible in the dark). One component carries both now, in the tone several
// of the app's notices already share: `surfaceVariant` with its `onSurfaceVariant` text, the pair the
// limited-access photo strip and the non-urgent backup stop card wear. The map's notices take that
// tone with them — a deliberate unification, since a notice is a notice on every screen.
//
// It is not the shell's snackbar (that reports what *just happened*, after the fact), and not a
// [PlaceholderScreen] (that is a whole screen's body — an excuse, not an aside). This one is for
// something true *while* the screen continues underneath it. The caller owns where it sits: the album
// lays it across its grid under the header, the map floats it over the tiles.

/** One small sentence on a raised strip, inside a screen that keeps running. */
@Composable
internal fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(SpaceMd),
        )
    }
}
