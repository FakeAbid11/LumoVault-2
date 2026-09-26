package com.lumovault.app.ui.viewer

import kotlin.math.abs
import kotlin.math.max

/**
 * The zoom and pan arithmetic of the viewer, kept away from the gestures that feed it.
 *
 * Split out because this is the half that can be wrong in a way a test can find and a developer cannot:
 * the gestures are Compose pointer input on a touchscreen, and nothing here compiles into one. Everything
 * below is floats and pixel counts, which means the bounds, the double-tap steps and the pan limits are all
 * asserted rather than felt.
 *
 * The invariants the arithmetic has to keep:
 *
 *  - **1 is the floor.** A photo smaller than the screen is *centred and letterboxed* at scale 1, not
 *    stretched to fill it, so the user's first sight of their photograph is its real proportions.
 *  - **Pan is bounded by the content, never by the viewport.** An offset may run as far as the scaled
 *    image overhangs the screen, and no further — dragging a 1× image off into the black is what makes a
 *    viewer feel broken rather than careful.
 *  - **The bounds are a function of the current scale, not of the last gesture.** A pinch and a double tap
 *    must agree, or the two paths diverge and the image sits in a position neither of them can leave.
 */
object ViewerZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f

    /** Where a double tap lands, and the step back down from there. */
    const val DOUBLE_TAP_SCALE = 3f

    /** Below this, a scale is 1: pinch deltas accumulate in thousandths and must not count as zoomed. */
    private const val EPSILON = 0.01f

    fun clampScale(candidate: Float): Float =
        if (!candidate.isFinite()) MIN_SCALE else candidate.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * The next scale for a double tap: 1 → 3 → 1.
     *
     * Two stops rather than three, because the third (full native resolution) differs per file and turns the
     * gesture into something whose outcome the user cannot predict.
     */
    fun scaleAfterDoubleTap(current: Float): Float =
        if (isZoomed(current)) MIN_SCALE else DOUBLE_TAP_SCALE

    /**
     * Whether the image is zoomed enough that the pager should stop swiping.
     *
     * A zoomed photo must pan under the finger, not turn the page; the two gestures share the same horizontal
     * axis, so this is the one place that decides which of them wins.
     */
    fun isZoomed(scale: Float): Boolean = abs(scale - MIN_SCALE) > EPSILON

    /**
     * How far the content may be moved along one axis, in pixels.
     *
     * Half the overhang, because the image is centred: a 2,000 px photo at 2× inside a 1,080 px column
     * overhangs 920 px, and so may travel 460 px each way. When the content is smaller than the viewport the
     * answer is 0, which is the invariant above expressed as arithmetic — at scale 1 an image cannot be
     * dragged off-screen at all.
     */
    fun maxTranslation(contentPx: Float, viewportPx: Float, scale: Float): Float =
        max(0f, (contentPx * scale - viewportPx) / 2f)

    fun clampTranslation(raw: Float, contentPx: Float, viewportPx: Float, scale: Float): Float {
        val limit = maxTranslation(contentPx = contentPx, viewportPx = viewportPx, scale = scale)
        return raw.coerceIn(-limit, limit)
    }

    /**
     * The offset a scale change should keep.
     *
     * Zooming out to 1 has to come back to the centre rather than to wherever the finger happened to be, or
     * a photo that fits the screen ends up half off it — and re-clamping at the *new* scale is what makes the
     * pinch-to-centre behaviour fall out of the same rule instead of needing a special case.
     */
    fun translationFor(newScale: Float, offsetX: Float, contentPx: Float, viewportPx: Float): Float =
        if (newScale <= MIN_SCALE + EPSILON) 0f
        else clampTranslation(offsetX, contentPx = contentPx, viewportPx = viewportPx, scale = newScale)
}
