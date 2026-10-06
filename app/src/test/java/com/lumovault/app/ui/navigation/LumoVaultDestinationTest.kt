package com.lumovault.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumoVaultDestinationTest {
    @Test
    fun `navigation exposes exactly the five primary destinations`() {
        assertEquals(
            listOf("photos", "albums", "cloud", "map", SettingsRoutes.HUB),
            LumoVaultDestination.entries.map { it.route },
        )
    }

    @Test
    fun `routes are unique so no two tabs share a back stack entry`() {
        val routes = LumoVaultDestination.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    @Test
    fun `the app opens on the local library`() {
        assertEquals(LumoVaultDestination.Photos, LumoVaultDestination.Start)
        assertTrue(LumoVaultDestination.entries.contains(LumoVaultDestination.Start))
    }

    @Test
    fun `settings routes light the settings tab rather than claiming another`() {
        assertEquals(LumoVaultDestination.Settings, LumoVaultDestination.forRoute(SettingsRoutes.HUB))
        assertEquals(LumoVaultDestination.Settings, LumoVaultDestination.forRoute(SettingsRoutes.ACCOUNT))
        assertEquals(LumoVaultDestination.Settings, LumoVaultDestination.forRoute(SettingsRoutes.ABOUT))
    }

    @Test
    fun `primary routes keep bottom navigation`() {
        LumoVaultDestination.entries.forEach { destination ->
            assertTrue(LumoVaultDestination.showsBottomNavigation(destination.route))
        }
        // Settings is a tab now, so its categories keep the bar the way album details keep Albums lit.
        assertTrue(LumoVaultDestination.showsBottomNavigation(SettingsRoutes.HUB))
        assertTrue(LumoVaultDestination.showsBottomNavigation(SettingsRoutes.ACCOUNT))
    }
}
