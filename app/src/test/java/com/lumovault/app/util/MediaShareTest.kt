package com.lumovault.app.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.lumovault.app.domain.model.ShareableMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The decisions that make a share right or wrong, off a phone.
 *
 * Every screen shares through [MediaShare], so the whole app's sharing behaviour is these tests:
 * one item must travel as `ACTION_SEND` with its own exact type, several as `ACTION_SEND_MULTIPLE`
 * with every URI and a family type, mixed media must tell the truth, the grant flag must be set on
 * the intent *and* the clip, and nothing — empty list, blank uri — may build a sheet with no bytes
 * behind it. A raw filesystem path is never an input here: the rows are MediaStore content URIs by
 * construction, and the one test fixture spells one out to prove what travels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaShareTest {
    private fun image(id: Long) =
        ShareableMedia(uri = "content://media/external/images/media/$id", mimeType = "image/jpeg")

    private fun video(id: Long) =
        ShareableMedia(uri = "content://media/external/video/media/$id", mimeType = "video/mp4")

    private fun gif(id: Long) =
        ShareableMedia(uri = "content://media/external/images/media/$id", mimeType = "image/gif")

    @Test
    fun oneItemIsACTION_SENDAndSeveralIsACTION_SEND_MULTIPLE() {
        assertEquals(Intent.ACTION_SEND, MediaShare.shareAction(1))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, MediaShare.shareAction(2))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, MediaShare.shareAction(500))
    }

    @Test
    fun aSingleItemKeepsItsOwnExactType() {
        assertEquals("image/gif", MediaShare.resolvedType(listOf("image/gif")))
        assertEquals("image/jpeg", MediaShare.resolvedType(listOf("image/jpeg")))
        assertEquals("video/mp4", MediaShare.resolvedType(listOf("video/mp4")))
    }

    @Test
    fun oneFamilyCollapsesAndAMixedSelectionTellsTheTruth() {
        assertEquals("image/*", MediaShare.resolvedType(listOf("image/jpeg", "image/gif")))
        assertEquals("video/*", MediaShare.resolvedType(listOf("video/mp4", "video/quicktime")))
        assertEquals("*/*", MediaShare.resolvedType(listOf("image/jpeg", "video/mp4")))
        assertEquals("*/*", MediaShare.resolvedType(emptyList()))
        assertEquals("*/*", MediaShare.resolvedType(listOf("", "")))
    }

    @Test
    fun aBlankTypeOnOneItemDoesNotSpoilTheFamily() {
        // MediaStore can decline to state a type for a row; the rest of the selection still speaks.
        assertEquals("image/jpeg", MediaShare.resolvedType(listOf("image/jpeg", "")))
        assertEquals("image/*", MediaShare.resolvedType(listOf("image/jpeg", "image/gif", "")))
    }

    @Test
    fun oneItemTravelsAsOneStreamUriWithAGrant() {
        val intent = checkNotNull(MediaShare.buildIntent(listOf(image(7))))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)

        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        assertEquals(Uri.parse("content://media/external/images/media/7"), stream)

        // The flag on the intent, and a clip carrying the same URI: the framework grants what the
        // clip holds, so either alone would be a sheet that opens and cannot read its own file.
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
        assertEquals(1, intent.clipData!!.itemCount)
        assertEquals(stream, intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun severalItemsTravelWithEveryUriAndTheFamilyType() {
        val items = listOf(image(1), video(2), gif(3))
        val intent = checkNotNull(MediaShare.buildIntent(items))

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)

        val streams: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        assertEquals(
            listOf(
                Uri.parse("content://media/external/images/media/1"),
                Uri.parse("content://media/external/video/media/2"),
                Uri.parse("content://media/external/images/media/3"),
            ),
            streams,
        )
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
        assertEquals(3, intent.clipData!!.itemCount)
    }

    @Test
    fun aTrayOfGifsAnnouncesGif() {
        val intent = checkNotNull(MediaShare.buildIntent(listOf(gif(1), gif(2), gif(3))))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/gif", intent.type)
    }

    @Test
    fun nothingToShareBuildsNoIntent() {
        assertNull(MediaShare.buildIntent(emptyList()))
        // A row whose address is blank is not shareable, and an intent over it would be a sheet
        // with nothing behind it — the caller is told instead, and says so.
        assertNull(MediaShare.buildIntent(listOf(ShareableMedia(uri = "", mimeType = "image/jpeg"))))
    }

    @Test
    fun theSameFileSelectedTwiceSharesOnce() {
        val intent = checkNotNull(MediaShare.buildIntent(listOf(image(5), image(5))))
        // One distinct URI left, so this is a single-item share: the receiver must not see the
        // same photograph twice because the user reached it through two routes.
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(1, intent.clipData!!.itemCount)
    }

    @Test
    fun shareOpensTheSystemSheetAndAnswersWhetherItDid() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(MediaShare.share(context, listOf(image(1))))
        assertFalse(MediaShare.share(context, emptyList()))
    }
}
