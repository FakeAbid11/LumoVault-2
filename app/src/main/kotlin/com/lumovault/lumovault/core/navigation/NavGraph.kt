package com.lumovault.lumovault.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumovault.lumovault.features.gallery.presentation.MediaViewerScreen
import com.lumovault.lumovault.features.gallery.presentation.TimelineScreen

sealed class Screen(val route: String) {
    data object Timeline : Screen("timeline")
    data object Albums : Screen("albums")
    data object People : Screen("people")
    data object Search : Screen("search")
    data object Map : Screen("map")
    data object Settings : Screen("settings")
    data object MediaViewer : Screen("media_viewer/{index}") {
        fun createRoute(index: Int) = "media_viewer/$index"
    }
}

data class TabItem(
    val screen: Screen,
    val label: String,
    val icon: @Composable () -> Unit,
)

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
            modifier = androidx.compose.ui.Modifier.padding(innerPadding),
        ) {
            composable(Screen.Timeline.route) {
                TimelineScreen(
                    onOpenItem = { index ->
                        // The viewer pages the same Flow the grid shows, so the
                        // grid position is the viewer's initial page.
                        navController.navigate(Screen.MediaViewer.createRoute(index))
                    },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                )
            }
            composable(Screen.Albums.route) { Placeholder("Albums") }
            composable(Screen.Search.route) { Placeholder("Search") }
            composable(Screen.People.route) { Placeholder("People") }
            composable(Screen.Settings.route) { Placeholder("Settings") }
            composable(Screen.MediaViewer.route) { entry ->
                val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                MediaViewerScreen(
                    initialIndex = index,
                    onBack = { navController.popBackStack() },
                )
            }
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

@Composable
private fun Placeholder(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("$name — coming up")
    }
}
