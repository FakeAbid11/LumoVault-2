package com.lumovault.app.ui.navigation

import com.lumovault.app.domain.model.SystemAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's route, which is the only thing that survives a process death.
 *
 * A round trip through the path segments is the whole contract: the screen is rebuilt from the back stack
 * after the system has killed the app, and if `of` and `decode` disagree about the spelling of anything, the
 * viewer reopens as the timeline — which is not merely the wrong screen, but a different *list* under the
 * swipe, so the photographs beside the one the user tapped are wrong too.
 */
class ViewerRoutesTest {
    @Test
    fun everySourceSurvivesAReplyThroughTheRoute() {
        val sources = listOf(
            ViewerTarget.Photos,
            ViewerTarget.Album(42L),
            ViewerTarget.SystemAlbumView(SystemAlbum.Camera),
            ViewerTarget.SystemAlbumView(SystemAlbum.Trash),
            ViewerTarget.SystemAlbumView(SystemAlbum.RecentlyAdded),
        )

        sources.forEach { source ->
            val route = ViewerRoutes.of(7L, source)

            assertTrue("the shell only recognises the viewer by its prefix: $route", viewerRouteActive(route))
            assertEquals(
                "$source must come back as itself, not as the timeline",
                source,
                ViewerTarget.decode(
                    kind = route.split("/")[2],
                    argument = route.split("/")[3],
                ),
            )
        }
    }

    @Test
    fun theMediaIdIsTheFirstSegmentSoTheScreenCanFindItsPage() {
        val route = ViewerRoutes.of(1_234_567_890L, ViewerTarget.Album(9L))

        assertEquals("viewer", route.split("/")[0])
        assertEquals("1234567890", route.split("/")[1])
    }

    @Test
    fun aSourceThatNoLongerExistsFallsBackToTheTimelineInsteadOfCrashing() {
        // A link from before an album was renamed, or a system album this build removed, is an ordinary thing
        // for a restored back stack to contain. Opening *something* is right; throwing is not.
        assertEquals(ViewerTarget.Photos, ViewerTarget.decode("album", "not-a-number"))
        assertEquals(ViewerTarget.Photos, ViewerTarget.decode("system", "Memories"))
        assertEquals(ViewerTarget.Photos, ViewerTarget.decode(null, null))
        assertEquals(ViewerTarget.Photos, ViewerTarget.decode("", ""))
    }

    @Test
    fun anAlbumScreenBecomesTheSourceTheViewerSwipesThrough() {
        assertEquals(
            ViewerTarget.Album(3L),
            ViewerTarget.of(AlbumTarget.User(3L)),
        )
        assertEquals(
            ViewerTarget.SystemAlbumView(SystemAlbum.Favorites),
            ViewerTarget.of(AlbumTarget.System(SystemAlbum.Favorites)),
        )
        assertEquals(
            "an unresolved album screen has no list of its own, so the timeline is the only honest answer",
            ViewerTarget.Photos,
            ViewerTarget.of(null),
        )
    }

    @Test
    fun anAlbumChildIsNotTheViewerAndViceVersa() {
        assertTrue(viewerRouteActive(ViewerRoutes.of(1L, ViewerTarget.Photos)))
        assertFalse("the album detail routes must keep the bar", viewerRouteActive(AlbumRoutes.user(2L)))
        assertFalse("and a tab must not be mistaken for a child", viewerRouteActive(LumoVaultDestination.Photos.route))
        assertFalse(viewerRouteActive(null))
    }
}
