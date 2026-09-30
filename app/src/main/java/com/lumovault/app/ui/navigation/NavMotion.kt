package com.lumovault.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * The app's one navigation motion: both graphs — the setup flow and everything after it — call these four
 * functions, so a screen change reads as the same event wherever it was started.
 *
 * The timing predates the sharing: six setup screens in a row is a sequence, and a sequence should feel like
 * pages turning rather than like being shoved through a corridor — a touch slower than the system's default
 * motion, a touch shorter in distance. The shape follows from the same idea: one axis, one weight. The
 * arriving screen arrives from the side you are moving toward and carries both the slide and the fade; the
 * outgoing screen only fades, because a full push-both-ways looks faster than it feels. Back runs the same
 * shape in reverse, with the incoming screen entering from the left.
 *
 * The constants live here rather than in either graph so the two cannot drift apart on time: a setup that
 * measures 260 ms and a library that slides at 300 ms is two designers, not one.
 */

/** How long the slide half of a transition runs. */
internal const val NavSlideMillis = 260

/**
 * How long the fade half runs. It finishes before the slide, which is what makes the motion read as a
 * screen arriving rather than as a wipe across the old one.
 */
internal const val NavFadeMillis = 200

/** Pushing forward: the new screen comes in from the right with a fade; the old one fades in place. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnterTransition(): EnterTransition =
    slideInHorizontally(tween(NavSlideMillis)) { width -> width / 5 } +
        fadeIn(tween(NavFadeMillis))

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navExitTransition(): ExitTransition =
    fadeOut(tween(NavFadeMillis))

/** Going back: the previous screen comes in from the left, again with a fade. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopEnterTransition(): EnterTransition =
    slideInHorizontally(tween(NavSlideMillis)) { width -> -width / 6 } +
        fadeIn(tween(NavFadeMillis))

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navPopExitTransition(): ExitTransition =
    fadeOut(tween(NavFadeMillis)) +
        slideOutHorizontally(tween(NavSlideMillis)) { width -> width / 5 }
