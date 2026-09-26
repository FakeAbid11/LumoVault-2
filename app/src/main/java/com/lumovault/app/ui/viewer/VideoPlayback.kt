package com.lumovault.app.ui.viewer

/**
 * Where one video page's player has got.
 *
 * Mirrors `MediaPlayer`'s own states, and the reason to write them down is that `MediaPlayer` throws
 * `IllegalStateException` from almost every method when called in the wrong one — including from its
 * callbacks, which run on a thread no Compose boundary can catch. Naming the states is what lets the
 * wrapper ask "is this legal now" instead of wrapping every call in a guess.
 */
enum class VideoPlayerState {
    /** No player, or one that has never been given a source. */
    Idle,

    /** A source is being checked and handed to the player. */
    Opening,

    /** `prepareAsync` is in flight; nothing may be asked of the player until it answers. */
    Preparing,

    /** Decoded and attached. Playback may be started. */
    Ready,

    Playing,

    Paused,

    /**
     * Something failed and will not recover.
     *
     * Terminal on purpose. A player that reported an error cannot be prepared again — `reset` on a
     * MediaPlayer that died mid-decode is itself a source of `RuntimeException` — so the page shows the
     * failure and the only way out is the way out of the screen.
     */
    Failed,

    /** Gone. Every operation on a released player is illegal, and releasing twice must be harmless. */
    Released,
}

/** Why playback stopped, as a category. No framework text, no path: either can name the user's file. */
enum class VideoFailureKind { SourceUnopenable, PreparationFailed, DecoderError, SurfaceLost }

/**
 * The lifecycle decisions of one video page, with no Android types in them.
 *
 * `VideoStage` owns a player; this owns *when it may be touched*. Four rules do all the work, and each one
 * exists because the alternative is a crash rather than a bad frame:
 *
 *  - **One open, once.** [open] answers only from [VideoPlayerState.Idle], so a page cannot hand a second
 *    data source to a player that is already prepared.
 *  - **A callback must still be current.** [open] hands back a generation that every callback carries; when
 *    it no longer matches, the callback belongs to a player that has been released, and the only safe thing
 *    to do with it is nothing. Released players deliver their events late — which is exactly the shape of
 *    "tapping a video sometimes closes the app": the listener writes to state and to a player that are both
 *    already gone, and the throw happens on a thread with nothing to catch it.
 *  - **A surface can die underneath it.** [detachSurface] moves playback to Paused and refuses every
 *    rendering operation until [attachSurface] says otherwise, because handing frames to a destroyed surface
 *    aborts in native code.
 *  - **Failed and Released are terminal.** Nothing retried here, and nothing asked of a dead player:
 *    [canPlay], [canSeek] and [shouldRelease] all say no.
 */
class VideoPlayback {
    var state: VideoPlayerState = VideoPlayerState.Idle
        private set

    var failure: VideoFailure? = null
        private set

    var durationMs: Long = 0L
        private set

    private var generation = 0
    private var surfaceAttached = false

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

    fun onSourceRejected(token: Int): Boolean = fail(token, VideoFailureKind.SourceUnopenable)

    /** The handover from Opening to Preparing. Legal only while the source we just set is the one in play. */
    fun onPrepareStarted(token: Int): Boolean {
        if (!isCurrent(token) || state != VideoPlayerState.Opening) return false
        state = VideoPlayerState.Preparing
        return true
    }

    fun onPrepared(token: Int, durationMillis: Long): Boolean {
        if (!isCurrent(token) || state != VideoPlayerState.Preparing) return false
        durationMs = durationMillis.coerceAtLeast(0L)
        state = VideoPlayerState.Ready
        return true
    }

    fun onPreparationFailed(token: Int): Boolean = fail(token, VideoFailureKind.PreparationFailed)

    /**
     * An operation the player refused: `start` or `seekTo` throwing on a state the machine thought was
     * legal, which happens when the decoder dies between the check and the call.
     *
     * Needs no token because the caller is holding the player this machine is describing — but it is still
     * refused after release, so a late failure cannot rewrite a page that has already moved on.
     */
    fun onOperationFailed(): Boolean {
        if (state == VideoPlayerState.Released || state == VideoPlayerState.Failed) return false
        failure = VideoFailure(VideoFailureKind.DecoderError)
        state = VideoPlayerState.Failed
        return true
    }

    /** The decoder's own error, reported by a callback that may arrive long after the page moved on. */
    fun onPlayerError(token: Int): Boolean = fail(token, VideoFailureKind.DecoderError)

    fun onSurfaceCreated(): Boolean {
        if (state == VideoPlayerState.Released || state == VideoPlayerState.Failed) return false
        surfaceAttached = true
        return true
    }

    /** True when the player has to be told to stop drawing, which is every live state but Paused. */
    fun onSurfaceDestroyed(): Boolean {
        surfaceAttached = false
        return when (state) {
            VideoPlayerState.Playing -> {
                state = VideoPlayerState.Paused
                true
            }

            VideoPlayerState.Ready, VideoPlayerState.Paused,
            VideoPlayerState.Opening, VideoPlayerState.Preparing,
            -> true

            VideoPlayerState.Failed, VideoPlayerState.Released, VideoPlayerState.Idle -> false
        }
    }

    /** Whether a `setDisplay` may be attempted at all: a live player, and a surface that exists. */
    fun canAttachSurface(): Boolean = surfaceAttached &&
        state != VideoPlayerState.Idle &&
        state != VideoPlayerState.Released &&
        state != VideoPlayerState.Failed

    fun canPlay(): Boolean = state == VideoPlayerState.Ready ||
        state == VideoPlayerState.Paused ||
        state == VideoPlayerState.Playing

    fun onPlayRequested(): Boolean {
        if (!canPlay()) return false
        state = VideoPlayerState.Playing
        return true
    }

    fun canPause(): Boolean = state == VideoPlayerState.Playing

    fun onPauseRequested() {
        if (state == VideoPlayerState.Playing) state = VideoPlayerState.Paused
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

    fun onSeek(targetMillis: Long) {
        positionMs = seekTarget(targetMillis) ?: positionMs
    }

    var positionMs: Long = 0L
        private set

    /** The playhead may only be read while the player is answering. */
    fun canReadPosition(): Boolean = state == VideoPlayerState.Playing || state == VideoPlayerState.Paused

    fun canReadDuration(): Boolean = state == VideoPlayerState.Preparing ||
        state == VideoPlayerState.Ready ||
        state == VideoPlayerState.Playing ||
        state == VideoPlayerState.Paused

    fun onCompletion() {
        if (state == VideoPlayerState.Playing) {
            state = VideoPlayerState.Paused
            positionMs = durationMs
        }
    }

    /**
     * Releases once.
     *
     * Returns false when there is nothing left to release, which is what makes the double release a
     * no-op rather than an `IllegalStateException` — the page can be disposed by the pager, by
     * navigation and by recomposition in the same frame, and all three used to reach this line.
     */
    fun shouldRelease(): Boolean {
        if (state == VideoPlayerState.Released) return false
        state = VideoPlayerState.Released
        surfaceAttached = false
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
