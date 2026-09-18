package com.lumovault.lumovault.core.auth

import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.core.tdlib.TdLibException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mocking [TdLibClient] keeps libtdjni out of the JVM test (mockk builds the
 * proxy without invoking the constructor). Authorization states are emitted
 * through a real [MutableSharedFlow] so the subscribe-before-send ordering and
 * the bootstrap race are exercised against real flow mechanics.
 *
 * Virtual time auto-advances while the test body is suspended on a delay, so
 * the timeout paths resolve without manual clock-winding; [runCurrent] is used
 * only to flush work that is merely queued rather than delayed.
 */
class TelegramAuthRepositoryTest {

    private val client: TdLibClient = mockk(relaxed = true)
    private val updates = MutableSharedFlow<TdLibClient.Update>(extraBufferCapacity = 16)

    private fun TestScope.repo(ensureConnected: (suspend () -> Unit)? = null) =
        TelegramAuthRepository(client, ensureConnected, this).also {
            every { client.updates } returns updates.asSharedFlow()
        }

    private val sentPhoneRequests = mutableListOf<TdApi.SetAuthenticationPhoneNumber>()

    @Test
    fun initializeReadsTheAuthorizationState() = runTest {
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateReady()
        val repo = repo()

        repo.initialize()

        assertEquals(AuthState.authenticated, repo.currentState)
        assertTrue(repo.hasResolvedAuth)
        repo.dispose()
    }

    @Test
    fun initializeRunsEnsureConnectedFirst() = runTest {
        val order = mutableListOf<String>()
        coEvery { client.getAuthorizationState() } answers {
            order.add("state")
            TdApi.AuthorizationStateWaitPhoneNumber()
        }
        val repo = repo(ensureConnected = { order.add("connect") })

        repo.initialize()

        assertEquals(listOf("connect", "state"), order)
        repo.dispose()
    }

