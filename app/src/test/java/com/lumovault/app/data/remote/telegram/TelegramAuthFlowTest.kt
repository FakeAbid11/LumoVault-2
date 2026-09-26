package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthFailure
import com.lumovault.app.domain.telegram.TelegramAuthState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in sequence driven against [FakeTelegramClient]: parameters, phone number, code, optional
 * password, ready.
 *
 * What matters here is not that each request is well-formed — the compiler proves that — but that the
 * state LumoVault shows comes only from what TDLib answered. Every test sends a request and then hands
 * back the state Telegram is supposed to reply with, so a repository that walked its own script instead
 * of following the answer fails loudly.
 *
 * [TelegramAuthRepository.connect] is exercised only on the branch it decides synchronously; the
 * update-collecting branch needs a test scheduler to be deterministic, which is a trade worth knowing
 * about rather than papering over with a sleep.
 */
class TelegramAuthFlowTest {
    private val databaseDirectory = File("tdlib-database")

    private val client = FakeTelegramClient()

    private val repository = repositoryFor(client)

    private fun repositoryFor(client: TelegramClient) = TelegramAuthRepositoryImpl(
        client = client,
        credentials = fakeCredentials(),
        info = fakeClientInfo(),
        storage = TelegramStorage(databaseDirectory, File("tdlib-files")),
        scope = CoroutineScope(Dispatchers.Default),
    )

    @Test
    fun `an unusable client is reported as not configured rather than as a failure`() {
        val unusableClient = FakeTelegramClient(usable = false)
        val unusable = repositoryFor(unusableClient)

        unusable.connect()

        assertEquals(TelegramAuthState.NotConfigured, unusable.state.value)
        assertEquals("nothing was sent to a client that is not there", 0, unusableClient.sent.size)
    }

    @Test
    fun `the handshake supplies its parameters before it renders a state`() {
        answerStates(TdApi.AuthorizationStateWaitTdlibParameters(), TdApi.AuthorizationStateWaitPhoneNumber())

        runBlocking { repository.requestCode(PHONE) }

        val parameters = client.sentOf<TdApi.SetTdlibParameters>().single()
        assertEquals(databaseDirectory.absolutePath, parameters.databaseDirectory)
        assertEquals(1234, parameters.apiId)
        assertEquals("0123456789abcdef", parameters.apiHash)
        assertFalse("LumoVault never uses the test DC", parameters.useTestDc)
        assertFalse("secret chats are out of scope for a backup library", parameters.useSecretChats)
        assertTrue("paging a channel's history needs the message database", parameters.useMessageDatabase)
        assertEquals("Test Handset", parameters.deviceModel)
        assertEquals("en", parameters.systemLanguageCode)

        // Parameters are a step, not a screen: the UI must land on the phone prompt, never on a state
        // that says "waiting for parameters".
        assertEquals(TelegramAuthState.ReadyForPhoneNumber, repository.state.value)
    }

    @Test
    fun `the handshake stops asking when TDLib does not advance`() {
        answerStates(TdApi.AuthorizationStateWaitTdlibParameters())

        runBlocking { repository.requestCode(PHONE) }

        // Bounded rather than looping forever, and it must not talk itself into a session.
        assertEquals(MAX_HANDSHAKE, client.sentOf<TdApi.SetTdlibParameters>().size)
        assertFalse(repository.state.value is TelegramAuthState.Authenticated)
    }

    @Test
    fun `the typed phone request carries the number and refuses call and flash delivery`() {
        answerStates(TdApi.AuthorizationStateWaitCode())

        runBlocking { repository.requestCode(PHONE) }

        val request = client.sentOf<TdApi.SetAuthenticationPhoneNumber>().single()
        assertEquals(PHONE, request.phoneNumber)
        // The code has to be typed by hand, which is the only path LumoVault's UI implements.
        assertFalse(request.settings.allowFlashCall)
        assertFalse(request.settings.allowMissedCall)
        assertFalse(request.settings.isCurrentPhoneNumber)
        assertEquals(0, request.settings.authenticationTokens.size)
    }

    @Test
    fun `the code prompt shows the channel and length Telegram actually asked for`() {
        answerStates(
            TdApi.AuthorizationStateWaitCode().apply {
                codeInfo = TdApi.AuthenticationCodeInfo().apply {
                    phoneNumber = PHONE
                    type = TdApi.AuthenticationCodeTypeSms().apply { length = 5 }
                }
            },
        )

        runBlocking { repository.requestCode(PHONE) }

        assertEquals(
            TelegramAuthState.WaitingForCode(AuthCodeChannel.Sms, 5),
            repository.state.value,
        )
    }

