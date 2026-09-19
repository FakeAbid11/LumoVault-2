package com.lumovault.lumovault.core.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumovault.lumovault.features.albums.presentation.AlbumDetailScreen
import com.lumovault.lumovault.features.albums.presentation.AlbumsScreen
import com.lumovault.lumovault.features.backup.presentation.BackupDashboardScreen
import com.lumovault.lumovault.features.backup.presentation.BackupSettingsScreen
import com.lumovault.lumovault.features.backup.presentation.StorageStatsScreen
import com.lumovault.lumovault.features.gallery.presentation.ArchiveScreen
import com.lumovault.lumovault.features.gallery.presentation.DuplicatesScreen
import com.lumovault.lumovault.features.gallery.presentation.FavoritesScreen
import com.lumovault.lumovault.features.gallery.presentation.HiddenScreen
import com.lumovault.lumovault.features.gallery.presentation.LocalScreen
import com.lumovault.lumovault.features.gallery.presentation.MapScreen
import com.lumovault.lumovault.features.gallery.presentation.MediaViewerScreen
import com.lumovault.lumovault.features.gallery.presentation.SearchScreen
import com.lumovault.lumovault.features.gallery.presentation.TimelineScreen
import com.lumovault.lumovault.features.gallery.presentation.TrashScreen
import com.lumovault.lumovault.features.onboarding.presentation.BackgroundPermissionsScreen
import com.lumovault.lumovault.features.onboarding.presentation.FolderSelectionScreen
import com.lumovault.lumovault.features.onboarding.presentation.PermissionsScreen
import com.lumovault.lumovault.features.onboarding.presentation.TelegramConnectScreen
import com.lumovault.lumovault.features.onboarding.presentation.WelcomeScreen
import com.lumovault.lumovault.features.people.presentation.PeopleScreen
import com.lumovault.lumovault.features.people.presentation.PersonDetailScreen
import com.lumovault.lumovault.features.restore.presentation.RestoreProgressScreen
import com.lumovault.lumovault.features.restore.presentation.RestoreScreen
import com.lumovault.lumovault.features.settings.presentation.AboutScreen
import com.lumovault.lumovault.features.settings.presentation.AccountScreen
import com.lumovault.lumovault.features.settings.presentation.AppearanceSettingsScreen
import com.lumovault.lumovault.features.settings.presentation.DeveloperSettingsScreen
import com.lumovault.lumovault.features.settings.presentation.GeneralSettingsScreen
import com.lumovault.lumovault.features.settings.presentation.MediaSettingsScreen
import com.lumovault.lumovault.features.settings.presentation.NotificationSettingsScreen
import com.lumovault.lumovault.features.settings.presentation.PrivacySettingsScreen
import com.lumovault.lumovault.features.settings.presentation.SettingsScreen
import com.lumovault.lumovault.features.settings.presentation.StorageInsightsScreen
import com.lumovault.lumovault.features.settings.presentation.StorageSettingsScreen

/**
 * Every destination in the app.
 *
 * Ported from lib/core/router/app_router.dart's 34 routes. Routes whose screen
 * is not implemented yet still resolve — to [NotImplementedScreen], which says
 * so plainly. That is deliberate: an unbuilt feature behind a navigation entry
 * that *looks* finished is how the previous scaffold ended up declaring 36
 * routes and wiring 17. A visible gap is easier to close than a hidden one.
 *
 * The argument-bearing routes carry their nav-arg spec next to the route
 * template so the two cannot drift.
 */
sealed class Screen(val route: String) {

    // -- Onboarding --
    data object OnboardingWelcome : Screen("onboarding/welcome")
    data object OnboardingPermissions : Screen("onboarding/permissions")
    data object OnboardingBackgroundPermissions : Screen("onboarding/background-permissions")
    data object OnboardingFolders : Screen("onboarding/folders")
    data object OnboardingTelegram : Screen("onboarding/telegram")

    // -- Shell tabs --
    data object Timeline : Screen("timeline")
    data object Albums : Screen("albums")
    data object Search : Screen("search")
    data object Map : Screen("map")
    data object People : Screen("people")
    data object Settings : Screen("settings")

    // -- Gallery --
    data object MediaViewer : Screen("media_viewer/{index}") {
        fun createRoute(index: Int) = "media_viewer/$index"
    }
    data object Favorites : Screen("favorites")
    data object Hidden : Screen("hidden")
    data object Archive : Screen("archive")
    data object Trash : Screen("trash")
    data object Duplicates : Screen("duplicates")

