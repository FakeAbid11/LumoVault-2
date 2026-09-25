package com.lumovault.app.data.remote.telegram

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * Drives TDLib through its own Java interface: requests are [TdApi.Function] objects handed to
 * [Client.send], and TDLib's single internal receiver thread calls each request's handler with the
 * typed answer.
 *
 * There is no `@extra` counter and no response matching here, because there is no stream to demultiplex:
 * TDLib's Java binding keeps the request→handler pairing in the JNI layer, in [Client.create]'s
 * process-wide receiver. Which also means exactly one instance of this class may be alive at a time —
 * [Client] supports several clients per process, but LumoVault has one Telegram session, and a second
 * client opening the same database directory is an error rather than a second window onto it.
 */
class TdLibClient(
    private val credentials: TelegramCredentials,
) : TelegramClient {
    private val startMutex = Mutex()

    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val updates: Flow<TdApi.Object> = _updates

    @Volatile
    private var client: Client? = null

    override val isUsable: Boolean
        get() = NATIVE_LOADED && credentials.isConfigured

    override suspend fun start() = startMutex.withLock {
        if (client != null) return@withLock
        check(isUsable) { "TDLib is not configured in this build" }

        client = Client.create(
            Client.ResultHandler { update -> _updates.tryEmit(update) },
            // A handler that throws is LumoVault's bug, not Telegram's, and TDLib would otherwise
            // discard it silently. Only the class name is recorded: a message from inside a handler
            // can carry the very chat, path or number the request was about.
            Client.ExceptionHandler { error -> logRejected(error) },
            Client.ExceptionHandler { error -> logRejected(error) },
        )
    }

    override suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>): T {
        val target = client ?: throw IllegalStateException("TDLib client has not been started")
        val response = CompletableDeferred<TdApi.Object>()

        withContext(Dispatchers.IO) {
            // TDLib copies and queues the request here rather than performing it, but the call is
            // still JNI: keeping it off the caller's thread is what stops a scroll hitch on the
            // screen that happens to be asking for a page of history.
            target.send(function, Client.ResultHandler { result -> response.complete(result) })
        }

        val answered = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { response.await() }
            ?: throw TelegramRequestException(code = 0, reason = TIMED_OUT)

        if (answered is TdApi.Error) {
            throw TelegramRequestException(code = answered.code, reason = answered.message)
        }

        @Suppress("UNCHECKED_CAST")
        return answered as T
    }

    private fun logRejected(error: Throwable) {
        Log.w(TAG, "telegram update handler rejected an object: ${error.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "LumoVaultTelegram"

        /** TDLib's first run opens and unpacks its database, which is slower than a normal call. */
        const val REQUEST_TIMEOUT_MILLIS = 120_000L

        /** Not a TDLib token: no response arrived at all, so nothing can be mapped from it. */
        const val TIMED_OUT = "TDLIB_TIMED_OUT"

        /**
         * Whether this APK carries `libtdjni.so`.
         *
         * Loaded here rather than left to [Client]'s own static initialiser because that class
         * swallows the [UnsatisfiedLinkError] and keeps going, which would surface as a crash on the
         * first native call instead of the honest "not configured" state. Availability is a property
         * of the APK rather than of the moment, so it is read once and remembered: an APK without the
         * binary must say so on every screen or none.
         */
        val NATIVE_LOADED: Boolean by lazy {
            runCatching { System.loadLibrary("tdjni") }.isSuccess
        }
    }
}
