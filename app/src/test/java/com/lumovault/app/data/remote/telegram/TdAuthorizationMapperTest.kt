package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthFailure
import com.lumovault.app.domain.telegram.TelegramAuthState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the step order against TDLib's real `td_api` shapes: the UI shows exactly what Telegram
 * reported, so a wrong mapping here would let a user appear to be asked for a password before any
 * code had been sent — or appear signed in when they are not.
 */
class TdAuthorizationMapperTest {
    private fun state(json: String): Classification =
        TdAuthorizationMapper.classify(json.toJsonObject())

    private fun value(json: String): TelegramAuthState = (state(json) as Classification.State).value

    @Test
    fun `a closed or phone-waiting session both mean show the number field`() {
        assertEquals(
            TelegramAuthState.ReadyForPhoneNumber,
            value("""{"@type":"authorizationStateWaitPhoneNumber"}"""),
        )
        assertEquals(TelegramAuthState.ReadyForPhoneNumber, value("""{"@type":"authorizationStateClosed"}"""))
    }

    @Test
    fun `supplying parameters is a step to perform, not a state to render`() {
        assertEquals(Classification.NeedsParameters, state("""{"@type":"authorizationStateWaitTdlibParameters"}"""))
    }

    @Test
    fun `channel and code length are read from code_info dot type`() {
        // td_api: authorizationStateWaitCode code_info:authenticationCodeInfo, whose `type` is an
        // authenticationCodeType carrying `length`.
        val waiting = value(
            """
            {"@type":"authorizationStateWaitCode","code_info":{
              "@type":"authenticationCodeInfo",
              "phone_number":"+8801712345678",
              "type":{"@type":"authenticationCodeTypeCall","length":6},
              "timeout":0
            }}
            """.trimIndent(),
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Call, 6), waiting)
    }

    @Test
    fun `a code type without a length leaves the field unconstrained`() {
        val waiting = value(
            """
            {"@type":"authorizationStateWaitCode","code_info":{
              "@type":"authenticationCodeInfo",
              "type":{"@type":"authenticationCodeTypeSms"}
            }}
            """.trimIndent(),
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Sms, null), waiting)
    }

    @Test
    fun `an unnamed delivery type degrades to unknown instead of throwing`() {
        val waiting = value(
            """
            {"@type":"authorizationStateWaitCode","code_info":{
              "@type":"authenticationCodeInfo",
              "type":{"@type":"authenticationCodeTypeFirebaseAndroid","length":5}
            }}
            """.trimIndent(),
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Unknown, 5), waiting)
    }

    @Test
    fun `the password hint is carried through for display`() {
        assertEquals(
            TelegramAuthState.WaitingForPassword(hint = "city"),
            value("""{"@type":"authorizationStateWaitPassword","password_hint":"city"}"""),
        )
    }

    @Test
    fun `a number with no Telegram account is an explainable failure`() {
        val failure = value("""{"@type":"authorizationStateWaitRegistration"}""") as TelegramAuthState.Failed

        assertEquals(TelegramAuthFailure.Kind.AccountNotFound, failure.failure.kind)
    }

    @Test
    fun `only authorizationStateReady may be read as authenticated`() {
        assertEquals(TelegramAuthState.Authenticated, value("""{"@type":"authorizationStateReady"}"""))
        // A state newer than this mapping must never be mistaken for a signed-in session.
        assertEquals(TelegramAuthState.Unknown, value("""{"@type":"authorizationStateWaitEmailCode"}"""))
        assertEquals(TelegramAuthState.Unknown, value("""{"@type":"authorizationStateSomethingNew"}"""))
    }

    @Test
    fun `the update wrapper is unwrapped and unrelated responses yield nothing`() {
        val update = """{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateReady"}}"""
        val classified = TdAuthorizationMapper.classifyResponse(update.toJsonObject())

        assertEquals(TelegramAuthState.Authenticated, (classified as Classification.State).value)
        assertNull(TdAuthorizationMapper.authorizationStateOf("""{"@type":"ok"}""".toJsonObject()))
    }

    private fun String.toJsonObject() = Json.parseToJsonElement(this).jsonObject
}
