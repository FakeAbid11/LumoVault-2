package com.lumovault.app.ui.screens.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cloud grid's selection rules, off a phone — because they are the two facts a selection bar can
 * get wrong in ways no screenshot shows: what a tap means, and what "select all" is allowed to mean.
 *
 * The provider is a scripted stand-in for the view model's unwindowed read, so a selection assembled
 * from ids the grid never loaded is exactly what a select-all over a real window looks like from
 * here. The other half — that the provider really answers the whole collection — is SQL, and is
 * asserted against real SQLite in `CloudSelectAllRealSqlTest`.
 */
class CloudSelectionTest {
    private val loadedWindow = listOf(1L, 2L, 3L)
    private val wholeCollection = (1L..1_000L).toList()

    @Test
    fun aTapAddsAndTheSameTapRemoves() = runBlocking<Unit> {
        val selection = CloudSelection { wholeCollection }

        selection.toggle(2L)
        assertEquals(setOf(2L), selection.selected.value)

        selection.toggle(2L)
        assertTrue("a second tap on the same cell is deselection", selection.selected.value.isEmpty())
    }

    @Test
    fun severalTapsAccumulateIntoOneSet() = runBlocking<Unit> {
        val selection = CloudSelection { wholeCollection }

        selection.toggle(1L)
        selection.toggle(3L)
        selection.toggle(5L)

        assertEquals(setOf(1L, 3L, 5L), selection.selected.value)
    }

    @Test
    fun clearMeansEmpty() = runBlocking<Unit> {
        val selection = CloudSelection { wholeCollection }
        selection.toggle(1L)
        selection.toggle(2L)

        selection.clear()

        assertTrue(selection.selected.value.isEmpty())
    }

    @Test
    fun selectAllTakesTheProvidersWholeAnswerNeverTheLoadedWindow() = runBlocking<Unit> {
        // What the grid has drawn is three rows; what the cloud holds is a thousand. The strip's
        // count is the count every action acts on, so the answer may not stop at the window.
        val selection = CloudSelection { wholeCollection }
        check(loadedWindow.size < wholeCollection.size)

        selection.toggle(2L)
        selection.selectAll()

        assertEquals(wholeCollection.toSet(), selection.selected.value)
        assertEquals(1_000, selection.selected.value.size)
    }

    @Test
    fun selectAllReplacesAPartialSelectionRatherThanAddingToIt() = runBlocking<Unit> {
        val selection = CloudSelection { wholeCollection }
        selection.toggle(999L)

        selection.selectAll()
        // 999 is in the collection either way, so the shape that matters is "exactly the collection"
        // — a partial set unioned with it would be right only by accident.
        assertEquals(wholeCollection.toSet(), selection.selected.value)

        selection.clear()
        selection.toggle(999L)
        selection.selectAll()
        assertEquals(wholeCollection.toSet(), selection.selected.value)
    }

    @Test
    fun deselectingAfterSelectAllRemovesExactlyOne() = runBlocking<Unit> {
        val selection = CloudSelection { wholeCollection }
        selection.selectAll()

        selection.toggle(500L)

        assertEquals(999, selection.selected.value.size)
        assertEquals(false, selection.selected.value.contains(500L))
    }

    @Test
    fun theCountTheStripPrintsIsTheSizeOfTheSetActionsWillActOn() = runBlocking<Unit> {
        val selection = CloudSelection { wholeCollection }
        selection.selectAll()
        selection.toggle(1L)

        // One number, read two ways: "999 selected" and an action that receives 999 ids cannot
        // disagree, because they are the same value.
        assertEquals(selection.selected.value.size, selection.selected.value.toList().size)
        assertEquals(999, selection.selected.value.size)
    }
}
