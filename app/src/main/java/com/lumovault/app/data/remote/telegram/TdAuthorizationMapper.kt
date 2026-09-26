package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthFailure
import com.lumovault.app.domain.telegram.TelegramAuthState
import org.drinkless.tdlib.TdApi

/**
 * What an [TdApi.AuthorizationState] means, split into the two bookkeeping steps LumoVault has to
 * perform and the states the UI actually renders.
 *
 * Pure and `internal` so it can be driven from hand-built TDLib objects in JVM tests: the
 * phone-number → code → password → ready sequence is the part of the login flow that must not be
 * wrong, and it should not need a device or a live account to prove.
 *
 * The `when` is exhaustive over the states TDLib can send at the pinned revision, with an `else` in
 * front of `Unknown`: a state this build does not name must read as "not one of ours", never as a
 * signed-in session.
 */
internal object TdAuthorizationMapper {
    /** Reads the state out of a response and classifies it. Null when the object carries none. */
    fun classifyResponse(response: TdApi.Object): Classification? =
        authorizationStateOf(response)?.let { classify(it) }

    /**
     * Pulls the state out of the wrapper update, or accepts a bare state object.
     *
     * [TdApi.Updates] is what `getCurrentState` answers with, and it carries the state among whatever
     * else TDLib thinks the process needs to restore — so it is searched rather than assumed to hold
     * exactly one update.
     */
    fun authorizationStateOf(response: TdApi.Object): TdApi.AuthorizationState? = when (response) {
        is TdApi.UpdateAuthorizationState -> response.authorizationState
        is TdApi.Updates -> response.updates.firstNotNullOfOrNull { authorizationStateOf(it) }
        is TdApi.AuthorizationState -> response
        else -> null
    }

    fun classify(state: TdApi.AuthorizationState): Classification = when (state) {
        is TdApi.AuthorizationStateWaitTdlibParameters -> Classification.NeedsParameters

        is TdApi.AuthorizationStateReady -> Classification.State(TelegramAuthState.Authenticated)

        // Closed means no session and nothing pending, which is exactly where the phone prompt belongs.
        is TdApi.AuthorizationStateClosed, is TdApi.AuthorizationStateWaitPhoneNumber ->
            Classification.State(TelegramAuthState.ReadyForPhoneNumber)

        // The number is valid but has no Telegram account; registering one is not LumoVault's job.
        is TdApi.AuthorizationStateWaitRegistration -> Classification.State(
            TelegramAuthState.Failed(TelegramAuthFailure(TelegramAuthFailure.Kind.AccountNotFound)),
        )

        is TdApi.AuthorizationStateWaitCode -> Classification.State(
            TelegramAuthState.WaitingForCode(
                channel = channelOf(state.codeInfo?.type),
                codeLength = state.codeInfo?.type.codeLength(),
            ),
        )

        is TdApi.AuthorizationStateWaitPassword -> Classification.State(
            TelegramAuthState.WaitingForPassword(hint = state.passwordHint.orEmpty()),
        )

        is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing ->
            Classification.State(TelegramAuthState.Initializing)

        else -> Classification.State(TelegramAuthState.Unknown)
    }

    private fun channelOf(codeType: TdApi.AuthenticationCodeType?): AuthCodeChannel = when (codeType) {
        is TdApi.AuthenticationCodeTypeSms -> AuthCodeChannel.Sms
        is TdApi.AuthenticationCodeTypeCall -> AuthCodeChannel.Call
        is TdApi.AuthenticationCodeTypeFlashCall -> AuthCodeChannel.FlashCall
        is TdApi.AuthenticationCodeTypeMissedCall -> AuthCodeChannel.MissedCall

        // Telegram's own in-app message is a channel LumoVault has no wording for, and every other
        // constructor (Firebase, Fragment, SMS words and phrases, whatever arrives next) is one it has
        // never heard of. Both arrive as Unknown rather than being forced onto the nearest label.
        else -> AuthCodeChannel.Unknown
    }

    /**
     * The code length Telegram asked for, or null when the delivery type carries no such field.
     *
     * `length` is a Java `int`, so an absent one reads as 0 rather than null; TDLib's own constructors
     * that omit it (FlashCall's `pattern`, the SMS word and phrase types) are exactly the ones where
     * the entry field must stay unconstrained.
     */
    private fun TdApi.AuthenticationCodeType?.codeLength(): Int? = when (this) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> length
        is TdApi.AuthenticationCodeTypeSms -> length
        is TdApi.AuthenticationCodeTypeCall -> length
        is TdApi.AuthenticationCodeTypeMissedCall -> length
        is TdApi.AuthenticationCodeTypeFragment -> length
        is TdApi.AuthenticationCodeTypeFirebaseAndroid -> length
        is TdApi.AuthenticationCodeTypeFirebaseIos -> length
        else -> null
    }?.takeIf { it > 0 }
}

/** Either a state to show, or a setup step the repository must perform before asking again. */
internal sealed interface Classification {
    data class State(val value: TelegramAuthState) : Classification
    data object NeedsParameters : Classification
}
