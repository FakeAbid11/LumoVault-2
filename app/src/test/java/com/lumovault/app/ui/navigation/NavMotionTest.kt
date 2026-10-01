package com.lumovault.app.ui.navigation

import com.lumovault.app.ui.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The classification NavMotion's four transitions read before choosing a shape, pinned because the
 * rules are judged from route strings alone: a tab arriving must fade, the viewer must scale open,
 * and a screen that merely lives *under* a tab must still slide like the push it is.
 */
class NavMotionTest {
    @Test
    fun `the four tabs fade through instead of sliding`() {
        LumoVaultDestination.entries.forEach { destination ->
            assertEquals(MotionShape.Tab, motionShapeOf(destination.route))
        }
    }

    @Test
    fun `the viewer opens like a photograph being picked up`() {
        assertEquals(MotionShape.Viewer, motionShapeOf(ViewerRoutes.of(7L, ViewerTarget.Photos)))
        assertEquals(MotionShape.Viewer, motionShapeOf(ViewerRoutes.of(7L, ViewerTarget.Album(1L))))
    }

    @Test
    fun `a screen under a tab is a push, not a tab`() {
        assertEquals(MotionShape.Push, motionShapeOf(AlbumRoutes.user(7L)))
        assertEquals(MotionShape.Push, motionShapeOf(AlbumRoutes.DETAIL_PREFIX + "system/Recent"))
    }

    @Test
    fun `settings, backup and the reconnect door all slide`() {
        assertEquals(MotionShape.Push, motionShapeOf(SettingsRoutes.HUB))
        assertEquals(MotionShape.Push, motionShapeOf(SettingsRoutes.ABOUT))
        assertEquals(MotionShape.Push, motionShapeOf(BackupRoutes.HUB))
        assertEquals(MotionShape.Push, motionShapeOf(AccountRoutes.CONNECT_TELEGRAM))
        assertEquals(MotionShape.Push, motionShapeOf(OnboardingStep.Connect.route))
    }

    @Test
    fun `an unrecognised route takes the shape of a push`() {
        assertEquals(MotionShape.Push, motionShapeOf(null))
        assertEquals(MotionShape.Push, motionShapeOf("somewhere/else"))
    }
}
