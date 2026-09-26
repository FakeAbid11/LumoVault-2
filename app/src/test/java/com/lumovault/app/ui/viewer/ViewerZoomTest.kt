package com.lumovault.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the pinch.
 *
 * Gesture recognition on a touchscreen cannot be tested from a build server, so the honest division is to
 * keep every *decision* here — what a scale is allowed to be, how far a photo may be dragged, when a
 * double-tap counts as zoomed — and leave only the wiring of fingers to those decisions unverified. Each
 * assertion below is a way a viewer can feel broken that has nothing to do with the finger that caused it.
 */
class ViewerZoomTest {

    @Test
    fun aScaleOutsideTheBoundsIsPulledBackRatherThanPassedThrough() {
        assertEquals(1.0, ViewerZoom.clampScale(0.4f).toDouble(), 0.0)
        assertEquals(1.0, ViewerZoom.clampScale(1f).toDouble(), 0.0)
        assertEquals(2.5, ViewerZoom.clampScale(2.5f).toDouble(), 0.0)
        assertEquals(5.0, ViewerZoom.clampScale(40f).toDouble(), 0.0)
        assertEquals(
            "a pinch can hand back a non-finite value at the edge of a zero-sized layout",
            1.0,
            ViewerZoom.clampScale(Float.NaN).toDouble(),
            0.0,
        )
    }

    @Test
    fun aDoubleTapStepsUpAndBackDownAgain() {
        assertEquals(3.0, ViewerZoom.scaleAfterDoubleTap(1f).toDouble(), 0.0)
        assertEquals(1.0, ViewerZoom.scaleAfterDoubleTap(3f).toDouble(), 0.0)
        assertEquals(
            "from any zoomed level the gesture means go back to the whole photo",
            1.0,
            ViewerZoom.scaleAfterDoubleTap(4.8f).toDouble(),
            0.0,
        )
    }

    @Test
    fun aDriftOfThousandthsIsNotCalledZoomed() {
        assertFalse(ViewerZoom.isZoomed(1f))
        assertFalse("a pinch that never quite left the floor", ViewerZoom.isZoomed(1.001f))
        assertTrue(ViewerZoom.isZoomed(1.4f))
    }

    /**
     * `isZoomed` is not a label: it is the argument `Modifier.transformable(state, canPan = …)` is called
     * with, and it decides whether a horizontal drag is a pan or a page turn.
     *
     * Compose's transformable gate is `zoomMotion > slop || rotationMotion > slop || (panMotion > slop &&
     * canPan(pan))`, and a single finger's movement *is* pan motion — so without this predicate the modifier
     * consumes a plain swipe at any scale, the pager's `awaitDragOrCancellation` sees a consumed change and
     * stops, and the viewer cannot be turned by dragging at all. The two assertions below are therefore the
     * swipe bug, expressed as the one function that can be tested without a finger.
     */
    @Test
    fun onlyAnActuallyMagnifiedPhotoClaimsTheDragFromThePager() {
        assertFalse(
            "at 1x the drag belongs to the pager, and the photo has nowhere to pan to anyway",
            ViewerZoom.isZoomed(ViewerZoom.MIN_SCALE),
        )
        assertTrue("magnified, the photo takes it", ViewerZoom.isZoomed(ViewerZoom.DOUBLE_TAP_SCALE))
    }

    @Test
    fun theBoundsOfTheZoomAreTheBoundsOfTheDrag() {
        // Whatever scale a pinch lands on, `isZoomed` must agree with the clamp that produced it: a photo at
        // the ceiling panning, and one pulled back to the floor handing the drag back.
        (1..20).forEach { step ->
            val scale = ViewerZoom.clampScale(1f + step * 0.5f)
            assertEquals(
                "at $scale, which is $(ViewerZoom.clampScale(scale)) once clamped",
                scale > ViewerZoom.MIN_SCALE + 0.01f,
                ViewerZoom.isZoomed(scale),
            )
        }
    }

    @Test
    fun panReachIsTheOverhangAndNeverTheViewport() {
        // A 2,000 px photo in a 1,080 px column: at 2x it overhangs 920 px each way, so the centre may move
        // half of that — 1,460 px.
        assertEquals(1460.0, ViewerZoom.maxTranslation(2000f, 1080f, scale = 2f).toDouble(), 0.001)
        assertEquals(460.0, ViewerZoom.maxTranslation(2000f, 1080f, scale = 1f).toDouble(), 0.001)
        assertEquals(
            "a photo smaller than the screen cannot be dragged at all, which is the one that makes a viewer feel broken",
            0.0,
            ViewerZoom.maxTranslation(800f, 1080f, scale = 1f).toDouble(),
            0.0,
        )
    }

    @Test
    fun aDragIsClampedToTheLimitOnBothSides() {
        assertEquals(460.0, ViewerZoom.clampTranslation(9_000f, 2000f, 1080f, 1f).toDouble(), 0.001)
        assertEquals(-460.0, ViewerZoom.clampTranslation(-9_000f, 2000f, 1080f, 1f).toDouble(), 0.001)
        assertEquals(120.0, ViewerZoom.clampTranslation(120f, 2000f, 1080f, 1f).toDouble(), 0.001)
    }

    @Test
    fun zoomingBackToTheFloorRecentresThePhoto() {
        assertEquals(
            0.0,
            ViewerZoom.translationFor(newScale = 1f, offsetX = 400f, contentPx = 2000f, viewportPx = 1080f).toDouble(),
            0.0,
        )
        assertEquals(
            "a partial zoom-out re-clamps against the NEW scale, not the one the finger was on",
            960.0,
            ViewerZoom.translationFor(newScale = 1.5f, offsetX = 9_000f, contentPx = 2000f, viewportPx = 1080f).toDouble(),
            0.001,
        )
    }
}
