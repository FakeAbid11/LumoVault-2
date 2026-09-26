package com.lumovault.app.ui.viewer

/**
 * What the engine reports about the clip it was given.
 *
 * Every event carries the generation it was produced under, because a released player can still deliver one
 * more callback, and the only way to tell that callback from a current one is to ask which player minted it.
 * The engine stamps the number it was handed; [VideoSession] drops anything that does not match the page it
 * is now describing.
 */
sealed interface VideoEngineEvent {
    val generation: Int

    /** The source was accepted and the decoder is being built. */
    data class Preparing(override val generation: Int) : VideoEngineEvent

    /** Decoded, and how long the clip turned out to be. */
    data class Prepared(override val generation: Int, val durationMs: Long) : VideoEngineEvent

    /** Frames are moving. */
    data class Playing(override val generation: Int) : VideoEngineEvent

    /** Frames are not, and nothing went wrong. */
    data class Paused(override val generation: Int) : VideoEngineEvent

    /** The clip reached its end. */
    data class Completed(override val generation: Int) : VideoEngineEvent

    /** Nothing after this works. The kind is a category, never framework text: either can name the file. */
    data class Failed(override val generation: Int, val kind: VideoFailureKind) : VideoEngineEvent
}

/**
 * The only thing LumoVault asks of a video player.
 *
 * Deliberately narrow, and deliberately without a single Android type: it is the seam that lets the
 * lifecycle be tested at all. [ExoPlayerEngine] is the real one, and everything here is what the page may
 * assume exists — which is why `play`, `pause` and `seek` answer `false` instead of throwing. A player that
 * refuses is a normal Tuesday on a device nobody has, and an exception from it would land on the player's
 * own thread.
 */
interface VideoEngine {
    /** Where to send what the player reports. Set once, by the session that owns this engine. */
    var onEvent: ((VideoEngineEvent) -> Unit)?

    /** Take this clip. Reports [VideoEngineEvent.Failed] with [VideoFailureKind.SourceUnopenable] if it cannot. */
    fun start(contentUri: String, generation: Int)

    fun play(generation: Int): Boolean

    fun pause(generation: Int): Boolean

    fun seek(positionMs: Long, generation: Int): Boolean

    /** Where the playhead is, or null when there is nothing to read. Never throws on a dead player. */
    fun positionMs(generation: Int): Long?

    /** Give the decoder back. Safe to call twice, and safe to call when nothing was ever started. */
    fun release()
}

/**
 * One video page's playback, with no Compose in it either.
 *
 * [VideoStage] draws; [VideoEngine] talks to Media3; this is the thing in between that decides what a
 * command means and what a report is allowed to change. Splitting it out is not decoration — it is what
 * makes the guards in [VideoPlayback] testable against a scripted engine instead of only on a phone, and it
 * puts every rule about *when the player may be touched* in one file that has no framework types in it.
 *
 * Three things hold here, and each one is a crash otherwise:
 *
 *  - **The machine is asked first, always.** A command that [VideoPlayback] refuses never reaches the
 *    player, so "tap play on a clip that has not decoded" and "scrub a page that has been swiped away" are
 *    no-ops rather than exceptions.
 *  - **The generation is carried everywhere.** [onEngineEvent] ignores anything from a player this page no
 *    longer has, which is the difference between a late callback and a late callback *rewriting the next
 *    video the user swiped to*.
 *  - **One page, one player.** [release] is the only way out, it is idempotent, and a released session stays
 *    released: a returning page is a new session rather than an old one talked out of being dead.
 */
