package com.lumovault.app.domain.map

import com.lumovault.app.domain.model.MediaLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the map should centre next time it is shown.
 *
 * A hand-off rather than a route argument, and the reason is the bottom bar. The map's route is one of the
 * four the bar compares against to decide which tab is lit, so giving it optional path segments would make
 * its destination route a pattern and stop the plain "map" navigation from matching it — a highlight bug on
 * the app's main bar, traded for nothing.
 *
 * It is also the right shape for what this is: a request from one screen to another, both alive, one tap
 * apart. It is not state the user owns, so it is not in Room, and it does not need to survive a process death
 * — a map that opens centred on the library instead is a mild disappointment, not a lost decision.
 */
class MapFocus {
    private val requested = MutableStateFlow<MediaLocation?>(null)

    /** The pending centre, for a map that is about to be composed. */
    val pending: StateFlow<MediaLocation?> = requested.asStateFlow()

    fun request(location: MediaLocation) {
        requested.value = location
    }

    /** Takes the centre once, so returning to the map later shows the whole library again. */
    fun consume(): MediaLocation? = requested.value.also { requested.value = null }
}
