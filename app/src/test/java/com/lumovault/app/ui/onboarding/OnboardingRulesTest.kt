package com.lumovault.app.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which sign-in screens offer to be skipped, decided away from the composable.
 *
 * The shape of the bug this pins is a control that means two different things depending on where it was
 * tapped: a skip beside a code entry would abandon a request Telegram has already answered, one on the
 * reconnect screen would offer a way out of the screen whose only job is to be that door, and one next to a
 * primary button that already leaves the step in a build without credentials gives the user two answers to
 * one question — which is how they start disagreeing.
 */
class OnboardingRulesTest {
    @Test
    fun thePhonePanelInSetupWithTelegramAvailableAndNothingInFlightCanBeSkipped() {
        assertTrue(
            telegramSkipOffered(
                panel = TelegramPanel.Phone,
                standalone = false,
                buildHasTelegram = true,
                busy = false,
            ),
        )
    }

    @Test
    fun aPanelThatIsAlreadyAnsweringTelegramCannotBeSkipped() {
        for (panel in listOf(TelegramPanel.Code, TelegramPanel.Password)) {
            assertFalse(
                "$panel is a step the user chose by sending a number",
                telegramSkipOffered(panel, standalone = false, buildHasTelegram = true, busy = false),
            )
        }
    }

    @Test
    fun theStandaloneReconnectScreenKeepsOnlyItsBackArrow() {
        assertFalse(
            telegramSkipOffered(
                panel = TelegramPanel.Phone,
                standalone = true,
                buildHasTelegram = true,
                busy = false,
            ),
        )
    }

    @Test
    fun aBuildWithoutTelegramDoesNotGetTwoDoorsOutOfTheSameStep() {
        assertFalse(
            "its primary button already moves past the step",
            telegramSkipOffered(
                panel = TelegramPanel.Phone,
                standalone = false,
                buildHasTelegram = false,
                busy = false,
            ),
        )
    }

    @Test
    fun aRequestInFlightIsNotAbandonedByASkip() {
        assertFalse(
            telegramSkipOffered(
                panel = TelegramPanel.Phone,
                standalone = false,
                buildHasTelegram = true,
                busy = true,
            ),
        )
    }
}
