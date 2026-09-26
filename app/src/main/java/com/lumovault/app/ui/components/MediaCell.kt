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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.lumovault.app.ui.theme.FavoriteAccent
import com.lumovault.app.ui.theme.MediaBadgeInset
import com.lumovault.app.ui.theme.MediaGlyphIconSize
import com.lumovault.app.ui.theme.MediaThumbCorner

/**
 * A single grid cell. Reused by the later Albums, Archive, Trash and Cloud screens, which is why
 * it takes a [Media] and knows how to label each type rather than being Photos-specific.
 *
 * Coil sizes the decode to the cell's layout bounds, so a 12-megapixel photo never enters memory to
 * fill a 100dp square; video frames come from TDLib-free `MediaMetadataRetriever` decoding shipped
 * by `coil-video`.
 *
 * The four corners are allocated, not improvised, because a badge that moves between screens is a badge the
 * user has to re-learn: top-start is the file's *journey* (queued, uploading, done, failed — or, when the item
 * is chosen, the tick that chose it), top-end is the user's own mark (a favourite), bottom-start is the type
 * that moves (a video) and bottom-end is text (a duration, a GIF tag). Nothing here is clickable: the whole
 * cell takes the tap and the long press, and a control on top of a photograph that swallows either would make
 * the photo unopenable by half its own area.
 *
 * [status] is the one visible record that a backup was asked for and what became of it. It is drawn small and
 * at the start edge, leaving the duration and GIF badges exactly where they were: the type badge is about the
 * file and the status badge is about its journey, and neither should displace the other.
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
            .clip(RoundedCornerShape(MediaThumbCorner))
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
                shape = RoundedCornerShape(MediaThumbCorner),
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

        // Top-start, in both cases: the tick replaces the status mark rather than sitting beside it, because
        // a cell that is being chosen has no interesting backup state to show at the same moment.
        if (selected) {
            MediaGlyph(
                icon = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.backup_cell_selected),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(MediaBadgeInset),
                iconSize = MediaGlyphIconSize,
            )
        } else {
            BackupStatusBadge(
                status = status,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(MediaBadgeInset),
            )
        }

        if (favorite) {
            MediaGlyph(
                icon = Icons.Filled.Favorite,
                contentDescription = stringResource(R.string.organization_favorite_marked),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MediaBadgeInset),
                iconSize = MediaGlyphIconSize,
                tint = FavoriteAccent,
            )
        }

        when (media.type) {
            MediaType.Video -> {
                MediaGlyph(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(MediaBadgeInset),
                    iconSize = MediaGlyphIconSize,
                )
                media.durationMillis?.let { millis ->
                    MediaPill(
                        text = formatDuration(millis),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(MediaBadgeInset),
                        textAlign = TextAlign.End,
                    )
                }
            }

            // A GIF's tag shares the corner a video's duration uses, and the two cannot both be on one cell.
            MediaType.Gif -> MediaPill(
                text = stringResource(R.string.media_badge_gif),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MediaBadgeInset),
            )

            MediaType.Photo -> Unit
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

    MediaGlyph(
        icon = icon,
        contentDescription = stringResource(label),
        modifier = modifier,
    )
}

@Composable
private fun ThumbnailPlaceholder(icon: ImageVector? = null) {
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
                modifier = Modifier.size(MediaGlyphIconSize),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Thicker than a hairline, because a 1 dp ring on a dark thumbnail is a line the eye reads as an artefact. */
private val SelectionRing = 2.5.dp
