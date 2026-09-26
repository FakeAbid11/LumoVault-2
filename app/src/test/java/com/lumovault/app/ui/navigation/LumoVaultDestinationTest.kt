package com.lumovault.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumoVaultDestinationTest {
    @Test
    fun `navigation exposes exactly the four primary destinations`() {
        assertEquals(
            listOf("photos", "albums", "cloud", "map"),
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
}
