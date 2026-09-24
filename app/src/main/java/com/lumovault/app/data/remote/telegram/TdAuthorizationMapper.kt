package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthFailure
import com.lumovault.app.domain.telegram.TelegramAuthState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What TDLib's `authorizationState*` means, split into the two bookkeeping steps LumoVault has to
 * perform and the states the UI actually renders.
 *
 * Pure and `internal` so it can be driven from canned JSON in JVM tests: the phone-number → code →
 * password → ready sequence is the part of Phase 2 that must not be wrong, and it should not need a
 * device or a live account to prove.
 */
internal object TdAuthorizationMapper {
    /** Reads the state out of a response and classifies it. Null when the object carries none. */
    fun classifyResponse(response: JsonObject): Classification? =
        authorizationStateOf(response)?.let { classify(it) }

    /** Pulls `authorization_state` out of the wrapper update, or accepts a bare state object. */
    fun authorizationStateOf(response: JsonObject): JsonObject? =
        when (response.type()) {
            "updateAuthorizationState" -> response.child(AUTH_STATE)
            null -> null
            else -> response.takeIf { it.type()?.startsWith(AUTHORIZATION_STATE_PREFIX) == true }
        }

    fun classify(state: JsonObject): Classification = when (state.type()) {
        "authorizationStateWaitTdlibParameters" -> Classification.NeedsParameters

        "authorizationStateReady" -> Classification.State(TelegramAuthState.Authenticated)

        // Closed means no session and nothing pending, which is exactly where the phone prompt belongs.
        "authorizationStateClosed", "authorizationStateWaitPhoneNumber" ->
            Classification.State(TelegramAuthState.ReadyForPhoneNumber)

        // The number is valid but has no Telegram account; registering one is not LumoVault's job.
        "authorizationStateWaitRegistration" -> Classification.State(
            TelegramAuthState.Failed(TelegramAuthFailure(TelegramAuthFailure.Kind.AccountNotFound)),
        )

        // TDLib carries both the channel and the code length inside code_info.type.
        "authorizationStateWaitCode" -> {
            val codeType = state.child(CODE_INFO)?.child(AUTH_CODE_TYPE)
            Classification.State(
                TelegramAuthState.WaitingForCode(
                    channel = AuthCodeChannel.fromTdType(codeType?.type()),
                    codeLength = codeType?.int(LENGTH),
                ),
            )
        }

        "authorizationStateWaitPassword" -> Classification.State(
            TelegramAuthState.WaitingForPassword(hint = state.text(PASSWORD_HINT).orEmpty()),
        )

        "authorizationStateLoggingOut", "authorizationStateClosing" ->
            Classification.State(TelegramAuthState.Initializing)

        else -> Classification.State(TelegramAuthState.Unknown)
    }
}

/** Either a state to show, or a setup step the repository must perform before asking again. */
internal sealed interface Classification {
    data class State(val value: TelegramAuthState) : Classification
    data object NeedsParameters : Classification
}

private const val AUTH_STATE = "authorization_state"
private const val AUTHORIZATION_STATE_PREFIX = "authorizationState"
private const val CODE_INFO = "code_info"
private const val AUTH_CODE_TYPE = "type"
private const val TYPE = "@type"
private const val LENGTH = "length"
private const val PASSWORD_HINT = "password_hint"

internal fun JsonObject.type(): String? = text(TYPE)

internal fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }
        ?.content

internal fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()