    @Test
    fun `submitting the code sends the code and shows what Telegram answered next`() {
        answerStates(TdApi.AuthorizationStateWaitPassword())

        runBlocking { repository.submitCode("12345") }

        assertEquals("12345", client.sentOf<TdApi.CheckAuthenticationCode>().single().code)
        assertEquals(TelegramAuthState.WaitingForPassword(hint = ""), repository.state.value)
    }

    @Test
    fun `a 2FA password is sent only through the typed password request`() {
        answerStates(TdApi.AuthorizationStateReady())

        runBlocking { repository.submitPassword("correct horse") }

        assertEquals(
            "correct horse",
            client.sentOf<TdApi.CheckAuthenticationPassword>().single().password,
        )
        assertEquals(TelegramAuthState.Authenticated, repository.state.value)
    }

    @Test
    fun `resending asks for a new code rather than retrying the last one`() {
        answerStates(TdApi.AuthorizationStateWaitCode())

        runBlocking { repository.resendCode() }

        assertTrue(
            client.sentOf<TdApi.ResendAuthenticationCode>().single().reason is
                TdApi.ResendCodeReasonUserRequest,
        )
    }

    @Test
    fun `signing out asks TDLib and does not report a session on its own`() {
        answerStates(TdApi.AuthorizationStateClosed())

        runBlocking { repository.signOut() }

        assertEquals(1, client.sentOf<TdApi.LogOut>().size)
        // Closed really does mean closed here: the phone prompt returns rather than a fake success.
        assertEquals(TelegramAuthState.ReadyForPhoneNumber, repository.state.value)
    }

    @Test
    fun `only the ready state is read as signed in`() {
        answerStates(TdApi.AuthorizationStateLoggingOut())

        runBlocking { repository.submitCode("12345") }

        assertEquals(TelegramAuthState.Initializing, repository.state.value)
    }

    @Test
    fun `a cancel from the code screen never claims a request was made`() {
        answerStates(TdApi.AuthorizationStateWaitCode())
        runBlocking { repository.requestCode(PHONE) }
        val before = client.sent.size

        repository.cancelPendingRequest()

        assertEquals(before, client.sent.size)
        assertEquals(TelegramAuthState.ReadyForPhoneNumber, repository.state.value)
    }

    @Test
    fun `an error from Telegram becomes LumoVault's own wording, never Telegram's text`() {
        client.answer = { function ->
            if (function is TdApi.SetAuthenticationPhoneNumber) {
                throw TelegramRequestException(code = 400, reason = "PHONE_NUMBER_INVALID")
            }
            TdApi.Ok()
        }

        runBlocking { repository.requestCode(PHONE) }

        val failed = repository.state.value as TelegramAuthState.Failed
        assertEquals(TelegramAuthFailure.Kind.InvalidPhoneNumber, failed.failure.kind)
    }

    @Test
    fun `a flood wait becomes a retry window instead of a generic failure`() {
        client.answer = { function ->
            if (function is TdApi.CheckAuthenticationCode) {
                throw TelegramRequestException(code = 420, reason = "FLOOD_WAIT_312")
            }
            TdApi.Ok()
        }

        runBlocking { repository.submitCode("12345") }

        val failed = repository.state.value as TelegramAuthState.Failed
        assertEquals(TelegramAuthFailure.Kind.TooManyRequests, failed.failure.kind)
        assertEquals(312, failed.failure.retryAfterSeconds)
    }

    @Test
    fun `a timeout is a failure to reach Telegram, not a rejection by it`() {
        client.answer = { _ -> throw TelegramRequestException(code = 0, reason = "TDLIB_TIMED_OUT") }

        runBlocking { repository.submitCode("12345") }

        val failed = repository.state.value as TelegramAuthState.Failed
        assertEquals(TelegramAuthFailure.Kind.Unexpected, failed.failure.kind)
    }

    /**
     * Answers [TdApi.GetCurrentState] from [states], repeating the last one, because after every
     * action the repository asks again.
     *
     * The state travels inside an `UpdateAuthorizationState` inside the `Updates` bundle, which is the
     * shape TDLib actually answers with — `Updates.updates` is an array of updates, not of
     * authorization states.
     */
    private fun answerStates(vararg states: TdApi.AuthorizationState) {
        var calls = 0
        client.answer = { function ->
            if (function !is TdApi.GetCurrentState) {
                TdApi.Ok()
            } else {
                val update = TdApi.UpdateAuthorizationState()
                update.authorizationState = states.getOrElse(calls) { states.last() }
                calls += 1
                val bundle = TdApi.Updates()
                bundle.updates = arrayOf<TdApi.Update>(update)
                bundle
            }
        }
    }

    private companion object {
        const val PHONE = "+8801712345678"

        /** Must track TelegramAuthRepositoryImpl's own bound, or this test proves nothing. */
        const val MAX_HANDSHAKE = 3
    }
}
