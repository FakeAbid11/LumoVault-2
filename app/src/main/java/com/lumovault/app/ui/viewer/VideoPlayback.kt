package com.lumovault.app.ui.viewer

/**
 * Where one video page's player has got.
 *
 * Written down because a player asked for the wrong thing at the wrong moment does not answer with an
 * error: ExoPlayer throws from inside its own callbacks, and the `MediaPlayer` this replaced threw from
 * nearly every method. A throw in a callback happens on a thread no composable's `runCatching` is anywhere
 * near, which is the difference between an unplayable clip showing a sentence and an unplayable clip
 * closing the app. Naming the states is what lets the wrapper ask "is this legal now".
 *
 * [Preparing] and [Ready] are Media3's `STATE_BUFFERING` and `STATE_READY` seen from this side of the
 * boundary: the clip has been handed over and the decoder has not answered yet, and then it has.
 */
enum class VideoPlayerState {
    /** No player, or one that has never been given a source. */
    Idle,

    /** A source is being checked and handed to the player. */
    Opening,

    /** The player has the source and is building a decoder; nothing may be asked of it until it answers. */
    Preparing,

    /** Decoded. Playback may be started. */
    Ready,

    Playing,

    Paused,

    /**
     * Something failed and will not recover.
     *
     * Terminal on purpose: a player that reported an error is not prepared again by this page, so the
     * failure is shown and the only way out is the way out of the screen.
     */
    Failed,

    /** Gone. Every operation on a released player is illegal, and releasing twice must be harmless. */
    Released,
}

/**
 * Why playback stopped, as a category. No framework text, no path: either can name the user's file.
 *
 * These are Media3's `PlaybackException.ERROR_CODE_*` groups folded down to the ones a person could act
 * on. They all draw the same one sentence; the distinction is for the log line, because "the file is gone",
 * "this container is not decodable" and "the decoder died" are different things to hear about when a device
 * report says a video would not start.
 */
enum class VideoFailureKind { SourceUnopenable, IoFailure, UnsupportedMedia, DecoderFailure, PreparationFailed, PlaybackFailed }

/**
 * The lifecycle decisions of one video page, with no Android types in them.
 *
 * [VideoSession] applies these to a player; this decides *whether a transition may happen at all*. Three
 * rules do the work, and each one exists because the alternative is a crash rather than a bad frame:
 *
 *  - **One open, once.** [open] answers only from [VideoPlayerState.Idle], so a page cannot hand a second
 *    source to a player that is already prepared.
 *  - **A callback must still be current.** [open] hands back a generation that every callback carries; when
 *    it no longer matches, the callback belongs to a player that has been released, and the only safe thing
 *    to do with it is nothing. Released players deliver their last events late — which is the exact shape of
 *    "tapping a video sometimes closes the app": the listener writes to state and to a player that are both
 *    already gone.
 *  - **Failed and Released are terminal.** Nothing is retried here and nothing is asked of a dead player:
 *    [canPlay], [seekTarget] and [canReadPosition] all say no.
 *
 * What used to be a fourth rule — a surface dying underneath a live player — is not here any more, because
 * Media3 registers the `SurfaceHolder.Callback` itself and detaches the video output before the surface
 * goes away. Keeping that guard here would mean keeping the surface it guarded.
 */
class VideoPlayback {
    var state: VideoPlayerState = VideoPlayerState.Idle
        private set

    var failure: VideoFailure? = null
        private set

    var durationMs: Long = 0L
        private set

    var positionMs: Long = 0L
        private set

    /**
     * The clip ran to its end.
     *
     * Tracked separately because a finished clip sits in the same state as a paused one and would otherwise
     * go on being polled ten times a second forever: the playhead is already where the clip ends, there is
     * nothing left to read, and a page behind the finger should not be reading a player at all.
     */
    var finished: Boolean = false
        private set

    private var generation = 0

    /** Why playback stopped, with the category only. */
    data class VideoFailure(val kind: VideoFailureKind)

    /** The token this attempt's callbacks must carry, or null when opening is not legal from here. */
    fun open(): Int? {
        if (state != VideoPlayerState.Idle) return null
        generation += 1
        state = VideoPlayerState.Opening
        return generation
    }

    /** False for every callback from a player that has since been released and replaced. */
    fun isCurrent(token: Int): Boolean = token == generation &&
        state != VideoPlayerState.Released && state != VideoPlayerState.Failed

    /**
     * The handover from Opening to Preparing. Legal only while the source we just set is the one in play.
     *
     * A player that goes back through buffering mid-clip — after a scrub into data it has not read yet —
     * reports the same thing, and the second time it is refused rather than replayed: the spinner belongs to
     * a clip that has never been seen, not to one that is merely catching up.
     */
    fun onPrepareStarted(token: Int): Boolean {
        if (!isCurrent(token) || state != VideoPlayerState.Opening) return false
        state = VideoPlayerState.Preparing
        return true
    }

    /**
     * The decoder answered, with how long the clip turned out to be.
     *
     * Legal from [VideoPlayerState.Preparing], which is the ordinary case, and from a clip that is already
     * running as well: Media3 reports "ready" and "playing" as two separate notifications, and this page does
     * not get to choose which one it hears first. A duration learned after playback started is still a
     * duration, and a page without one has a slider nobody can move.
     */
    fun onPrepared(token: Int, durationMillis: Long): Boolean {
        if (!isCurrent(token)) return false
        val length = durationMillis.coerceAtLeast(0L)
        if (state == VideoPlayerState.Preparing) {
            durationMs = length
            state = VideoPlayerState.Ready
            return true
        }
        if (length <= 0L) return false
        if (state != VideoPlayerState.Ready && state != VideoPlayerState.Playing && state != VideoPlayerState.Paused) {
            return false
        }
        durationMs = length
        return true
    }

