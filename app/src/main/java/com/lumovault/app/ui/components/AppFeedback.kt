package com.lumovault.app.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow

/**
 * One line of feedback for something the user just did, as the string it is.
 *
 * [count] is set only for a message that comes in plurals, and is the quantity the plural form
 * selects as well as the number the sentence prints. Everything else leaves it null, so a count of
 * zero is never mistaken for "no message".
 */
data class AppMessage(@StringRes val text: Int, val count: Int? = null) {
    fun resolve(resources: Resources): String =
        if (count == null) resources.getString(text)
        else resources.getQuantityString(text, count, count)
}

/**
 * The shell's one snackbar, reached by every screen that has something to say.
 *
 * Held in a composition local rather than passed as a parameter because the host belongs to the
 * scaffold — the screens are inside the nav host, not near the slot that draws it — and a screen
 * that collects messages outside the shell is a wiring mistake worth failing on rather than
 * swallowing the line it was handed.
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("A screen collecting AppMessages must run inside LumoVaultApp's Scaffold.")
}

/**
 * Shows each of [messages] as it arrives, one line at a time, while the screen is on display.
 *
 * This is the app's single answer to "the user did something and the UI never said so": a failed
 * write used to be one log line nobody reads, and a bulk action from a selection bar used to end
 * in a grid that refilled with no count of what actually happened. The collector runs only while
 * its screen composes, so a line emitted for an action whose screen has already been left is not
 * replayed at the user later — feedback about the wrong moment is worse than none.
 */
@Composable
fun CollectAppMessages(messages: Flow<AppMessage>) {
    val host = LocalSnackbarHostState.current
    val resources = LocalContext.current.resources
    LaunchedEffect(messages, host, resources) {
        messages.collect { message -> host.showSnackbar(message.resolve(resources)) }
    }
}
