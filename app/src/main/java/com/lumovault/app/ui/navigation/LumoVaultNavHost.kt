package com.lumovault.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.lumovault.app.ui.screens.AlbumsScreen
import com.lumovault.app.ui.screens.CloudScreen
import com.lumovault.app.ui.screens.MapScreen
import com.lumovault.app.ui.screens.PhotosScreen

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
        composable(LumoVaultDestination.Photos.route) { PhotosScreen() }
        composable(LumoVaultDestination.Albums.route) { AlbumsScreen() }
        composable(LumoVaultDestination.Cloud.route) { CloudScreen() }
        composable(LumoVaultDestination.Map.route) { MapScreen() }
    }
}
