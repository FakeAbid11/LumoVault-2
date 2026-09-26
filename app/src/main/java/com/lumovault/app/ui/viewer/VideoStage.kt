package com.lumovault.app.ui.viewer

import android.net.Uri
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import coil3.compose.SubcomposeAsyncImage
import com.lumovault.app.R
import com.lumovault.app.ui.theme.OnMedia
import com.lumovault.app.util.formatDuration
import kotlinx.coroutines.delay

/**
 * A clip, played — and the reason a clip that cannot be played must not take the process with it.
 *
 * The player is Media3's ExoPlayer, reached through [ExoPlayerEngine]; what it may be asked, and when, is
 * decided by [VideoPlayback] through [VideoSession]. What is left here is the part only Compose can do: hold
 * one session per visit to one clip, mirror its state onto the screen, and draw the transport this app
 * already had.
 *
 * That split is the point of the arrangement. A framework player reports its news on a thread no
 * `runCatching` around a `@Composable` is anywhere near, and every crash this page has had came from acting
 * on such a report after the page had moved on. So nothing below touches a player: each control asks
 * [VideoSession], which asks the machine, and a refusal is a tap that changes nothing.
 *
 * Four properties of a clip inside a pager are still handled here, because none is visible until it is
 * wrong:
 *
 *  - **Only the visible page owns a player.** It is allocated when this page is looked at and released when it
 *    stops being looked at, so swiping never holds two decoders and the old one is gone before the new one is
 *    built. A page that comes back gets a new session, because *released* is not a state a player can be taken
 *    back from.
 *  - **The source is checked before it is handed over**, in the engine: `content://` is only a claim until
 *    something opens it. That turns "the file was deleted outside the app" into a named failure instead of an
 *    asynchronous native IO error arriving after the user has already left. No copy of the clip is ever read
 *    into memory — the bytes are streamed from the resolver.
 *  - **The rendering surface belongs to Media3.** `PlayerView` with its controller switched off is a surface
 *    plus aspect-ratio handling, and it is the library that registers the surface callback and detaches the
 *    video output before the surface is taken away. The hand-written holder callback this page used to keep is
 *    one of the reasons the page could crash at all.
 *  - **A failure is shown, and it is the only thing shown.** `PlayerView` is given no error-message provider,
 *    so no framework text can reach the person looking at the screen; LumoVault's own sentence and a Close
 *    button take its place — visible whatever the viewer's chrome is doing, because a screen that is black
 *    with no way out is how a crash looks to somebody who is not holding a stack trace.
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
     * [VideoPlayerState.Released] is terminal, so without a fresh session here, swiping away from a clip and
     * back would leave a page that shows a spinner forever and never opens anything. One visit, one player,
     * and a new one for the next.
     */
    var attempt by remember(contentUri) { mutableIntStateOf(0) }

    // Keyed on the clip and the visit rather than on the recomposition: this is the identity the player
    // belongs to, and a page recomposed mid-swipe must not find a new decoder in it.
    val engine = remember(contentUri, attempt) { ExoPlayerEngine(context, mediaStoreId) }
    val session = remember(contentUri, attempt, engine) { VideoSession(engine) }
    val machine = session.playback

    // The mirror of the machine's state, so a transition recomposes the screen. Every write goes through the
    // machine first, which is what makes the UI unable to claim a state the player does not have.
    var state by remember(contentUri, attempt) { mutableStateOf(VideoPlayerState.Idle) }
    var durationMs by remember(contentUri, attempt) { mutableLongStateOf(0L) }
    var positionMs by remember(contentUri, attempt) { mutableLongStateOf(0L) }
    var scrubbingTo by remember(contentUri, attempt) { mutableLongStateOf(NO_SCRUB) }

    // The one way state reaches the screen: ask the machine, then draw what it says. A refused transition
    // still has to be drawn, or the transport keeps offering a control the player will reject.
    fun sync() {
        state = machine.state
        durationMs = machine.durationMs
        positionMs = machine.positionMs
    }

    // Declared before anything can report, so no engine event is heard by a page that is not listening, and
    // cleared on the way out for the same reason in reverse.
    DisposableEffect(session) {
        session.onChanged = { sync() }
        onDispose {
            session.onChanged = {}
            session.release()
        }
    }

    // Decoding starts when the page becomes the visible one, and ends when it stops being visible: the pager
    // composes its neighbours, and a neighbour that prepares a clip holds a hardware decoder for a video the
    // user may never reach.
    LaunchedEffect(isActive, contentUri, attempt) {
        if (!isActive) {
            session.release()
            return@LaunchedEffect
        }
        if (machine.state == VideoPlayerState.Released) {
            attempt += 1
            return@LaunchedEffect
        }
        session.activate(contentUri)
    }

    // Screen-off and backgrounding arrive here and change nothing about `isActive` — the page a person is
    // looking at is still "active" while the phone is in their pocket. The clip stops either way, and comes
    // back to a paused player rather than a running one.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.pauseForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The playhead is polled rather than subscribed to, and only while the clip is running: a finished clip has
    // nowhere left to go, and a page that is not the one being looked at has no player to ask.
    LaunchedEffect(state) {
        while (machine.canReadPosition()) {
            session.readPosition()
            delay(POSITION_POLL_MILLIS)
        }
    }

    if (!isActive) {
        // Coil's video decoder yields the first frame of the same uri, so an offscreen page looks like the clip
        // it is standing in for rather than like a screen that has lost its content. No description: this page
        // is not the one being looked at, and the chrome names whatever is.
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
            // One tap on the picture belongs to the viewer, not to the player: the transport row is the clip's
            // control, the chrome is the screen's. A `PlayerView` with no controller is not clickable, so the
            // gesture reaches this instead of being eaten by the surface.
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
                    PlayerView(viewContext).apply {
                        // LumoVault already has a transport and it is the one below. Left on, Media3's own
                        // control bar would be a second set of buttons over the same clip, in the demo
                        // player's styling.
                        useController = false
                    }
                },
                // The view and the player are built at different moments — the view when the page is composed,
                // the player when the page is looked at — so the pairing is re-asserted on every recomposition
                // rather than assumed to have happened in one order.
                update = { view -> engine.attachView(view) },
                onRelease = { view -> engine.detachView(view) },
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
                // The control is drawn before a clip is ready on purpose — it is the one that stays put while
                // the spinner comes and goes — so tapping early is too early, not an error, and nothing is
                // asked of the player until the machine says it may be asked.
                onClick = { session.togglePlay() },
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
                // One seek per drag. Seeking on every frame asks the decoder to resynchronise a dozen times a
                // second, which is what a scrub on a long clip should not cost.
                onValueChangeFinished = {
                    val target = scrubbingTo
                    scrubbingTo = NO_SCRUB
                    if (target != NO_SCRUB) session.seekTo(target)
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

/** How often the playhead is read while a clip runs. */
private const val POSITION_POLL_MILLIS = 100L

/** "The thumb is not being held", because position 0 is a place a person can drag to. */
private const val NO_SCRUB = -1L
