package com.lumovault.lumovault.core.tdlib

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale

/**
 * Wraps the official TDLib client.
 *
 * Ported from lib/core/tdlib/tdlib_client.dart, but the typed Java client
 * replaces the Dart port's entire FFI substrate: no hand-rolled JSON receive
 * isolate, no `@extra` request correlation, no completer map. [send] correlates
 * responses itself, and updates arrive on a dedicated handler thread.
 *
 * What carried over deliberately:
 *  - the 30-second request timeout and the late-response drop discipline;
 *  - `setTdlibParameters` answered internally, so callers only ever see
 *    phone/code/password states;
 *  - the update flow survives [close] (consumers subscribe once and would be
 *    left deaf across a reconnect if the flow were terminated);
 *  - initialization is single-flighted, and the native client is only
 *    recreated after the previous one is destroyed, so two clients never open
 *    the same encrypted database directory.
 */
class TdLibClient(
    private val context: Context,
    private val config: TdLibConfig,
    private val keyStore: TdLibKeyStore,
    coroutineScope: CoroutineScope? = null,
) {
    /** One Telegram update, broadcast to every subscriber. */
    sealed interface Update {
        data class AuthorizationState(val state: TdApi.AuthorizationState) : Update
        data class ConnectionReady(val ready: Boolean) : Update
        data class FileUpdated(val file: TdApi.File) : Update
        data class MessageSendSucceeded(val oldMessageId: Long, val message: TdApi.Message) : Update
        data class MessageSendFailed(val oldMessageId: Long, val error: TdApi.Error) : Update
        data class NewMessage(val message: TdApi.Message) : Update
        data class MessageContentChanged(val chatId: Long, val messageId: Long, val newContent: TdApi.MessageContent) : Update
        data class MessagesDeleted(val chatId: Long, val messageIds: LongArray) : Update
    }

    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Buffered so a burst of updates does not suspend the native receive thread.
    private val _updates = MutableSharedFlow<Update>(extraBufferCapacity = 256)
    val updates: SharedFlow<Update> = _updates.asSharedFlow()

    @Volatile private var client: Client? = null
    @Volatile private var initialized = false

    private val initLock = Any()
    private var initJob: Job? = null

    private val requestHandler = Client.ResultHandler { result ->
        when (result.getConstructor()) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                val state = (result as TdApi.UpdateAuthorizationState).authorizationState
                maybeAnswerWaitParameters(state)
                _updates.tryEmit(Update.AuthorizationState(state))
            }
            TdApi.UpdateConnectionState.CONSTRUCTOR -> {
                val state = (result as TdApi.UpdateConnectionState).state
                _updates.tryEmit(Update.ConnectionReady(state is TdApi.ConnectionStateReady))
            }
            TdApi.UpdateFile.CONSTRUCTOR ->
                _updates.tryEmit(Update.FileUpdated((result as TdApi.UpdateFile).file))
            TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> {
                val u = result as TdApi.UpdateMessageSendSucceeded
                _updates.tryEmit(Update.MessageSendSucceeded(u.oldMessageId, u.message))
            }
            TdApi.UpdateMessageSendFailed.CONSTRUCTOR -> {
                val u = result as TdApi.UpdateMessageSendFailed
                _updates.tryEmit(Update.MessageSendFailed(u.oldMessageId, u.error))
            }
            TdApi.UpdateNewMessage.CONSTRUCTOR ->
                _updates.tryEmit(Update.NewMessage((result as TdApi.UpdateNewMessage).message))
            TdApi.UpdateMessageContent.CONSTRUCTOR -> {
                val u = result as TdApi.UpdateMessageContent
                _updates.tryEmit(Update.MessageContentChanged(u.chatId, u.messageId, u.newContent))
            }
            TdApi.UpdateDeleteMessages.CONSTRUCTOR -> {
                val u = result as TdApi.UpdateDeleteMessages
                _updates.tryEmit(Update.MessagesDeleted(u.chatId, u.messageIds))
            }
        }
    }

    /**
     * Creates the native client and starts receiving. Idempotent and
     * single-flighted: two concurrent first callers used to build two clients
     * against the same database directory.
     */
    suspend fun initialize() {
        synchronized(initLock) {
            if (initialized) return
            initJob?.let { return }
            initJob = scope.launch { initializeNow() }
        }
        initJob?.join()
    }

    private fun initializeNow() {
        check(config.hasCredentials) {
            TdLibException(code = "API_ID_INVALID", message = "Telegram API credentials are missing")
        }

        File(context.filesDir, "tdlib_db").mkdirs()
        File(context.filesDir, "tdlib_files").mkdirs()

        // Client.create loads libtdjni in its static initializer and spawns the
        // native receive thread; the update handler runs on it from this point on.
        val created = Client.create(requestHandler, null, null)
            ?: throw TdLibException(code = "CLIENT_CREATE_FAILED", message = "TDLib client could not be created")
        client = created
        initialized = true
    }

    /**
     * Sends [request] and awaits its response, with a hard 30-second ceiling.
     *
     * A timed-out request's late response simply arrives on the update handler
     * and is ignored — the Dart port kept a bounded set of expired ids to keep
     * them out of the update stream; with the typed client a stray response
     * has no `@extra` to collide with, so nothing is misrouted.
     *
     * @throws TdLibException on a TDLib error or timeout.
     */
    suspend fun send(request: TdApi.Function<out TdApi.Object>): TdApi.Object {
        val client = this.client
        check(initialized && client != null) {
            TdLibException(code = "CLIENT_NOT_INITIALIZED", message = "TDLib client is not initialized")
        }
        val deferred = CompletableDeferred<TdApi.Object>()
        client.send(request) { result: TdApi.Object ->
            deferred.complete(result)
        }
        val result = try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw TdLibException(
                code = "REQUEST_TIMEOUT",
                message = "TDLib request timed out",
            )
        }
        if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
            throw TdLibException.from(result as TdApi.Error)
        }
        return result
    }

    /** Fire-and-forget variant for requests whose reply we do not need. */
    fun sendFireAndForget(request: TdApi.Function<out TdApi.Object>) {
        client?.send(request) { _: TdApi.Object -> /* reply dropped */ }
    }

    /**
     * Answers [TdApi.SetTdlibParameters] internally. Callers therefore only ever
     * observe phone/code/password states, never the parameters prompt — which is
     * an implementation detail of the client, not a user-facing auth step.
     * TDLib re-emits this state after a rejected parameters call or a logOut
     * cycle, so the answer must be re-entrant.
     */
    private fun maybeAnswerWaitParameters(state: TdApi.AuthorizationState) {
        if (state !is TdApi.AuthorizationStateWaitTdlibParameters) return
        val key = keyStore.readOrCreate()
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(context.filesDir, "tdlib_db").absolutePath
            filesDirectory = File(context.filesDir, "tdlib_files").absolutePath
            // TDLib takes the key as raw bytes, and stores it base64 on our side.
            databaseEncryptionKey = Base64.decode(key, Base64.NO_WRAP)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = config.apiId
            apiHash = config.apiHash
            systemLanguageCode = Locale.getDefault().language
            deviceModel = DEVICE_MODEL
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = config.appVersion
        }
        sendFireAndForget(params)
    }

    suspend fun getAuthorizationState(): TdApi.AuthorizationState =
        send(TdApi.GetAuthorizationState()) as TdApi.AuthorizationState


    suspend fun isAuthenticated(): Boolean = try {
        getAuthorizationState() is TdApi.AuthorizationStateReady
    } catch (_: Exception) {
        // Any failure — including a network error — looks the same as signed
        // out here; callers must not treat this as authoritative for security.
        false
    }

    fun logOut() = sendFireAndForget(TdApi.LogOut())

    /**
     * Destroys the native client. The update flow is deliberately *not*
     * closed: consumers subscribed once at construction and hold those
     * subscriptions for the app lifetime, and a closed SharedFlow never
     * recovers, so a reconnect would leave every consumer deaf while the
     * connection looked healthy.
     */
    fun close() {
        synchronized(initLock) {
            sendFireAndForget(TdApi.Close())
            client = null
            initialized = false
            initJob = null
        }
    }

    fun dispose() {
        close()
        scope.cancel()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val DEVICE_MODEL = "LumoVault"
    }
}

/**
 * Credentials and build details injected into the client.
 *
 * apiId/apiHash come from BuildConfig, which is populated from GitHub secrets
 * at build time; [hasCredentials] gates the whole backup feature so a debug
 * build fails with a clear message instead of an obscure TDLib error.
 */
data class TdLibConfig(
    val apiId: Int,
    val apiHash: String,
    val appVersion: String,
) {
    val hasCredentials: Boolean get() = apiId != 0 && apiHash.isNotEmpty()

    companion object {
        const val STORAGE_CHANNEL_NAME = "LumoVault Backup"
        const val STORAGE_CHANNEL_DESCRIPTION = "LumoVault backup storage — managed by LumoVault app"
        const val DATABASE_KEY_LENGTH = 32
        const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB, Telegram's ceiling
    }
}
