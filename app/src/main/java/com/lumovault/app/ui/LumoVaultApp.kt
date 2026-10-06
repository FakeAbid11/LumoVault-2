package com.lumovault.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lumovault.app.R
import com.lumovault.app.ui.components.LocalSnackbarHostState
import com.lumovault.app.ui.navigation.AccountRoutes
import com.lumovault.app.ui.navigation.AlbumRoutes
import com.lumovault.app.ui.navigation.BackupRoutes
import com.lumovault.app.ui.navigation.LumoVaultDestination
import com.lumovault.app.ui.navigation.navigateToTab
import com.lumovault.app.ui.navigation.LumoVaultNavHost
import com.lumovault.app.ui.navigation.SettingsRoutes
import com.lumovault.app.ui.navigation.viewerRouteActive
import com.lumovault.app.ui.navigation.icon
import com.lumovault.app.ui.navigation.label

/**
 * The shell: title bar, screen, and the Photos | Albums | Cloud | Map | Settings bar.
 *
 * The bar carries no actions. The gear moved into the bar as the fifth tab (see [LumoVaultDestination])
 * and the theme cycle went with it — theme choice is Settings > Appearance's System/Light/Dark, an
 * explicit pick rather than a blind cycle, so the shortcut was duplicating a worse version of a control
 * the app already had one screen deeper. What is left is the one thing only the shell can say: which
 * tab this is, and — on a screen pushed under one — the way back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumoVaultApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val currentDestination = LumoVaultDestination.forRoute(route) ?: LumoVaultDestination.Start
    // An album screen is a child of the Albums tab rather than a fifth tab, so the bar keeps Albums
    // highlighted and only the back arrow changes.
    val isNested = route?.startsWith(AlbumRoutes.DETAIL_PREFIX) == true ||
        route?.startsWith(BackupRoutes.PREFIX) == true
    // A photograph is the screen, so the bar it would otherwise sit under is not drawn at all rather than
    // drawn over it: a translucent nav bar on top of a dark image is a second, dimmer copy of the same black.
    val isViewer = viewerRouteActive(route)
    // The backup and storage screens each draw their own bar, because each has a title that is not a tab's
    // name and a back arrow that is not a tab change. A screen that owns its chrome gets one bar, not two —
    // and the one that would be added here is the one that cannot name them. The standalone Telegram
    // reconnect is the same case wearing the setup flow's clothes: OnboardingScaffold draws a back bar on
    // the gradient, and the shell's opaque bar stacked above it made two titles where the user could only
    // act on the lower one. Settings keeps its own bar for the gradient rather than the title now: its
    // title and the tab's label are the same word, but its bar carries the tint the rest of section 43's
    // screens use, and the shell's flat surface painted across that tint is the same two-tone stripe.
    // The tab bar stays either way — it is how you get back to the library.
    val ownsItsBar = route?.startsWith(BackupRoutes.PREFIX) == true ||
        route?.startsWith(SettingsRoutes.PREFIX) == true ||
        route == AccountRoutes.CONNECT_TELEGRAM

    // The app's one snackbar. It lives here rather than in each screen because every destination is
    // drawn inside this scaffold — including the backup screens and the viewer — and one host means
    // one place messages queue instead of a host per screen competing for the same corner.
    val snackbarHostState = remember { SnackbarHostState() }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            modifier = modifier,
            topBar = {
                if (!isViewer && !ownsItsBar) {
                    TopBar(
                        destination = currentDestination,
                        onNavigateUp = { if (isNested) navController.navigateUp() },
                        showNavigateUp = isNested,
                    )
                }
            },
            bottomBar = {
                if (LumoVaultDestination.showsBottomNavigation(route)) NavigationBar {
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
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { innerPadding ->
            LumoVaultNavHost(
                navController = navController,
                // The viewer's own chrome pads for the system bars it is drawn behind; every other screen lets
                // the scaffold do it, which is what keeps a status bar from landing on top of a title. The
                // screens that own their bar get the viewer's treatment for the *top* inset only — each of
                // them paints the brand gradient full-height behind a transparent app bar of its own, and an
                // outer top pad here was what cut that gradient off below the status bar, leaving the shell's
                // flat surface colour as a two-tone stripe above it. The bottom stays: backup routes keep the
                // shell's tab bar, and the rest still need the gesture-bar inset.
                modifier = when {
                    isViewer -> modifier
                    ownsItsBar -> modifier.padding(bottom = innerPadding.calculateBottomPadding())
                    else -> modifier.padding(innerPadding)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    destination: LumoVaultDestination,
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
    )
}

