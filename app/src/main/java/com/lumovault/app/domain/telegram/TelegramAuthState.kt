package com.lumovault.app.domain.telegram

/**
 * Every state LumoVault can observe in the Telegram sign-in flow. The UI renders from this and
 * nothing else, so a screen cannot advance past a step Telegram has not actually confirmed —
 * [Authenticated] arrives only when TDLib reports its ready authorization state.
 *
 * [NotConfigured] is deliberately separate from [Failed]: a build without the TDLib binary or
 * without API credentials is a packaging gap, not something the user did wrong.
 */
sealed interface TelegramAuthState {
    /** Not decided yet; the UI waits instead of showing the phone prompt. */
    data object Unknown : TelegramAuthState

    /** TDLib's native library or the API id/hash is missing from this build. */
    data object NotConfigured : TelegramAuthState

    /** Client starting up and reading its on-disk session. */
    data object Initializing : TelegramAuthState

    data object ReadyForPhoneNumber : TelegramAuthState
    data object SendingCode : TelegramAuthState

    data class WaitingForCode(
        /**
         * How Telegram is sending the code. Modern TDLib reports the channel but not the number
         * tail, so the UI masks the number the user typed instead of inventing a hint.
         */
        val channel: AuthCodeChannel,
        /** Code length Telegram asked for, so the entry field is neither hardcoded nor guessed. */
        val codeLength: Int?,
    ) : TelegramAuthState

    data object VerifyingCode : TelegramAuthState

    data class WaitingForPassword(val hint: String) : TelegramAuthState

    data object Authenticating : TelegramAuthState

    /**
     * A confirmed session. Account details (the user id, the channel to create later) come from
     * `getMe` in Phase 4, which is the first phase that needs them.
     */
    data object Authenticated : TelegramAuthState

    data class Failed(val failure: TelegramAuthFailure) : TelegramAuthState
}

/** True only for a confirmed session; drives both the onboarding checklist and launch decisions. */
val TelegramAuthState.isAuthenticated: Boolean
    get() = this is TelegramAuthState.Authenticated
