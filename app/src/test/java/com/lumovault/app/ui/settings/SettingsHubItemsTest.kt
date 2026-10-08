package com.lumovault.app.ui.settings

import com.lumovault.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings hub's shape, checked where a wrong answer produces a door that cannot be reached.
 *
 * The hub is a loop over [settingsHubItems], so a door added to the enum but not to the list is a door
 * nobody can get to — it compiles, it has a name, and no test notices. These assertions are the ones that
 * catch that: every door exactly once, every heading followed by something, and no two doors sharing a
 * title, because two rows that read identically is how a user opens the wrong screen and concludes the app
 * is broken.
 */
class SettingsHubItemsTest {
    private val items = settingsHubItems()
    private val doors = items.filterIsInstance<SettingsHubDoor>()

    @Test
    fun everyDoorReachesItsOwnScreenExactlyOnce() {
        assertEquals(
            "a door with no row is a room with no door, and a door with two rows is the same room twice",
            SettingsDoor.entries.toSet(),
            doors.map { it.door }.toSet(),
        )
        assertEquals(
            "a duplicated row is a screen reached by two identical-looking entries",
            doors.size,
            doors.map { it.door }.toSet().size,
        )
    }

    @Test
    fun noTwoDoorsShareATitleOrANote() {
        assertEquals(
            "two rows reading the same are how a user opens the wrong screen and blames the app",
            doors.size,
            doors.map { it.labelRes }.toSet().size,
        )
        assertEquals(doors.size, doors.map { it.noteRes }.toSet().size)
    }

    @Test
    fun everyGroupIsIntroducedByAHeadingAndHoldsSomething() {
        var currentHeader = -1
        var doorsInGroup = 0
        items.forEach { item ->
            when (item) {
                is SettingsHubHeader -> {
                    assertTrue(
                        "a heading with nothing under it is a heading that promises a section that is not there",
                        doorsInGroup > 0,
                    )
                    currentHeader = item.labelRes
                    doorsInGroup = 0
                }

                is SettingsHubDoor -> {
                    assertTrue(
                        "${item.door} sits under no heading, so the list reads as one undifferentiated run",
                        currentHeader >= 0,
                    )
                    doorsInGroup++
                }
            }
        }
        assertTrue("the last group needs something in it too", doorsInGroup > 0)
    }

    @Test
    fun theGroupsAreTheThreeAReaderHasQuestionsAbout() {
        assertEquals(
            "connected as what, what happens to my photos, and what does this app look and ask for",
            listOf(
                R.string.settings_section_account,
                R.string.settings_section_backup,
                R.string.settings_section_app,
            ),
            items.filterIsInstance<SettingsHubHeader>().map { it.labelRes },
        )
    }

    @Test
    fun theDoorsAreInReadingOrderWithinTheirGroup() {
        val accountGroup = doors.takeWhile { it.door != SettingsDoor.Backup }.map { it.door }
        assertEquals(listOf(SettingsDoor.Account, SettingsDoor.Cloud), accountGroup)

        val appGroup = doors.dropWhile { it.door != SettingsDoor.Appearance }.map { it.door }
        assertEquals(
            listOf(
                SettingsDoor.Appearance,
                SettingsDoor.Notifications,
                SettingsDoor.Permissions,
                SettingsDoor.About,
            ),
            appGroup,
        )
    }

    @Test
    fun theStorageDoorSaysWhatIsBehindItRatherThanQuotingTheScreenItOpens() {
        val storage = doors.first { it.door == SettingsDoor.Storage }
        assertEquals(R.string.settings_storage_note, storage.noteRes)
        assertTrue(
            "borrowing the free-up-space screen's sentence made one row read as the other",
            storage.noteRes != R.string.free_space_entry_note,
        )
    }
}