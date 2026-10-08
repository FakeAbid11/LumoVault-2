package com.lumovault.app.ui.settings

import com.lumovault.app.ui.backup.TelegramWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing the account screen may offer, and whether it asks first.
 *
 * Both halves are one decision over the session's word, and both were previously written as
 * `if (word == Connected) … else …` at the call site — which is how a settings screen comes to offer a
 * "Reconnect" door on a healthy session, or to end a session it never had.
 */
class AccountActionTest {

    @Test
    fun onlyALiveSessionCanBeSignedOutOf() {
        assertEquals(AccountAction.SignOut, accountActionFor(TelegramWord.Connected))
        assertEquals(AccountAction.Connect, accountActionFor(TelegramWord.WaitingForSignIn))
        assertEquals(
            "a build that cannot reach Telegram has no session to end, so offering Sign out would be "
                + "offering to break something that was never joined",
            AccountAction.Connect,
            accountActionFor(TelegramWord.NotConfigured),
        )
        assertEquals(AccountAction.Connect, accountActionFor(TelegramWord.Unavailable))
    }

    @Test
    fun signingOutAsksFirstExactlyWhereItCanBeAsked() {
        assertTrue(
            "getting back in costs a phone number and a two-step password, so the press confirms first",
            signOutNeedsConfirming(TelegramWord.Connected),
        )
        listOf(
            TelegramWord.WaitingForSignIn,
            TelegramWord.NotConfigured,
            TelegramWord.Unavailable,
        ).forEach { word ->
            assertFalse(
                "$word has no Sign out row to confirm, so asking would be a dialog with nothing behind it",
                signOutNeedsConfirming(word),
            )
        }
    }

    @Test
    fun confirmationIsOfferedForTheRowThatExistsAndNowhereElse() {
        TelegramWord.entries.forEach { word ->
            assertEquals(
                "the confirmation exists to protect one row, so it must exist on exactly that row's states",
                accountActionFor(word) == AccountAction.SignOut,
                signOutNeedsConfirming(word),
            )
        }
    }
}