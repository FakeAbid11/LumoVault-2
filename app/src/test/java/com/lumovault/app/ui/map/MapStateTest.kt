package com.lumovault.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's four silences, told apart without a map.
 *
 * The screen shows exactly one of them, and they are not interchangeable: an empty library, a library whose
 * EXIF has not been read yet, a permission nobody has asked for, and a query that threw all render as "no
 * pins" from the outside. Two of those are actionable and two are merely true, and a map that reports the
 * wrong one sends the user to the wrong fix — the failure case in particular must never be shown as an empty
 * map, because the photographs are almost certainly still there.
 *
 * [mapStateFor] is the whole of `MapViewModel.state`'s derivation, over its five inputs, so it can be asked
 * of without a view model, a Room instance or an Application.
 */
class MapStateTest {
    private fun stateOf(
        drawnPins: Int = 0,
        located: Int = 0,
        waiting: Int = 0,
        allowed: Boolean = true,
        readFailed: Boolean = false,
    ) = mapStateFor(
        drawnPins = drawnPins,
        located = located,
        waiting = waiting,
        allowed = allowed,
        readFailed = readFailed,
    )

    @Test
    fun aDeniedPermissionWithWorkLeftToDoAsksForLocation() {
        val state = stateOf(allowed = false, waiting = 4)

        assertTrue(
            "nothing is plotted, files are unread, and the reason is a permission nobody asked for",
            state.shouldAskForLocations,
        )
        assertFalse("the read has not failed; it has not happened", state.locationsUnavailable)
        assertEquals(4, state.extractionWaiting)
        assertEquals(0, state.placedCount)
    }

    @Test
    fun thereIsNothingLeftToReadSoNobodyIsAsked() {
        assertFalse(
            "every file has been tried; asking again cannot move the map",
            stateOf(allowed = false, waiting = 0).shouldAskForLocations,
        )
        assertFalse(
            "and asking is pointless once rows came back with positions",
            stateOf(allowed = false, located = 12, waiting = 0).shouldAskForLocations,
        )
        assertFalse(
            "a permission that has been granted is not a permission to request",
            stateOf(allowed = true, waiting = 4).shouldAskForLocations,
        )
    }

    @Test
    fun aLibraryWithoutPositionsIsNotAQueryFailure() {
        val state = stateOf(allowed = true, located = 0, waiting = 0, readFailed = false)

        assertFalse("the read answered; it did not throw", state.locationsUnavailable)
        assertFalse("and there is nothing to ask about", state.shouldAskForLocations)
        assertEquals(
            "zero placed rows is the fact the screen says 'none of your photos' about",
            0,
            state.placedCount,
        )
        assertFalse(state.hasPins)
    }

    @Test
    fun aFailedReadIsReportedAsAFailureRatherThanAsNothing() {
        val failed = stateOf(allowed = true, located = 90, waiting = 0, readFailed = true)

        assertTrue("the query threw, and the screen has a notice for that", failed.locationsUnavailable)
        assertEquals(
            "rows the earlier reads did place are still rows — the failure is about this viewport",
            90,
            failed.placedCount,
        )

        val recovered = stateOf(allowed = true, located = 90, waiting = 0, readFailed = false)
        assertFalse("the next answer that arrives takes the notice down with it", recovered.locationsUnavailable)
    }

    @Test
    fun pinsAreClaimedOnlyWhenSomethingWasActuallyPlotted() {
        assertTrue(stateOf(drawnPins = 1).hasPins)
        assertFalse(
            "an empty pin list is the 'you are looking at the wrong part of the world' case, "
                + "which is a different notice from a failed read",
            stateOf(drawnPins = 0, readFailed = false).hasPins,
        )
        assertFalse(
            "a failure draws no pins and must say why rather than looking like a dead viewport",
            stateOf(drawnPins = 0, readFailed = true).hasPins,
        )
    }
}
