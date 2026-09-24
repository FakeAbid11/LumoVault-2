package com.lumovault.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.lumovault.app.R
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.util.formatDuration

/**
 * A single grid cell. Reused by the later Albums, Archive, Trash and Cloud screens, which is why
 * it takes a [Media] and knows how to label each type rather than being Photos-specific.
 *
 * Coil sizes the decode to the cell's layout bounds, so a 12-megapixel photo never enters memory to
 * fill a 100dp square; video frames come from TDLib-free `MediaMetadataRetriever` decoding shipped
 * by `coil-video`.
 */
@Composable
fun MediaCell(
    media: Media,
    modifier: Modifier = Modifier,
) {
    val description = when (media.type) {
        MediaType.Photo -> stringResource(R.string.media_kind_photo)
        MediaType.Video -> stringResource(R.string.media_kind_video)
        MediaType.Gif -> stringResource(R.string.media_kind_gif)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ShapeCorner)),
    ) {
        SubcomposeAsyncImage(
            model = Uri.parse(media.contentUri),
            // The date is deliberately absent: every cell is announced under the day header that
            // already states it, so repeating it per cell would be ten thousand times per screen
            // reader pass. The filename is personal data and is not used as a label either.
            contentDescription = description,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            loading = { ThumbnailPlaceholder() },
            error = { ThumbnailPlaceholder(icon = Icons.Filled.BrokenImage) },
        )

        when (media.type) {
            MediaType.Video -> {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(ShapeInset)
                        .size(IconSize),
                    tint = OnMediaScrim,
                )
                if (media.durationMillis != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(ShapeInset),
                        shape = RoundedCornerShape(SmallCorner),
                        color = BadgeScrim,
                        contentColor = OnMediaScrim,
                    ) {
                        Text(
                            text = formatDuration(media.durationMillis),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = BadgePadding, vertical = 1.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }

            MediaType.Gif -> Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(ShapeInset),
                shape = RoundedCornerShape(SmallCorner),
                color = BadgeScrim,
                contentColor = OnMediaScrim,
            ) {
                Text(
                    text = stringResource(R.string.media_badge_gif),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = BadgePadding, vertical = 1.dp),
                )
            }

            MediaType.Photo -> Unit
        }
    }
}

@Composable
private fun ThumbnailPlaceholder(icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private val ShapeCorner = 4.dp
private val ShapeInset = 6.dp
private val SmallCorner = 3.dp
private val IconSize = 18.dp
private val BadgePadding = 4.dp
private val BadgeScrim = Color(0xB3000000)
private val OnMediaScrim = Color(0xFFFFFFFF)
