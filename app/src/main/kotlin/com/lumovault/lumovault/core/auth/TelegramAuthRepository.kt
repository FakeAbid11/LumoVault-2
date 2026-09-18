package com.lumovault.lumovault.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import kotlin.time.Duration.Companion.seconds

/**
 * Real Telegram authentication over TDLib.
 *
 * Ported from lib/core/auth/telegram_auth_repository.dart. The typed Java
 * client dissolves most of the original's substrate: there is no JSON to
 * shape-gate by runtime version, and updates arrive as a typed flow rather
 * than maps keyed on `@type`. What survives, deliberately, is the timing
 * discipline — three separate races in the original each had a bug and a fix:
 *
 *  - [initialize] must not trust the authorization state it reads if that
 *    state is still [TdApi.AuthorizationStateWaitTdlibParameters]. The client
 *    returns as soon as its receive loop starts, not once a persisted session
 *    has been validated, so this snapshot can be transient. Trusting it is
 *    why the Account screen flipped between showing signed-in and "Not signed
 *    in" depending on whether the handshake had finished first. We wait for
 *    the settled state instead.
 *  - [verifyCode] and [submitPassword] must subscribe to the state flow
 *    *before* sending the request. TDLib answers `checkAuthenticationCode`
 *    with `Ok` and only then emits the next authorization state, so the reply
 *    alone means "code accepted" — not "what comes next". On a 2FA account the
 *    next state is WaitPassword; returning Success on the Ok navigated the
 *    user into the app while TDLib was still parked in WaitPassword, and the
 *    password screen — the only listener — had already been disposed.
 *  - [logout] must not report signed-out on failure. A network error during
 *    logOut leaves the session live server-side; reporting success showed a
 *    Sign-In button over an active session.
 */
