package com.lumovault.app.ui.settings

import com.lumovault.app.R
import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.ui.components.PillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Permissions screen claims about Android's answers, decided without a phone.
 *
 * These rows cannot be seen in a unit test through a composable, and every one of them is a place where a
 * plausible-looking screen would report something false: a partial grant called empty, a version with no
 * notification permission called refused, a device with no battery page given a button that goes nowhere,
 * or a permission the user already granted given a second way to change it. All four read the same
 * repository the onboarding setup cards read, which is what makes them comparable.
 */
class PermissionRowsTest {
    private fun rows(
        media: MediaAccessStatus = MediaAccessStatus.Granted,
        notifications: NotificationsStatus = NotificationsStatus.Granted,
        photoLocationGranted: Boolean = true,
        backgroundBackup: BackgroundBackupStatus = BackgroundBackupStatus.Unrestricted,
        canOpenBatterySettings: Boolean = true,
    ) = permissionRowsFor(media, notifications, photoLocationGranted, backgroundBackup, canOpenBatterySettings)

    private fun row(kind: PermissionKind, from: List<PermissionRow>) = from.first { it.kind == kind }

    @Test
    fun everyOneOfTheFourPermissionsIsListedInAFixedOrder() {
        assertEquals(
            "the library, the alerts, the coordinates, the power budget — the order a reader meets them in",
            listOf(
                PermissionKind.Media,
                PermissionKind.Notifications,
                PermissionKind.PhotoLocation,
                PermissionKind.BackgroundBackup,
            ),
            rows().map { it.kind },
        )
    }

    @Test
    fun aGrantedLibraryIsAllowedAndOffersNothing() {
        val media = row(PermissionKind.Media, rows(media = MediaAccessStatus.Granted))

        assertEquals(R.string.status_allowed, media.statusRes)
        assertEquals(PillTone.Done, media.tone)
        assertEquals(
            "a grant the user already gave is not a shortcut, it is a second copy of a decision made",
            PermissionFix.None,
            media.fix,
        )
        assertNull(media.fixNoteRes)
    }

    @Test
    fun partialAccessIsARealGrantOfPartOfTheLibrary() {
        val media = row(PermissionKind.Media, rows(media = MediaAccessStatus.PartiallyGranted))

        assertEquals(
            "\"some of it\" is what Android reported, and reporting it as a gap would send the user to fix "
                + "a decision they made",
            R.string.status_partly_allowed,
            media.statusRes,
        )
        assertEquals(PillTone.Done, media.tone)
        assertEquals(PermissionFix.None, media.fix)
    }

    @Test
    fun aRefusedOrUnlookedUpLibrarySendsTheUserToTheOnePageThatCanChangeIt() {
        listOf(MediaAccessStatus.Denied, MediaAccessStatus.Unknown).forEach { status ->
            val media = row(PermissionKind.Media, rows(media = status))

            assertEquals(
                "$status is the same gap as far as the user's next step is concerned",
                R.string.status_not_set,
                media.statusRes,
            )
            assertEquals(PillTone.Missing, media.tone)
            assertEquals(PermissionFix.OpenAppSettings, media.fix)
            assertEquals(R.string.permissions_open_system, media.fixLabelRes)
        }
    }

    @Test
    fun anAndroidVersionWithoutNotificationPermissionIsSatisfiedRatherThanRefused() {
        val notRequired = row(
            PermissionKind.Notifications,
            rows(notifications = NotificationsStatus.NotRequired),
        )

        assertEquals(
            "there is no dialog to re-open on this version, so \"Skipped\" would report a refusal nobody made",
            R.string.status_not_required,
            notRequired.statusRes,
        )
        assertEquals(PillTone.Done, notRequired.tone)
        assertEquals(PermissionFix.None, notRequired.fix)
        assertEquals(
            "and it explains itself rather than repeating the permission's own paragraph",
            R.string.setup_notifications_not_required,
            notRequired.descriptionRes,
        )
    }

    @Test
    fun aRefusedNotificationPermissionIsSkippedAndStillOffersThePage() {
        val denied = row(PermissionKind.Notifications, rows(notifications = NotificationsStatus.Denied))

        assertEquals(R.string.status_skipped, denied.statusRes)
        assertEquals(PillTone.Skipped, denied.tone)
        assertEquals(
            "the user said no, and the way to change their mind is system settings",
            PermissionFix.OpenAppSettings,
            denied.fix,
        )
    }

    @Test
    fun photoLocationsAreReportedWhetherOrNotTheyAreGranted() {
        assertEquals(
            R.string.status_allowed,
            row(PermissionKind.PhotoLocation, rows(photoLocationGranted = true)).statusRes,
        )
        val refused = row(PermissionKind.PhotoLocation, rows(photoLocationGranted = false))
        assertEquals(R.string.status_not_set, refused.statusRes)
        assertEquals(PillTone.Missing, refused.tone)
        assertEquals(
            "Settings must not be the one screen that cannot say why the map is empty",
            PermissionFix.OpenAppSettings,
            refused.fix,
        )
    }

    @Test
    fun anUnrestrictedBatteryIsADoneWithNothingToPress() {
        val background = row(PermissionKind.BackgroundBackup, rows())

        assertEquals(R.string.status_unrestricted, background.statusRes)
        assertEquals(PillTone.Done, background.tone)
        assertEquals(PermissionFix.None, background.fix)
    }

    @Test
    fun aRestrictedBatterySendsTheUserToTheBatteryPageOnlyWhenTheDeviceHasOne() {
        val withPage = row(PermissionKind.BackgroundBackup, rows(backgroundBackup = BackgroundBackupStatus.Restricted))

        assertEquals(R.string.status_restricted, withPage.statusRes)
        assertEquals(PillTone.Missing, withPage.tone)
        assertEquals(PermissionFix.OpenBatterySettings, withPage.fix)
        assertEquals(R.string.permissions_open_battery, withPage.fixLabelRes)
        assertNull(withPage.fixNoteRes)

        val withoutPage = row(
            PermissionKind.BackgroundBackup,
            rows(backgroundBackup = BackgroundBackupStatus.Restricted, canOpenBatterySettings = false),
        )
        assertEquals(
            "a phone with no battery page cannot be sent anywhere useful, and the row has to say so",
            PermissionFix.Unavailable,
            withoutPage.fix,
        )
        assertEquals(R.string.permissions_no_way_to_fix, withoutPage.fixNoteRes)
    }

    @Test
    fun anUnlookedUpBatteryIsUnavailableRatherThanRestricted() {
        val unknown = row(PermissionKind.BackgroundBackup, rows(backgroundBackup = BackgroundBackupStatus.Unknown))

        assertEquals(
            "\"Restricted\" would be a claim about the device that this app has not checked for",
            R.string.status_unavailable,
            unknown.statusRes,
        )
        assertEquals(PillTone.Unavailable, unknown.tone)
    }

    @Test
    fun noRowEverPrintsAFixNoteWithoutHavingNothingToPressInstead() {
        val everyState = rows(
            media = MediaAccessStatus.Granted,
            notifications = NotificationsStatus.NotRequired,
            backgroundBackup = BackgroundBackupStatus.Unrestricted,
        )
        everyState.forEach { row ->
            val hasButton = row.fix == PermissionFix.OpenAppSettings || row.fix == PermissionFix.OpenBatterySettings
            assertTrue(
                "${row.kind} prints ${row.fixNoteRes} while still offering ${row.fix}: the row explains the "
                    + "absence of a button and then draws one",
                hasButton || row.fixNoteRes == null,
            )
        }
    }
}