package com.lumovault.app.data.remote.telegram

import com.lumovault.app.BuildConfig

/**
 * Telegram's api_id/api_hash, which identify the *app*, not the user. They arrive as build
 * configuration (see `app/build.gradle.kts`) and are absent by default, which the auth repository
 * reports as [com.lumovault.app.domain.telegram.TelegramAuthState.NotConfigured] instead of failing.
 *
 * `toString` is overridden because this is a data class: without this, a single accidental
 * `Log.d("creds=$creds")` would put the hash in logcat, and hashes are the credential half of the
 * Telegram API pair.
 */
data class TelegramCredentials(val apiId: Int, val apiHash: String) {
    val isConfigured: Boolean get() = apiId > 0 && apiHash.isNotBlank()

    override fun toString(): String = "TelegramCredentials(apiId=$apiId, apiHash=<redacted>)"

    companion object {
        fun fromBuildConfig(): TelegramCredentials =
            TelegramCredentials(apiId = BuildConfig.TELEGRAM_API_ID, apiHash = BuildConfig.TELEGRAM_API_HASH)
    }
}
