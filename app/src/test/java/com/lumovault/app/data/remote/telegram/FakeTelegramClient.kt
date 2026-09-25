package com.lumovault.app.data.remote.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.drinkless.tdlib.TdApi

/**
 * A deterministic stand-in for TDLib at the one boundary LumoVault owns — [TelegramClient].
 *
 * It answers typed [TdApi.Function] objects with typed [TdApi.Object]s, exactly as `Client.send` and
 * its result handler do in production, so the code under test runs against the same request classes
 * and the same field names the real client will carry. The alternative — a fake that speaks JSON —
 * would keep the old transport alive behind the tests and prove nothing about the new one.
 *
 * Every request is recorded in [sent] so a test can assert what actually went to Telegram, not merely
 * what the screen ended up showing.
 */
internal class FakeTelegramClient(
    var usable: Boolean = true,
) : TelegramClient {
    val sent = mutableListOf<TdApi.Function<*>>()
    var started = 0

    /** What the fake answers a request with. Reassigned per test to script a sequence. */
    var answer: (TdApi.Function<*>) -> TdApi.Object = { TdApi.Ok() }

    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 32)

    override val updates: Flow<TdApi.Object> = _updates

    override val isUsable: Boolean
        get() = usable

    override suspend fun start() {
        started += 1
    }

    override suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>): T {
        check(usable) { "TDLib is not configured in this build" }
        sent += function

        @Suppress("UNCHECKED_CAST")
        return answer(function) as T
    }

    /** Every request of [F] the flow under test sent, in order. */
    inline fun <reified F : TdApi.Function<*>> sentOf(): List<F> = sent.filterIsInstance<F>()
}

/** The api id/hash pair TDLib is configured with. A placeholder value, never a real credential. */
internal fun fakeCredentials() = TelegramCredentials(apiId = 1234, apiHash = "0123456789abcdef")

internal fun fakeClientInfo() = TelegramClientInfo(
    applicationVersion = "0.1.0",
    deviceModel = "Test Handset",
    systemVersion = "Android 15",
    systemLanguageCode = "en",
)

internal fun chat(
    id: Long,
    title: String,
    supergroupId: Long,
    isChannel: Boolean = true,
): TdApi.Chat = TdApi.Chat().apply {
    this.id = id
    this.title = title
    type = TdApi.ChatTypeSupergroup().apply {
        this.supergroupId = supergroupId
        this.isChannel = isChannel
    }
}

internal fun remoteFile(id: String, size: Long = 0): TdApi.File = TdApi.File().apply {
    this.size = size
    remote = TdApi.RemoteFile().apply { this.id = id }
}
