package com.lumovault.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video page's controller, with a player that only pretends.
 *
 * [VideoPlaybackTest] checks the rules; this checks that the page *follows* them — that a command the machine
 * refuses never reaches a player, that a report from a player the page no longer owns changes nothing at all,
 * and that the one thing a released page still does is hand its decoder back. Those are the properties the
 * crash this replaced depended on, and they are the ones nobody sees on a device unless somebody swipes at
 * exactly the wrong millisecond.
 *
 * The engine here is a script rather than a mocking library: what it records is the page's behavior and what
 * it reports is a player's, and keeping both visible in one small class is what lets these tests say "the old
 * player was released before the new one was built" instead of "a method was called".
 */
class VideoSessionTest {
    private class FakeEngine : VideoEngine {
        override var onEvent: ((VideoEngineEvent) -> Unit)? = null

        /** The attempt this fake has a player for; 0 is "no player", and no real token is ever 0. */
        var generation = 0
            private set

        val started = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        var plays = 0
            private set
        var pauses = 0
            private set
        var releases = 0
            private set
        var playRefused = false
        var positionToReport: Long? = null

        override fun start(contentUri: String, generation: Int) {
            this.generation = generation
            started += contentUri
        }

        override fun play(generation: Int): Boolean {
            if (playRefused || generation != this.generation) return false
            plays += 1
            return true
        }

        override fun pause(generation: Int): Boolean {
            if (generation != this.generation) return false
            pauses += 1
            return true
        }

        override fun seek(positionMs: Long, generation: Int): Boolean {
            if (generation != this.generation) return false
            seeks += positionMs
            return true
        }

        override fun positionMs(generation: Int): Long? =
            positionToReport?.takeIf { this.generation != 0 && generation == this.generation }

        override fun release() {
            if (generation == 0) return
            releases += 1
            generation = 0
        }

        /** News from the player this fake currently is. */
        fun report(what: (Int) -> VideoEngineEvent) {
            if (generation != 0) onEvent?.invoke(what(generation))
        }

        /**
         * News from a player that is already gone. 99 is a token no test here reaches by counting, and a real
         * released player is exactly this: an object still able to speak, with nothing left to say it to.
         */
        fun reportLate(what: (Int) -> VideoEngineEvent) {
            onEvent?.invoke(what(LATE_GENERATION))
        }

        companion object {
            private const val LATE_GENERATION = 99
        }
    }

    private class Fixture {
        var changes = 0
        val engine = FakeEngine()
        val session = VideoSession(engine, onChanged = { changes += 1 })
        val clip = "content://media/external/video/media/42"

        /** The reports a healthy clip produces, in the order a real player produces them. */
        fun walkToReady(duration: Long = 4_000L) {
            session.activate(clip)
            engine.report { VideoEngineEvent.Preparing(it) }
            engine.report { VideoEngineEvent.Prepared(it, duration) }
        }
    }

    @Test
    fun openingAPageHandsItTheClipExactlyOnce() {
        val fixture = Fixture()

        fixture.session.activate(fixture.clip)
        fixture.session.activate(fixture.clip)

        assertEquals(listOf(fixture.clip), fixture.engine.started)
        assertEquals(VideoPlayerState.Opening, fixture.session.playback.state)
    }

    @Test
    fun theWalkFromOpeningToReleasedIsTheOneThePlayerDescribes() {
        val fixture = Fixture()
        fixture.walkToReady()
        assertEquals(VideoPlayerState.Ready, fixture.session.playback.state)
        assertEquals(4_000L, fixture.session.playback.durationMs)

        assertTrue(fixture.session.togglePlay())
        assertEquals(VideoPlayerState.Playing, fixture.session.playback.state)
        assertEquals(1, fixture.engine.plays)

        fixture.engine.report { VideoEngineEvent.Paused(it) }
        assertEquals(VideoPlayerState.Paused, fixture.session.playback.state)

        fixture.session.release()

        assertEquals(VideoPlayerState.Released, fixture.session.playback.state)
        assertEquals("the page's one player is the one that was released", 1, fixture.engine.releases)
    }

    @Test
    fun anInactivePageStopsOwningTheClipAndRefusesToReadIt() {
        val fixture = Fixture()
        fixture.walkToReady()
        assertTrue(fixture.session.togglePlay())
        fixture.engine.positionToReport = 1_200L

        // What the pager does the moment the finger leaves: this page stops being the active one.
        fixture.session.release()

        assertFalse("a released page cannot start anything", fixture.session.togglePlay())
        assertFalse(fixture.session.readPosition())
        assertNull(fixture.session.seekTo(2_000L))
        assertFalse(fixture.session.pauseForBackground())
        assertEquals("and nothing else reached the player", 1, fixture.engine.plays)
        assertEquals(0, fixture.engine.pauses)
        assertEquals("releasing is the one thing a page leaving is still allowed to do",
            1, fixture.engine.releases)
        assertEquals(emptyList<Long>(), fixture.engine.seeks)
        assertFalse(fixture.session.playback.canReadPosition())
    }

    @Test
    fun backgroundingTheViewerPausesTheClipWithoutGivingThePlayerUp() {
        val fixture = Fixture()
        fixture.walkToReady()
        fixture.session.togglePlay()

        assertTrue(fixture.session.pauseForBackground())

        assertEquals(VideoPlayerState.Paused, fixture.session.playback.state)
        assertEquals(1, fixture.engine.pauses)
        assertEquals("the page is still the one being looked at, so the decoder stays", 0, fixture.engine.releases)
    }

