package com.lumovault.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.ui.backup.BackupFoldersScreen
import com.lumovault.app.ui.backup.BackupHealthScreen
import com.lumovault.app.ui.backup.BackupHubScreen
import com.lumovault.app.ui.backup.DiagnosticsScreen
import com.lumovault.app.ui.backup.FreeUpSpaceScreen
import com.lumovault.app.ui.screens.AlbumsScreen
import com.lumovault.app.ui.screens.CloudScreen
import com.lumovault.app.ui.map.MapScreen
import com.lumovault.app.ui.onboarding.ConnectTelegramDestination
import com.lumovault.app.ui.screens.PhotosScreen
import com.lumovault.app.ui.screens.albums.AlbumDetailScreen
import com.lumovault.app.ui.settings.AboutSettingsScreen
import com.lumovault.app.ui.settings.AccountSettingsScreen
import com.lumovault.app.ui.settings.AppearanceSettingsScreen
import com.lumovault.app.ui.settings.CloudSettingsScreen
import com.lumovault.app.ui.settings.NotificationsSettingsScreen
import com.lumovault.app.ui.settings.PermissionsSettingsScreen
import com.lumovault.app.ui.settings.SettingsHubScreen
import com.lumovault.app.ui.viewer.MediaViewerScreen

/**
 * The app's routes: the five tabs of the redesign (PRD section 42's four, plus Settings), and the
 * album screens underneath one of them.
 *
 * A detail route is declared with its argument in the pattern rather than passed as a stateful parameter,
 * so it survives process death with the back stack — the album id or name is what comes back, and the
 * screen reloads the rest from Room.
 */
/**
 * The Phase 9 screens, all under one prefix so the shell can tell a nested route from a tab.
 *
 * They hang off a tab rather than being tabs themselves: PRD section 42 gives the content tabs to the
 * library, and backup is something you do to the library rather than a fifth place to look at it.
 */
object BackupRoutes {
    const val PREFIX = "backup/"
    const val HUB = PREFIX + "hub"
    const val HEALTH = PREFIX + "health"
    const val DIAGNOSTICS = PREFIX + "diagnostics"
    const val FREE_SPACE = PREFIX + "free-space"

    /** Which folders automatic backup may read, shared with onboarding's two columns. */
    const val FOLDERS = PREFIX + "folders"
}

/**
 * The sign-in route kept outside onboarding.
 *
 * A lapsed Telegram session must be reconnectable in place — the root screen's own comment promises
 * it — and the credential panels live in the onboarding graph, which the completed app never
 * re-enters. This route hosts the same panels over the same view model, reached from the Cloud tab.
 */
object AccountRoutes {
    const val CONNECT_TELEGRAM = "connect-telegram"
}

/**
 * PRD section 43's graph: the hub, and the five categories that had no screen.
 *
 * The same shape as Phase 9's routes — one prefix so the shell can tell a settings screen from a
 * tab — and for the same reason: every settings entry, hub included, lives under the prefix, so one
 * `startsWith` is all the shell and the tab map ever need. The prefix now decides membership rather
 * than altitude: `Settings` is a bottom-bar destination, so its whole subtree keeps the bar and only
 * the categories (children of the hub) draw a back arrow.
 */
object SettingsRoutes {
    const val PREFIX = "settings/"
    const val HUB = PREFIX + "hub"
    const val ACCOUNT = PREFIX + "account"
    const val CLOUD = PREFIX + "cloud"
    const val APPEARANCE = PREFIX + "appearance"
    const val NOTIFICATIONS = PREFIX + "notifications"

    /**
     * Everything Android decides about what LumoVault may read, in one list.
     *
     * A separate screen rather than a fourth row on Notifications because two thirds of what it reports is
     * about something else entirely — the library, the coordinates inside photographs, the device's power
     * budget — and burying those under a screen the user only visits when alerts are the wrong thing.
     */
    const val PERMISSIONS = PREFIX + "permissions"
    const val ABOUT = PREFIX + "about"
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
        // The app's motion, shared rather than imitated — see NavMotion.kt, where each move picks its
        // shape from the two entries involved: tabs fade through, the viewer scales open, pushed
        // screens slide. The predictive parameters point at the same pop functions, so an edge swipe
        // and a completed back press scrub one motion with two drivers.
        enterTransition = { navEnterTransition() },
        exitTransition = { navExitTransition() },
        popEnterTransition = { navPopEnterTransition() },
        popExitTransition = { navPopExitTransition() },
        predictivePopEnterTransition = { navPopEnterTransition() },
        predictivePopExitTransition = { navPopExitTransition() },
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
                        is AlbumTarget.LocalFolder -> navController.navigate(AlbumRoutes.local(target.relativePath))
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

