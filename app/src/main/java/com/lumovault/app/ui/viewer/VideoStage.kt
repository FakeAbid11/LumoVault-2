package com.lumovault.app.ui.viewer

import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumovault.app.R
import com.lumovault.app.ui.theme.OnMedia
import com.lumovault.app.util.formatDuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale

/**
 * A clip, played — and the reason a clip that cannot be played must not take the process with it.
 *
 * `MediaPlayer` is the tool: one local `content://` file at a time, with play, pause, a seek bar and a
 * duration, so a media library does not need a media *framework*. It is also an object that throws
 * `IllegalStateException` from almost every method when asked in the wrong state, and it delivers its
 * callbacks on its own thread — where a throw kills the process and no `runCatching` around a
 * `@Composable` is anywhere near the stack. So the rules live in [VideoPlayback], which has no Android
 * types in it and is unit-tested, and everything below is the thin, guarded translation of those rules
 * into calls on one player.
 *
 * Four properties of a surface inside a pager are handled here because none is visible until it is wrong:
 *
 *  - **Only the visible page owns a player.** A `MediaPlayer` is allocated when this page is looked at and
 *    released when it stops being looked at, so swiping never holds two decoders, and the old one is gone
 *    before the new one is built.
 *  - **The source is checked before it is handed over.** `content://` is only a claim until something opens
 *    it: the file can have been deleted outside the app, or its permission revoked. Opening a descriptor
 *    first turns that into [VideoFailureKind.SourceUnopenable] rather than an asynchronous native IO error
 *    arriving after the user has already left the page. Playback then streams from the resolver — the bytes
 *    are never copied into memory.
 *  - **A surface can die underneath a live player.** Feeding frames to a destroyed surface aborts in native
 *    code, so the holder's callback detaches and re-attaches the display, and the player pauses while there
 *    is nowhere to draw.
 *  - **A failure is shown, and it is the only thing shown.** `MediaPlayer`'s own error path inflates a
 *    framework dialog from resources the app cannot theme or translate; an `OnErrorListener` returning true
 *    suppresses it, and LumoVault's own sentence and a Close button take its place — visible whatever the
 *    viewer's chrome is doing, because a screen that is black with no way out is how a crash looks to
 *    somebody who is not holding a stack trace.
 */