    @Test
    fun aReportFromAPlayerThatHasAlreadyBeenReleasedChangesNothingOnScreen() {
        val fixture = Fixture()
        fixture.session.activate(fixture.clip)
        fixture.session.release()
        val changesWhenGone = fixture.changes

        fixture.engine.reportLate { VideoEngineEvent.Prepared(it, 9_000L) }
        fixture.engine.reportLate { VideoEngineEvent.Playing(it) }
        fixture.engine.reportLate { VideoEngineEvent.Failed(it, VideoFailureKind.DecoderFailure) }

        assertEquals("a late callback cannot rewrite a released page",
            VideoPlayerState.Released, fixture.session.playback.state)
        assertEquals(0L, fixture.session.playback.durationMs)
        assertNull(fixture.session.playback.failure)
        assertEquals("and it cannot even ask for a recomposition", changesWhenGone, fixture.changes)
    }

    /**
     * A page that comes back is a new session, because [VideoPlayerState.Released] is terminal — but the new
     * session has to reach Playing, or swiping away and back leaves a spinner on screen forever.
     */
    @Test
    fun aReturnedPageGetsANewSessionThatCanPlayAgain() {
        val gone = Fixture()
        gone.walkToReady()
        gone.session.release()
        assertFalse("the old one is finished, including its own token", gone.session.togglePlay())
        assertEquals(1, gone.engine.releases)

        val back = Fixture()
        back.walkToReady(duration = 6_000L)

        assertEquals(VideoPlayerState.Ready, back.session.playback.state)
        assertEquals(listOf(back.clip), back.engine.started)
        assertTrue(back.session.togglePlay())
        assertEquals(VideoPlayerState.Playing, back.session.playback.state)
    }

    @Test
    fun aFailureGivesTheDecoderBackAndLeavesTheSentenceOnScreen() {
        val fixture = Fixture()
        fixture.walkToReady()
        fixture.session.togglePlay()

        fixture.engine.report { VideoEngineEvent.Failed(it, VideoFailureKind.UnsupportedMedia) }

        assertEquals(VideoPlayerState.Failed, fixture.session.playback.state)
        assertEquals(VideoFailureKind.UnsupportedMedia, requireNotNull(fixture.session.playback.failure).kind)
        assertEquals("a player that reported an error is not worth holding on to", 1, fixture.engine.releases)
        assertFalse(fixture.session.togglePlay())
        assertEquals("and the transport cannot call into it either", 1, fixture.engine.plays)
    }

    @Test
    fun aPlayerThatRefusesACommandFailsThePageRatherThanThrowingAtIt() {
        val fixture = Fixture()
        fixture.walkToReady()
        fixture.engine.playRefused = true

        assertFalse(fixture.session.togglePlay())

        assertEquals(VideoPlayerState.Failed, fixture.session.playback.state)
        assertEquals(VideoFailureKind.DecoderFailure, requireNotNull(fixture.session.playback.failure).kind)
        assertEquals(1, fixture.engine.releases)
        assertFalse(fixture.session.readPosition())
    }

    @Test
    fun aSeekBeforeTheDurationIsKnownNeverReachesThePlayer() {
        val fixture = Fixture()
        fixture.session.activate(fixture.clip)
        fixture.engine.report { VideoEngineEvent.Preparing(it) }

        assertNull("nothing to seek through yet", fixture.session.seekTo(2_000L))
        assertEquals(emptyList<Long>(), fixture.engine.seeks)

        fixture.engine.report { VideoEngineEvent.Prepared(it, 4_000L) }
        fixture.session.togglePlay()

        assertEquals(4_000L, fixture.session.seekTo(99_000L))
        assertEquals("the clamp happens before the player is asked", listOf(4_000L), fixture.engine.seeks)
        assertEquals(4_000L, fixture.session.playback.positionMs)
    }

    @Test
    fun finishingAClipParksTheBarAtItsEndAndTheNextPlayStartsFromTheTop() {
        val fixture = Fixture()
        fixture.walkToReady(duration = 8_000L)
        fixture.session.togglePlay()
        fixture.engine.positionToReport = 7_900L
        assertTrue(fixture.session.readPosition())

        fixture.engine.report { VideoEngineEvent.Completed(it) }

        assertEquals(VideoPlayerState.Paused, fixture.session.playback.state)
        assertEquals(8_000L, fixture.session.playback.positionMs)
        assertTrue(fixture.session.playback.finished)
        assertFalse("and a finished clip is not polled any more", fixture.session.readPosition())

        assertTrue(fixture.session.togglePlay())

        assertEquals("replaying starts at the beginning", listOf(0L), fixture.engine.seeks)
        assertEquals(2, fixture.engine.plays)
        assertEquals(VideoPlayerState.Playing, fixture.session.playback.state)
        assertFalse(fixture.session.playback.finished)
        assertEquals(0L, fixture.session.playback.positionMs)
    }

    @Test
    fun aPositionReadFromThePlayerIsClampedToTheClipRatherThanBelieved() {
        val fixture = Fixture()
        fixture.walkToReady(duration = 5_000L)
        fixture.session.togglePlay()
        fixture.engine.positionToReport = 42_000L

        assertTrue(fixture.session.readPosition())

        assertEquals(5_000L, fixture.session.playback.positionMs)
        fixture.engine.positionToReport = 1_000L
        assertTrue(fixture.session.readPosition())
        assertEquals(1_000L, fixture.session.playback.positionMs)
        assertTrue("the screen was told both times", fixture.changes > 0)
    }

    @Test
    fun bufferingMidClipIsNotReportedAsStartingOver() {
        val fixture = Fixture()
        fixture.walkToReady()
        fixture.session.togglePlay()

        // A scrub into data the player has not read yet reports the same state a first load does, and the page
        // must not answer by putting the spinner back.
        fixture.engine.report { VideoEngineEvent.Preparing(it) }

        assertEquals(VideoPlayerState.Playing, fixture.session.playback.state)
        assertFalse(fixture.session.playback.loading)
    }
}
