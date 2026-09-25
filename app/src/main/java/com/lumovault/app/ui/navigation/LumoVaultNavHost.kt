package com.lumovault.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.ui.screens.AlbumsScreen
import com.lumovault.app.ui.screens.CloudScreen
import com.lumovault.app.ui.screens.MapScreen
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
        composable(LumoVaultDestination.Map.route) { MapScreen() }

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
