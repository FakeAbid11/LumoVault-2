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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BrokenImage
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
import com.lumovault.app.ui.components.IconCircle
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs
import com.lumovault.app.ui.theme.SpaceXxs

/**
 * The albums' shared look: a cover, and two lines about it on the screen's own background.
 *
 * There used to be a `Surface` behind those two lines, which made every tile a card — a raised, bordered,
 * clickable *thing* — where the screen's job is to show a picture and say what it is called. The picture is
 * the whole tile now, and the label under it takes no colour of its own, so a grid of twelve albums reads as
 * twelve covers rather than twelve boxes. The label is inset from neither side, so a title's first letter
 * sits over the cover's left edge rather than four pixels right of it — the alignment `ScreenEdge` exists to
 * guarantee everywhere else.
 *
 * A system album gets an icon because it has no cover of its own — its "contents" are a predicate, and
 * using its newest item as artwork would make the tile change under the user every time a photo arrived,
 * which is a strange thing for a category to do. A user album and a device folder get a real thumbnail,
 * which is what `coverUri` is for: the screen draws a picture whenever the index has one to give it.
 *
 * [hasMedia] is what keeps the fallback honest. A tile with no picture and nothing in it may say so; a tile
 * with a thousand photographs whose cover was deleted out from under it may not — "Empty album" under a
 * count of 1,024 is a claim about the album that is simply false, and it was the claim this placeholder
 * used to make in exactly that case.
 */
@Composable
fun AlbumCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    coverUri: String? = null,
    icon: ImageVector? = null,
    hasMedia: Boolean = true,
    onClick: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                coverUri != null -> SubcomposeAsyncImage(
                    model = Uri.parse(coverUri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    // No `loading` slot on purpose. The Box behind it is already filled with the scheme's
                    // surfaceVariant, which is exactly what MediaCell's and the map strip's loading slots
                    // draw, so a cover mid-decode looks the same as every other thumbnail mid-decode.
                    // Adding a spinner here would make album covers the one image in the app that says
                    // "wait" rather than simply being quiet.
                    error = { AlbumFallback(icon, hasMedia) },
                )

                else -> AlbumFallback(icon, hasMedia)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SpaceSm, bottom = SpaceXs),
            verticalArrangement = Arrangement.spacedBy(SpaceXxs),
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

/**
 * Which of the three cover states a tile with no picture should draw.
 *
 * Named rather than inlined in [AlbumFallback] because "a cover-less album with media in it must not be
 * called empty" is a product rule, not a rendering detail — and the `when` that used to hold it was only
 * reachable by composing the tile.
 */
internal enum class AlbumCoverFallback {
    /** A system album: a category's mark, not a photograph. */
    Badge,

    /** An album with nothing in it. The only state that may say "Empty album". */
    Empty,

    /** An album with photographs whose cover is missing or failed to decode. */
    Missing,
}

/**
 * The whole of [AlbumFallback]'s `when`, as a decision over two facts about the tile.
 *
 * The order is the rule: an icon answers first (a category is never empty or broken — it is a predicate
 * over the library), and only a cover-less tile with media in it reaches [AlbumCoverFallback.Missing].
 * Anything that returns [AlbumCoverFallback.Empty] for `hasMedia = true` would print a count underneath
 * that contradicts the word above it.
 */
internal fun albumCoverFallback(hasIcon: Boolean, hasMedia: Boolean): AlbumCoverFallback = when {
    hasIcon -> AlbumCoverFallback.Badge
    !hasMedia -> AlbumCoverFallback.Empty
    else -> AlbumCoverFallback.Missing
}

/**
 * What a tile draws when it has no picture.
 *
 * Three cases, because they are three different facts: a category, which has a mark of its own; an album
 * with nothing in it, which can say so; and an album with photographs in it whose cover is gone — deleted,
 * or unreadable — which gets the same broken-image glyph a grid cell wears for the same event, rather than
 * a sentence contradicting the count printed underneath it.
 */
@Composable
private fun AlbumFallback(icon: ImageVector?, hasMedia: Boolean) {
    when (albumCoverFallback(hasIcon = icon != null, hasMedia = hasMedia)) {
        AlbumCoverFallback.Badge -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Reached only when `icon != null`: [albumCoverFallback] answers the icon question first.
            requireNotNull(icon).Badge()
        }

        AlbumCoverFallback.Empty -> Text(
            // A user album with nothing in it has no thumbnail to draw. Named rather than left blank,
            // because an empty tile is indistinguishable from a picture that failed to load.
            text = stringResource(R.string.album_cover_unavailable),
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(SpaceMd),
        )

        AlbumCoverFallback.Missing -> Icon(
            imageVector = Icons.Filled.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(BrokenCoverSize),
        )
    }
}

/**
 * A category's mark, at the size of a category's mark.
 *
 * It was a 56 dp disc on a 150 dp tile — a poster rather than an icon, which made each of the eight system
 * collections a mostly-empty square with something floating in the middle of it. The coin is what keeps it
 * looking deliberate at 40 dp, and it is literally the same component as the empty states' halo and the
 * setup flow's permission cards now: [IconCircle], one palette, one shape, sized for its surface.
 */
@Composable
private fun ImageVector.Badge() {
    IconCircle(
        imageVector = this@Badge,
        size = BadgeSize,
        glyph = BadgeIconSize,
    )
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

private val BadgeSize = 40.dp
private val BadgeIconSize = 20.dp

/**
 * The mark a lost cover leaves, smaller than a category's badge.
 *
 * A broken image is a fact about one tile, not the identity of a section, so it reads at the size a
 * status glyph reads rather than at the coin size that says "this whole tile is a kind of thing".
 */
private val BrokenCoverSize = 28.dp
