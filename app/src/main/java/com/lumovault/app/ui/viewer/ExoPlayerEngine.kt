package com.lumovault.app.ui.viewer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * One clip, one ExoPlayer, and the only place in LumoVault that names a Media3 type.
 *
 * The split is the point: [VideoPlayback] decides whether a transition is legal, [VideoSession] applies it,
 * and this file is the thin translation between those decisions and a framework object that reports its own
 * news on its own thread. Everything Media3 says comes back out as a [VideoEngineEvent] carrying the
 * generation it was heard under, so a callback from a player that has already been released is recognised as
 * one instead of being written into the state of whatever page is on screen now.
 *
 * Four things this handles that the `MediaPlayer` version could not delegate:
 *
 *  - **The surface is not ours.** `Player.setVideoSurfaceView` makes Media3 register the `SurfaceHolder`
 *    callback and detach the video output before the surface is taken away, so the page hands over a
 *    [PlayerView] and never speaks to a holder again. `PlayerView` is asked for no controls of its own: it is
 *    a surface with Media3's aspect-ratio handling, and the transport the person sees stays LumoVault's.
 *  - **An error is a category, not a string.** `PlaybackException` is reduced to a [VideoFailureKind] and the
 *    numeric code. Its message is never read: an IO failure's text is built from the URI that failed, and a
 *    URI is the name of somebody's file.
 *  - **The player is built where it must be.** `PlayerView.setPlayer` insists on a player whose application
 *    looper is the main one, so construction happens on the main thread and nowhere else. It gets the
 *    application context, never the activity's, so a page that is gone cannot be kept alive by its own
 *    decoder.
 *  - **A retired engine stays retired.** Once [release] has run this object will not build another player, so
 *    the only way a new clip gets a decoder is by being a new session — which is also what stops an old
 *    player from ever drawing the next video the user swipes to.
 */
