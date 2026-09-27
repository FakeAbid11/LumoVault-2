package com.lumovault.app.data.backup

import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.usecase.QueueRun
import com.lumovault.app.domain.usecase.RunAutomaticBackupUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 *
 * Which is why the assertions below name a *chain* and not just a mapping. Reading `wifiOnly` into
 * `NetworkType.UNMETERED` correctly and then putting it on the scan request produced a worse failure than the
 * one this file was written for: a phone off its charger stopped noticing new photos at all, quietly, with no
 * queue to report as waiting. A pass's whole identity is which chain it joins and what that chain will wait
 * for, so both are asserted here — through `PassPlan`, which is decided without a WorkManager instance, rather
 * than through a `Constraints` object that cannot be inspected off-device.
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
    fun theScanPassThatFillsTheQueueWaitsForNothing() {
        // The point of this file. Both scan passes read MediaStore and write rows, which needs neither a
        // network nor a charger, so no preference may stand in front of them — and the assertion is made
        // against the strictest possible settings pair, because a plan that ignores `preferences` entirely is
        // the plan that is correct here.
        val strictest = BackupPreferences(automatic = true, wifiOnly = true, chargingOnly = true)

        assertNull(
            "a charger holding back the scan means nothing is ever queued, and a queue that is empty has no " +
                "waiting reason for the Backup screen to name — the feature reads as off from a screen that " +
                "says it is on",
            periodicScanPlan().waitsFor,
        )
        assertNull(
            "the one-shot scan a folder save asks for is the same worker, so it waits for the same nothing",
            immediateScanPlan().waitsFor,
        )
    }

    @Test
    fun theSendIsTheOnlyChainThePreferencesBound() {
        // The companion to the test above, and the one that stops the fix being made by stripping constraints
        // everywhere: under the same strict settings the *send* must still wait for both.
        val strictest = BackupPreferences(automatic = true, wifiOnly = true, chargingOnly = true)

        assertEquals(
            WorkRequest(requiresUnmeteredNetwork = true, requiresCharging = true),
            automaticSendPlan(strictest).waitsFor,
        )
    }

    @Test
    fun aHandTappedSendNeverInheritsAPreferenceHoweverItIsSet() {
        for (wifiOnly in listOf(true, false)) {
            for (chargingOnly in listOf(true, false)) {
                val preferences = BackupPreferences(
                    automatic = true,
                    wifiOnly = wifiOnly,
                    chargingOnly = chargingOnly,
                )

                assertEquals(
                    "wifiOnly=$wifiOnly chargingOnly=$chargingOnly must not reach a tap",
                    manualWorkRequest,
                    manualSendPlan().waitsFor,
                )
            }
        }
    }

    @Test
    fun noTwoPassesEverShareAChainName() {
        // A chain waits for the constraints of the work inside it, so a shared name is a shared promise about
        // what will hold it up. This is the shape the bug came back in once already.
        val strictest = BackupPreferences(automatic = true, wifiOnly = true, chargingOnly = true)
        val names = listOf(
            periodicScanPlan().workName,
            immediateScanPlan().workName,
            automaticSendPlan(strictest).workName,
            manualSendPlan().workName,
        )

        assertEquals("all four chains are addressed apart: $names", names.size, names.distinct().size)
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

    @Test
    fun aScanWindowThatFilledKeepsGoingAndOneThatDidNotIsDone() {
        // The only branch of the scan policy a user would ever notice, and until now the only one with no test
        // beside it: it was written inline in a `try` inside `doWork`, which no JVM test can reach.
        assertEquals(
            "a full window means the library is larger than one pass, and the pass is telling itself to keep " +
                "going rather than the app claiming a whole library was one run",
            AutoPassDirective.TryAgain,
            RunAutomaticBackupUseCase.Outcome.Queued(queued = 200, moreRemaining = true)
                .toAutoPassDirective(attempt = 0),
        )
        assertEquals(
            AutoPassDirective.Finished,
            RunAutomaticBackupUseCase.Outcome.Queued(queued = 3, moreRemaining = false)
                .toAutoPassDirective(attempt = 0),
        )
    }

    @Test
    fun aMissingGrantIsRetriedUpToTheLimitAndThenLeftAlone() {
        // The grant can come back without another period elapsing, so the retries are worth having. Past the
        // limit it is a decision the person made, and work that can only return the same answer should not
        // wake them every backoff interval for ever.
        for (attempt in 0 until ACCESS_RETRY_LIMIT) {
            assertEquals(
                "attempt $attempt",
                AutoPassDirective.TryAgain,
                RunAutomaticBackupUseCase.Outcome.NoMediaAccess.toAutoPassDirective(attempt = attempt),
            )
        }
        assertEquals(
            "at the limit the next period decides, not this pass",
            AutoPassDirective.Finished,
            RunAutomaticBackupUseCase.Outcome.NoMediaAccess
                .toAutoPassDirective(attempt = ACCESS_RETRY_LIMIT),
        )
    }

    @Test
    fun aPassToldNotToRunDoesNotAskAgain() {
        for (refusal in listOf(
            RunAutomaticBackupUseCase.Outcome.Disabled,
            RunAutomaticBackupUseCase.Outcome.NoSourceSelected,
        )) {
            assertEquals(
                "$refusal is an answer the app already has, not a condition to wait out",
                AutoPassDirective.Finished,
                refusal.toAutoPassDirective(attempt = 0),
            )
        }
    }
}
