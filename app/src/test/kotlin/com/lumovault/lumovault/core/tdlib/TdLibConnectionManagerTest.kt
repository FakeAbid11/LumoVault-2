package com.lumovault.lumovault.core.tdlib

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mocking [TdLibClient] keeps the native client out of the JVM test: mockk
 * builds the proxy without invoking the constructor, so `Client`'s static
 * loader (which pulls in libtdjni) never runs.
 *
 * Timing notes: the heartbeat is an infinite `while (true) { delay(...) }`
 * loop, so [kotlinx.coroutines.test.advanceUntilIdle] would advance virtual
 * time forever and hang. Tests therefore drive the clock with
 * [advanceTimeBy] only, flush immediate work with [runCurrent], and dispose
 * the manager before returning so no coroutine outlives the test.
 */
class TdLibConnectionManagerTest {

    private val client: TdLibClient = mockk(relaxed = true)
    private val updates = MutableSharedFlow<TdLibClient.Update>(extraBufferCapacity = 16)

    private fun TestScope.manager() = TdLibConnectionManager(client, this).also {
        every { client.updates } returns updates
    }

    private suspend fun failing(block: suspend () -> Unit): Throwable = try {
        block()
        AssertionError("expected the call to throw")
    } catch (t: Throwable) {
        t
    }

    @Test
    fun connectBringsClientUpAndDeclaresConnected() = runTest {
        val manager = manager()

        manager.connect()

        assertEquals(ConnectionStatus.connected, manager.status.value)
        coVerify(exactly = 1) { client.initialize() }
        manager.dispose()
    }

    @Test
    fun connectIsIdempotentWhileConnected() = runTest {
        val manager = manager()
        manager.connect()

        manager.connect()

        coVerify(exactly = 1) { client.initialize() }
        manager.dispose()
    }

    @Test
    fun connectMarksFailedAndRethrowsTdLibErrors() = runTest {
        coEvery { client.initialize() } throws TdLibException(code = "API_ID_INVALID", message = "no creds")
        val manager = manager()

        val thrown = failing { manager.connect() }

        assertTrue("expected TdLibException, got $thrown", thrown is TdLibException)
        assertEquals("API_ID_INVALID", (thrown as TdLibException).code)
        assertEquals(ConnectionStatus.failed, manager.status.value)
    }

    @Test
    fun connectWrapsUnexpectedFailuresAsConnectFailed() = runTest {
        coEvery { client.initialize() } throws RuntimeException("native load failed")
        val manager = manager()

        val thrown = failing { manager.connect() }

        assertTrue(thrown is TdLibException)
        assertEquals("CONNECT_FAILED", (thrown as TdLibException).code)
        assertEquals(ConnectionStatus.failed, manager.status.value)
    }

    @Test
    fun transientSendErrorArmsReconnectButDoesNotFireEarly() = runTest {
        val manager = manager()
        manager.connect()
        coEvery { client.send(any()) } throws TdLibException(code = "NETWORK_ERROR", message = "down")

        failing { manager.send(TdApi.GetAuthorizationState()) }

        assertEquals(ConnectionStatus.reconnecting, manager.status.value)
        // The 1s backoff has not elapsed, so no reconnect attempt has run.
        coVerify(exactly = 1) { client.initialize() }
        manager.dispose()
    }

    @Test
    fun nonTransientErrorsDoNotArmReconnect() = runTest {
        val manager = manager()
        manager.connect()
        coEvery { client.send(any()) } throws TdLibException(code = "PHONE_NUMBER_INVALID", message = "bad")

        failing { manager.send(TdApi.GetAuthorizationState()) }

        assertEquals(ConnectionStatus.connected, manager.status.value)
        advanceTimeBy(5_000)
        verify(exactly = 0) { client.close() }
        manager.dispose()
    }

    @Test
    fun sendRefusesWhileDisconnected() = runTest {
        val manager = manager()

        val thrown = failing { manager.send(TdApi.GetAuthorizationState()) }

        assertTrue(thrown is TdLibException)
        assertEquals("NOT_CONNECTED", (thrown as TdLibException).code)
    }

    /**
     * The regression net for the reconnect bug. The scheduled path must not
     * tear down a live native client: TDLib owns transport-level recovery and
     * [TdLibClient.initialize] is idempotent, so this path is a state reset,
     * not a rebuild. [close] never happening is what keeps the client alive.
     */
    @Test
    fun aScheduledReconnectRestoresConnectedWithoutClosingTheClient() = runTest {
        val manager = manager()
        manager.connect()
        coEvery { client.send(any()) } throws TdLibException(code = "NETWORK_ERROR", message = "down")
        failing { manager.send(TdApi.GetAuthorizationState()) }

        advanceTimeBy(1_000) // initial backoff
        runCurrent()

        assertEquals(ConnectionStatus.connected, manager.status.value)
        verify(exactly = 0) { client.close() }
        // connect() was entered twice — the original calls initialize() on
        // every connect and relies on the client's own idempotency.
        coVerify(exactly = 2) { client.initialize() }
        manager.dispose()
    }

    @Test
    fun explicitReconnectFromConnectedClosesAndRebuildsTheClient() = runTest {
        val manager = manager()
        manager.connect()

        manager.reconnect()

        verify(exactly = 1) { client.close() }
        coVerify(exactly = 2) { client.initialize() }
        assertEquals(ConnectionStatus.connected, manager.status.value)
        manager.dispose()
    }

