package com.lumovault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumovault.app.ui.theme.MediaBadgeCorner
import com.lumovault.app.ui.theme.MediaBadgePadding
import com.lumovault.app.ui.theme.MediaBadgeScrim
import com.lumovault.app.ui.theme.MediaGlyphIconSize
import com.lumovault.app.ui.theme.MediaGlyphScrim
import com.lumovault.app.ui.theme.MediaGlyphSize
import com.lumovault.app.ui.theme.OnMedia

// The two marks that sit on top of a photograph, in one place.
//
// They live here because the timeline, the cloud grid and the album covers all draw them and had three
// slightly different versions: a text pill in one file, the same pill hand-rolled in another, and glyphs with
// no backing at all. A glyph with no backing is the bug worth naming — a white play triangle on a snow scene,
// a white heart on a wedding dress — and the mark that is supposed to say "this is a video" disappears exactly
// when the photo is bright, which is most of them. So every mark on a thumbnail gets a scrim by construction,
// and a screen cannot forget to add one. Neither one takes a click: a badge that swallowed the tap would make
// the cell it decorates unopenable.

/** A small dark disc with a glyph in it: the video mark, the favourite heart, a backup or cloud state. */
@Composable
fun MediaGlyph(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = MediaGlyphIconSize,
    tint: Color = OnMedia,
) {
    Box(
        modifier = modifier
            .size(MediaGlyphSize)
            .clip(CircleShape)
            .background(MediaGlyphScrim),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

/** A short piece of text — a duration, "GIF" — on the same kind of backing, squared off so it reads as a tag. */
@Composable
fun MediaPill(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MediaBadgeCorner))
            .background(MediaBadgeScrim),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = OnMedia,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
            modifier = Modifier.padding(horizontal = MediaBadgePadding, vertical = 1.dp),
        )
    }
}