class TelegramAuthRepository(
    private val client: TdLibClient,
    private val ensureConnected: (suspend () -> Unit)? = null,
    coroutineScope: CoroutineScope? = null,
) : AuthService {

    private val ownsScope = coroutineScope == null
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AuthState.unauthenticated)
    override val state: StateFlow<AuthState> = _state.asStateFlow()
    override val currentState: AuthState get() = _state.value

    @Volatile private var authResolved = false
    override val hasResolvedAuth: Boolean get() = authResolved

    private var updateJob: Job? = null
    private val initLock = Any()
    @Volatile private var initializeInFlight: Deferred<Unit>? = null

    override suspend fun initialize() {
        val deferred = synchronized(initLock) {
            initializeInFlight ?: scope.async {
                try {
                    initializeNow()
                } finally {
                    // A failure to reach TDLib must be retryable, so the
                    // single-flight slot is freed on every completion.
                    initializeInFlight = null
                }
            }.also { initializeInFlight = it }
        }
        deferred.await()
    }

    private suspend fun initializeNow() {
        try {
            ensureConnected?.invoke()

            // UNDISPATCHED so the subscription is registered before the state
            // query below runs; otherwise an update emitted between the query
            // and a lazily-scheduled collector would be missed entirely.
            updateJob?.cancel()
            updateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.updates.collect { update ->
                    if (update is TdLibClient.Update.AuthorizationState) {
                        syncAuthState(update.state)
                    }
                }
            }

            val snapshot = client.getAuthorizationState()
            syncAuthState(snapshot)

            if (snapshot is TdApi.AuthorizationStateWaitTdlibParameters) {
                // See the class doc: the snapshot is transient. Wait for the
                // real state rather than concluding "not signed in" from it.
                try {
                    val settled = withTimeout(BOOTSTRAP_TIMEOUT) {
                        client.updates.first { update ->
                            update is TdLibClient.Update.AuthorizationState &&
                                update.state !is TdApi.AuthorizationStateWaitTdlibParameters
                        }
                    }
                    syncAuthState(settled.state)
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    // Bootstrap is taking unusually long — leave the state as
                    // last synced rather than blocking the caller indefinitely.
                }
            }
            // Deliberately no catch-all: a genuine failure to reach TDLib used
            // to be swallowed and mapped to unauthenticated, which told a
            // signed-in user they were signed out. Propagating it lets callers
            // surface a retryable error instead.
        } finally {
            // Any completion — success, timeout, or connect failure — answers
            // the "what is my auth state" question with best-effort knowledge.
            authResolved = true
        }
    }

    override suspend fun sendCode(phoneNumber: String): AuthResult {
        updateState(AuthState.loading)
        return try {
            // We build TDLib from source at HEAD, so the nested
            // PhoneNumberAuthenticationSettings shape is always the right one —
            // the original's runtime version probe existed only to pick
            // between that and the pre-1.7 flat shape.
            client.send(
                TdApi.SetAuthenticationPhoneNumber(
                    phoneNumber,
                    TdApi.PhoneNumberAuthenticationSettings().apply {
                        allowFlashCall = false
                        isCurrentPhoneNumber = true
                    },
                ),
            )
            // TDLib will emit authorizationStateWaitCode; transition
            // optimistically so the UI is not blocked on the round trip.
            updateState(AuthState.codeSent)
            AuthResult.CodeSent(phoneNumber)
        } catch (e: TdLibException) {
            updateState(AuthState.error)
            AuthResult.Error(message = e.displayMessage, code = e.code)
        }
    }

    override suspend fun verifyCode(code: String): AuthResult {
        updateState(AuthState.loading)
        return try {
            val settled = sendAndAwaitState(TdApi.CheckAuthenticationCode(code))
                ?: return reapplyCurrentState()
            applySettledState(settled)
        } catch (e: TdLibException) {
            // PASSWORD_HASH_INVALID belongs to the password step, not here. A
            // wrong code is a generic auth error; the password transition
            // arrives via the WaitPassword update.
            updateState(AuthState.error)
            AuthResult.Error(message = e.displayMessage, code = e.code)
        }
    }

    override suspend fun submitPassword(password: String): AuthResult {
        updateState(AuthState.loading)
        return try {
            val settled = sendAndAwaitState(TdApi.CheckAuthenticationPassword(password))
                ?: return reapplyCurrentState()
            applySettledState(settled)
        } catch (e: TdLibException) {
            updateState(AuthState.error)
            AuthResult.Error(message = e.displayMessage, code = e.code)
        }
    }

    /**
     * Subscribes to the state flow *before* sending [request], then awaits the
     * authorization state TDLib emits in response. Returns null on timeout —
     * the caller must not read that as success.
     */
    private suspend fun sendAndAwaitState(
        request: TdApi.Function<out TdApi.Object>,
    ): TdApi.AuthorizationState? = coroutineScope {
        // UNDISPATCHED so the subscription is registered before the send below
        // runs; a lazily-scheduled collector would miss the reply update.
        val subscription = async(start = CoroutineStart.UNDISPATCHED) {
            client.updates.first { it is TdLibClient.Update.AuthorizationState }
        }
        try {
            client.send(request)
            withTimeout(AUTH_STATE_TIMEOUT) { subscription.await() }.state
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } finally {
            // No state update arrived; leave no hung collector behind.
            subscription.cancel()
        }
    }

    /**
     * The timeout fallback: re-read the current state rather than assume the
     * request succeeded, then map it. Never returns Success from ignorance.
     */
    private suspend fun reapplyCurrentState(): AuthResult {
        val current = try {
            client.getAuthorizationState()
        } catch (e: Throwable) {
            updateState(AuthState.error)
            return AuthResult.Error(
                message = "Could not confirm sign-in. Please try again.",
                code = "AUTH_STATE_UNKNOWN",
            )
        }
        return applySettledState(current)
    }

    /** Maps a post-code/post-password authorization state to an [AuthResult]. */
    private fun applySettledState(state: TdApi.AuthorizationState): AuthResult = when (state) {
        is TdApi.AuthorizationStateWaitPassword -> {
            updateState(AuthState.passwordRequired)
            AuthResult.PasswordRequired
        }
        is TdApi.AuthorizationStateReady -> {
            updateState(AuthState.authenticated)
            AuthResult.Success
        }
        is TdApi.AuthorizationStateWaitRegistration -> {
            // LumoVault cannot create a Telegram account from here.
            updateState(AuthState.error)
            AuthResult.Error(
                message = "This phone number needs a new Telegram account. " +
                    "Please register one in the official Telegram app first.",
                code = "REGISTRATION_REQUIRED",
            )
        }
        else -> {
            // WaitPhoneNumber (or anything unexpected) after a code is not a
            // path to navigate the user further into; surface it.
            updateState(AuthState.error)
            AuthResult.Error(
                message = "Sign-in could not be completed. Please try again.",
                code = state::class.simpleName ?: "AUTH_STATE_UNEXPECTED",
            )
        }
    }

    override suspend fun logout() {
        updateState(AuthState.loading)
        try {
            // Await the reply rather than fire-and-forget: a failed logOut
            // leaves the session live, and that must be surfaced, not hidden.
            client.send(TdApi.LogOut())
            updateState(AuthState.unauthenticated)
        } catch (_: Throwable) {
            // TDLib may still be authenticated — reporting signed-out here
            // showed a Sign-In button over a live session. Let the error state
            // hold until an update or a retry decides the truth.
            updateState(AuthState.error)
        }
    }

    /** Maps any TDLib authorization state onto our own. */
    private fun syncAuthState(tdLibState: TdApi.AuthorizationState) {
        val newState = when (tdLibState) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.unauthenticated
            is TdApi.AuthorizationStateWaitCode -> AuthState.codeSent
            is TdApi.AuthorizationStateWaitPassword -> AuthState.passwordRequired
            is TdApi.AuthorizationStateReady -> AuthState.authenticated
            is TdApi.AuthorizationStateLoggingOut -> AuthState.loading
            is TdApi.AuthorizationStateClosing -> AuthState.loading
            is TdApi.AuthorizationStateClosed -> AuthState.unauthenticated
            // WaitTdlibParameters / WaitRegistration / anything unknown: leave
            // the standing state alone. These are transients the caller is
            // about to get a definitive answer for.
            else -> _state.value
        }

        // Any authorization state resolves the "what is my auth state" question
        // — even WaitPhoneNumber answers it, definitively, with unauthenticated.
        authResolved = true
        updateState(newState)
    }

    private fun updateState(newState: AuthState) {
        _state.value = newState
    }

    override fun dispose() {
        updateJob?.cancel()
        if (ownsScope) scope.cancel()
    }

    private companion object {
        val BOOTSTRAP_TIMEOUT = 10.seconds
        val AUTH_STATE_TIMEOUT = 10.seconds
    }
}
