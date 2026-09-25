package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthFailure
import com.lumovault.app.domain.telegram.TelegramAuthState
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the step order against TDLib's real [TdApi.AuthorizationState] classes: the UI shows exactly
 * what Telegram reported, so a wrong mapping here would let a user appear to be asked for a password
 * before any code had been sent — or appear signed in when they are not.
 *
 * The objects are built through TDLib's generated no-argument constructors rather than filled from
 * text. That is the point of the typed interface: a state LumoVault misremembers cannot be
 * constructed, so these fixtures are checked against the same class definitions production compiles
 * against.
 *
 * Nothing here asserts *on* a `TdApi` object: [TdApi.Object.toString] is a native method, so a failure
 * would report an [UnsatisfiedLinkError] instead of the difference that actually matters. Every
 * assertion is on LumoVault's own values.
 */
class TdAuthorizationMapperTest {
    private fun value(state: TdApi.AuthorizationState): TelegramAuthState =
        (TdAuthorizationMapper.classify(state) as Classification.State).value

    @Test
    fun `a closed or phone-waiting session both mean show the number field`() {
        assertEquals(
            TelegramAuthState.ReadyForPhoneNumber,
            value(TdApi.AuthorizationStateWaitPhoneNumber()),
        )
        assertEquals(TelegramAuthState.ReadyForPhoneNumber, value(TdApi.AuthorizationStateClosed()))
    }

    @Test
    fun `supplying parameters is a step to perform, not a state to render`() {
        assertEquals(
            Classification.NeedsParameters,
            TdAuthorizationMapper.classify(TdApi.AuthorizationStateWaitTdlibParameters()),
        )
    }

    @Test
    fun `channel and code length come from the code type itself`() {
        val waiting = value(
            TdApi.AuthorizationStateWaitCode().apply {
                codeInfo = TdApi.AuthenticationCodeInfo().apply {
                    phoneNumber = "+8801712345678"
                    type = TdApi.AuthenticationCodeTypeCall().apply { length = 6 }
                }
            },
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Call, 6), waiting)
    }

    @Test
    fun `a code type that was never given a length leaves the field unconstrained`() {
        val waiting = value(
            TdApi.AuthorizationStateWaitCode().apply {
                codeInfo = TdApi.AuthenticationCodeInfo().apply {
                    type = TdApi.AuthenticationCodeTypeSms()
                }
            },
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Sms, null), waiting)
    }

    @Test
    fun `a delivery type with no length field at all is unconstrained rather than zero`() {
        val waiting = value(
            TdApi.AuthorizationStateWaitCode().apply {
                codeInfo = TdApi.AuthenticationCodeInfo().apply {
                    type = TdApi.AuthenticationCodeTypeFlashCall().apply { pattern = "+880" }
                }
            },
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.FlashCall, null), waiting)
    }

    @Test
    fun `an unnamed delivery type degrades to unknown instead of throwing`() {
        val waiting = value(
            TdApi.AuthorizationStateWaitCode().apply {
                codeInfo = TdApi.AuthenticationCodeInfo().apply {
                    type = TdApi.AuthenticationCodeTypeFirebaseAndroid().apply { length = 5 }
                }
            },
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Unknown, 5), waiting)
    }

    @Test
    fun `a code info TDLib did not fill in is still a step to show`() {
        val waiting = value(TdApi.AuthorizationStateWaitCode())

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Unknown, null), waiting)
    }

    @Test
    fun `the password hint is carried through for display`() {
        assertEquals(
            TelegramAuthState.WaitingForPassword(hint = "city"),
            value(TdApi.AuthorizationStateWaitPassword().apply { passwordHint = "city" }),
        )
    }

    @Test
    fun `a number with no Telegram account is an explainable failure`() {
        val failure = value(TdApi.AuthorizationStateWaitRegistration()) as TelegramAuthState.Failed

        assertEquals(TelegramAuthFailure.Kind.AccountNotFound, failure.failure.kind)
    }

    @Test
    fun `only the ready state may be read as authenticated`() {
        assertEquals(TelegramAuthState.Authenticated, value(TdApi.AuthorizationStateReady()))
        // States newer than this mapping must never be mistaken for a signed-in session — and the
        // premium-purchase and email steps are exactly the ones Telegram has added over the years.
        assertEquals(TelegramAuthState.Unknown, value(TdApi.AuthorizationStateWaitEmailAddress()))
        assertEquals(TelegramAuthState.Unknown, value(TdApi.AuthorizationStateWaitOtherDeviceConfirmation()))
    }

    @Test
    fun `logging out is not the same as being signed out`() {
        assertEquals(TelegramAuthState.Initializing, value(TdApi.AuthorizationStateLoggingOut()))
        assertEquals(TelegramAuthState.Initializing, value(TdApi.AuthorizationStateClosing()))
    }

    @Test
    fun `the update wrapper is unwrapped and unrelated responses yield nothing`() {
        val update = TdApi.UpdateAuthorizationState().apply {
            authorizationState = TdApi.AuthorizationStateReady()
        }
        val classified = TdAuthorizationMapper.classifyResponse(update)

        assertEquals(TelegramAuthState.Authenticated, (classified as Classification.State).value)
        assertNull(TdAuthorizationMapper.authorizationStateOf(TdApi.Ok()))
    }

    @Test
    fun `the state is found inside the getCurrentState bundle`() {
        // `Updates` carries whatever TDLib thinks the process must restore, so the authorization state
        // can sit anywhere in it.
        val bundle = TdApi.Updates().apply {
            updates = arrayOf(
                TdApi.UpdateOption().apply { name = "version" },
                TdApi.UpdateAuthorizationState().apply {
                    authorizationState = TdApi.AuthorizationStateWaitCode().apply {
                        codeInfo = TdApi.AuthenticationCodeInfo().apply {
                            type = TdApi.AuthenticationCodeTypeSms().apply { length = 5 }
                        }
                    }
                },
            )
        }

        val classified = TdAuthorizationMapper.classifyResponse(bundle) as Classification.State

        assertEquals(
            TelegramAuthState.WaitingForCode(AuthCodeChannel.Sms, 5),
            classified.value,
        )
    }
}