    @Test
    fun initializeWaitsForTheSettledStateWhenTheSnapshotIsTransient() = runTest {
        // The handshake has not finished: TDLib is still asking for parameters,
        // even though a session is about to be restored.
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitTdlibParameters()
        val repo = repo()

        repo.initialize()
        assertFalse("the snapshot is transient, not an answer", repo.hasResolvedAuth)

        updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateReady()))
        runCurrent()

        assertEquals(
            "trusting the transient snapshot would have shown signed-out",
            AuthState.authenticated,
            repo.currentState,
        )
        assertTrue(repo.hasResolvedAuth)
        repo.dispose()
    }

    @Test
    fun initializeDoesNotBlockForeverOnASlowBootstrap() = runTest {
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitTdlibParameters()
        val repo = repo()

        repo.initialize() // returns once the bootstrap window elapses

        // Best-effort: the question is answered with the last synced state
        // rather than the caller hanging indefinitely.
        assertTrue(repo.hasResolvedAuth)
        repo.dispose()
    }

    @Test
    fun initializeIsSingleFlight() = runTest {
        var stateCalls = 0
        coEvery { client.getAuthorizationState() } coAnswers {
            stateCalls++
            delay(500)
            TdApi.AuthorizationStateReady()
        }
        val repo = repo()

        repo.initialize()
        repo.initialize()

        assertEquals("a concurrent call must not re-run the bootstrap", 1, stateCalls)
        repo.dispose()
    }

    @Test
    fun aFailedInitializeCanBeRetried() = runTest {
        var failedOnce = false
        coEvery { client.getAuthorizationState() } answers {
            if (!failedOnce) {
                failedOnce = true
                throw TdLibException(code = "NETWORK_ERROR", message = "down")
            }
            TdApi.AuthorizationStateReady()
        }
        val repo = repo()

        var thrown: Throwable? = null
        try { repo.initialize() } catch (t: Throwable) { thrown = t }
        assertTrue("a real failure must propagate, not become signed-out", thrown is TdLibException)
        assertFalse(repo.hasResolvedAuth)

        repo.initialize()
        assertEquals(AuthState.authenticated, repo.currentState)
        repo.dispose()
    }

    @Test
    fun authorizationUpdatesAreSyncedAfterInitialize() = runTest {
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitPhoneNumber()
        val repo = repo()
        repo.initialize()
        runCurrent()

        updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateWaitCode()))
        runCurrent()
        assertEquals(AuthState.codeSent, repo.currentState)

        updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateClosed()))
        runCurrent()
        assertEquals(AuthState.unauthenticated, repo.currentState)
        repo.dispose()
    }

    @Test
    fun unknownAuthorizationStatesLeaveTheStandingStateAlone() = runTest {
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateReady()
        val repo = repo()
        repo.initialize()
        runCurrent()

        updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()))
        runCurrent()

        assertEquals(AuthState.authenticated, repo.currentState)
        repo.dispose()
    }

    @Test
    fun sendCodeTransitionsToCodeSent() = runTest {
        coEvery { client.send(any()) } returns TdApi.Ok()
        val repo = repo()

        val result = repo.sendCode("+15551234567")

        assertEquals(AuthState.codeSent, repo.currentState)
        assertTrue(result is AuthResult.CodeSent)
        assertEquals("+15551234567", (result as AuthResult.CodeSent).phoneNumber)
        coVerify { client.send(any<TdApi.SetAuthenticationPhoneNumber>()) }
        repo.dispose()
    }

    @Test
    fun sendCodeUsesTheNestedAuthSettingsShape() = runTest {
        coEvery { client.send(any()) } answers {
            sentPhoneRequests.add(firstArg())
            TdApi.Ok()
        }
        val repo = repo()

        repo.sendCode("+15551234567")

        val params = sentPhoneRequests.single()
        assertEquals("+15551234567", params.phoneNumber)
        assertFalse(params.settings.allowFlashCall)
        assertTrue(params.settings.isCurrentPhoneNumber)
        repo.dispose()
    }

    @Test
    fun sendCodeMapsTdLibErrors() = runTest {
        coEvery { client.send(any()) } throws TdLibException(code = "PHONE_NUMBER_INVALID", message = "bad")
        val repo = repo()

        val result = repo.sendCode("+15551234567")

        assertTrue(result is AuthResult.Error)
        assertEquals("PHONE_NUMBER_INVALID", (result as AuthResult.Error).code)
        assertEquals(AuthState.error, repo.currentState)
        repo.dispose()
    }

    @Test
    fun verifyCodeWaitsForTheNextStateRatherThanTheOkReply() = runTest {
        coEvery { client.send(any()) } answers {
            // TDLib accepts the code and only then emits the next state.
            updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateWaitPassword()))
            TdApi.Ok()
        }
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitCode()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.verifyCode("12345")

        assertTrue(
            "returning on the Ok would report Success and skip 2FA",
            result is AuthResult.PasswordRequired,
        )
        assertEquals(AuthState.passwordRequired, repo.currentState)
        repo.dispose()
    }

    @Test
    fun verifyCodeReturnsSuccessWhenTheStateIsReady() = runTest {
        coEvery { client.send(any()) } answers {
            updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateReady()))
            TdApi.Ok()
        }
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitCode()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.verifyCode("12345")

        assertTrue(result is AuthResult.Success)
        assertEquals(AuthState.authenticated, repo.currentState)
        repo.dispose()
    }

    @Test
    fun verifyCodeMapsARegistrationRequirementAsAnError() = runTest {
        coEvery { client.send(any()) } answers {
            updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateWaitRegistration()))
            TdApi.Ok()
        }
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitCode()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.verifyCode("12345")

        assertTrue(result is AuthResult.Error)
        assertEquals("REGISTRATION_REQUIRED", (result as AuthResult.Error).code)
        repo.dispose()
    }

    @Test
    fun verifyCodeMapsAnUnexpectedStateAsAnError() = runTest {
        coEvery { client.send(any()) } answers {
            updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber()))
            TdApi.Ok()
        }
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitCode()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.verifyCode("12345")

        // Navigating into a half-authenticated app is not an option here.
        assertTrue(result is AuthResult.Error)
        repo.dispose()
    }

    @Test
    fun verifyCodeMapsTdLibErrors() = runTest {
        coEvery { client.send(any()) } throws TdLibException(code = "PHONE_CODE_INVALID", message = "wrong")
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitCode()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.verifyCode("12345")

        assertTrue(result is AuthResult.Error)
        assertEquals("PHONE_CODE_INVALID", (result as AuthResult.Error).code)
        repo.dispose()
    }

    @Test
    fun verifyCodeReReadsTheStateOnTimeoutInsteadOfAssumingSuccess() = runTest {
        // The Ok arrives but no authorization state update ever follows.
        coEvery { client.send(any()) } returns TdApi.Ok()
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitPassword()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.verifyCode("12345")

        assertTrue("must not report Success from a timeout", result is AuthResult.PasswordRequired)
        coVerify(atLeast = 2) { client.getAuthorizationState() }
        repo.dispose()
    }

    @Test
    fun verifyCodeReportsUnknownWhenTheStateCannotBeReRead() = runTest {
        coEvery { client.send(any()) } returns TdApi.Ok()
        coEvery { client.getAuthorizationState() } throws TdLibException(code = "NETWORK_ERROR", message = "down")
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.verifyCode("12345")

        assertTrue(result is AuthResult.Error)
        assertEquals("AUTH_STATE_UNKNOWN", (result as AuthResult.Error).code)
        repo.dispose()
    }

    @Test
    fun submitPasswordWaitsForAuthorizationStateReady() = runTest {
        coEvery { client.send(any()) } answers {
            updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateReady()))
            TdApi.Ok()
        }
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitPassword()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.submitPassword("hunter2")

        assertTrue(result is AuthResult.Success)
        assertEquals(AuthState.authenticated, repo.currentState)
        repo.dispose()
    }

    @Test
    fun submitPasswordMapsTdLibErrors() = runTest {
        coEvery { client.send(any()) } throws TdLibException(code = "PASSWORD_HASH_INVALID", message = "wrong")
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitPassword()
        val repo = repo()
        repo.initialize()
        runCurrent()

        val result = repo.submitPassword("hunter2")

        assertTrue(result is AuthResult.Error)
        assertEquals("PASSWORD_HASH_INVALID", (result as AuthResult.Error).code)
        repo.dispose()
    }

    @Test
    fun logoutAwaitsTheReplyAndReportsSignedOut() = runTest {
        coEvery { client.send(any()) } returns TdApi.Ok()
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateReady()
        val repo = repo()
        repo.initialize()
        runCurrent()

        repo.logout()

        assertEquals(AuthState.unauthenticated, repo.currentState)
        coVerify { client.send(any<TdApi.LogOut>()) }
        repo.dispose()
    }

    @Test
    fun logoutDoesNotReportSignedOutWhenTheRequestFails() = runTest {
        coEvery { client.send(any()) } throws TdLibException(code = "NETWORK_ERROR", message = "down")
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateReady()
        val repo = repo()
        repo.initialize()
        runCurrent()

        repo.logout()

        assertEquals(
            "a failed logOut leaves the session live server-side",
            AuthState.error,
            repo.currentState,
        )
        repo.dispose()
    }

    @Test
    fun authStaysUnresolvedUntilTdLibActuallyAnswers() = runTest {
        coEvery { client.getAuthorizationState() } coAnswers {
            delay(1_000)
            TdApi.AuthorizationStateWaitPhoneNumber()
        }
        val repo = repo()

        assertFalse("unresolved must not read as signed-out", repo.hasResolvedAuth)

        repo.initialize()

        assertTrue(repo.hasResolvedAuth)
        repo.dispose()
    }

    @Test
    fun waitPhoneNumberResolvesAuthAsUnauthenticated() = runTest {
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitPhoneNumber()
        val repo = repo()

        repo.initialize()

        assertTrue(
            "waitPhoneNumber is a definitive answer, not an unknown",
            repo.hasResolvedAuth,
        )
        repo.dispose()
    }

    @Test
    fun stateFlowEmitsTransitions() = runTest {
        val scope = this
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateWaitPhoneNumber()
        val repo = repo()
        val recorded = mutableListOf<AuthState>()
        val collector = scope.launch { repo.state.collect { recorded.add(it) } }
        runCurrent()

        repo.initialize()
        runCurrent()
        updates.tryEmit(TdLibClient.Update.AuthorizationState(TdApi.AuthorizationStateWaitCode()))
        runCurrent()

        assertTrue(recorded.contains(AuthState.codeSent))
        collector.cancel()
        repo.dispose()
    }

    @Test
    fun disposeCancelsTheUpdateCollectorWithoutCancellingAnInjectedScope() = runTest {
        val scope = this
        coEvery { client.getAuthorizationState() } returns TdApi.AuthorizationStateReady()
        val repo = repo()
        repo.initialize()
        runCurrent()

        repo.dispose()

        assertTrue(
            "the injected scope belongs to its owner",
            scope.coroutineContext[Job]!!.isActive,
        )
    }
}
