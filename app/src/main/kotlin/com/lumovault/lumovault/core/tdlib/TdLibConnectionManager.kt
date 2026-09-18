package com.lumovault.lumovault.core.tdlib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Coarse connection health, distinct from the Telegram *authorization* state.
 *
 * Ported from lib/core/tdlib/tdlib_connection_manager.dart. Two state machines
 * exist in the original and must not be conflated: this one tracks transport
 * reachability; the auth repository tracks sign-in progress.
 */
enum class ConnectionStatus {
    disconnected,
    connecting,
    connected,
    reconnecting,
    failed,
}

/**
 * Drives reconnection with exponential backoff and a liveness heartbeat.
 *
 * Ported from lib/core/tdlib/tdlib_connection_manager.dart. The subtle parts:
 *  - [reconnect] deliberately does NOT reset the retry counter. The counter is
 *    read when scheduling, so resetting it here pins every attempt to the
 *    initial backoff. It only resets on a genuine [ConnectionStatus.connected]
 *    (and on an explicit [disconnect], which is a teardown, not a retry).
 *  - [disconnect] closes the native client. Without that, [client]'s
 *    `initialize` stays idempotently-true and [reconnect] rebuilds nothing,
 *    so every scheduled retry after a transient failure would be a no-op and
 *    the ladder would march straight to [ConnectionStatus.failed].
 *  - the database key is [TdLibKeyStore]'s problem, not this layer's: the
 *    client answers `SetTdlibParameters` itself and reads the key there, so a
 *    reconnect reuses it without the manager having to thread it through.
 *  - "connected" is declared when [TdLibClient.initialize] returns, as in the
 *    original, and *also* on a later connectionStateReady update. The first is
 *    load-bearing: the update flow has no replay, so if TDLib emits its ready
 *    state during initialize() — before the listener subscribes — a
 *    subscription-only state machine would sit on "connecting" forever.
 */
class TdLibConnectionManager(
    private val client: TdLibClient,
    coroutineScope: CoroutineScope? = null,
) {
    private val ownsScope = coroutineScope == null
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(ConnectionStatus.disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    val isConnected: Boolean get() = _status.value == ConnectionStatus.connected

    private var retryCount = 0
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var updateJob: Job? = null

    /**
     * Brings the client up and starts watching for connection state changes.
     *
     * Idempotent: a second call while connected or connecting returns, so a
     * reconnect racing a manual connect cannot build two clients.
     */
    suspend fun connect() {
        val current = _status.value
        if (current == ConnectionStatus.connected || current == ConnectionStatus.connecting) return

        // Cancel any pending reconnect so it does not race with this connect.
        stopReconnect()
        _status.value = ConnectionStatus.connecting

        try {
            client.initialize()
        } catch (e: TdLibException) {
            _status.value = ConnectionStatus.failed
            throw e
        } catch (e: Throwable) {
            // initialize() can fail with non-TdLib errors too (native load
            // failures, IO errors). Without this branch the status stayed stuck
            // on "connecting" forever while the caller got an untyped error.
            _status.value = ConnectionStatus.failed
            throw TdLibException(
                code = "CONNECT_FAILED",
                message = "Failed to connect to TDLib: ${e.message}",
            )
        }

        // The client is up. Declaring it here (rather than waiting on the
        // connectionStateReady update) is what the original does and closes the
        // no-replay race described in the class doc.
        retryCount = 0
        _status.value = ConnectionStatus.connected

        startUpdateListener()
        startHeartbeat()
    }

    /** Force a fresh handshake. Disconnects first if we were up. */
    suspend fun reconnect() {
        if (isConnected) disconnect()
        // NOTE: retryCount is intentionally left alone — see the class doc.
        connect()
    }

    /**
     * Tears the client down gracefully. Closes the native client so a later
     * [connect] actually rebuilds it, and clears the retry ladder.
     */
    fun disconnect() {
        stopReconnect()
        stopHeartbeat()
        stopUpdateListener()
        client.close()
        retryCount = 0
        _status.value = ConnectionStatus.disconnected
    }

    /**
     * Wraps a request so a transient failure schedules a reconnect and the
     * error still propagates. Non-transient errors are the caller's problem.
     */
    suspend fun send(request: TdApi.Function<out TdApi.Object>): TdApi.Object {
        if (!isConnected) {
            throw TdLibException(code = "NOT_CONNECTED", message = "Not connected to Telegram")
        }
        return try {
            client.send(request)
        } catch (e: TdLibException) {
            if (isTransient(e)) scheduleReconnect()
            throw e
        }
    }

    private fun isTransient(e: TdLibException): Boolean = e.code in TRANSIENT_ERRORS

    private fun scheduleReconnect() {
        // Already on it; a second timer would only double-fire the retry.
        if (_status.value == ConnectionStatus.reconnecting) return
        if (retryCount >= MAX_RETRIES) {
            _status.value = ConnectionStatus.failed
            return
        }
        _status.value = ConnectionStatus.reconnecting
        val backoff = calculateBackoff()
        retryCount++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            try {
                reconnect()
            } catch (_: Throwable) {
                // This attempt failed; climb to the next rung. A failed
                // attempt leaves status on "failed" via connect(), and this
                // re-arm is what walks the ladder at all — without it the
                // first initialize() failure would be permanent. MAX_RETRIES
                // is the only stop condition, as in the original.
                scheduleReconnect()
            }
        }
    }

    /** initialBackoff * 2^retryCount, capped at [MAX_BACKOFF_MS]. */
    private fun calculateBackoff(): Long {
        val shifted = INITIAL_BACKOFF_MS * (1L shl retryCount.coerceAtMost(SHIFT_CAP))
        return shifted.coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun startUpdateListener() {
        updateJob?.cancel()
        updateJob = scope.launch {
            client.updates.collect { update ->
                if (update is TdLibClient.Update.ConnectionReady) {
                    if (update.ready) {
                        // Cancel an armed reconnect from a prior outage: the
                        // connection is already live, so a late fire would call
                        // reconnect() (close + initialize) and churn a working
                        // session. Also resets the backoff ladder.
                        stopReconnect()
                        retryCount = 0
                        _status.value = ConnectionStatus.connected
                    } else {
                        _status.value = ConnectionStatus.connecting
                    }
                }
            }
        }
    }

    private fun stopUpdateListener() {
        updateJob?.cancel()
        updateJob = null
    }

    /**
     * Periodically pokes TDLib; a request that cannot complete means the
     * transport is gone and we should reconnect. Skipped while not connected,
     * otherwise the handshake's own failures would arm a reconnect loop.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isConnected) continue
                try {
                    client.send(TdApi.GetAuthorizationState())
                } catch (_: Throwable) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun dispose() {
        disconnect()
        client.dispose() // graceful Close already sent; this also frees its scope
        if (ownsScope) scope.cancel()
    }

    internal companion object {
        const val MAX_RETRIES = 10
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 2L * 60 * 1000 // 2 minutes
        const val SHIFT_CAP = 20
        const val HEARTBEAT_INTERVAL_MS = 30_000L
        val TRANSIENT_ERRORS = setOf("NETWORK_ERROR", "TIMEOUT", "REQUEST_TIMEOUT", "NOT_CONNECTED")
    }
}
