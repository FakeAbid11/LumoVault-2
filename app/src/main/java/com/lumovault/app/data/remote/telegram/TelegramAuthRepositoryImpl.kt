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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The authentication state machine, expressed against TDLib's JSON interface.
 *
 * TDLib, not this class, decides which step comes next: every method here sends one request and
 * then re-reads the authorization state, so the UI follows Telegram's answer rather than a script
 * LumoVault wrote for itself. Nothing in here reports success on its own — [TelegramAuthState
 * .Authenticated] only appears for `authorizationStateReady`.
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
            method = "setAuthenticationPhoneNumber",
            params = buildJsonObject {
                put(PHONE_NUMBER, internationalNumber)
                put(
                    SETTINGS,
                    buildJsonObject {
                        put("@type", "phoneNumberAuthenticationSettings")
                        // No flash call, no missed call: the code has to be typed by hand, which is
                        // the only path LumoVault's UI actually implements.
                        put("allow_flash_call", false)
                        put("allow_missed_call", false)
                        put("is_current_phone_number", false)
                        put("has_unknown_phone_number", false)
                        put("allow_sms_retriever_api", false)
                    },
                )
            },
        )
    }

    override suspend fun submitCode(code: String) = guard(TelegramAuthState.VerifyingCode) {
        client.request(method = "checkAuthenticationCode", params = buildJsonObject { put(CODE, code) })
    }

    override suspend fun resendCode() = guard(TelegramAuthState.SendingCode) {
        client.request(
            method = "resendAuthenticationCode",
            // TDLib requires a reason; this is the user-asked-for-one rather than a retry after a
            // rejected code.
            params = buildJsonObject {
                put(REASON, buildJsonObject { put("@type", "resendCodeReasonUserRequest") })
            },
        )
    }

    override suspend fun submitPassword(password: String) = guard(TelegramAuthState.Authenticating) {
        client.request(method = "checkAuthenticationPassword", params = buildJsonObject { put(PASSWORD, password) })
    }

    override fun cancelPendingRequest() {
        // No network call: Telegram has nothing to cancel, and the next update corrects this anyway.
        if (_state.value is TelegramAuthState.WaitingForCode || _state.value is TelegramAuthState.WaitingForPassword) {
            _state.value = TelegramAuthState.ReadyForPhoneNumber
        }
    }

    override suspend fun signOut() = guard(TelegramAuthState.Initializing) {
        client.request("logOut")
    }

    /**
     * Runs [action], then lets TDLib say where the flow actually is. The optimistic [pending] state
     * is only shown while the request is in flight; if Telegram answers with something else, that
     * answer wins.
     */
    private suspend fun guard(pending: TelegramAuthState, action: suspend () -> JsonObject) {
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
     * The setup calls answer with a plain `ok`, so the state has to be asked for again after each
     * one — reading it from the response would stop the loop and leave the UI on `Unknown`.
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

                    Classification.NeedsParameters -> client.request(
                        method = "setTdlibParameters",
                        params = tdlibParameters(),
                    )
                }
            }
        } finally {
            handshakeRunning = false
        }
    }

    private suspend fun currentAuthorizationState(): JsonObject? =
        TdAuthorizationMapper.authorizationStateOf(client.request("getCurrentState"))

    /** An unsolicited update may carry the state directly; setup steps need a re-read instead. */
    private suspend fun publish(response: JsonObject) {
        val state = TdAuthorizationMapper.authorizationStateOf(response) ?: return
        val classification = TdAuthorizationMapper.classify(state)

        if (classification is Classification.State) {
            _state.value = classification.value
        } else {
            refresh()
        }
    }

    /**
     * TDLib takes its parameters flat, not as a nested object. Field names come from the current
     * `td_api.tl` scheme; `tdlib_parameters` and a separate encryption-key step were older shapes
     * and are gone.
     */
    private fun tdlibParameters(): JsonObject = buildJsonObject {
        put("use_test_dc", false)
        put("database_directory", storage.databaseDirectory.absolutePath)
        put("files_directory", storage.filesDirectory.absolutePath)
        // bytes travel base64 over the JSON interface; an empty key means "no passphrase".
        put("database_encryption_key", "")
        // Authentication needed neither history nor secret chats; the cloud scanner does need the
        // message database, because paging a channel's history means reading it back out of TDLib.
        put("use_file_database", true)
        put("use_chat_info_database", true)
        put("use_message_database", true)
        put("use_secret_chats", false)
        put("api_id", credentials.apiId)
        put("api_hash", credentials.apiHash)
        put("system_language_code", info.systemLanguageCode)
        put("device_model", info.deviceModel)
        put("system_version", info.systemVersion)
        put("application_version", info.applicationVersion)
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
        const val PHONE_NUMBER = "phone_number"
        const val SETTINGS = "settings"
        const val REASON = "reason"
        const val CODE = "code"
        const val PASSWORD = "password"

        /** Parameters, then a real state: anything longer means TDLib is not advancing. */
        const val MAX_HANDSHAKE_STEPS = 3
    }
}
