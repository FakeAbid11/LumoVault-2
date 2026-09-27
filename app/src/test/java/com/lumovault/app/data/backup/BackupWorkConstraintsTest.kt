package com.lumovault.app.data.backup

import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.usecase.QueueRun
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which background work waits for what, and what a finished pass asks for next — both decided away from
 * WorkManager.
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

    @Test
    fun aPassThatFinishedItsWorkDoesNotAskForAnother() {
        assertEquals(
            PassDirective.Finished,
            QueueRun.Done(sent = 2, failed = 0, deferred = false).toPassDirective(attempt = 0, recognitionStoppedEarly = false),
        )
        assertEquals(
            "a recognition pass that ran out of its budget left the frontier unhashed, so the queue has " +
                "nothing to deduplicate against yet",
            PassDirective.TryAgain,
            QueueRun.Done(sent = 0, failed = 0, deferred = false).toPassDirective(attempt = 0, recognitionStoppedEarly = true),
        )
    }

    @Test
    fun aRefusedItemIsLeftToBackoffRatherThanRetriedHere() {
        assertEquals(
            PassDirective.TryAgain,
            QueueRun.Done(sent = 1, failed = 1, deferred = true).toPassDirective(attempt = 0, recognitionStoppedEarly = false),
        )
    }

    @Test
    fun aStopOnlyAPersonCanUndoIsRetriedAndThenSaidOutLoud() {
        // Both refusals are the same shape: nothing failed, and nothing can move until somebody signs in or
        // lets the app adopt a channel. Ending on the first one would leave a full queue unattended until
        // the next periodic pass.
        for (refusal in listOf(QueueRun.NoChannel, QueueRun.SignedOut)) {
            for (attempt in 0 until STOP_DIRECTIVE_ATTEMPT_LIMIT) {
                assertEquals(
                    "$refusal attempt $attempt",
                    PassDirective.TryAgain,
                    refusal.toPassDirective(attempt = attempt, recognitionStoppedEarly = false),
                )
            }
            assertEquals(
                "$refusal at the limit must stop asking, and leave the reason to a screen",
                PassDirective.Abandoned,
                refusal.toPassDirective(attempt = STOP_DIRECTIVE_ATTEMPT_LIMIT, recognitionStoppedEarly = false),
            )
        }
    }

    @Test
    fun aBuildWithNoTelegramIsOverOnTheFirstPass() {
        assertEquals(
            "no retry and no user can give this build a TDLib binary",
            PassDirective.Abandoned,
            QueueRun.TelegramUnavailable.toPassDirective(attempt = 0, recognitionStoppedEarly = false),
        )
    }
}
