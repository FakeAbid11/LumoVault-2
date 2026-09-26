package com.lumovault.app.ui.screens.albums

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.lumovault.app.R
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs

/**
 * The albums' shared look: a cover, and two lines about it on the screen's own background.
 *
 * There used to be a `Surface` behind those two lines, which made every tile a card — a raised, bordered,
 * clickable *thing* — where the screen's job is to show a picture and say what it is called. The picture is
 * the whole tile now, and the label under it takes no colour of its own, so a grid of twelve albums reads as
 * twelve covers rather than twelve boxes.
 *
 * A system album gets an icon because it has no cover of its own — its "contents" are a predicate, and
 * using its newest item as artwork would make the tile change under the user every time a photo arrived,
 * which is a strange thing for a category to do. A user album and a device folder get a real thumbnail,
 * which is what `coverUri` is for: the screen draws a picture whenever the index has one to give it.
 */
@Composable
fun AlbumCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    coverUri: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ShapeCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                coverUri != null -> SubcomposeAsyncImage(
                    model = Uri.parse(coverUri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    error = { AlbumFallback(icon) },
                )

                else -> AlbumFallback(icon)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SpaceXs, end = SpaceXs, top = SpaceSm, bottom = SpaceXs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = LumoVaultType.itemTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = LumoVaultType.sectionDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AlbumFallback(icon: ImageVector?) {
    if (icon == null) {
        // A user album with nothing in it has no thumbnail to draw. Named rather than left blank, because
        // an empty tile is indistinguishable from a picture that failed to load.
        Text(
            text = stringResource(R.string.album_cover_unavailable),
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(SpaceMd),
        )
        return
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        icon.Badge()
    }
}

/**
 * A category's mark, at the size of a category's mark.
 *
 * It was a 56 dp disc on a 150 dp tile — a poster rather than an icon, which made each of the eight system
 * collections a mostly-empty square with something floating in the middle of it. The coin is what keeps it
 * looking deliberate at 40 dp, and it is the same device the empty states use.
 */
@Composable
private fun ImageVector.Badge() {
    Box(
        modifier = Modifier
            .size(BadgeSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = this@Badge,
            contentDescription = null,
            modifier = Modifier.size(BadgeIconSize),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The name a system album is shown by. The three derived-from-the-folder ones are named after the folder
 * they come from rather than after a promise about provenance: "Camera" is `DCIM/Camera`, which is what
 * makes an item belong, and the app does not claim to know anything the path does not say.
 */
@get:StringRes
val SystemAlbum.titleRes: Int
    get() = when (this) {
        SystemAlbum.Camera -> R.string.album_system_camera
        SystemAlbum.Screenshots -> R.string.album_system_screenshots
        SystemAlbum.Downloads -> R.string.album_system_downloads
        SystemAlbum.Videos -> R.string.album_system_videos
        SystemAlbum.Favorites -> R.string.album_system_favorites
        SystemAlbum.Archive -> R.string.album_system_archive
        SystemAlbum.Trash -> R.string.album_system_trash
        SystemAlbum.RecentlyAdded -> R.string.album_system_recently_added
    }

val SystemAlbum.icon: ImageVector
    get() = when (this) {
        SystemAlbum.Camera -> Icons.Filled.PhotoCamera
        SystemAlbum.Screenshots -> Icons.Filled.Screenshot
        SystemAlbum.Downloads -> Icons.Filled.Download
        SystemAlbum.Videos -> Icons.Filled.Movie
        SystemAlbum.Favorites -> Icons.Filled.Favorite
        SystemAlbum.Archive -> Icons.Filled.Archive
        SystemAlbum.Trash -> Icons.Filled.Delete
        SystemAlbum.RecentlyAdded -> Icons.Filled.Schedule
    }

private val ShapeCorner = 12.dp
private val BadgeSize = 40.dp
private val BadgeIconSize = 20.dp
