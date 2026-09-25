package com.lumovault.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import com.lumovault.app.ui.screens.photos.BackupCellStatus
import com.lumovault.app.util.formatDuration

/**
 * A single grid cell. Reused by the later Albums, Archive, Trash and Cloud screens, which is why
 * it takes a [Media] and knows how to label each type rather than being Photos-specific.
 *
 * Coil sizes the decode to the cell's layout bounds, so a 12-megapixel photo never enters memory to
 * fill a 100dp square; video frames come from TDLib-free `MediaMetadataRetriever` decoding shipped
 * by `coil-video`.
 *
 * [status] is the one visible record that a backup was asked for and what became of it. It is drawn
 * small and at the start edge, leaving the duration and GIF badges exactly where they were: the type
 * badge is about the file and the status badge is about its journey, and neither should displace the
 * other.
 */
@Composable
fun MediaCell(
    media: Media,
    modifier: Modifier = Modifier,
    status: BackupCellStatus = BackupCellStatus.Unbacked,
    selected: Boolean = false,
    favorite: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val description = when (media.type) {
        MediaType.Photo -> stringResource(R.string.media_kind_photo)
        MediaType.Video -> stringResource(R.string.media_kind_video)
        MediaType.Gif -> stringResource(R.string.media_kind_gif)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ShapeCorner))
            .then(
                // The modifiers are built conditionally rather than passing null lambdas: a
                // `combinedClickable` with a null long-press still swallows the tap.
                if (onClick == null && onLongClick == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                },
            )
            .border(
                width = if (selected) SelectionRing else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(ShapeCorner),
            ),
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

        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.backup_cell_selected),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ShapeInset)
                    .size(IconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            BackupStatusBadge(
                status = status,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ShapeInset)
                    .size(IconSize),
            )
        }

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
                    // Bottom edge, because the favourite mark owns the top-right corner: a GIF that is
                    // also a favourite has to show both, and the type badge has nowhere else to crowd.
                    .align(Alignment.BottomEnd)
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

        if (favorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = stringResource(R.string.organization_favorite_marked),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(ShapeInset)
                    .size(IconSize),
                tint = FavoriteTint,
            )
        }
    }
}

/**
 * The four marks PRD section 48 maps onto thumbnails, plus two more the queue genuinely distinguishes.
 *
 * [BackupCellStatus.Unbacked] draws nothing at all. Every item in a library starts un-backuped, so a
 * glyph on all of them would not be information — it would be the grid covered in cloud icons until the
 * few that matter stopped standing out. What is drawn instead is the exception: queued, going, done,
 * failed.
 */
@Composable
private fun BackupStatusBadge(status: BackupCellStatus, modifier: Modifier = Modifier) {
    val (icon, label) = when (status) {
        BackupCellStatus.Unbacked -> return
        BackupCellStatus.Queued -> Icons.Filled.CloudQueue to R.string.backup_state_queued
        BackupCellStatus.Preparing -> Icons.Filled.HourglassBottom to R.string.backup_preparing
        BackupCellStatus.Uploading -> Icons.Filled.ArrowUpward to R.string.backup_state_uploading
        BackupCellStatus.BackedUp -> Icons.Filled.CloudDone to R.string.backup_state_backed_up
        BackupCellStatus.Failed -> Icons.Filled.Refresh to R.string.backup_state_failed
    }

    // A scrim behind the glyph, as the duration badge uses: a thumbnail is arbitrary content and a
    // thin white icon vanishes against a bright sky.
    Box(
        modifier = modifier.background(BadgeScrim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(label),
            modifier = Modifier.padding(2.dp),
            tint = OnMediaScrim,
        )
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
private val SelectionRing = 2.dp
private val BadgeScrim = Color(0xB3000000)
private val OnMediaScrim = Color(0xFFFFFFFF)

/** Not the theme's primary: a heart in the accent colour would compete with the selection ring for "this one is special". */
private val FavoriteTint = Color(0xFFFF5A6E)
