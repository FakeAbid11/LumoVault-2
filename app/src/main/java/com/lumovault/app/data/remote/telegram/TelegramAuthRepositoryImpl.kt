package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.TelegramAuthFailure
import com.lumovault.app.domain.telegram.TelegramAuthRepository
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.util.Privacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * The authentication state machine, expressed against TDLib's typed interface.
 *
 * TDLib, not this class, decides which step comes next: every method here sends one request and then
 * re-reads the authorization state, so the UI follows Telegram's answer rather than a script
 * LumoVault wrote for itself. Nothing in here reports success on its own — [TelegramAuthState
 * .Authenticated] only appears for [TdApi.AuthorizationStateReady].
 *
 * The phone number is held only for the duration of a request. Codes and passwords are never
 * stored, never returned, and never logged.
 */
class TelegramAuthRepositoryImpl(
    private val client: TelegramClient,
    private val credentials: TelegramCredentials,
    private val info: TelegramClientInfo,
    private val storage: TelegramStorage,
    private val scope: CoroutineScope,
) : TelegramAuthRepository {
    private val _state = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Unknown)
    override val state: StateFlow<TelegramAuthState> = _state.asStateFlow()

    private var sessionJob: Job? = null

    /**
     * TDLib asks for its parameters once per client; re-sending them after a state change would be
     * wrong, so this only guards the startup handshake.
     */
    private var handshakeRunning = false

    override fun connect() {
        if (!client.isUsable) {
            _state.value = TelegramAuthState.NotConfigured
            return
        }
        if (sessionJob?.isActive == true) return

        sessionJob = scope.launch {
            try {
                _state.value = TelegramAuthState.Initializing
                client.start()
                launch { client.updates.collect { response -> publish(response) } }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    override suspend fun requestCode(internationalNumber: String) = guard(TelegramAuthState.SendingCode) {
        client.request(
            TdApi.SetAuthenticationPhoneNumber().apply {
                phoneNumber = internationalNumber
                settings = TdApi.PhoneNumberAuthenticationSettings().apply {
                    // No flash call, no missed call: the code has to be typed by hand, which is the
                    // only path LumoVault's UI actually implements.
                    allowFlashCall = false
                    allowMissedCall = false
                    isCurrentPhoneNumber = false
                    hasUnknownPhoneNumber = false
                    allowSmsRetrieverApi = false
                    // An empty array, not a null one: TDLib's docs call this field nullable only for
                    // firebase_authentication_settings. A vector it expects to iterate arrives either
                    // full or empty, and JNI would hand it a reference it cannot measure.
                    authenticationTokens = emptyArray()
                }
            },
        )
    }

    override suspend fun submitCode(code: String) = guard(TelegramAuthState.VerifyingCode) {
        client.request(TdApi.CheckAuthenticationCode().apply { this.code = code })
    }

    override suspend fun resendCode() = guard(TelegramAuthState.SendingCode) {
        client.request(
            // TDLib requires a reason; this is the user-asked-for-one rather than a retry after a
            // rejected code.
            TdApi.ResendAuthenticationCode().apply {
                reason = TdApi.ResendCodeReasonUserRequest()
            },
        )
    }

    override suspend fun submitPassword(password: String) = guard(TelegramAuthState.Authenticating) {
        client.request(TdApi.CheckAuthenticationPassword().apply { this.password = password })
    }

    override fun cancelPendingRequest() {
        // No network call: Telegram has nothing to cancel, and the next update corrects this anyway.
        if (_state.value is TelegramAuthState.WaitingForCode || _state.value is TelegramAuthState.WaitingForPassword) {
            _state.value = TelegramAuthState.ReadyForPhoneNumber
        }
    }

    override suspend fun signOut() = guard(TelegramAuthState.Initializing) {
        client.request(TdApi.LogOut())
    }

    /**
     * Runs [action], then lets TDLib say where the flow actually is. The optimistic [pending] state
     * is only shown while the request is in flight; if Telegram answers with something else, that
     * answer wins.
     */
    private suspend fun guard(pending: TelegramAuthState, action: suspend () -> TdApi.Object) {
        _state.value = pending
        try {
            action()
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fail(error)
        }
    }

    /**
     * Re-reads the authorization state, completing TDLib's one-time setup steps on the way.
     *
     * The setup calls answer with a plain [TdApi.Ok], so the state has to be asked for again after
     * each one — reading it from the response would stop the loop and leave the UI on `Unknown`.
     */
    private suspend fun refresh() {
        if (handshakeRunning) return
        handshakeRunning = true
        try {
            repeat(MAX_HANDSHAKE_STEPS) {
                val state = currentAuthorizationState() ?: return
                when (val classification = TdAuthorizationMapper.classify(state)) {
                    is Classification.State -> {
                        _state.value = classification.value
                        return
                    }

                    Classification.NeedsParameters -> client.request(tdlibParameters())
                }
            }
        } finally {
            handshakeRunning = false
        }
    }

    private suspend fun currentAuthorizationState(): TdApi.AuthorizationState? =
        TdAuthorizationMapper.authorizationStateOf(client.request(TdApi.GetCurrentState()))

    /** An unsolicited update may carry the state directly; setup steps need a re-read instead. */
    private suspend fun publish(response: TdApi.Object) {
        val state = TdAuthorizationMapper.authorizationStateOf(response) ?: return
        val classification = TdAuthorizationMapper.classify(state)

        if (classification is Classification.State) {
            _state.value = classification.value
        } else {
            refresh()
        }
    }

    /**
     * TDLib takes its parameters flat, not as a nested object, and every field is required.
     *
     * `databaseEncryptionKey` is `bytes`, so an empty array rather than the empty string the JSON
     * interface took: it means "no passphrase", which is this app's answer because the session lives
     * in app-private storage that Phase 5's encryption covers separately.
     */
    private fun tdlibParameters(): TdApi.Function<TdApi.Ok> =
        TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = storage.databaseDirectory.absolutePath
            filesDirectory = storage.filesDirectory.absolutePath
            databaseEncryptionKey = byteArrayOf()
            // Authentication needs neither the file cache nor secret chats; the cloud scanner does need
            // the message database, because paging a channel's history means reading it back out of TDLib.
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = credentials.apiId
            apiHash = credentials.apiHash
            systemLanguageCode = info.systemLanguageCode
            deviceModel = info.deviceModel
            systemVersion = info.systemVersion
            applicationVersion = info.applicationVersion
        }

    private fun fail(error: Exception) {
        val failure = when (error) {
            is TelegramRequestException -> TdErrorMapper.from(error.code, error.reason)
            else -> TelegramAuthFailure(TelegramAuthFailure.Kind.Unexpected)
        }

        // Only the code and the mapped kind are logged; the raw reason goes through digit masking
        // because Telegram's own text can quote the number being dialled.
        android.util.Log.w(TAG, "telegram auth failed code=${error.diagnosticsCode()} kind=${failure.kind} detail=${Privacy.maskDigits(error.localizedMessage.orEmpty())}")
        _state.value = TelegramAuthState.Failed(failure)
    }

    private fun Exception.diagnosticsCode(): Int = (this as? TelegramRequestException)?.code ?: -1

    private companion object {
        const val TAG = "LumoVaultTelegram"

        /** Parameters, then a real state: anything longer means TDLib is not advancing. */
        const val MAX_HANDSHAKE_STEPS = 3
    }
}
