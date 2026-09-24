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
 * Guards the step order. The UI shows exactly what TDLib reported, so a wrong mapping here would
 * make a user appear to be asked for a password before any code had been sent — or, worse, appear
 * authenticated when they are not.
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
    fun `the two one-time setup steps are steps, not renderable states`() {
        assertEquals(Classification.NeedsParameters, state("""{"@type":"authorizationStateWaitTdlibParameters"}"""))
        assertEquals(Classification.NeedsEncryptionKey, state("""{"@type":"authorizationStateWaitEncryptionKey"}"""))
    }

    @Test
    fun `code length and channel are read from the response, not hardcoded`() {
        val waiting = value(
            """
            {"@type":"authorizationStateWaitCode","code_info":{
              "@type":"authCodeInfo",
              "code_length":{"@type":"authCodeLength","first_part_length":3,"length":6},
              "type":{"@type":"authCodeTypeCall"},
              "timeout":0
            }}
            """.trimIndent(),
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Call, 6), waiting)
    }

    @Test
    fun `a response without code length leaves the field unconstrained`() {
        val waiting = value(
            """
            {"@type":"authorizationStateWaitCode","code_info":{
              "@type":"authCodeInfo","type":{"@type":"authCodeTypeSms"},"timeout":0
            }}
            """.trimIndent(),
        )

        assertEquals(TelegramAuthState.WaitingForCode(AuthCodeChannel.Sms, null), waiting)
    }

    @Test
    fun `an unrecognised code channel degrades to unknown instead of throwing`() {
        val waiting = value(
            """
            {"@type":"authorizationStateWaitCode","code_info":{
              "@type":"authCodeInfo",
              "code_length":{"@type":"authCodeLength","first_part_length":0,"length":5},
              "type":{"@type":"authCodeTypeSomeNewThing"}
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
        assertEquals(TelegramAuthState.Unknown, value("""{"@type":"authorizationStateSomethingNew"}"""))
    }

    @Test
    fun `wrappers are unwrapped and unrelated responses yield nothing`() {
        val update = """{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateReady"}}"""
        val updateResult = TdAuthorizationMapper.classifyResponse(update.toJsonObject())
        assertEquals(TelegramAuthState.Authenticated, (updateResult as Classification.State).value)

        val result = """{"@type":"getCurrentStateResult","authorization_state":{"@type":"authorizationStateWaitPhoneNumber"}}"""
        val resultState = TdAuthorizationMapper.classifyResponse(result.toJsonObject())
        assertEquals(TelegramAuthState.ReadyForPhoneNumber, (resultState as Classification.State).value)

        // A plain `ok` carries no authorization state; the caller has to ask again.
        assertNull(TdAuthorizationMapper.authorizationStateOf("""{"@type":"ok"}""".toJsonObject()))
    }

    private fun String.toJsonObject() = Json.parseToJsonElement(this).jsonObject
}
