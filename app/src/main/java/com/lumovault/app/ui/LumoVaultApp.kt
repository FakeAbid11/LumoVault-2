package com.lumovault.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
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
import com.lumovault.app.ui.navigation.LumoVaultDestination
import com.lumovault.app.ui.navigation.LumoVaultNavHost
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
    val currentDestination = LumoVaultDestination.entries
        .firstOrNull { it.route == backStackEntry?.destination?.route }
        ?: LumoVaultDestination.Start

    Scaffold(
        modifier = modifier,
        topBar = {
            TopBar(destination = currentDestination, onCycleThemeMode = onCycleThemeMode)
        },
        bottomBar = {
            NavigationBar {
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
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(destination: LumoVaultDestination, onCycleThemeMode: () -> Unit) {
    TopAppBar(
        title = { Text(destination.label()) },
        actions = {
            // Stand-in for the Settings entry point (PRD section 43); it becomes a real
            // destination in Phase 2, when there is a connected account to show.
            IconButton(onClick = onCycleThemeMode) {
                Icon(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = stringResource(R.string.theme_toggle),
                )
            }
        },
    )
}

private fun NavHostController.navigateToTab(destination: LumoVaultDestination) {
    navigate(destination.route) {
        popUpTo(LumoVaultDestination.Start.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
