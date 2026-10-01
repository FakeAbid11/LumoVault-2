package com.lumovault.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * The app's navigation motion, decided here so both graphs — the setup flow and everything after it —
 * move by the same rules.
 *
 * The rules are keyed to what a move *is*, not to which screen happened to start it. Every transition
 * lambda receives both entries of the transition, so forward moves read the arriving entry: a tab
 * arrives quietly (a fade-through — switching tabs is a change of place, not travel), the viewer opens
 * like a photograph being picked up (a slight scale with the fade), and anything else — a pushed screen,
 * a settings category, a setup step — slides from the side it is moving toward. Back moves read the
 * departing entry instead: you only travel back if you had traveled deep, so returning from a pushed
 * screen runs the mirror of its entrance, while returning to where you were from a tab or from the
 * viewer has no sideways motion at all. The old shape treated every move as the same push; a photo
 * opening does not need a corridor, and a tab switch does not need a direction.
 *
 * The departing screen now travels a little as well as fading — half the incoming distance or less —
 * which is what gives a push its depth. It stays the shorter journey on purpose: a full push in both
 * directions reads faster than it feels and made screens look like they were being shoved through.
 *
 * The predictive back parameters on each `NavHost` point at the same pop functions this file exposes:
 * the system gesture scrubs them frame by frame, so a swipe and a completed back press are one motion
 * with two drivers. The swipe's edge is ignored — the shape belongs to the move, not to the thumb.
 *
 * Timings live here so the graphs cannot drift apart on time, and the easings are Material's
 * emphasized pair — arrives decelerating, leaves accelerating — rather than the plain default curve.
 */

/** How long the slide half of a push runs. */
internal const val NavSlideMillis = 260

/**
 * How long the fade half of a push runs. It finishes before the slide, which is what makes the motion
 * read as a screen arriving rather than as a wipe across the old one.
 */
internal const val NavFadeMillis = 200

/** How long a tab's outgoing screen fades for: the short out-half of a fade-through. */
internal const val TabFadeOutMillis = 100

/** How long the incoming half of a tab's fade-through runs, after its short delay. */
internal const val TabFadeInMillis = 200

/** How long the delayed fade-in waits, so the outgoing tab is most of the way gone first. */
internal const val TabFadeInDelayMillis = 50

/** How long the viewer takes to open or close: a scale with the fade, both together. */
internal const val ViewerMillis = 250

/** The viewer's resting size on screen — opened from it, closed back toward it. */
private const val ViewerRestScale = 0.96f

private val EmphasizedEnter = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedExit = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** How a route moves: a tab fades in place, the viewer scales open, everything else slides. */
internal enum class MotionShape { Tab, Viewer, Push }

/**
 * Which shape a route falls under. Exact tab routes are tabs — an album *under* the Albums tab is a
 * pushed screen despite sharing its prefix — and an unrecognised route takes the push shape, because
 * a slide is the app's general motion and the safe answer for a screen whose kind is unknown.
 */
internal fun motionShapeOf(route: String?): MotionShape = when {
    route == null -> MotionShape.Push
    LumoVaultDestination.entries.any { destination -> destination.route == route } -> MotionShape.Tab
    viewerRouteActive(route) -> MotionShape.Viewer
    else -> MotionShape.Push
}

/** Forward: the arriving tab fades through, after the screen it replaces has mostly gone. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnterTransition(): EnterTransition =
    when (motionShapeOf(targetState.destination.route)) {
        MotionShape.Tab ->
            fadeIn(tween(TabFadeInMillis, delayMillis = TabFadeInDelayMillis, easing = EmphasizedEnter))
        MotionShape.Viewer ->
            fadeIn(tween(ViewerMillis, easing = EmphasizedEnter)) +
                scaleIn(initialScale = ViewerRestScale, animationSpec = tween(ViewerMillis, easing = EmphasizedEnter))
        MotionShape.Push ->
            slideInHorizontally(tween(NavSlideMillis, easing = EmphasizedEnter)) { width -> width / 5 } +
                fadeIn(tween(NavFadeMillis, easing = EmphasizedEnter))
    }

/** Forward: how the screen being left behaves, given what is arriving over it. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navExitTransition(): ExitTransition =
    when (motionShapeOf(targetState.destination.route)) {
        MotionShape.Tab -> fadeOut(tween(TabFadeOutMillis, easing = EmphasizedExit))
        MotionShape.Viewer -> fadeOut(tween(NavFadeMillis, easing = EmphasizedExit))
        MotionShape.Push ->
            slideOutHorizontally(tween(NavSlideMillis, easing = EmphasizedExit)) { width -> -width / 8 } +
                fadeOut(tween(NavFadeMillis, easing = EmphasizedExit))
    }

/**
 * Back: keyed on where the move comes *from*. Depth mirrors itself back; a tab or the viewer just
 * returns to view, because the screen beneath never travelled away.
 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnterTransition(): EnterTransition =
    when (motionShapeOf(initialState.destination.route)) {
        MotionShape.Push ->
            slideInHorizontally(tween(NavSlideMillis, easing = EmphasizedEnter)) { width -> -width / 6 } +
                fadeIn(tween(NavFadeMillis, easing = EmphasizedEnter))
        MotionShape.Tab, MotionShape.Viewer -> fadeIn(tween(NavFadeMillis, easing = EmphasizedEnter))
    }

/** Back: the departing screen leaves the way it arrived — or fades, if it never slid in. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExitTransition(): ExitTransition =
    when (motionShapeOf(initialState.destination.route)) {
        MotionShape.Push ->
            slideOutHorizontally(tween(NavSlideMillis, easing = EmphasizedExit)) { width -> width / 5 } +
                fadeOut(tween(NavFadeMillis, easing = EmphasizedExit))
        MotionShape.Tab -> fadeOut(tween(TabFadeOutMillis, easing = EmphasizedExit))
        MotionShape.Viewer ->
            scaleOut(targetScale = ViewerRestScale, animationSpec = tween(ViewerMillis, easing = EmphasizedExit)) +
                fadeOut(tween(ViewerMillis, easing = EmphasizedExit))
    }
