package com.lumovault.app.ui.viewer

import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lumovault.app.R
import com.lumovault.app.util.formatDuration
import kotlinx.coroutines.delay
import com.lumovault.app.ui.theme.OnMedia
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A clip, played.
 *
 * Built on `MediaPlayer` and a `SurfaceView` rather than on a media library, and the reason is worth stating
 * because it will be questioned: this is one local `content://` file at a time, with play, pause, a seek bar
 * and a duration. Media3 is the better tool for remote and progressive sources — Phase 9's download of a
 * stored original is exactly where it earns seven artifacts and Guava — and taking it now would buy a state
 * machine this phase has no use for. The seam is this file and nothing above it: a uri in, a clip out, which
 * is the same contract an `ExoPlayer` would be handed.
 *
 * Three properties of a surface inside Compose are handled here because none of them is visible until it is
 * wrong:
 *
 *  - **Aspect.** `VIDEO_SCALING_MODE_SCALE_TO_FIT` letterboxes the picture inside the surface, so a 9:16 clip
 *    stays 9:16 on a 20:9 screen instead of filling it.
 *  - **Failure.** `MediaPlayer`'s default error path inflates a dialog from the framework's own internal
 *    resources — unthemable, untranslatable, and appearing over a screen the app owns. An `OnErrorListener`
 *    that returns true claims the error, which is the only thing that suppresses it, and LumoVault's own
 *    sentence is drawn in its place.
 *  - **Lifetime.** Nothing is decoded until the page is the visible one, it is paused the moment it stops
 *    being visible or the screen stops being foreground, and the player is released when the page leaves the
 *    composition. A clip that keeps playing off-screen is sound coming from a photograph nobody is looking at,
 *    and a pager that prepared three pages at once would hold three hardware decoders for one video.
 */
@Composable
fun VideoStage(
    contentUri: String,
    isActive: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var prepared by remember(contentUri) { mutableStateOf(false) }
    var playing by remember(contentUri) { mutableStateOf(false) }
    var failed by remember(contentUri) { mutableStateOf(false) }
    var durationMs by remember(contentUri) { mutableLongStateOf(0L) }
    var positionMs by remember(contentUri) { mutableLongStateOf(0L) }

    // Where a drag has reached, kept apart from the playhead so the thumb follows the finger while the
    // decoder stays still, and reset to "nobody is dragging" once it lets go.
    var scrubbingTo by remember(contentUri) { mutableLongStateOf(NO_SCRUB) }

    // Guards the prepare below: `isActive` flips on every swipe, and a second `setDataSource` on a player
    // that is already prepared is an IllegalStateException that would read as a broken file.
    var starting by remember(contentUri) { mutableStateOf(false) }

    val player = remember(contentUri) { MediaPlayer() }

    // The listeners belong to the player, and the player to this page's content — so a page that is simply
    // not being looked at yet still cannot leak one.
    DisposableEffect(player) {
        runCatching {
            player.setOnPreparedListener { mp ->
                prepared = true
                failed = false
                durationMs = mp.duration.coerceAtLeast(0).toLong()
            }
            player.setOnCompletionListener { mp ->
                playing = false
                positionMs = mp.duration.coerceAtLeast(0).toLong()
            }
            player.setOnErrorListener { _, _, _ ->
                // Returning true is what stops the framework dialog. Nothing else about the error is kept:
                // `MediaPlayer`'s extras can name the file, and a filename is personal data.
                failed = true
                prepared = false
                playing = false
                true
            }
        }
        onDispose {
            runCatching { player.release() }
            prepared = false
            playing = false
        }
    }

    // Decoding starts when the page becomes the visible one, not when the pager composes it. A viewer that
    // prepares its neighbours hands the device three hardware decoders for a clip the user will probably
    // never reach; the swipe that reveals a page is the same gesture that asks for it to be ready.
    LaunchedEffect(contentUri, isActive) {
        if (!isActive || prepared || failed || starting) return@LaunchedEffect
        starting = true
        runCatching {
            player.setDataSource(Uri.parse(contentUri).toString())
            player.isLooping = false
            player.prepareAsync()
        }.onFailure {
            failed = true
            starting = false
        }
    }

    LaunchedEffect(player) {
        runCatching { player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
    }

    // Leaving the page stops the sound; returning never restarts it by itself, because a viewer that plays
    // from where it left off is a video call, and this is a photograph library.
    LaunchedEffect(isActive) {
        if (!isActive) {
            runCatching { if (player.isPlaying) player.pause() }
            playing = false
        }
    }

    // Screen-off and backgrounding land here, and neither changes `isActive` — a pager page the user is
    // still on is "active" while the phone is in their pocket. The codec and the sound both have to stop.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { if (player.isPlaying) player.pause() }
                playing = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // `MediaPlayer` has no progress callback, so the playhead is polled while it runs. A tenth of a second is
    // smooth enough to look attached to the picture and cheap enough to run beside decoding.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = runCatching { player.currentPosition.coerceAtLeast(0).toLong() }.getOrDefault(positionMs)
            delay(POSITION_POLL_MILLIS)
        }
    }

    if (failed) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.viewer_video_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = OnMedia,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // One tap on the video surface belongs to the screen, not to the player: the transport row is the
            // clip's own control, and the picture is the viewer's.
            .pointerInput(Unit) { detectTapGestures { onTap() } },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                // `AndroidView` keeps its view across content changes, so the surface is attached in
                // `update` as well as in `factory`. A player built for a new page otherwise gets the old
                // page's already-composed view — or none at all, which is sound and a black rectangle.
                factory = { context -> SurfaceView(context).also { player.setDisplay(it.holder) } },
                update = { view -> player.setDisplay(view.holder) },
                modifier = Modifier.fillMaxSize(),
            )
            if (!prepared) {
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
                    if (!prepared) return@IconButton
                    if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    } else {
                        runCatching { player.start() }
                        playing = true
                    }
                },
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.viewer_pause else R.string.viewer_play,
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
                    if (prepared && durationMs > 0L) scrubbingTo = value.toLong().coerceIn(0L, durationMs)
                },
                // One seek per drag. Seeking on every frame asks the decoder to resynchronise a dozen
                // times a second, which is what a scrub on a long clip should not cost.
                onValueChangeFinished = {
                    if (prepared && durationMs > 0L && scrubbingTo != NO_SCRUB) {
                        // `seekTo` takes milliseconds, unlike the EXIF durations this phase reads elsewhere;
                        // mixing the two is a five-minute clip jumping to a thirty-second mark.
                        runCatching { player.seekTo(scrubbingTo.toInt()) }
                        positionMs = scrubbingTo
                    }
                    scrubbingTo = NO_SCRUB
                },
                valueRange = 0f..(if (durationMs > 0L) durationMs.toFloat() else 1f),
                enabled = prepared && durationMs > 0L,
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

/** How often the playhead is read while a clip runs. */
private const val POSITION_POLL_MILLIS = 100L

/** "The thumb is not being held", because position 0 is a place a person can drag to. */
private const val NO_SCRUB = -1L
