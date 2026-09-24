package com.lumovault.app.data.remote.telegram

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.content
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Drives TDLib through its JSON interface: requests go out on the calling coroutine, one receive
 * loop pulls everything back, and responses are matched to their caller by the `@extra` id that
 * TDLib echoes.
 *
 * The blocking `receive` call runs on [Dispatchers.IO], so TDLib never touches the main thread while
 * still cancelling promptly between polls.
 */
class TdLibJsonClient(
    private val native: TdLibNative,
    private val credentials: TelegramCredentials,
    private val scope: CoroutineScope,
) : TelegramClient {
    private val startMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val nextRequestId = AtomicLong(1)

    private val _updates = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var clientId: Int = 0

    private var receiveJob: Job? = null

    override val isUsable: Boolean
        get() = native.isAvailable && credentials.isConfigured

    override val updates: Flow<JsonObject> = _updates

    override suspend fun start() = startMutex.withLock {
        if (receiveJob?.isActive == true) return@withLock
        check(isUsable) { "TDLib is not configured in this build" }

        val created = native.createClientId()
        clientId = created
        receiveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val raw = native.receive(created, RECEIVE_TIMEOUT_SECONDS) ?: continue
                dispatch(raw)
            }
        }
    }

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        check(isUsable) { "TDLib is not configured in this build" }
        val target = clientId
        check(target != 0) { "TDLib client has not been started" }

        val requestId = nextRequestId.getAndIncrement()
        val response = CompletableDeferred<JsonObject>()
        pending[requestId] = response

        try {
            withContext(Dispatchers.IO) { native.send(target, buildRequest(requestId, method, params)) }
            return withTimeout(REQUEST_TIMEOUT_MILLIS) { response.await() }.toResult(method)
        } finally {
            pending.remove(requestId)
        }
    }

    override suspend fun stop() = startMutex.withLock {
        receiveJob?.cancel()
        receiveJob = null

        val target = clientId
        if (target != 0) {
            clientId = 0
            withContext(Dispatchers.IO) { native.destroy(target) }
        }

        val closed = TelegramRequestException(code = 0, method = "stop", reason = "client stopped")
        pending.values.forEach { it.completeExceptionally(closed) }
        pending.clear()
    }

    private fun dispatch(raw: String) {
        val element = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val extra = element[EXTRA_KEY]?.jsonPrimitive?.longOrNull

        if (extra == null) {
            _updates.tryEmit(element)
        } else {
            pending.remove(extra)?.complete(element)
        }
    }

    private fun buildRequest(requestId: Long, method: String, params: JsonObject): String =
        buildJsonObject {
            put(TYPE_KEY, method)
            put(EXTRA_KEY, requestId)
            params.forEach { (key, value) -> put(key, value) }
        }.toString()

    /** An `error` response is still a matched response, so it becomes a typed failure here. */
    private fun JsonObject.toResult(method: String): JsonObject {
        if (this[TYPE_KEY]?.jsonPrimitive?.content != ERROR_TYPE) return this

        throw TelegramRequestException(
            code = this[CODE_KEY]?.jsonPrimitive?.intOrNull ?: 0,
            method = method,
            reason = this[MESSAGE_KEY]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private companion object {
        const val TYPE_KEY = "@type"
        const val EXTRA_KEY = "@extra"
        const val CODE_KEY = "code"
        const val MESSAGE_KEY = "message"
        const val ERROR_TYPE = "error"

        /** TDLib's first run opens and unpacks its database, which is slower than a normal call. */
        const val REQUEST_TIMEOUT_MILLIS = 120_000L

        const val RECEIVE_TIMEOUT_SECONDS = 2.0
    }
}
