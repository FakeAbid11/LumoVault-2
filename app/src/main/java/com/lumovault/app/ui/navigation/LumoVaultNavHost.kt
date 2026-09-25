package com.lumovault.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.ui.backup.BackupHealthScreen
import com.lumovault.app.ui.backup.BackupHubScreen
import com.lumovault.app.ui.backup.DiagnosticsScreen
import com.lumovault.app.ui.backup.FreeUpSpaceScreen
import com.lumovault.app.ui.screens.AlbumsScreen
import com.lumovault.app.ui.screens.CloudScreen
import com.lumovault.app.ui.map.MapScreen
import com.lumovault.app.ui.screens.PhotosScreen
import com.lumovault.app.ui.screens.albums.AlbumDetailScreen
import com.lumovault.app.ui.viewer.MediaViewerScreen

/**
 * The app's routes: the four tabs of PRD section 42, plus the album screens underneath one of them.
 *
 * A detail route is declared with its argument in the pattern rather than passed as a stateful parameter,
 * so it survives process death with the back stack — the album id or name is what comes back, and the
 * screen reloads the rest from Room.
 */
/**
 * The Phase 9 screens, all under one prefix so the shell can tell a nested route from a tab.
 *
 * They hang off the top bar rather than the bottom bar: PRD section 42 gives the four tabs to the library,
 * and backup is something you do to the library rather than a fifth place to look at it.
 */
object BackupRoutes {
    const val PREFIX = "backup/"
    const val HUB = PREFIX + "hub"
    const val HEALTH = PREFIX + "health"
    const val DIAGNOSTICS = PREFIX + "diagnostics"
    const val FREE_SPACE = PREFIX + "free-space"
}

@Composable
fun LumoVaultNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = LumoVaultDestination.Start.route,
        modifier = modifier,
    ) {
        composable(LumoVaultDestination.Photos.route) {
            PhotosScreen(
                onOpenMedia = { mediaId ->
                    navController.navigate(ViewerRoutes.of(mediaId, ViewerTarget.Photos))
                },
            )
        }

        composable(LumoVaultDestination.Albums.route) {
            AlbumsScreen(
                onOpenAlbum = { target ->
                    when (target) {
                        is AlbumTarget.User -> navController.navigate(AlbumRoutes.user(target.albumId))
                        is AlbumTarget.System -> navController.navigate(AlbumRoutes.system(target.album))
                    }
                },
            )
        }

        composable(
            route = AlbumRoutes.USER_PATTERN,
            arguments = listOf(
                navArgument(AlbumRoutes.ARG_ALBUM_ID) { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { entry ->
            val target = AlbumTarget.User(entry.arguments?.getLong(AlbumRoutes.ARG_ALBUM_ID) ?: 0L)
            AlbumDetailScreen(
                target = target,
                onNavigateUp = navController::navigateUp,
                onOpenMedia = { mediaId ->
                    navController.navigate(ViewerRoutes.of(mediaId, ViewerTarget.of(target)))
                },
            )
        }

        composable(
            route = AlbumRoutes.SYSTEM_PATTERN,
            arguments = listOf(
                navArgument(AlbumRoutes.ARG_TARGET) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val album = entry.arguments?.getString(AlbumRoutes.ARG_TARGET)
                ?.let { name -> SystemAlbum.entries.firstOrNull { it.name == name } }
            val target = album?.let { AlbumTarget.System(it) }
            AlbumDetailScreen(
                target = target,
                onNavigateUp = navController::navigateUp,
                onOpenMedia = { mediaId ->
                    navController.navigate(ViewerRoutes.of(mediaId, ViewerTarget.of(target)))
                },
            )
        }

        composable(LumoVaultDestination.Cloud.route) { CloudScreen() }

        composable(BackupRoutes.HUB) {
            BackupHubScreen(
                onNavigateUp = navController::navigateUp,
                onOpenFreeUpSpace = { navController.navigate(BackupRoutes.FREE_SPACE) },
                onOpenHealth = { navController.navigate(BackupRoutes.HEALTH) },
                onOpenDiagnostics = { navController.navigate(BackupRoutes.DIAGNOSTICS) },
            )
        }
        composable(BackupRoutes.HEALTH) {
            BackupHealthScreen(onNavigateUp = navController::navigateUp)
        }
        composable(BackupRoutes.DIAGNOSTICS) {
            DiagnosticsScreen(onNavigateUp = navController::navigateUp)
        }
        composable(BackupRoutes.FREE_SPACE) {
            FreeUpSpaceScreen(onNavigateUp = navController::navigateUp)
        }
        composable(LumoVaultDestination.Map.route) {
            MapScreen(
                onOpenMedia = { mediaId ->
                    // Swiping from a map marker walks the positioned photos in view, which is the timeline's
                    // window for now: the map's own viewport is not a list Room can page through, and opening
                    // the viewer on the library rather than on nothing is the useful half.
                    navController.navigate(ViewerRoutes.of(mediaId, ViewerTarget.Photos))
                },
            )
        }

        composable(
            route = ViewerRoutes.PATTERN,
            arguments = listOf(
                navArgument(ViewerRoutes.ARG_MEDIA_ID) { type = NavType.LongType; defaultValue = 0L },
                navArgument(ViewerRoutes.ARG_KIND) { type = NavType.StringType; defaultValue = ViewerTarget.NO_ARGUMENT },
                navArgument(ViewerRoutes.ARG_ARGUMENT) { type = NavType.StringType; defaultValue = ViewerTarget.NO_ARGUMENT },
            ),
        ) { entry ->
            val arguments = entry.arguments
            MediaViewerScreen(
                mediaStoreId = arguments?.getLong(ViewerRoutes.ARG_MEDIA_ID) ?: 0L,
                target = ViewerTarget.decode(
                    kind = arguments?.getString(ViewerRoutes.ARG_KIND),
                    argument = arguments?.getString(ViewerRoutes.ARG_ARGUMENT),
                ),
                onNavigateUp = navController::navigateUp,
                onOpenMap = { navController.navigate(LumoVaultDestination.Map.route) },
            )
        }
    }
}
