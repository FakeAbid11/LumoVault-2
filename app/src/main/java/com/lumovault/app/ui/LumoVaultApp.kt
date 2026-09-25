package com.lumovault.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumovault.app.R
import com.lumovault.app.ui.navigation.AlbumRoutes
import com.lumovault.app.ui.navigation.BackupRoutes
import com.lumovault.app.ui.navigation.LumoVaultDestination
import com.lumovault.app.ui.navigation.navigateToTab
import com.lumovault.app.ui.navigation.LumoVaultNavHost
import com.lumovault.app.ui.navigation.viewerRouteActive
import com.lumovault.app.ui.navigation.icon
import com.lumovault.app.ui.navigation.label

/**
 * The shell from PRD section 42: title bar, screen, and the Photos | Albums | Cloud | Map bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumoVaultApp(
    onCycleThemeMode: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val currentDestination = LumoVaultDestination.forRoute(route)
    // An album screen is a child of the Albums tab rather than a fifth tab, so the bar keeps Albums
    // highlighted and only the back arrow changes.
    val isNested = route?.startsWith(AlbumRoutes.DETAIL_PREFIX) == true ||
        route?.startsWith(BackupRoutes.PREFIX) == true
    // A photograph is the screen, so the bar it would otherwise sit under is not drawn at all rather than
    // drawn over it: a translucent nav bar on top of a dark image is a second, dimmer copy of the same black.
    val isViewer = viewerRouteActive(route)
    // The backup and storage screens each draw their own bar, because each has a title that is not a tab's
    // name and a back arrow that is not a tab change. A screen that owns its chrome gets one bar, not two —
    // and the one that would be added here is the one that cannot name them.
    val ownsItsBar = route?.startsWith(BackupRoutes.PREFIX) == true

    Scaffold(
        modifier = modifier,
        topBar = {
            if (!isViewer && !ownsItsBar) {
                TopBar(
                    destination = currentDestination,
                    onCycleThemeMode = onCycleThemeMode,
                    onNavigateUp = { if (isNested) navController.navigateUp() },
                    showNavigateUp = isNested,
                    onOpenBackup = {
                        navController.navigate(BackupRoutes.HUB) {
                            // The gear stays in the bar on every tab, so a double tap on the way past the
                            // backup screen would otherwise leave two of them, and back would retrace one.
                            launchSingleTop = true
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isViewer) Unit else NavigationBar {
                LumoVaultDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == currentDestination,
                        onClick = { navController.navigateToTab(destination) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label()) },
                    )
                }
            }
        },
    ) { innerPadding ->
        LumoVaultNavHost(
            navController = navController,
            // The viewer's own chrome pads for the system bars it is drawn behind; every other screen lets
            // the scaffold do it, which is what keeps a status bar from landing on top of a title.
            modifier = if (isViewer) modifier else modifier.padding(innerPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    destination: LumoVaultDestination,
    onCycleThemeMode: () -> Unit,
    onOpenBackup: () -> Unit,
    onNavigateUp: () -> Unit,
    showNavigateUp: Boolean,
) {
    TopAppBar(
        title = { Text(destination.label()) },
        navigationIcon = {
            // The nested screens are the only ones with somewhere to go back to, and an arrow that
            // appeared on a tab would be a control that does nothing.
            if (showNavigateUp) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
        actions = {

            IconButton(onClick = onCycleThemeMode) {
                Icon(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = stringResource(R.string.theme_toggle),
                )
            }
            // PRD section 42 puts Settings in this corner. It opens the backup and storage screen, which is
            // where Phase 9's decisions live; the rest of section 43 stays Phase 10's, and an entry that
            // promised them now would be a screen of placeholders.
            IconButton(onClick = onOpenBackup) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.backup_entry),
                )
            }
        },
    )
}

