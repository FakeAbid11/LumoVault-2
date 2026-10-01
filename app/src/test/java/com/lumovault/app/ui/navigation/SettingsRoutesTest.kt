package com.lumovault.app.ui.navigation

import com.lumovault.app.ui.backup.TelegramWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 43's routes, checked where a wrong answer would send the gear to the wrong room.
 *
 * Nothing renders — that needs a phone. What is checkable off-device are the two judgements the
 * shell makes from route strings alone: a settings screen must keep the Cloud tab highlighted (it is
 * that tab's work, exactly like backup), and every entry must stay under its prefix, because the
 * shell decides "this screen draws its own bar" from that prefix alone — one route spelled bare
 * would stack two titles on one screen. The uniqueness pass exists for the reason every collision
 * test does: two names for one route means navigating to one arrives at whichever the graph
 * registered first.
 */
class SettingsRoutesTest {

    @Test
    fun everySettingsRouteIsNestedUnderThePrefixAndKeepsTheCloudTabHighlighted() {
        settingsRoutes().forEach { route ->
            assertEquals(
                "$route is opened from the gear rather than a tab, so the bar must stay put",
                LumoVaultDestination.Cloud,
                LumoVaultDestination.forRoute(route),
            )
            assertTrue(
                "$route must carry the prefix the shell reads to hand the screen its own bar",
                route.startsWith(SettingsRoutes.PREFIX),
            )
        }
    }

    @Test
    fun everySettingsRouteIsSpelledOnceAndCollidesWithNoOtherRouteInTheGraph() {
        val settings = settingsRoutes()
        assertEquals(
            "two names for one screen is two screens for one name",
            settings.toSet().size,
            settings.size,
        )

        val elsewhere = listOf(
            LumoVaultDestination.Photos.route,
            LumoVaultDestination.Albums.route,
            LumoVaultDestination.Cloud.route,
            LumoVaultDestination.Map.route,
            AccountRoutes.CONNECT_TELEGRAM,
            BackupRoutes.HUB,
            BackupRoutes.FOLDERS,
            BackupRoutes.HEALTH,
            BackupRoutes.DIAGNOSTICS,
            BackupRoutes.FREE_SPACE,
        )
        val all = settings + elsewhere
        assertEquals(
            "a shared route string opens whichever destination the graph matched first",
            all.toSet().size,
            all.size,
        )
    }

    /**
     * The session's four words keep four distinct sentences.
     *
     * The account screen and the diagnostics panel now read one shared label per state, so a
     * copy-paste that gave two states the same sentence would misreport the one state the user is
     * meant to act on — the same bug class as two refusals sharing a reason, asserted in the backup
     * rules.
     */
    @Test
    fun theSessionWordAndItsLabelNeverDriftApart() {
        val words = TelegramWord.entries

        assertEquals("connected, waiting, not configured, unavailable", 4, words.size)
        assertEquals(
            "two states sharing a sentence is one state wearing the other's face",
            words.size,
            words.map { it.labelRes }.toSet().size,
        )
    }

    private fun settingsRoutes() = listOf(
        SettingsRoutes.HUB,
        SettingsRoutes.ACCOUNT,
        SettingsRoutes.CLOUD,
        SettingsRoutes.APPEARANCE,
        SettingsRoutes.NOTIFICATIONS,
        SettingsRoutes.ABOUT,
    )
}
