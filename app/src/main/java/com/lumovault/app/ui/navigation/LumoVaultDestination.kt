package com.lumovault.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R

/**
 * The four primary destinations from PRD section 42. Routes live here so no screen declares
 * a raw navigation string.
 */
enum class LumoVaultDestination(val route: String) {
    Photos("photos"),
    Albums("albums"),
    Cloud("cloud"),
    Map("map");

    companion object {
        /** LumoVault opens into the local library (PRD section 11). */
        val Start: LumoVaultDestination = Photos

        /**
         * Which tab a route belongs to.
         *
         * An album detail screen is not a tab, so it is reported as the one it sits under — otherwise
         * opening an album would un-highlight the bar, and a bottom bar with nothing selected reads as a
         * screen that has lost track of itself.
         */
        fun forRoute(route: String?): LumoVaultDestination = when {
            route == null -> Start
            route == Photos.route -> Photos
            route == Cloud.route -> Cloud
            route == Map.route -> Map
            route == Albums.route || route.startsWith(AlbumRoutes.DETAIL_PREFIX) -> Albums
            // Backup, health, diagnostics and free-up-space all belong to the cloud tab's work.
            route?.startsWith(BackupRoutes.PREFIX) == true -> Cloud
            else -> Start
        }
    }
}

@get:StringRes
private val LumoVaultDestination.labelRes: Int
    get() = when (this) {
        LumoVaultDestination.Photos -> R.string.nav_photos
        LumoVaultDestination.Albums -> R.string.nav_albums
        LumoVaultDestination.Cloud -> R.string.nav_cloud
        LumoVaultDestination.Map -> R.string.nav_map
    }

internal val LumoVaultDestination.icon: ImageVector
    get() = when (this) {
        LumoVaultDestination.Photos -> Icons.Filled.Photo
        LumoVaultDestination.Albums -> Icons.Filled.PhotoAlbum
        LumoVaultDestination.Cloud -> Icons.Filled.Cloud
        LumoVaultDestination.Map -> Icons.Filled.Map
    }

@Composable
internal fun LumoVaultDestination.label(): String = stringResource(labelRes)
