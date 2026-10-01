package com.lumovault.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lumovault.app.ui.theme.RingStrokeBold
import com.lumovault.app.ui.theme.RingWaiting

// The body a screen draws while its first answer is in flight: one ring, no words.
//
// Four moments need exactly this and each had written its own copy: the startup surface before Room
// named the onboarding flag, the album grid before its items arrived, the photo timeline during the
// first permission read, and the cloud tab before its first sync moved off `Idle`. Two of them drew
// the platform's bare 40 dp default — while the theme's own waiting ring ([RingWaiting], 32 dp with
// [RingStrokeBold]) sat unused on these two screens, declared for precisely this role — and two drew
// *nothing at all*, which is the frame this component exists to close. Silence was chosen to avoid
// flashing "no photos" on a device that holds thousands, and that reasoning still holds; but an empty
// frame also tells the user nothing, and is indistinguishable from a process that never woke up.
//
// The ring is the only claim every one of those four moments can honestly make. Nothing is titled
// because nothing yet can be: this body draws *before* the state that would supply a title has an
// answer, and the no-fake-fact rule runs both ways — no success the build cannot show, and no fact
// (a title, a count, an "empty") that has not been read yet. Work that already knows what it is
// doing and how far it has got is [WorkingScreen]'s to draw, not this one's.

/** The body of a screen while its first answer is in flight. */
@Composable
internal fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(RingWaiting),
            strokeWidth = RingStrokeBold,
        )
    }
}
