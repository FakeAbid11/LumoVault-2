package com.lumovault.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import androidx.navigation.NavHostController

/**
 * The five primary destinations. Routes live here so no screen declares a raw navigation string.
 *
 * PRD section 42 drew four tabs with Settings behind a gear in the app bar; the redesign brief asks for
 * Photos, Cloud, Albums, Map and Settings to be *immediately accessible* from the main navigation and to
 * choose the architecture rather than preserve the layout — so Settings is the fifth tab and the gear is
 * gone. It stays last because settings is where every app puts it and it is the least-visited door; the
 * four content tabs keep PRD section 42's order.
 */
enum class LumoVaultDestination(val route: String) {
    Photos("photos"),
    Albums("albums"),
    Cloud("cloud"),
    Map("map"),
    Settings(SettingsRoutes.HUB);

    companion object {
        /** LumoVault opens into the local library (PRD section 11). */
        val Start: LumoVaultDestination = Photos

        /**
         * Which tab a route belongs to.
         *
         * A screen that is not itself a tab is reported as the one it sits under — otherwise opening an
         * album or a settings category would un-highlight the bar, and a bottom bar with nothing selected
         * reads as a screen that has lost track of itself.
         */
        fun forRoute(route: String?): LumoVaultDestination? = when {
            route == null -> Start
            route == Photos.route -> Photos
            route == Cloud.route -> Cloud
            route == Map.route -> Map
            route == Albums.route || route.startsWith(AlbumRoutes.DETAIL_PREFIX) -> Albums
            // Reconnecting Telegram is the cloud tab's work, wherever it was opened from.
            route == AccountRoutes.CONNECT_TELEGRAM -> Cloud
            // Backup, health, diagnostics and free-up-space all belong to the cloud tab's work.
            route?.startsWith(BackupRoutes.PREFIX) == true -> Cloud
            // The hub is the tab itself; every category beneath it keeps the tab lit, the way an album
            // detail keeps Albums lit.
            route?.startsWith(SettingsRoutes.PREFIX) == true -> Settings
            else -> null
        }

        /**
         * The bar leaves only for the viewer, where the photograph is the screen.
         *
         * Settings used to be excluded here — it was a pushed flow behind the app bar's gear, and a bar
         * under it would have claimed a tab it did not belong to. It is a tab now, so its categories keep
         * the bar the way every other screen under a tab does.
         */
        fun showsBottomNavigation(route: String?): Boolean = !viewerRouteActive(route)
    }
}

/**
 * How a bottom-bar destination is opened: one copy on the stack, and the state the user left behind.
 *
 * Reused by the screens that link between tabs — a photograph's "show on the map" is the same
 * destination as the map tab, and navigating to it by hand would stack a second map.
 */
internal fun NavHostController.navigateToTab(destination: LumoVaultDestination) {
    navigate(destination.route) {
        popUpTo(LumoVaultDestination.Start.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@get:StringRes
private val LumoVaultDestination.labelRes: Int
    get() = when (this) {
        LumoVaultDestination.Photos -> R.string.nav_photos
        LumoVaultDestination.Albums -> R.string.nav_albums
        LumoVaultDestination.Cloud -> R.string.nav_cloud
        LumoVaultDestination.Map -> R.string.nav_map
        // The hub's own title doubles as the tab's label: the word is "Settings" either way, and a second
        // string saying the same thing is a second string to keep in step.
        LumoVaultDestination.Settings -> R.string.settings_title
    }

internal val LumoVaultDestination.icon: ImageVector
    get() = when (this) {
        LumoVaultDestination.Photos -> Icons.Filled.Photo
        LumoVaultDestination.Albums -> Icons.Filled.PhotoAlbum
        LumoVaultDestination.Cloud -> Icons.Filled.Cloud
        LumoVaultDestination.Map -> Icons.Filled.Map
        LumoVaultDestination.Settings -> Icons.Filled.Settings
    }

@Composable
internal fun LumoVaultDestination.label(): String = stringResource(labelRes)