    @Test
    fun disconnectClosesTheClientSoAReconnectActuallyRebuilds() = runTest {
        val manager = manager()
        manager.connect()
        coVerify(exactly = 1) { client.initialize() }

        manager.disconnect()

        assertEquals(ConnectionStatus.disconnected, manager.status.value)
        verify(exactly = 1) { client.close() }

        manager.connect()
        coVerify(exactly = 2) { client.initialize() }
        manager.dispose()
    }

    /**
     * The ladder only visibly climbs when the reconnect attempt itself fails;
     * a successful one resets the counter via [connect], as in the original.
     * Here the second rung is 2s, so 1s of virtual time must not have fired it.
     */
    @Test
    fun aFailingReconnectClimbsTheBackoffLadder() = runTest {
        val attempt = AtomicInteger(0)
        coEvery { client.initialize() } answers {
            if (attempt.incrementAndGet() == 1) Unit
            else throw TdLibException(code = "NETWORK_ERROR", message = "down")
        }
        coEvery { client.send(any()) } throws TdLibException(code = "NETWORK_ERROR", message = "down")
        val manager = manager()
        manager.connect()

        failing { manager.send(TdApi.GetAuthorizationState()) } // arms rung 1 (1s)
        advanceTimeBy(1_000) // fires, attempt 2 fails, arms rung 2 (2s)
        runCurrent()
        assertEquals(ConnectionStatus.reconnecting, manager.status.value)

        advanceTimeBy(1_000) // only halfway through rung 2
        runCurrent()
        assertEquals("rung 2 must not have fired after 1s", ConnectionStatus.reconnecting, manager.status.value)
        assertEquals(2, attempt.get())

        advanceTimeBy(1_000) // rung 2 elapses
        runCurrent()
        assertEquals(3, attempt.get())
        manager.dispose()
    }

    @Test
    fun backoffWalksToMaxRetriesThenGivesUp() = runTest {
        val scheduler = testScheduler
        val attemptTimes = mutableListOf<Long>()
        val attempt = AtomicInteger(0)
        coEvery { client.initialize() } answers {
            attemptTimes.add(scheduler.currentTime)
            if (attempt.incrementAndGet() == 1) Unit
            else throw TdLibException(code = "NETWORK_ERROR", message = "down")
        }
        coEvery { client.send(any()) } throws TdLibException(code = "NETWORK_ERROR", message = "down")
        val manager = manager()
        manager.connect()

        failing { manager.send(TdApi.GetAuthorizationState()) }
        // 10 minutes of virtual time — past every rung, including the cap.
        advanceTimeBy(10L * 60 * 1000)
        runCurrent()

        assertEquals(ConnectionStatus.failed, manager.status.value)
        // Initial connect + one attempt per rung; the MAX_RETRIES check stops
        // the ladder without arming another attempt.
        assertEquals(TdLibConnectionManager.MAX_RETRIES + 1, attempt.get())

        // Rung widths are 1s, 2s, 4s ... 64s, then capped at 2 minutes. Without
        // the cap the ladder would keep doubling past 120s.
        val gaps = attemptTimes.zipWithNext { a, b -> b - a }
        assertEquals(
            "the deep rungs must all sit at the cap",
            List(3) { TdLibConnectionManager.MAX_BACKOFF_MS },
            gaps.takeLast(3),
        )
        manager.dispose()
    }

    @Test
    fun connectionReadyCancelsAnArmedReconnect() = runTest {
        val manager = manager()
        manager.connect()
        runCurrent() // let the update collector subscribe
        coEvery { client.send(any()) } throws TdLibException(code = "NETWORK_ERROR", message = "down")
        failing { manager.send(TdApi.GetAuthorizationState()) }
        assertEquals(ConnectionStatus.reconnecting, manager.status.value)

        // TDLib reports the transport healthy again, before the timer fires.
        updates.tryEmit(TdLibClient.Update.ConnectionReady(ready = true))
        runCurrent()

        assertEquals(ConnectionStatus.connected, manager.status.value)
        advanceTimeBy(5_000) // the armed timer was cancelled, so nothing fires
        runCurrent()
        verify(exactly = 0) { client.close() }
        manager.dispose()
    }

    @Test
    fun heartbeatDoesNotPokeAnUnconnectedClient() = runTest {
        val manager = manager() // never connected

        advanceTimeBy(120_000) // four heartbeat intervals
        runCurrent()

        coVerify(exactly = 0) { client.send(any()) }
    }

    @Test
    fun heartbeatPokesAConnectedClient() = runTest {
        val manager = manager()
        manager.connect()
        coEvery { client.send(any()) } returns TdApi.AuthorizationStateReady()

        advanceTimeBy(60_000) // two heartbeat intervals
        runCurrent()

        coVerify(atLeast = 2) { client.send(any()) }
        manager.dispose()
    }

    @Test
    fun disposeDisconnectsWithoutCancellingAnInjectedScope() = runTest {
        val scope = this
        val manager = manager()
        manager.connect()

        manager.dispose()

        verify(exactly = 1) { client.close() }
        assertTrue(
            "the injected scope belongs to its owner, not to the manager",
            scope.coroutineContext[Job]!!.isActive,
        )
    }
}
