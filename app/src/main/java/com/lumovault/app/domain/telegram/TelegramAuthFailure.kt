package com.lumovault.app.domain.telegram

/**
 * What went wrong during authentication, described by meaning rather than by Telegram's raw text.
 *
 * The UI owns the wording, and this layer never carries an exception message to the screen — a
 * response that happens to quote the number being dialled cannot leak through as visible text.
 */
data class TelegramAuthFailure(
    val kind: Kind,
    /** Set for [Kind.TooManyRequests] so the UI can say *when* to retry instead of hammering Telegram. */
    val retryAfterSeconds: Int? = null,
) {
    enum class Kind {
        InvalidPhoneNumber,
        InvalidCode,
        CodeExpired,
        PasswordIncorrect,
        TooManyRequests,
        NetworkUnavailable,

        /** The number is valid but has no Telegram account; LumoVault does not register accounts. */
        AccountNotFound,

        /** The stored session died. Reconnecting is needed; onboarding itself is not. */
        SessionInvalid,

        /** TDLib reported something LumoVault does not model. */
        Unexpected,
    }
}
