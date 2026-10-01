package com.lumovault.app.ui.theme

/**
 * The two moments where motion happens inside a screen: how long the viewer's chrome takes to come and go,
 * and how long the cloud's picture overlay takes to open and close.
 *
 * Route changes are deliberately not here. The setup flow and everything after it take their slide and fade
 * from `NavMotion.kt`, whose constants sit with the graphs so the two cannot drift apart from each other;
 * these numbers answer a different question — not "which screen am I on" but "how does one surface on this
 * screen arrive". Grid lists are the third kind of motion and take no number from here either: they call
 * `Modifier.animateItem()` with the foundation library's spring defaults, which is already the platform's
 * own cadence for a list reshaping itself, and a constant added just to have one would be a claim that
 * this app knows better than the library what a thumbnail costs to move.
 *
 * The chrome is the quicker of the pair because it is the answer to a finger on the picture: a control band
 * that lags the tap makes the screen feel slow rather than the chrome feel smooth. The overlay takes the
 * navigation fade's 200 ms, because a full-screen picture opening or closing is the same weight of event
 * as changing screens, and two events of equal weight moving at two speeds is the kind of thing a user
 * cannot name but feels as sloppiness. Both are fades, not moves, and neither fires unless the user asked
 * for it — the motion budget PRD section 75 sets is spent entirely on acknowledging input, never on
 * decorating the screen.
 */

/** How long the viewer's scrim, top bar and action row take to fade in or out on a tap. */
internal const val ChromeFadeMillis = 150

/** How long the cloud overlay takes to appear and disappear. Equal to `NavFadeMillis` on purpose. */
internal const val OverlayFadeMillis = 200
