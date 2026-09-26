package com.lumovault.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video page's lifecycle, without a player.
 *
 * Every case here is a call that `MediaPlayer` would answer by throwing `IllegalStateException` on a
 * thread no Compose boundary can catch — which is the whole difference between an unplayable clip showing a
 * sentence and an unplayable clip closing the app. They are asserted on the machine rather than on the
 * wrapper because the wrapper's job is to ask permission first and to swallow nothing.
 */
class VideoPlaybackTest {
    /** Opens, prepares and returns the token every later callback must carry. */
    private fun prepared(playback: VideoPlayback = VideoPlayback(), duration: Long = 4_000L): Pair<VideoPlayback, Int> {
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))
        assertTrue(playback.onPrepared(token, duration))
        return playback to token
    }

    @Test
    fun aHealthyClipGoesIdleToOpeningToPreparingToReadyToPlaying() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())

        assertEquals(VideoPlayerState.Opening, playback.state)
        // Nothing may be asked of it before it answers.
        assertFalse(playback.canPlay())
        assertNull(playback.seekTarget(1_000))

        assertTrue(playback.onPrepareStarted(token))
        assertEquals(VideoPlayerState.Preparing, playback.state)
        assertTrue(playback.loading)
        assertFalse(playback.canPlay())

        assertTrue(playback.onPrepared(token, 8_400L))
        assertEquals(VideoPlayerState.Ready, playback.state)
        assertFalse("a prepared clip is no longer loading", playback.loading)
        assertTrue(playback.canPlay())

        assertTrue(playback.onPlayRequested())
        assertEquals(VideoPlayerState.Playing, playback.state)
        assertTrue(playback.canPause())
        assertTrue(playback.canReadPosition())
    }

    @Test
    fun aSourceThatWillNotOpenFailsThePageRatherThanThrowing() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())

        assertTrue(playback.onSourceRejected(token))

        assertEquals(VideoPlayerState.Failed, playback.state)
        assertEquals(VideoFailureKind.SourceUnopenable, requireNotNull(playback.failure).kind)
        assertTrue(playback.failed)
        assertFalse("a failed page shows a sentence, not a spinner", playback.loading)
        assertFalse(playback.canPlay())
    }

    @Test
    fun aPrepareThatNeverAnswersIsAFailureAndNotAWait() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))

        assertTrue(playback.onPreparationFailed(token))

        assertEquals(VideoPlayerState.Failed, playback.state)
        assertEquals(VideoFailureKind.PreparationFailed, requireNotNull(playback.failure).kind)
    }

    @Test
    fun anUnsupportedOrCorruptClipEndsInOneControlledFailure() {
        val (playback, token) = prepared()
        assertTrue(playback.onPlayRequested())

        assertTrue(playback.onPlayerError(token))

        assertEquals(VideoPlayerState.Failed, playback.state)
        assertEquals(VideoFailureKind.DecoderError, requireNotNull(playback.failure).kind)
        // Terminal: nothing after this is allowed to ask the decoder for anything again.
        assertFalse(playback.canPlay())
        assertFalse(playback.canPause())
        assertFalse(playback.onPlayRequested())
        assertNull(playback.seekTarget(2_000))
        assertFalse(playback.onPrepared(token, 1L))
    }

    @Test
    fun aSurfaceThatDiesWhilePreparingLeavesThePlayerAloneUntilItComesBack() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))
        assertTrue(playback.onSurfaceCreated())

        assertTrue("a preparing player must stop drawing", playback.onSurfaceDestroyed())

        assertEquals(VideoPlayerState.Preparing, playback.state)
        assertFalse("and no display may be handed to a surface that is gone", playback.canAttachSurface())
        assertTrue(
            "a decoder that gives up while preparing is still heard, even with nowhere to draw",
            playback.onPlayerError(token),
        )
        assertEquals(VideoPlayerState.Failed, playback.state)
    }

    @Test
    fun aSurfaceThatDiesMidPlaybackPausesRatherThanCrashes() {
        val (playback, _) = prepared()
        assertTrue(playback.onSurfaceCreated())
        assertTrue(playback.onPlayRequested())

        assertTrue(playback.onSurfaceDestroyed())

        assertEquals(
            "Playing into a destroyed surface aborts in native code, so the machine moves it out of Playing first",
            VideoPlayerState.Paused,
            playback.state,
        )
        assertFalse(playback.canAttachSurface())
        assertTrue(playback.onSurfaceCreated())
        assertTrue("and a recreated surface may be attached again, on the same player", playback.canAttachSurface())
    }

    @Test
    fun releasingIsIdempotentBecauseThreeThingsCanAllDecideToReleaseAtOnce() {
        val (playback, _) = prepared()

        assertTrue(playback.shouldRelease())
        assertFalse("a second release would be an IllegalStateException on the player", playback.shouldRelease())
        assertFalse(playback.shouldRelease())
        assertEquals(VideoPlayerState.Released, playback.state)
        assertFalse(playback.canAttachSurface())
        assertFalse(playback.canReadPosition())
    }

    /**
     * A `MediaPlayer` can deliver one more event after it has been released, and the crash is not the stale
     * write to Compose state — it is the `duration` call inside that listener, on a thread where nothing
     * catches. So the page's machine must refuse its own late callbacks, and a page that comes back is a new
     * machine rather than the old one being talked out of being dead.
     */
    @Test
    fun aLateCallbackFromAReleasedPlayerIsRefusedRatherThanRun() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))

        assertTrue(playback.shouldRelease())
        assertFalse("the player that owns this token is gone", playback.isCurrent(token))
        assertFalse(playback.onPrepared(token, 5_000L))
        assertFalse(playback.onPlayerError(token))
        assertFalse(playback.onPreparationFailed(token))
        assertFalse(playback.onSurfaceCreated())
        assertFalse(playback.onSurfaceDestroyed())
        assertEquals("nothing a late callback says can rewrite a released page",
            VideoPlayerState.Released, playback.state)
        assertNull("and no duration is learned after the fact", playback.durationMs.takeIf { it > 0L })

        val next = VideoPlayback()
        val fresh = requireNotNull(next.open())
        assertTrue(next.onPrepared(fresh, 5_000L).not())
        assertTrue(next.onPrepareStarted(fresh))
        assertTrue(next.onPrepared(fresh, 5_000L))
        assertEquals(VideoPlayerState.Ready, next.state)
    }

    @Test
    fun seekingIsRefusedUntilThereIsSomethingToSeekThrough() {
        val playback = VideoPlayback()
        assertNull("nothing is known about a clip that has not been opened", playback.seekTarget(500))

        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))
        assertNull("a duration of zero is not a track", playback.seekTarget(500))

        assertTrue(playback.onPrepared(token, 10_000L))
        assertEquals(4_000L, playback.seekTarget(4_000))
        assertEquals("a wild drag is clamped, not obeyed", 10_000L, playback.seekTarget(99_000))
        assertEquals(0L, playback.seekTarget(-5L))
    }

    @Test
    fun aSeekAfterReleaseDoesNothingAndASeekAfterFailureDoesNothing() {
        val (released, _) = prepared()
        assertTrue(released.shouldRelease())
        assertNull(released.seekTarget(3_000))
        released.onSeek(3_000)
        assertEquals("the playhead stays where it was", 0L, released.positionMs)

        val (broken, token) = prepared()
        assertTrue(broken.onPlayerError(token))
        assertNull(broken.seekTarget(3_000))
    }

    @Test
    fun startingAReleasedPlayerIsRefusedRatherThanThrownAt() {
        val (playback, _) = prepared()
        assertTrue(playback.onPlayRequested())
        assertTrue(playback.shouldRelease())

        assertFalse(playback.canPlay())
        assertFalse(playback.canPause())
        assertFalse(playback.onPlayRequested())
        assertFalse("a refused operation cannot be reported as playing", playback.canReadPosition())
    }

    @Test
    fun onlyTheCurrentPageMayOwnAPlayerAndLeavingItReleasesTheOnlyOne() {
        val playback = VideoPlayback()
        val token = playback.open()
        assertNotNull(token)
        assertNull("a page that is already opening cannot open again", playback.open())
        assertNull("nor can a page that is preparing", VideoPlayback().let { p ->
            assertTrue(p.onPrepareStarted(requireNotNull(p.open())))
            p.open()
        })

        assertTrue(playback.shouldRelease())
        assertNull("and a released page needs a new player, which is a new machine", playback.open())
    }

    @Test
    fun aFailedPageStillRefusesEverythingTheCloseButtonDoesNotAsk() {
        val (playback, token) = prepared()
        assertTrue(playback.onSurfaceCreated())
        assertTrue(playback.onPlayRequested())
        assertTrue(playback.onPlayerError(token))

        // What the screen draws is decided by these three: the failure sentence, no spinner, and no
        // transport that could call into a dead decoder. Close and Back are the caller's, which is why the
        // failure branch is handed an `onClose` the pager's chrome does not provide once the bars hide.
        assertTrue(playback.failed)
        assertFalse(playback.loading)
        assertNull(playback.seekTarget(1L))
        assertFalse(playback.canReadPosition())
        assertTrue(playback.shouldRelease())
    }

    @Test
    fun aCompletionEndsTheClipAtItsOwnLengthRatherThanWhereverTheDecoderStopped() {
        val (playback, _) = prepared(duration = 9_000L)
        assertTrue(playback.onPlayRequested())

        playback.onCompletion()

        assertEquals(VideoPlayerState.Paused, playback.state)
        assertEquals("the bar lands at the end of the clip", 9_000L, playback.positionMs)
        assertFalse("and the playhead is no longer polled", playback.canReadPosition())

        playback.onSeek(2_500L)
        assertEquals("scrubbing back into it starts the playhead again", 2_500L, playback.positionMs)
        assertTrue(playback.canReadPosition())
    }
}