    // -- People --
    data object PersonDetail : Screen("people/{personId}") {
        fun createRoute(personId: Long) = "people/$personId"
    }

    // -- Albums --
    data object AlbumDetail : Screen("albums/{albumId}") {
        fun createRoute(albumId: Long) = "albums/$albumId"
    }
    data object DeviceFolder : Screen("albums/folder/{folderId}") {
        fun createRoute(folderId: String) = "albums/folder/$folderId"
    }

    // -- Backup --
    data object BackupDashboard : Screen("backup")
    data object BackupSettings : Screen("backup/settings")
    data object StorageStats : Screen("backup/stats")

    // -- Restore --
    data object Restore : Screen("restore")
    data object RestoreProgress : Screen("restore/progress")

    // -- Settings sub-routes --
    data object SettingsAccount : Screen("settings/account")
    data object SettingsAbout : Screen("settings/about")
    data object SettingsGeneral : Screen("settings/general")
    data object SettingsMedia : Screen("settings/media")
    data object SettingsStorage : Screen("settings/storage")
    data object SettingsStorageInsights : Screen("settings/storage-insights")
    data object SettingsAppearance : Screen("settings/appearance")
    data object SettingsPrivacy : Screen("settings/privacy")
    data object SettingsNotifications : Screen("settings/notifications")
    data object SettingsDeveloper : Screen("settings/developer")
    data object ConnectTelegram : Screen("connect-telegram")
}

data class TabItem(
    val screen: Screen,
    val label: String,
    val icon: @Composable () -> Unit,
)

/**
 * The five shell destinations, matching the Flutter `StatefulShellRoute`'s
 * branches: This device / Cloud / Map / People / Albums. Here the Settings tab
 * stands in for Cloud until the backup dashboard exists as a top-level
 * destination.
 */
val tabs = listOf(
    TabItem(Screen.Timeline, "Photos", { Icon(Icons.Filled.PhotoLibrary, "Photos") }),
    TabItem(Screen.Albums, "Albums", { Icon(Icons.Filled.Collections, "Albums") }),
    TabItem(Screen.Search, "Search", { Icon(Icons.Filled.Search, "Search") }),
    TabItem(Screen.People, "People", { Icon(Icons.Filled.People, "People") }),
    TabItem(Screen.Settings, "Settings", { Icon(Icons.Filled.Settings, "Settings") }),
)

