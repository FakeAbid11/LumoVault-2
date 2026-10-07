package com.lumovault.app.ui.screens.albums

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a cover-less album tile is allowed to claim about itself.
 *
 * The tile prints a count underneath the artwork, so the artwork's fallback and the count are two halves of
 * one sentence. "Empty album" under "1,024 items" is not a style problem; it is the app contradicting
 * itself about the user's own photographs, and it is what the placeholder said for any album whose cover had
 * been deleted or could not be decoded. The three states are decided here so that contradiction is
 * impossible to reintroduce without a test failing first.
 */
class AlbumCoverFallbackTest {
    @Test
    fun aSystemAlbumAlwaysWearsItsBadge() {
        assertEquals(
            "a category has a mark of its own and can be empty in the sense of 'nothing matching yet'",
            AlbumCoverFallback.Badge,
            albumCoverFallback(hasIcon = true, hasMedia = false),
        )
        assertEquals(
            "and a mark beats a thumbnail whenever a tile has both, because a system album's contents are a "
                + "predicate rather than a thing a cover could represent",
            AlbumCoverFallback.Badge,
            albumCoverFallback(hasIcon = true, hasMedia = true),
        )
    }

    @Test
    fun anAlbumWithNothingInItMaySaySo() {
        assertEquals(
            "the one state that earns the sentence — no cover, and nothing under it to contradict",
            AlbumCoverFallback.Empty,
            albumCoverFallback(hasIcon = false, hasMedia = false),
        )
    }

    @Test
    fun aCoverlessAlbumWithMediaIsBrokenRatherThanEmpty() {
        assertEquals(
            "the count printed below says otherwise; a missing cover is a picture that failed, "
                + "not an album that has no photographs",
            AlbumCoverFallback.Missing,
            albumCoverFallback(hasIcon = false, hasMedia = true),
        )
    }
}
