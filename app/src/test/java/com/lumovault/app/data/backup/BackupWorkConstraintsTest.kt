package com.lumovault.app.data.backup

import com.lumovault.app.domain.model.BackupPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which background work waits for what, decided away from WorkManager.
 *
 * This mapping is the entire difference between a preference and a promise. Wi-Fi-only and charging-only are
 * read from the settings row on every re-install and turned into constraints on a request, so a rule applied
 * to the wrong chain is invisible in the settings screen and obvious on a phone: a hand-tapped backup held
 * behind a Wi-Fi network the user never agreed to use is the failure this file exists to keep from coming
 * back, and it came back once already — both paths used to share one unique work name.
 */
class BackupWorkConstraintsTest {
    @Test
    fun theUnattendedSendWaitsForExactlyWhatTheUserTicked() {
        for (wifiOnly in listOf(true, false)) {
            for (chargingOnly in listOf(true, false)) {
                val preferences = BackupPreferences(
                    automatic = true,
                    wifiOnly = wifiOnly,
                    chargingOnly = chargingOnly,
                )

                assertEquals(
                    "wifiOnly=$wifiOnly chargingOnly=$chargingOnly",
                    WorkRequest(requiresUnmeteredNetwork = wifiOnly, requiresCharging = chargingOnly),
                    preferences.toAutomaticWorkRequest(),
                )
            }
        }
    }

    @Test
    fun aBackupTheUserStartedWaitsForAConnectionAndNothingElse() {
        assertEquals(
            "the tap is the agreement, so no preference may stand behind it",
            WorkRequest(requiresUnmeteredNetwork = false, requiresCharging = false),
            manualWorkRequest,
        )
    }

    @Test
    fun thePreferencesThatSwitchEverythingOffCarryNoConstraintsOfTheirOwn() {
        // `automatic = false` is decided by the pass and the schedule, not by the constraints: an unconstrained
        // send request for a queue the user filled by hand still has to work, which is why the mapping below
        // reads only the two network flags and never this one.
        val preferences = BackupPreferences(automatic = false, wifiOnly = true, chargingOnly = true)

        assertEquals(
            WorkRequest(requiresUnmeteredNetwork = true, requiresCharging = true),
            preferences.toAutomaticWorkRequest(),
        )
    }
}
