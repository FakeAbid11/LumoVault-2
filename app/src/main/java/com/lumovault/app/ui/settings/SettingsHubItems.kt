package com.lumovault.app.ui.settings

import androidx.annotation.StringRes
import com.lumovault.app.R

/**
 * The settings hub's content, as data.
 *
 * The hub used to be seven doors in one flat list with no headings at all, which is a list to read rather
 * than a map to navigate: nothing told the reader which of the seven rooms shared a subject, so the only
 * way to find the appearance setting was to remember roughly where it sat. It is data rather than a
 * composable body for two reasons. The first is that a door added to a screen and forgotten in its
 * section is invisible — a door here has nowhere to hide. The second is that the sections themselves are
 * worth asserting: every door exactly once, every section with something in it, and a heading before the
 * first door of its group, so a category can be added without re-deriving what belongs beside it.
 */
internal sealed interface SettingsHubItem {
    /** What this item contributes: a heading's words, or a door's. */
    @get:StringRes
    val labelRes: Int
}

/** A heading. It separates; it is not a door and it takes no press. */
internal data class SettingsHubHeader(@StringRes override val labelRes: Int) : SettingsHubItem

/** One room. [noteRes] says what is behind it, because a settings list that renamed existing screens
 * would send the reader looking for a second copy of something already built. */
internal data class SettingsHubDoor(
    val door: SettingsDoor,
    @StringRes override val labelRes: Int,
    @StringRes val noteRes: Int,
) : SettingsHubItem

/**
 * The doors themselves, so the hub can hand each one the right way out.
 *
 * An enum rather than a lambda per row because a list of pairs of label and callback is how a door ends up
 * calling the previous row's destination: the label and the navigation are then written separately and can
 * disagree, and nothing says so until someone taps the wrong one.
 */
internal enum class SettingsDoor {
    Account,
    Cloud,
    Backup,
    Storage,
    Appearance,
    Notifications,
    Permissions,
    About,
}

/**
 * The hub, in reading order.
 *
 * Three groups, not one per category: the doors were seven rooms because the app has seven subjects, but
 * a user has three questions — who am I connected as, what happens to my photos, and what does this app
 * look and ask for. Backup and Storage are one group because they are one subsystem (the backup hub's own
 * third section is already called Storage), and splitting them would print two headings over four rows.
 */
internal fun settingsHubItems(): List<SettingsHubItem> = listOf(
    SettingsHubHeader(R.string.settings_section_account),
    SettingsHubDoor(
        door = SettingsDoor.Account,
        labelRes = R.string.settings_account,
        noteRes = R.string.settings_account_note,
    ),
    SettingsHubDoor(
        door = SettingsDoor.Cloud,
        labelRes = R.string.settings_cloud,
        noteRes = R.string.settings_cloud_note,
    ),
    SettingsHubHeader(R.string.settings_section_backup),
    SettingsHubDoor(
        door = SettingsDoor.Backup,
        labelRes = R.string.settings_backup,
        noteRes = R.string.settings_backup_note,
    ),
    SettingsHubDoor(
        // Its own sentence rather than the free-up-space screen's: this row is a door, and a door's note
        // should say what is on the other side of it rather than quote the screen it opens.
        door = SettingsDoor.Storage,
        labelRes = R.string.settings_storage,
        noteRes = R.string.settings_storage_note,
    ),
    SettingsHubHeader(R.string.settings_section_app),
    SettingsHubDoor(
        door = SettingsDoor.Appearance,
        labelRes = R.string.settings_appearance,
        noteRes = R.string.settings_appearance_note,
    ),
    SettingsHubDoor(
        door = SettingsDoor.Notifications,
        labelRes = R.string.settings_notifications,
        noteRes = R.string.settings_notifications_note,
    ),
    SettingsHubDoor(
        door = SettingsDoor.Permissions,
        labelRes = R.string.settings_permissions,
        noteRes = R.string.settings_permissions_note,
    ),
    SettingsHubDoor(
        door = SettingsDoor.About,
        labelRes = R.string.settings_about,
        noteRes = R.string.settings_about_note,
    ),
)