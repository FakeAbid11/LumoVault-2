package com.lumovault.app.data.remote.telegram

import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the real client promises before TDLib is even talking to Telegram.
 *
 * A JVM unit test has no `libtdjni.so` on its path, which is precisely the state an APK built without
 * the pinned native artifacts is in — so this is the one place where the "report it, never fake it"
 * rule can be checked without a device.
 */
class TdLibClientTest {
    @Test
    fun `a build with no TDLib binary reports itself unusable instead of crashing`() {
        val client = TdLibClient(fakeCredentials())

        assertFalse(client.isUsable)

        val error = runBlocking { runCatching { client.start() }.exceptionOrNull() }
        assertTrue(
            "starting without the binary must be a clear refusal, not an UnsatisfiedLinkError",
            error is IllegalStateException,
        )
    }

    @Test
    fun `a request in a build without TDLib is refused rather than quietly queued`() {
        val client = TdLibClient(fakeCredentials())

        // `request` starts the client itself — that is what lets the unattended backup work in a fresh
        // process — so what a build without the binary has to see is `start()`'s refusal arriving
        // through the request, not an UnsatisfiedLinkError and not a silently dropped question.
        val error = runBlocking { runCatching { client.request(TdApi.GetMe()) }.exceptionOrNull() }

        assertTrue(error is IllegalStateException)
    }
}
