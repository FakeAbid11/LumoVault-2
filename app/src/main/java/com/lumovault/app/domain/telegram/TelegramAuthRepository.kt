package com.lumovault.app.domain.telegram

import kotlinx.coroutines.flow.StateFlow

/**
 * Everything LumoVault needs from Telegram authentication, with no TDLib types crossing the line.
 *
 * The UI depends on this interface only. Later phases (channel discovery, upload, download) get
 * their Telegram capabilities through sibling repositories, so nothing downstream learns how TDLib
 * happens to be driven.
 */
interface TelegramAuthRepository {
    /** [TelegramAuthState.Unknown] until the client has actually reported a state. */
    val state: StateFlow<TelegramAuthState>

    /** Starts the client and resolves any stored session. Safe to call more than once. */
    fun connect()

    /** [internationalNumber] must already be E.164 (see `PhoneNumbers`); this layer does not concatenate codes. */
    suspend fun requestCode(internationalNumber: String)

    suspend fun submitCode(code: String)

    suspend fun resendCode()

    /**
     * The password is used once and never stored or logged. Callers must not retain it in
     * long-lived state once this returns.
     */
    suspend fun submitPassword(password: String)

    /** Back out of a code request without discarding the entered number. */
    fun cancelPendingRequest()

    /** Clears the local session. Called by the future Settings sign-out, not by back navigation. */
    suspend fun signOut()
}