@Composable
fun LumoVaultNavGraph() {
    val navController: NavHostController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // The collection the viewer pages through. A flag grid sets this before
    // navigating so the viewer shows *its* photos; the timeline path leaves it
    // null and the viewer falls back to its own timeline flow.
    var viewerItems by remember { mutableStateOf<List<com.lumovault.lumovault.core.database.entity.MediaItemEntity>?>(null) }

    ScaffoldWithBottomBar(
        currentRoute = currentDestination?.route,
        onNavigate = { screen ->
            navController.navigate(screen.route) {
                // Pop back to the start destination rather than stacking a new
                // instance of a tab every time it's re-selected.
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Timeline.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            // -- Implemented --
            composable(Screen.Timeline.route) {
                TimelineScreen(
                    onOpenItem = { index ->
                        // The viewer pages the same Flow the grid shows, so the
                        // grid position is the viewer's initial page.
                        viewerItems = null
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                )
            }
            composable(Screen.MediaViewer.route) { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                MediaViewerScreen(
                    initialIndex = index,
                    items = viewerItems,
                    onBack = { navController.popBackStack() },
                )
            }

            // -- Flag collections --
            // Each passes its own list to the viewer: an index into one of
            // these grids is not an index into the timeline.
            composable(Screen.Favorites.route) {
                FavoritesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }
            composable(Screen.Hidden.route) {
                HiddenScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }
            composable(Screen.Archive.route) {
                ArchiveScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }
            composable(Screen.Trash.route) {
                TrashScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }
            composable(Screen.Duplicates.route) {
                DuplicatesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }

            // -- Albums --
            composable(Screen.Albums.route) {
                AlbumsScreen(
                    onOpenAlbum = { navController.navigate(Screen.AlbumDetail.createRoute(it)) },
                    onOpenFolder = { bucketId, _ ->
                        navController.navigate(Screen.DeviceFolder.createRoute(bucketId))
                    },
                )
            }
            composable(Screen.AlbumDetail.route) { entry ->
                val albumId = entry.arguments?.getString("albumId")?.toLongOrNull() ?: return@composable
                AlbumDetailScreen(
                    albumId = albumId,
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }

            // -- Onboarding (chained flow) --
            composable(Screen.OnboardingWelcome.route) {
                WelcomeScreen(onNext = { navController.navigate(Screen.OnboardingPermissions.route) })
            }
            composable(Screen.OnboardingPermissions.route) {
                PermissionsScreen(
                    onNext = { navController.navigate(Screen.OnboardingBackgroundPermissions.route) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.OnboardingBackgroundPermissions.route) {
                BackgroundPermissionsScreen(
                    onNext = { navController.navigate(Screen.OnboardingFolders.route) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.OnboardingFolders.route) {
                FolderSelectionScreen(
                    onNext = { navController.navigate(Screen.OnboardingTelegram.route) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.OnboardingTelegram.route) {
                TelegramConnectScreen(
                    onFinished = {
                        navController.navigate(Screen.Timeline.route) {
                            popUpTo(Screen.OnboardingWelcome.route) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            // -- Gallery remainder --
            composable(Screen.Search.route) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }
            composable(Screen.Map.route) {
                MapScreen(
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }

            // -- People --
            composable(Screen.People.route) {
                PeopleScreen(
                    onOpenPerson = { navController.navigate(Screen.PersonDetail.createRoute(it)) },
                )
            }
            composable(Screen.PersonDetail.route) { entry ->
                val personId = entry.arguments?.getString("personId")?.toLongOrNull() ?: return@composable
                PersonDetailScreen(
                    personId = personId,
                    onBack = { navController.popBackStack() },
                    onOpenItem = { index, items ->
                        viewerItems = items
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                )
            }

            // -- Backup + restore (screens; engines land in Phase 4) --
            composable(Screen.BackupDashboard.route) {
                BackupDashboardScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { navController.navigate(it) },
                    onConnectTelegram = { navController.navigate(Screen.ConnectTelegram.route) },
                )
            }
            composable(Screen.BackupSettings.route) {
                BackupSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.StorageStats.route) {
                StorageStatsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Restore.route) {
                RestoreScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { navController.navigate(it) },
                )
            }
            composable(Screen.RestoreProgress.route) {
                RestoreProgressScreen(onBack = { navController.popBackStack() })
            }

            // -- Settings hub + sub-screens --
            composable(Screen.Settings.route) {
                SettingsScreen(onNavigate = { navController.navigate(it) })
            }
            composable(Screen.SettingsAccount.route) {
                AccountScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsAbout.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsGeneral.route) {
                GeneralSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsMedia.route) {
                MediaSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsStorage.route) {
                StorageSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsStorageInsights.route) {
                StorageInsightsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsAppearance.route) {
                AppearanceSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsPrivacy.route) {
                PrivacySettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsNotifications.route) {
                NotificationSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.SettingsDeveloper.route) {
                DeveloperSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.ConnectTelegram.route) {
                // The same phone->code->2FA flow as onboarding's last step;
                // finishing here returns to wherever the user came from.
                TelegramConnectScreen(
                    onFinished = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }

            // -- Declared, not yet implemented --
            NotImplementedDestination(Screen.DeviceFolder, navController)
        }
    }
}

@Composable
private fun ScaffoldWithBottomBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.screen.route,
                        onClick = { onNavigate(tab.screen) },
                        label = { Text(tab.label) },
                        icon = tab.icon,
                    )
                }
            }
        },
        content = content,
    )
}

/**
 * A route that resolves to an honest "not built yet" screen.
 *
 * Distinct from a placeholder that renders mock data: this never implies the
 * feature works. It takes the nav controller only so it can offer a back
 * action, and it names the route so the gap is visible in the running app
 * rather than only in the source.
 */
@Composable
private fun androidx.navigation.NavGraphBuilder.NotImplementedDestination(
    screen: Screen,
    navController: NavHostController,
) {
    composable(screen.route) {
        NotImplementedScreen(route = screen.route, onBack = { navController.popBackStack() })
    }
}

@Composable
fun NotImplementedScreen(route: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Construction,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = route,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Not implemented yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