class VideoSession(
    private val engine: VideoEngine,
    /**
     * Called after anything the screen draws could depend on.
     *
     * A `var` rather than a constructor argument because the page that mirrors this session's state cannot
     * be named before the session exists, and because leaving it empty is how a page that has gone away stops
     * being written to from a player callback that arrived one moment too late.
     */
    var onChanged: () -> Unit = {},
) {
    val playback = VideoPlayback()

    private var generation: Int? = null

    init {
        engine.onEvent = { event -> onEngineEvent(event) }
    }

    /** The clip this page is showing may now be played. Does nothing if a player is already live. */
    fun activate(contentUri: String) {
        val token = playback.open() ?: return
        generation = token
        engine.start(contentUri, token)
    }

    /**
     * Play, or pause, or nothing.
     *
     * A finished clip replays from the top rather than resuming from its own end, because that is what the
     * button has always meant here and what a person expects from a play arrow that appears after a clip
     * stops — and because the alternative is relying on undocumented behaviour in a player that has just
     * reported `ENDED`.
     */
    fun togglePlay(): Boolean {
        val token = generation ?: return false
        return if (playback.canPause()) {
            command(token, { engine.pause(token) }, { playback.onPauseRequested() })
        } else if (playback.canPlay()) {
            // Back to the top through the same door a drag uses, so the bar and the "is it over" flag move
            // with the seek rather than after it.
            if (playback.finished) seekTo(0L)
            command(token, { engine.play(token) }, { playback.onPlayRequested() })
        } else {
            false
        }
    }

    /**
     * Move the playhead, or refuse.
     *
     * Returns where it actually went: the caller has to know whether the drag was a seek, because a clamped
     * one still has to redraw the bar at the position the clip allows rather than at the finger.
     */
    fun seekTo(targetMillis: Long): Long? {
        val token = generation ?: return null
        val allowed = playback.seekTarget(targetMillis) ?: return null
        return if (command(token, { engine.seek(allowed, token) }, { playback.onSeek(allowed) })) allowed else null
    }

    /** Read the playhead, if this page is still running a clip worth reading. */
    fun readPosition(): Boolean {
        val token = generation ?: return false
        if (!playback.canReadPosition()) return false
        val reported = engine.positionMs(token) ?: return false
        return if (playback.onPosition(reported)) {
            onChanged()
            true
        } else {
            false
        }
    }

    /** Stop the clip and keep the player, for a screen that is going to the background. */
    fun pauseForBackground(): Boolean {
        val token = generation ?: return false
        return command(token, { engine.pause(token) }, { playback.onPauseRequested() })
    }

    /**
     * End this page's ownership of a decoder.
     *
     * Called by the pager when the page stops being the visible one, by the composable when it leaves, and
     * by anything in between: [VideoPlayback.shouldRelease] answers only the first time, and the engine is
     * called regardless so a machine that was already released cannot leave a decoder running.
     */
    fun release() {
        playback.shouldRelease()
        generation = null
        engine.release()
        onChanged()
    }

    /**
     * The one way a player's own news reaches the page.
     *
     * A failure is applied *and* acted on: once Media3 has reported an error the player is in its idle state,
     * and holding it means holding a decoder for a clip that will never play. The page's state then stays
     * [VideoPlayerState.Failed] rather than becoming Released, because what the person sees is the sentence,
     * not the bookkeeping.
     */
    fun onEngineEvent(event: VideoEngineEvent) {
        if (event.generation != generation) return
        val applied = when (event) {
            is VideoEngineEvent.Preparing -> playback.onPrepareStarted(event.generation)
            is VideoEngineEvent.Prepared -> playback.onPrepared(event.generation, event.durationMs)
            is VideoEngineEvent.Playing -> playback.onEnginePlaying(event.generation)
            is VideoEngineEvent.Paused -> playback.onEngineStopped(event.generation)
            is VideoEngineEvent.Completed -> playback.onCompletion()
            is VideoEngineEvent.Failed -> playback.onEngineFailure(event.generation, event.kind)
        }
        if (!applied) return
        if (event is VideoEngineEvent.Failed) {
            generation = null
            engine.release()
        }
        onChanged()
    }

    /** Ask the engine, then the machine — and if the engine threw, believe it rather than the plan. */
    private fun command(token: Int, action: () -> Boolean, commit: () -> Unit): Boolean {
        if (!action()) {
            playback.onOperationFailed()
            generation = null
            engine.release()
            onChanged()
            return false
        }
        commit()
        onChanged()
        return true
    }
}