class ExoPlayerEngine(
    context: Context,
    private val mediaStoreId: Long,
) : VideoEngine {
    private val appContext = context.applicationContext

    override var onEvent: ((VideoEngineEvent) -> Unit)? = null

    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    private var playerView: PlayerView? = null

    /** The attempt the current player belongs to. 0 means "nothing is live", and 0 is never a real token. */
    private var generation = 0

    /** Set once and never cleared: a released engine is done, not idle. */
    private var retired = false

    /**
     * Take the page's rendering surface.
     *
     * Called when the view appears and again on every recomposition, because the two orders things happen in
     * are both possible: the view can arrive before the player exists, or after it has already prepared.
     */
    fun attachView(view: PlayerView) {
        playerView = view
        runCatching { view.player = player }
            .onFailure { Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=surface_attach ${it.javaClass.simpleName}") }
    }

    /** Drop a surface the page is losing, but only if it is still the one this engine is drawing into. */
    fun detachView(view: PlayerView) {
        if (playerView !== view) return
        playerView = null
        runCatching { view.player = null }
            .onFailure { Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=surface_detach ${it.javaClass.simpleName}") }
    }

    override fun start(contentUri: String, generation: Int) {
        if (retired || player != null) return
        this.generation = generation
        Log.i(TAG, "VIDEO_OPEN_STARTED id=$mediaStoreId type=video")

        // `content://` is a claim, not a promise: the file can have been deleted outside the app, or its
        // permission revoked since it was indexed. Opening a descriptor first is what separates "this file is
        // gone" from "the decoder disliked it", and it costs one syscall rather than an asynchronous native
        // IO error that arrives after the user has already left the page. The descriptor is closed without
        // being read: ExoPlayer streams the file itself through the content resolver, so no copy of the clip
        // exists in memory at any point.
        val readable = runCatching {
            appContext.contentResolver.openAssetFileDescriptor(Uri.parse(contentUri), "r")?.use { true } ?: false
        }.getOrDefault(false)

        if (!readable) {
            Log.w(TAG, "VIDEO_DATASOURCE_FAILED id=$mediaStoreId kind=unopenable")
            emit(VideoEngineEvent.Failed(generation, VideoFailureKind.SourceUnopenable))
            return
        }

        // Media3 loads on its own playback thread, so this is the honest equivalent of `prepareAsync` rather
        // than a blocking call: from here the player is building, and `STATE_READY` is the answer.
        emit(VideoEngineEvent.Preparing(generation))
        runCatching { buildPlayer(contentUri, generation) }.onFailure {
            Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=preparation ${it.javaClass.simpleName}")
            emit(VideoEngineEvent.Failed(generation, VideoFailureKind.PreparationFailed))
        }
    }

    override fun play(generation: Int): Boolean = commanded(generation) { it.play() }

    override fun pause(generation: Int): Boolean = commanded(generation) { it.pause() }

    // The one command with a log line of its own, because play and pause are reported by the player as they
    // actually start and stop, whereas a seek is a request whose result is not a visible state change.
    override fun seek(positionMs: Long, generation: Int): Boolean =
        commanded(generation) { it.seekTo(positionMs) }
            .also { succeeded -> if (succeeded) Log.i(TAG, "VIDEO_SEEK id=$mediaStoreId") }

    override fun positionMs(generation: Int): Long? =
        livePlayer(generation)?.let { runCatching { it.currentPosition }.getOrNull() }

    /**
     * Give the decoder back.
     *
     * Retires the engine whether or not a player was ever built: a clip whose source could not be opened has
     * nothing to release, and an engine that has been told once that its page is over must not answer a later
     * `start` with a fresh player either. That is also what makes the second and third release a no-op — the
     * page can be disposed by the pager, by navigation and by recomposition in the same frame.
     */
    override fun release() {
        generation = 0
        retired = true
        val dying = player ?: return
        player = null
        listener?.let { runCatching { dying.removeListener(it) } }
        listener = null
        // Detach the surface before releasing, which is `PlayerView`'s own recommended order and the one that
        // leaves no window in which the view can be handed a frame by a player that has stopped existing. The
        // reference is dropped with it: a view holds its context, and an engine that is done with a page has no
        // business keeping that page's activity alive one recomposition longer.
        playerView?.let { view ->
            runCatching { view.player = null }
            playerView = null
        }
        runCatching { dying.release() }
            .onFailure { Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=release ${it.javaClass.simpleName}") }
        Log.i(TAG, "VIDEO_RELEASED id=$mediaStoreId")
    }

    /**
     * Build and hand over the player, stamping it with the attempt it belongs to.
     *
     * [stamp] is captured rather than read back from the property, and that is the whole guard: an anonymous
     * listener that asked the engine "what generation are we on" would always agree with itself, and a stale
     * callback would then be waved through the one door built to stop it.
     */
    private fun buildPlayer(contentUri: String, stamp: Int) {
        // `Builder` takes the looper of the thread that calls it, and `PlayerView` only accepts a player
        // bound to the main one. This file must therefore be driven from the main thread — which is what
        // VideoStage's effects and listeners do.
        val built = ExoPlayer.Builder(appContext).build()
        val heard = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (stamp != this@ExoPlayerEngine.generation) return
                when (state) {
                    Player.STATE_BUFFERING -> emit(VideoEngineEvent.Preparing(stamp))

                    Player.STATE_READY -> {
                        // Read defensively: this callback can be the one a released player had already queued,
                        // and a `duration` call on a player that no longer exists is the crash this whole page
                        // was rewritten to avoid.
                        val length = runCatching { durationMsOf(built) }.getOrDefault(0L)
                        Log.i(TAG, "VIDEO_READY id=$mediaStoreId duration_ms=$length")
                        emit(VideoEngineEvent.Prepared(stamp, length))
                    }

                    Player.STATE_ENDED -> {
                        Log.i(TAG, "VIDEO_COMPLETED id=$mediaStoreId")
                        emit(VideoEngineEvent.Completed(stamp))
                    }

                    // The state a player that has just reported an error ends up in, and the state a player
                    // that stopped answering is in. Either way the clip is not coming back on this player.
                    Player.STATE_IDLE -> {
                        Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=playback")
                        emit(VideoEngineEvent.Failed(stamp, VideoFailureKind.PlaybackFailed))
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (stamp != this@ExoPlayerEngine.generation) return
                if (isPlaying) {
                    Log.i(TAG, "VIDEO_STARTED id=$mediaStoreId")
                    emit(VideoEngineEvent.Playing(stamp))
                } else {
                    Log.i(TAG, "VIDEO_PAUSED id=$mediaStoreId")
                    emit(VideoEngineEvent.Paused(stamp))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (stamp != this@ExoPlayerEngine.generation) return
                val kind = videoFailureKindOf(error.errorCode)
                // The category and the number, never the message: an IO error's text names the file it could
                // not read, and a filename is personal data.
                Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=${kind.logName} code=${error.errorCode}")
                emit(VideoEngineEvent.Failed(stamp, kind))
            }
        }
        built.addListener(heard)
        player = built
        listener = heard
        built.setMediaItem(MediaItem.fromUri(Uri.parse(contentUri)))
        playerView?.let { view -> runCatching { view.player = built } }
        built.prepare()
    }

    /** The player only if it is still the one this attempt minted, which is what makes a stale call a no-op. */
    private fun livePlayer(generation: Int): ExoPlayer? =
        player?.takeIf { !retired && generation == this.generation && generation != 0 }

    private fun commanded(generation: Int, action: (ExoPlayer) -> Unit): Boolean {
        val target = livePlayer(generation) ?: return false
        return runCatching { action(target) }
            .onFailure {
                // A player that throws at a command is a player whose decoder has gone away underneath it.
                // The session turns this into a failure state rather than letting it reach the caller.
                Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=command_refused ${it.javaClass.simpleName}")
            }
            .isSuccess
    }

    private fun emit(event: VideoEngineEvent) {
        onEvent?.invoke(event)
    }

    /** A duration the player has confirmed, or nothing. `C.TIME_UNSET` is Media3 for "not known yet". */
    private fun durationMsOf(player: Player): Long =
        player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
}

/**
 * Which of Media3's error codes mean what to LumoVault.
 *
 * Grouped by the ranges `PlaybackException` documents rather than by every constant, with two exceptions
 * pulled out in front: a file that is gone and a file this app is no longer allowed to read are not
 * "loading failed", they are "there is nothing here", and that is the difference between telling somebody
 * to check their storage and telling them the clip is corrupt.
 */
internal fun videoFailureKindOf(errorCode: Int): VideoFailureKind = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    -> VideoFailureKind.SourceUnopenable

    in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_DISCONNECTED,
    -> VideoFailureKind.IoFailure

    PlaybackException.ERROR_CODE_NOT_SUPPORTED,
    in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> VideoFailureKind.UnsupportedMedia

    in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    in PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    -> VideoFailureKind.DecoderFailure

    else -> VideoFailureKind.PlaybackFailed
}

/** The category as it appears in a log line, in the shape the existing video logs already use. */
private val VideoFailureKind.logName: String
    get() = when (this) {
        VideoFailureKind.SourceUnopenable -> "unopenable"
        VideoFailureKind.IoFailure -> "io"
        VideoFailureKind.UnsupportedMedia -> "unsupported"
        VideoFailureKind.DecoderFailure -> "decoder"
        VideoFailureKind.PreparationFailed -> "preparation"
        VideoFailureKind.PlaybackFailed -> "playback"
    }

/** Ids, categories and codes only — no uri, no path, no framework error text. */
private const val TAG = "LumoVaultVideo"
