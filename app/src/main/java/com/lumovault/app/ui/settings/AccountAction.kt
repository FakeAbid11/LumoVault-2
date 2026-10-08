package com.lumovault.app.ui.settings

import com.lumovault.app.ui.backup.TelegramWord

/**
 * The one action the account screen offers, decided from the session's word.
 *
 * It is a function because the two states are opposites and getting them the wrong way round is a real
 * mistake rather than a cosmetic one: a "Reconnect" door on a live session leads to a sign-in screen with
 * nothing to do, and a "Sign out" row on a build that cannot reach Telegram offers to end a session this
 * app never had.
 */
internal enum class AccountAction {
    /** There is a session to end. */
    SignOut,

    /** There is not — the sign-in panels are behind this door. */
    Connect,
}

internal fun accountActionFor(word: TelegramWord): AccountAction = when (word) {
    TelegramWord.Connected -> AccountAction.SignOut
    TelegramWord.WaitingForSignIn,
    TelegramWord.NotConfigured,
    TelegramWord.Unavailable,
    -> AccountAction.Connect
}

/**
 * Whether ending the session asks first.
 *
 * It does, and the reason is worth stating because the previous behaviour — the row's own note explaining
 * that nothing is lost, and signing out straight away — was defensible. A confirmation that says what
 * happens is still better here: signing out does not remove backups, but a user who taps it by mistake has
 * to type a phone number and a two-step password to get back, and that cost is not visible in the row's
 * one-line note while the finger is already on the glass. One extra press buys a decision the user can see
 * the shape of first.
 *
 * Only a live session offers the row at all, so this is true exactly where it can be asked.
 */
internal fun signOutNeedsConfirming(word: TelegramWord): Boolean = accountActionFor(word) == AccountAction.SignOut