        // A device folder, addressed by its path rather than by a row id — which is the whole point: no
        // number in here can ever collide with an id in `albums`.
        composable(
            route = AlbumRoutes.LOCAL_PATTERN,
            arguments = listOf(
                navArgument(AlbumRoutes.ARG_PATH) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val path = entry.arguments?.getString(AlbumRoutes.ARG_PATH)
            val target = path?.takeIf { it.isNotBlank() }?.let {
                AlbumTarget.LocalFolder(FolderPaths.normalize(it))
            }
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

        composable(LumoVaultDestination.Cloud.route) {
            CloudScreen(
                onConnectTelegram = { navController.navigate(AccountRoutes.CONNECT_TELEGRAM) },
                // A cloud item the device holds opens the *shared* viewer on its local row, swiping
                // through the cloud's own list — the Cloud tab never grows a second viewer.
                onOpenCloudMedia = { mediaId ->
                    navController.navigate(ViewerRoutes.of(mediaId, ViewerTarget.Cloud))
                },
            )
        }

        composable(AccountRoutes.CONNECT_TELEGRAM) {
            ConnectTelegramDestination(onNavigateUp = navController::navigateUp)
        }

        // The hub is the Settings tab's own screen, so it takes no back arrow: the gradient bar above
        // it is the title only, and the bottom bar is how you leave.
        composable(SettingsRoutes.HUB) {
            SettingsHubScreen(
                onOpenAccount = { navController.navigate(SettingsRoutes.ACCOUNT) },
                // Backup and Storage are rows onto screens that already exist — the settings hub's
                // sibling and free-up-space — rather than a second copy of either.
                onOpenBackup = { navController.navigate(BackupRoutes.HUB) },
                onOpenCloud = { navController.navigate(SettingsRoutes.CLOUD) },
                onOpenStorage = { navController.navigate(BackupRoutes.FREE_SPACE) },
                onOpenAppearance = { navController.navigate(SettingsRoutes.APPEARANCE) },
onOpenNotifications = { navController.navigate(SettingsRoutes.NOTIFICATIONS) },
        onOpenPermissions = { navController.navigate(SettingsRoutes.PERMISSIONS) },
        onOpenAbout = { navController.navigate(SettingsRoutes.ABOUT) },
            )
        }
        composable(SettingsRoutes.ACCOUNT) {
            AccountSettingsScreen(
                onNavigateUp = navController::navigateUp,
                onConnectTelegram = { navController.navigate(AccountRoutes.CONNECT_TELEGRAM) },
            )
        }
        composable(SettingsRoutes.CLOUD) {
            CloudSettingsScreen(
                onNavigateUp = navController::navigateUp,
                onOpenDiagnostics = { navController.navigate(BackupRoutes.DIAGNOSTICS) },
            )
        }
        composable(SettingsRoutes.APPEARANCE) {
            AppearanceSettingsScreen(onNavigateUp = navController::navigateUp)
        }
composable(SettingsRoutes.NOTIFICATIONS) {
    NotificationsSettingsScreen(onNavigateUp = navController::navigateUp)
}
composable(SettingsRoutes.PERMISSIONS) {
    PermissionsSettingsScreen(
        onNavigateUp = navController::navigateUp,
        // The notification channel is not a permission, so it stays on the notifications screen; this is
        // only the way there, and it is one press rather than two back-and-forths.
        onOpenNotifications = {
            navController.navigate(SettingsRoutes.NOTIFICATIONS) {
                popUpTo(SettingsRoutes.PERMISSIONS) { inclusive = true }
            }
        },
    )
}
composable(SettingsRoutes.ABOUT) {
    AboutSettingsScreen(onNavigateUp = navController::navigateUp)
}

        composable(BackupRoutes.HUB) {
            BackupHubScreen(
                onNavigateUp = navController::navigateUp,
                onOpenFreeUpSpace = { navController.navigate(BackupRoutes.FREE_SPACE) },
                onOpenHealth = { navController.navigate(BackupRoutes.HEALTH) },
                onOpenDiagnostics = { navController.navigate(BackupRoutes.DIAGNOSTICS) },
                onOpenFolders = { navController.navigate(BackupRoutes.FOLDERS) },
                // A queue that cannot send says which refusal it stopped on and offers the door: the tab
                // that adopts a channel, or the sign-in this app has lost.
                onOpenCloudTab = { navController.navigateToTab(LumoVaultDestination.Cloud) },
                onConnectTelegram = { navController.navigate(AccountRoutes.CONNECT_TELEGRAM) },
            )
        }
        composable(BackupRoutes.FOLDERS) {
            BackupFoldersScreen(onNavigateUp = navController::navigateUp)
        }
        composable(BackupRoutes.HEALTH) {
            BackupHealthScreen(
                onNavigateUp = navController::navigateUp,
                onOpenDiagnostics = { navController.navigate(BackupRoutes.DIAGNOSTICS) },
            )
        }
        composable(BackupRoutes.DIAGNOSTICS) {
            DiagnosticsScreen(
                onNavigateUp = navController::navigateUp,
                // A reason on its own is half an answer; the photo it belongs to is one tap away, and the
                // viewer is where the per-item Retry action already lives.
                onOpenMedia = { mediaId -> navController.navigate(ViewerRoutes.of(mediaId, ViewerTarget.Photos)) },
            )
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
