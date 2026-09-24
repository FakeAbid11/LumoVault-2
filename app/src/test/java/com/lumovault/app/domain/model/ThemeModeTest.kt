package com.lumovault.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `every mode survives a storage round trip`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStorageKey(mode.storageKey))
        }
    }

    @Test
    fun `missing or unknown key falls back to the product default`() {
        assertEquals(ThemeMode.Default, ThemeMode.fromStorageKey(null))
        assertEquals(ThemeMode.Default, ThemeMode.fromStorageKey("sepia"))
    }

    @Test
    fun `an explicit caller fallback wins over the default`() {
        assertEquals(ThemeMode.System, ThemeMode.fromStorageKey("nope", fallback = ThemeMode.System))
    }

    @Test
    fun `storage keys are stable identifiers, not enum names`() {
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.storageKey })
    }
}
