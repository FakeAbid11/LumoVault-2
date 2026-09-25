package com.lumovault.app.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import com.lumovault.app.R
import com.lumovault.app.ui.theme.OnMedia

/**
 * One image, at the size it was taken, under the user's fingers.
 *
 * The decode is asked for at the image's own dimensions — `Size.ORIGINAL`, with `Precision.INEXACT` — because
 * a zoomed photo is exactly the case where sizing to the cell would be wrong, and `INEXACT` lets Coil answer
 * with the nearest size it can produce rather than forcing an exact one. Coil's own 4,096 px ceiling still
 * applies, so a 108-megapixel original arrives as the largest bitmap a phone can hold instead of as an
 * out-of-memory crash. The trade is visible: pinched past roughly four times, the detail stops sharpening.
 *
 * The same path draws GIFs, and the two are named apart in [ViewerPresentation.rendererFor] precisely because
 * they must not be confused. Animation comes from `coil-gif`, which registers an `AnimatedImageDecoder`
 * through the same ServiceLoader mechanism `coil-video` already uses here, and whose `AnimatedImageDrawable`
 * is started by the painter that shows it. A GIF through a still-frame route would look exactly like a correct
 * GIF right up until it failed to move.
 *
 * Zoom and pan are one gesture each and both end in [ViewerZoom]: a pinch that would take the image to 0.4× or
 * 12× is clamped, and a drag that would leave the photo floating off-screen is clamped to the overhang. The
 * screen is told when this page is zoomed, so a horizontal drag on a 3× photo pans the photo instead of
 * turning the page.
 *
 * One detail is the whole reason `onTap` is a parameter rather than a handler here: this page needs
 * double-tap-to-zoom, and the screen needs single-tap-to-toggle-chrome. Two tap detectors stacked on the same
 * gesture eat each other — the child consumes the down and the parent's toggle never fires — so a single
 * detector owns both taps here and hands the plain one back to the screen.
 */
@Composable
fun ZoomableImage(
    contentUri: String,
    contentDescription: String?,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var scale by remember(contentUri) { mutableFloatStateOf(ViewerZoom.MIN_SCALE) }
    var offset by remember(contentUri) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Offset.Zero) }

    val transformable = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = ViewerZoom.clampScale(scale * zoomChange)
        offset = if (newScale <= ViewerZoom.MIN_SCALE) {
            Offset.Zero
        } else {
            Offset(
                x = ViewerZoom.clampTranslation(
                    raw = offset.x + panChange.x,
                    contentPx = viewport.x,
                    viewportPx = viewport.x,
                    scale = newScale,
                ),
                y = ViewerZoom.clampTranslation(
                    raw = offset.y + panChange.y,
                    contentPx = viewport.y,
                    viewportPx = viewport.y,
                    scale = newScale,
                ),
            )
        }
        scale = newScale
        onZoomChanged(ViewerZoom.isZoomed(newScale))
    }

    val request = remember(contentUri) { originalSizeRequest(context, contentUri) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> viewport = Offset(size.width.toFloat(), size.height.toFloat()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(state = transformable)
            .pointerInput(contentUri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { point ->
                        val target = ViewerZoom.scaleAfterDoubleTap(scale)
                        if (target <= ViewerZoom.MIN_SCALE) {
                            scale = ViewerZoom.MIN_SCALE
                            offset = Offset.Zero
                        } else {
                            scale = target
                            offset = centredToward(point, viewport, target)
                        }
                        onZoomChanged(ViewerZoom.isZoomed(scale))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            loading = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            },
            error = {
                Text(
                    text = stringResource(R.string.viewer_image_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnMedia,
                    modifier = Modifier.padding(24.dp),
                )
            },
        )
    }
}

/**
 * Zooming in toward the tapped point.
 *
 * Whatever was under the finger stays under it, until the pan limit says otherwise — which is the only version
 * of the gesture that lets a person read the corner they just tapped. At scale 1 the limit is zero, so the
 * first double tap on a photo that already fits simply centres it: an image with no overhang has nowhere to
 * move, and pretending otherwise is how a viewer ends up showing black where the photograph should be.
 */
private fun centredToward(point: Offset, viewport: Offset, scale: Float): Offset {
    if (viewport.x <= 0f || viewport.y <= 0f) return Offset.Zero
    val centre = Offset(viewport.x / 2f, viewport.y / 2f)
    val desired = (point - centre) * -scale
    return Offset(
        x = ViewerZoom.clampTranslation(desired.x, viewport.x, viewport.x, scale),
        y = ViewerZoom.clampTranslation(desired.y, viewport.y, viewport.y, scale),
    )
}

private fun originalSizeRequest(context: Context, contentUri: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(contentUri))
        .size(Size.ORIGINAL)
        .precision(Precision.INEXACT)
        .build()
