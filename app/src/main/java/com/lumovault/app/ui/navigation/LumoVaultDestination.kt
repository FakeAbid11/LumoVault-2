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