    /**
     * The one door every failure comes through, whatever Media3 called it.
     *
     * A source that will not open, a container that cannot be parsed, a decoder that gave up and a player
     * that stopped answering are the same thing to the page: the clip is over, here is why in one word. The
     * kind is kept for the log line and for nothing else, because the sentence on the screen must not depend
     * on a framework's taxonomy.
     */
    fun onEngineFailure(token: Int, kind: VideoFailureKind): Boolean = fail(token, kind)

    /**
     * An operation the player refused.
     *
     * Needs no token because the caller is holding the player this machine is describing — but it is still
     * refused after release and after failure, so a late exception cannot rewrite a page that has already
     * moved on.
     */
    fun onOperationFailed(kind: VideoFailureKind = VideoFailureKind.DecoderFailure): Boolean {
        if (state == VideoPlayerState.Released || state == VideoPlayerState.Failed) return false
        failure = VideoFailure(kind)
        state = VideoPlayerState.Failed
        return true
    }

    fun canPlay(): Boolean = state == VideoPlayerState.Ready ||
        state == VideoPlayerState.Paused ||
        state == VideoPlayerState.Playing

    /** The optimistic half of pressing play: the icon changes now, not when the decoder agrees. */
    fun onPlayRequested(): Boolean {
        if (!canPlay()) return false
        state = VideoPlayerState.Playing
        return true
    }

    fun canPause(): Boolean = state == VideoPlayerState.Playing

    fun onPauseRequested(): Boolean {
        if (!canPause()) return false
        state = VideoPlayerState.Paused
        return true
    }

    /**
     * The player says it is running.
     *
     * Accepted from a clip that is ready or paused, and from one still building: see [onPrepared] for why the
     * order of the two notifications is not this page's to depend on. It is never accepted from Opening — a
     * clip that has not been handed a source cannot be playing, and a state machine that believes that story
     * is the one that lets a stale player drive.
     */
    fun onEnginePlaying(token: Int): Boolean {
        if (!isCurrent(token)) return false
        val legal = state == VideoPlayerState.Preparing ||
            state == VideoPlayerState.Ready ||
            state == VideoPlayerState.Paused ||
            state == VideoPlayerState.Playing
        if (!legal || state == VideoPlayerState.Playing) return false
        state = VideoPlayerState.Playing
        return true
    }

    /**
     * The player says it is not running any more.
     *
     * Only ever leaves Playing, so a clip that was never started cannot be reported as paused: the state to
     * draw for a ready-but-untouched player is Ready, and its spinner is gone either way.
     */
    fun onEngineStopped(token: Int): Boolean {
        if (!isCurrent(token) || state != VideoPlayerState.Playing) return false
        state = VideoPlayerState.Paused
        return true
    }

    /**
     * Where a seek may go, or null when it may not happen.
     *
     * Clamped to the clip rather than to the slider: a duration the player never confirmed is a duration of
     * zero, and seeking into a zero-length clip is how a scrub ends with the decoder past its own end.
     */
    fun seekTarget(millis: Long): Long? {
        if (durationMs <= 0L) return null
        if (state != VideoPlayerState.Ready && state != VideoPlayerState.Paused && state != VideoPlayerState.Playing) {
            return null
        }
        return millis.coerceIn(0L, durationMs)
    }

    /** Whether the playhead moved, so a caller can tell a seek from a drag that went nowhere. */
    fun onSeek(targetMillis: Long): Boolean {
        val allowed = seekTarget(targetMillis) ?: return false
        positionMs = allowed
        // Dragging the bar back into the clip is a request to watch it again, so the playhead starts being
        // read again with it.
        finished = false
        return true
    }

    /** A position the player offered. Refused once the clip is over, so a poll cannot move a finished bar. */
    fun onPosition(positionMillis: Long): Boolean {
        if (!canReadPosition()) return false
        positionMs = positionMillis.coerceIn(0L, durationMs)
        return true
    }

    /**
     * The clip ran to its end, wherever the playhead last happened to be read.
     *
     * Accepted from Playing, Ready and Paused alike: an `ENDED` from the player is a fact about the clip, and
     * a zero-length file can report it before anybody pressed anything.
     */
    fun onCompletion(): Boolean {
        if (state != VideoPlayerState.Playing &&
            state != VideoPlayerState.Ready &&
            state != VideoPlayerState.Paused
        ) {
            return false
        }
        state = VideoPlayerState.Paused
        positionMs = durationMs
        finished = true
        return true
    }

    /** The playhead may only be read while the player is answering, and has somewhere left to go. */
    fun canReadPosition(): Boolean = !finished &&
        (state == VideoPlayerState.Playing || state == VideoPlayerState.Paused)

    /**
     * Releases once.
     *
     * Returns false when there is nothing left to release, which is what makes the double release a no-op
     * rather than an exception — the page can be disposed by the pager, by navigation and by recomposition
     * in the same frame, and all three reach this line.
     */
    fun shouldRelease(): Boolean {
        if (state == VideoPlayerState.Released) return false
        state = VideoPlayerState.Released
        return true
    }

    /** What the page should draw: a spinner while the decoder is being built, an error once it has failed. */
    val loading: Boolean get() = state == VideoPlayerState.Opening || state == VideoPlayerState.Preparing

    val failed: Boolean get() = state == VideoPlayerState.Failed

    private fun fail(token: Int, kind: VideoFailureKind): Boolean {
        if (!isCurrent(token)) return false
        if (state == VideoPlayerState.Released) return false
        failure = VideoFailure(kind)
        state = VideoPlayerState.Failed
        return true
    }
}
