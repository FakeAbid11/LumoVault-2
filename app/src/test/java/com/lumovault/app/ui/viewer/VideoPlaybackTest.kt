package com.lumovault.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video page's lifecycle, with no player in the room.
 *
 * Every case here is a call that a framework player answers either by doing something or by throwing — and a
 * throw from one of its own callbacks happens on a thread no composable can catch, which is the difference
 * between an unplayable clip showing a sentence and an unplayable clip closing the app. They are asserted on
 * the machine rather than on the wrapper because the wrapper's job is to ask permission first and to swallow
 * nothing. [VideoSessionTest] covers the same rules one level up, against a scripted engine.
 */
class VideoPlaybackTest {
    /** Opens, prepares and returns the token every later callback must carry. */
    private fun prepared(
        playback: VideoPlayback = VideoPlayback(),
        duration: Long = 4_000L,
    ): Pair<VideoPlayback, Int> {
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))
        assertTrue(playback.onPrepared(token, duration))
        return playback to token
    }

    /** Idle to Playing, in the order a healthy clip actually arrives. */
    @Test
    fun aHealthyClipGoesIdleToOpeningToPreparingToReadyToPlaying() {
        val playback = VideoPlayback()
        assertEquals(VideoPlayerState.Idle, playback.state)

        val token = requireNotNull(playback.open())
        assertEquals(VideoPlayerState.Opening, playback.state)
        assertTrue("an unopened clip is a spinner", playback.loading)
        // Nothing may be asked of it before it answers.
        assertFalse(playback.canPlay())
        assertNull(playback.seekTarget(1_000))

        assertTrue(playback.onPrepareStarted(token))
        assertEquals(VideoPlayerState.Preparing, playback.state)
        assertTrue(playback.loading)
        assertFalse("still no control to give", playback.canPlay())

        assertTrue(playback.onPrepared(token, 8_400L))
        assertEquals(VideoPlayerState.Ready, playback.state)
        assertEquals(8_400L, playback.durationMs)
        assertFalse("a prepared clip is no longer loading", playback.loading)
        assertTrue(playback.canPlay())

        assertTrue(playback.onPlayRequested())
        assertEquals(VideoPlayerState.Playing, playback.state)
        assertTrue(playback.canPause())
        assertTrue(playback.canReadPosition())
    }

    @Test
    fun pausingARunningClipHandsTheTransportBackToTheBar() {
        val (playback, _) = prepared()
        assertTrue(playback.onPlayRequested())

        assertTrue(playback.onPauseRequested())

        assertEquals(VideoPlayerState.Paused, playback.state)
        assertFalse("and a paused clip has nothing left to pause", playback.canPause())
        assertTrue("but it can be started again", playback.canPlay())
        assertTrue("the playhead is still worth reading while it waits", playback.canReadPosition())
    }

    /** The engine's own account of running and stopped wins over what the page guessed. */
    @Test
    fun aPlayerThatReportsItselfStoppedIsBelievedRatherThanArguedWith() {
        val (playback, token) = prepared()
        assertTrue(playback.onPlayRequested())

        assertTrue(playback.onEngineStopped(token))

        assertEquals(VideoPlayerState.Paused, playback.state)
        assertFalse("a clip that stopped on its own cannot still be drawn as running", playback.canPause())
        assertTrue(playback.onEnginePlaying(token))
        assertEquals(VideoPlayerState.Playing, playback.state)
        assertFalse("the same report twice moves nothing", playback.onEnginePlaying(token))
    }

    @Test
    fun aCompletionEndsTheClipAtItsOwnLengthRatherThanWhereverTheDecoderStopped() {
        val (playback, _) = prepared(duration = 9_000L)
        assertTrue(playback.onPlayRequested())
        assertTrue(playback.onPosition(7_100L))

        assertTrue(playback.onCompletion())

        assertEquals(VideoPlayerState.Paused, playback.state)
        assertEquals("the bar lands at the end of the clip", 9_000L, playback.positionMs)
        assertTrue(playback.finished)
        assertFalse("and the playhead is no longer polled", playback.canReadPosition())
        assertFalse("a poll that was already in flight cannot move it back", playback.onPosition(4_000L))
        assertEquals(9_000L, playback.positionMs)

        playback.onSeek(2_500L)
        assertEquals("scrubbing back into it starts the playhead again", 2_500L, playback.positionMs)
        assertFalse(playback.finished)
        assertTrue(playback.canReadPosition())
    }

    @Test
    fun aClipThatEndsBeforeAnythingWasPressedIsStillFinished() {
        val (playback, _) = prepared(duration = 0L)

        assertTrue("a zero-length file reports ENDED without ever being started", playback.onCompletion())

        assertEquals(VideoPlayerState.Paused, playback.state)
        assertEquals("the clip is as long as the player said it was", 0L, playback.durationMs)
        assertTrue(playback.finished)
    }

    /** Media3 posts "ready" and "playing" separately, and this page does not choose the order. */
    @Test
    fun aReportOfPlayingBeforeReadyStillLeavesTheClipPlayable() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))

        assertTrue("running is believed even while the decoder is still building", playback.onEnginePlaying(token))
        assertEquals(VideoPlayerState.Playing, playback.state)
        assertFalse("a clip that is drawing frames is not a spinner", playback.loading)
        assertNull("with no duration yet, the bar is not draggable", playback.seekTarget(500))

        assertTrue("and the length learned afterwards is still recorded", playback.onPrepared(token, 6_000L))
        assertEquals(6_000L, playback.durationMs)
        assertEquals("without dragging the clip back out of play", VideoPlayerState.Playing, playback.state)
        assertEquals(2_000L, playback.seekTarget(2_000))
    }

    @Test
    fun aPrepareThatNeverAnswersIsAFailureAndNotAWait() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))

        assertTrue(playback.onEngineFailure(token, VideoFailureKind.PreparationFailed))

        assertEquals(VideoPlayerState.Failed, playback.state)
        assertEquals(VideoFailureKind.PreparationFailed, requireNotNull(playback.failure).kind)
        assertFalse("a failed page shows a sentence, not a spinner", playback.loading)
    }

    @Test
    fun aSourceThatWillNotOpenFailsThePageRatherThanThrowing() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())

        assertTrue(playback.onEngineFailure(token, VideoFailureKind.SourceUnopenable))

        assertEquals(VideoPlayerState.Failed, playback.state)
        assertEquals(VideoFailureKind.SourceUnopenable, requireNotNull(playback.failure).kind)
        assertFalse(playback.canPlay())
    }

    @Test
    fun anUnsupportedOrCorruptClipEndsInOneControlledFailure() {
        for (kind in listOf(
            VideoFailureKind.DecoderFailure,
            VideoFailureKind.UnsupportedMedia,
            VideoFailureKind.IoFailure,
            VideoFailureKind.PlaybackFailed,
        )) {
            val (playback, token) = prepared()
            assertTrue(playback.onPlayRequested())

            assertTrue("every reported failure arrives at one door: $kind", playback.onEngineFailure(token, kind))

            assertEquals(VideoPlayerState.Failed, playback.state)
            assertEquals(kind, requireNotNull(playback.failure).kind)
            // Terminal: nothing after this is allowed to ask the decoder for anything again.
            assertFalse(playback.canPlay())
            assertFalse(playback.canPause())
            assertFalse(playback.onPlayRequested())
            assertNull(playback.seekTarget(2_000))
            assertFalse(playback.onPrepared(token, 1L))
            assertFalse(playback.onCompletion())
            assertFalse(playback.onEnginePlaying(token))
            assertFalse(playback.canReadPosition())
        }
    }

    /**
     * A refused operation has no token, because the caller is holding the player that refused — but it is
     * still refused once the page is over, so a late exception cannot rewrite a clip the user has left.
     */
    @Test
    fun anOperationRefusedByALivePlayerFailsItAndOneRefusedByADeadPlayerIsIgnored() {
        val live = VideoPlayback()
        assertNotNull(live.open())
        assertTrue(live.onOperationFailed())
        assertEquals(VideoPlayerState.Failed, live.state)
        assertEquals(VideoFailureKind.DecoderFailure, requireNotNull(live.failure).kind)

        val (gone, _) = prepared()
        assertTrue(gone.shouldRelease())
        assertFalse("a dead player cannot be reported as having just died", gone.onOperationFailed())
        assertEquals(VideoPlayerState.Released, gone.state)
    }

    @Test
    fun releasingIsIdempotentBecauseThreeThingsCanAllDecideToReleaseAtOnce() {
        val (playback, _) = prepared()

        assertTrue(playback.shouldRelease())
        assertFalse("a second release would be an exception on the player", playback.shouldRelease())
        assertFalse(playback.shouldRelease())
        assertEquals(VideoPlayerState.Released, playback.state)
        assertFalse(playback.canReadPosition())
    }

    /**
     * A player can deliver one more event after it has been released, and the crash is not the stale write to
     * Compose state — it is the call inside that listener, on a thread where nothing catches. So the page's
     * machine must refuse its own late callbacks, and a page that comes back is a new machine rather than the
     * old one being talked out of being dead.
     */
    @Test
    fun aLateCallbackFromAReleasedPlayerIsRefusedRatherThanRun() {
        val playback = VideoPlayback()
        val token = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(token))

        assertTrue(playback.shouldRelease())
        assertFalse("the player that owns this token is gone", playback.isCurrent(token))
        assertFalse(playback.onPrepared(token, 5_000L))
        assertFalse(playback.onPrepareStarted(token))
        assertFalse(playback.onEnginePlaying(token))
        assertFalse(playback.onEngineStopped(token))
        assertFalse(playback.onEngineFailure(token, VideoFailureKind.DecoderFailure))
        assertFalse(playback.onCompletion())
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
    fun aFailedMachineIsTerminalSoItsTokenIsDeadToo() {
        val playback = VideoPlayback()
        val first = requireNotNull(playback.open())
        assertTrue(playback.onPrepareStarted(first))
        assertTrue(playback.onEngineFailure(first, VideoFailureKind.IoFailure))

        // A failed page is terminal, so a retry is a new machine rather than this one being re-opened. The
        // point of the assertion is that the *old* token stops working the moment the machine stops.
        assertNull(playback.open())
        assertFalse(playback.isCurrent(first))
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
    }

    @Test
    fun aWildDragIsClampedToTheClipRatherThanToTheFinger() {
        val (playback, _) = prepared(duration = 10_000L)
        assertTrue(playback.onPlayRequested())

        assertEquals(10_000L, playback.seekTarget(99_000))
        assertEquals(0L, playback.seekTarget(-5L))

        playback.onSeek(99_000L)
        assertEquals("and the playhead never ends up past the end of the clip", 10_000L, playback.positionMs)
    }

    @Test
    fun aSeekAfterReleaseDoesNothingAndASeekAfterFailureDoesNothing() {
        val (released, _) = prepared()
        assertTrue(released.shouldRelease())
        assertNull(released.seekTarget(3_000))
        assertFalse(released.onSeek(3_000))
        assertEquals("the playhead stays where it was", 0L, released.positionMs)

        val (broken, token) = prepared()
        assertTrue(broken.onEngineFailure(token, VideoFailureKind.DecoderFailure))
        assertNull(broken.seekTarget(3_000))
        assertFalse(broken.onSeek(3_000))
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
        assertTrue(playback.onPlayRequested())
        assertTrue(playback.onEngineFailure(token, VideoFailureKind.DecoderFailure))

        // What the screen draws is decided by these: the failure sentence, no spinner, and no transport that
        // could call into a dead decoder. Close and Back are the caller's, which is why the failure branch is
        // handed an `onClose` the pager's chrome does not provide once the bars hide.
        assertTrue(playback.failed)
        assertFalse(playback.loading)
        assertNull(playback.seekTarget(1L))
        assertFalse(playback.canPlay())
        assertFalse(playback.canPause())
        assertFalse(playback.canReadPosition())
        assertFalse("and the only thing left to do is let go of it", playback.onCompletion())
        assertTrue(playback.shouldRelease())
    }
}