@Composable
fun VideoStage(
    contentUri: String,
    mediaStoreId: Long,
    isActive: Boolean,
    onTap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    /**
     * Bumped the moment a page that has already released its player becomes the visible one again.
     *
     * [VideoPlayerState.Released] is terminal — a `MediaPlayer` cannot be taken back from it — so without a
     * fresh session here, swiping away from a clip and back would leave a page that shows a spinner forever
     * and never opens anything. One swipe, one player, and a new one for the next visit.
     */
    var attempt by remember(contentUri) { mutableIntStateOf(0) }
    val session = remember(contentUri, attempt) { VideoSession() }
    val machine = session.playback

    // The mirror of the machine's state, so a transition recomposes the screen. Every write below goes
    // through the machine first, which is what makes the UI unable to claim a state the player does not
    // have.
    var state by remember(contentUri, attempt) { mutableStateOf(VideoPlayerState.Idle) }
    var durationMs by remember(contentUri, attempt) { mutableLongStateOf(0L) }
    var positionMs by remember(contentUri, attempt) { mutableLongStateOf(0L) }
    var scrubbingTo by remember(contentUri, attempt) { mutableLongStateOf(NO_SCRUB) }
    var surface by remember(contentUri, attempt) { mutableStateOf<SurfaceHolder?>(null) }

    // The one way state reaches the screen: ask the machine, then draw what it says. A refused transition
    // still has to be drawn, or the transport keeps offering a control the player will reject.
    fun sync() {
        state = machine.state
        durationMs = machine.durationMs
        positionMs = machine.positionMs
    }

    fun destroyPlayer() {
        val player = session.player
        session.player = null
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
            Log.i(TAG, "VIDEO_RELEASED id=$mediaStoreId")
        }
    }

    fun MediaPlayer.attachSurface(holder: SurfaceHolder?) = runCatching { setDisplay(holder) }

    fun buildPlayer(): MediaPlayer {
        session.player?.let { return it }
        val player = MediaPlayer()
        session.player = player
        // Legal only before preparation, which is the state a freshly built player is in.
        runCatching { player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
        player.attachSurface(surface)
        return player
    }

    // Decoding starts when the page becomes the visible one, and ends when it stops being visible: the pager
    // composes its neighbours, and a neighbour that prepares a clip holds a hardware decoder for a video the
    // user may never reach.
    LaunchedEffect(isActive, contentUri, attempt) {
        if (!isActive) {
            if (machine.shouldRelease()) sync()
            destroyPlayer()
            return@LaunchedEffect
        }
        if (machine.state == VideoPlayerState.Released) {
            attempt += 1
            return@LaunchedEffect
        }
        if (machine.state != VideoPlayerState.Idle) return@LaunchedEffect

        val token = machine.open() ?: return@LaunchedEffect
        sync()
        Log.i(TAG, "VIDEO_OPEN_STARTED id=$mediaStoreId type=video")

        // `content://` is a claim, not a promise. Opening the descriptor first is what separates "this file
        // is gone" from "the decoder disliked it", and it costs one syscall rather than a callback that
        // arrives too late to matter. The descriptor is closed immediately: `setDataSource(context, uri)`
        // streams the file itself, so no bytes are copied into memory here or anywhere else.
        val readable = runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(contentUri), "r")?.use { true } ?: false
        }.getOrDefault(false)

        if (!readable) {
            machine.onSourceRejected(token)
            sync()
            Log.w(TAG, "VIDEO_DATASOURCE_FAILED id=$mediaStoreId kind=unopenable")
            return@LaunchedEffect
        }

        val player = buildPlayer()
        if (!machine.onPrepareStarted(token)) {
            sync()
            return@LaunchedEffect
        }

        // Every callback carries the token it was registered under and checks the identity of the player
        // that fired it. A released MediaPlayer can still deliver one event, and the crash is not the stale
        // write to state — it is the `duration` call on the object that no longer exists.
        player.setOnPreparedListener { mp ->
            if (mp !== player || machine.isCurrent(token).not()) return@setOnPreparedListener
            val length = runCatching { if (machine.canReadDuration()) mp.duration.toLong() else 0L }.getOrDefault(0L)
            if (machine.onPrepared(token, length)) {
                state = machine.state
                durationMs = machine.durationMs
                Log.i(TAG, "VIDEO_PREPARED id=$mediaStoreId duration_ms=$durationMs")
            } else {
                state = machine.state
            }
        }
        player.setOnCompletionListener { mp ->
            if (mp !== player) return@setOnCompletionListener
            machine.onCompletion()
            sync()
        }
        player.setOnErrorListener { mp, _, _ ->
            // True is what stops the framework dialog. Nothing of the error itself is kept: `what` and
            // `extra` can name the file, and a filename is personal data.
            if (mp === player) {
                machine.onPlayerError(token)
                sync()
            }
            Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=decoder")
            true
        }

        val prepared = runCatching {
            player.setDataSource(context, Uri.parse(contentUri))
            player.isLooping = false
            player.prepareAsync()
        }.isSuccess

        if (prepared) {
            Log.i(TAG, "VIDEO_PREPARE_STARTED id=$mediaStoreId")
        } else {
            machine.onPreparationFailed(token)
            state = machine.state
            destroyPlayer()
            Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=preparation")
        }
    }

    // Leaving the viewer, and anything that ends the composition: released once, and never again.
    DisposableEffect(session) {
        onDispose {
            if (machine.shouldRelease()) sync()
            destroyPlayer()
        }
    }

    // Screen-off and backgrounding arrive here and change nothing about `isActive` — the page a person is
    // looking at is still "active" while the phone is in their pocket.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && machine.canPause()) {
                runCatching { session.player?.pause() }
                machine.onPauseRequested()
                sync()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // `MediaPlayer` has no progress callback, so the playhead is polled while the clip runs — and only while
    // it runs, because `getCurrentPosition` on a released player is another `IllegalStateException`.
    LaunchedEffect(state) {
        while (machine.canReadPosition()) {
            runCatching { session.player?.currentPosition?.toLong() }
                .getOrNull()
                ?.let { positionMs = it }
            delay(POSITION_POLL_MILLIS)
        }
    }

    if (!isActive && state == VideoPlayerState.Idle) {
        // Coil's video decoder yields the first frame of the same uri, so an offscreen page looks like the
        // clip it is standing in for rather than like a screen that has lost its content. No description:
        // this page is not the one being looked at, and the chrome names whatever is.
        SubcomposeAsyncImage(
            model = Uri.parse(contentUri),
            contentDescription = null,
            modifier = modifier.fillMaxSize().background(Color.Black),
            contentScale = ContentScale.Crop,
        )
        return
    }

    if (machine.failed) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.viewer_video_failed_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnMedia,
            )
            Text(
                text = stringResource(R.string.viewer_video_failed_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // Not the pager's chrome: the viewer hides its bars on a tap, and a failure the user can only
            // leave by guessing at gestures is not a state, it is a trap.
            OutlinedButton(onClick = onClose) {
                Text(stringResource(R.string.viewer_close))
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // One tap on the picture belongs to the viewer, not to the player: the transport row is the
            // clip's control, the chrome is the screen's.
            .pointerInput(Unit) { detectTapGestures { onTap() } },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { viewContext ->
                    SurfaceView(viewContext).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    if (machine.onSurfaceCreated()) {
                                        surface = holder
                                        if (machine.canAttachSurface()) {
                                            runCatching { session.player?.setDisplay(holder) }
                                        }
                                        Log.i(TAG, "VIDEO_SURFACE_CREATED id=$mediaStoreId")
                                    }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    // Detach before the surface goes, or the decoder keeps drawing into
                                    // memory the compositor has already taken away.
                                    val live = machine.onSurfaceDestroyed()
                                    if (live) runCatching { session.player?.setDisplay(null) }
                                    if (machine.canPause()) runCatching { session.player?.pause() }
                                    surface = null
                                    sync()
                                    Log.i(TAG, "VIDEO_SURFACE_DESTROYED id=$mediaStoreId")
                                }
                            },
                        )
                    }
                },
                // `AndroidView` keeps its view across recompositions while the page's player may not be the
                // same object, so the attach happens here too — and only when the machine says a display may
                // be set at all.
                update = { view ->
                    if (machine.canAttachSurface()) {
                        runCatching { session.player?.setDisplay(view.holder) }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (machine.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = OnMedia,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            IconButton(
                onClick = {
                    val player = session.player
                    // The control is drawn before a clip is ready on purpose — it is the one that stays put
                    // while the spinner comes and goes — so tapping early is too early, not an error, and
                    // nothing is asked of the player until the machine says it may be asked.
                    if (player != null && machine.canPause()) {
                        runCatching { player.pause() }
                        machine.onPauseRequested()
                        sync()
                        Log.i(TAG, "VIDEO_PAUSED id=$mediaStoreId")
                    } else if (player != null && machine.canPlay()) {
                        if (runCatching { player.start() }.isSuccess) {
                            machine.onPlayRequested()
                            sync()
                            Log.i(TAG, "VIDEO_STARTED id=$mediaStoreId")
                        } else {
                            // `start` can still throw on a decoder that died between the check and the call.
                            machine.onOperationFailed()
                            sync()
                            destroyPlayer()
                            Log.w(TAG, "VIDEO_ERROR id=$mediaStoreId kind=start_refused")
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = if (state == VideoPlayerState.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (state == VideoPlayerState.Playing) R.string.viewer_pause else R.string.viewer_play,
                    ),
                    tint = OnMedia,
                )
            }

            Slider(
                value = if (durationMs > 0L) {
                    (if (scrubbingTo == NO_SCRUB) positionMs else scrubbingTo).toFloat()
                } else {
                    0f
                },
                onValueChange = { value ->
                    if (state != VideoPlayerState.Released && durationMs > 0L) {
                        scrubbingTo = value.toLong().coerceIn(0L, durationMs)
                    }
                },
                // One seek per drag. Seeking on every frame asks the decoder to resynchronise a dozen
                // times a second, which is what a scrub on a long clip should not cost.
                onValueChangeFinished = {
                    val target = scrubbingTo
                    scrubbingTo = NO_SCRUB
                    if (target != NO_SCRUB) {
                        val allowed = machine.seekTarget(target)
                        val player = session.player
                        if (allowed != null && player != null && runCatching { player.seekTo(allowed.toInt()) }.isSuccess) {
                            machine.onSeek(allowed)
                            positionMs = machine.positionMs
                            state = machine.state
                            Log.i(TAG, "VIDEO_SEEK id=$mediaStoreId")
                        }
                    }
                },
                valueRange = 0f..(if (durationMs > 0L) durationMs.toFloat() else 1f),
                enabled = durationMs > 0L && state != VideoPlayerState.Released,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = formatDuration(positionMs) + " / " + formatDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = OnMedia,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            )
        }
    }
}

/**
 * The one mutable thing a page owns: its player, and the machine that decides when it may be touched.
 *
 * Deliberately not `remember { MediaPlayer() }`: the allocation belongs to the moment the page is looked
 * at, not to the moment the pager composes it, so a neighbour costs nothing at all.
 */
private class VideoSession {
    val playback = VideoPlayback()
    var player: MediaPlayer? = null
}

/** How often the playhead is read while a clip runs. */
private const val POSITION_POLL_MILLIS = 100L

/** "The thumb is not being held", because position 0 is a place a person can drag to. */
private const val NO_SCRUB = -1L

/** Ids and categories only — no uri, no path, no framework error text. */
private const val TAG = "LumoVaultVideo"
