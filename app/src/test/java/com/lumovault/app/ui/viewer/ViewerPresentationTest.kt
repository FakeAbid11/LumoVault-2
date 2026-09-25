package com.lumovault.app.ui.viewer

import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MediaMetadata
import java.text.DateFormat
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The viewer's judgement, tested where it does not need a screen.
 *
 * Which renderer a type gets, what the backup control is currently offering, and which lines the details
 * panel may draw are all decided before any composable runs, and each has a wrong answer that a person would
 * feel rather than read: a GIF shown still, a button that re-queues a finished upload, a date that belongs to
 * the wrong event.
 */
class ViewerPresentationTest {
    private lateinit var defaultLocale: Locale

    /**
     * The byte and date formatters answer in the platform's locale, which is right for a person and
     * untestable if the test does not know what that locale is — a runner in another one turns "4.2 MB" into
     * "4,2 MB" and reports it as a failure of the code rather than of the assumption. Pinned for the run and
     * handed back after.
     */
    @Before
    fun usePredictableLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun eachKindOfMediaGetsItsOwnRenderer() {
        assertEquals(ViewerRenderer.ZoomableImage, ViewerPresentation.rendererFor(MediaType.Photo))
        assertEquals(
            "a GIF is animated by a different decoder, and folding it into 'image' is how it ends up still",
            ViewerRenderer.AnimatedImage,
            ViewerPresentation.rendererFor(MediaType.Gif),
        )
        assertEquals(ViewerRenderer.Video, ViewerPresentation.rendererFor(MediaType.Video))
    }

    @Test
    fun theRouteOpensOnTheItemItNamed() {
        assertEquals(1, ViewerPresentation.pageIndex(listOf(7L, 8L, 9L), 8L))
        assertEquals(0, ViewerPresentation.pageIndex(listOf(7L, 8L), 7L))
        assertEquals(
            "an id that is not in the list is -1, and never silently page zero — opening a different " +
                "photograph than the one tapped is worse than saying the file is gone",
            -1,
            ViewerPresentation.pageIndex(listOf(7L, 8L), 99L),
        )
        assertEquals(-1, ViewerPresentation.pageIndex(emptyList(), 7L))
    }

    @Test
    fun theBackupControlSaysWhatTheQueueIsDoing() {
        assertEquals(ViewerBackupStatus.NotBackedUp, ViewerPresentation.backupAction(null).status)
        assertEquals(
            "no row at all and a row that says 'not backed up' are the same thing to the user",
            ViewerBackupStatus.NotBackedUp,
            ViewerPresentation.backupAction(UploadState.NotBackedUp).status,
        )
        assertEquals(ViewerBackupStatus.Uploading, ViewerPresentation.backupAction(UploadState.Queued).status)
        assertEquals(ViewerBackupStatus.Uploading, ViewerPresentation.backupAction(UploadState.Preparing).status)
        assertEquals(ViewerBackupStatus.Uploading, ViewerPresentation.backupAction(UploadState.Uploading).status)
        assertEquals(ViewerBackupStatus.BackedUp, ViewerPresentation.backupAction(UploadState.BackedUp).status)
        assertEquals(ViewerBackupStatus.Failed, ViewerPresentation.backupAction(UploadState.Failed).status)
    }

    @Test
    fun onlyTheStatesThatCanActHaveAnEnabledButton() {
        assertTrue(ViewerPresentation.backupAction(UploadState.NotBackedUp) is ViewerBackupAction.Show)
        assertTrue(ViewerPresentation.backupAction(UploadState.Failed) is ViewerBackupAction.Show)
        assertTrue(ViewerPresentation.backupAction(UploadState.BackedUp) is ViewerBackupAction.Show)
        assertEquals(
            "three in-flight states, and nothing to offer but to wait",
            ViewerBackupAction.Busy(status = ViewerBackupStatus.Uploading),
            ViewerPresentation.backupAction(UploadState.Uploading),
        )
        assertFalse(
            "a control that is drawn but refused is a bug report waiting to arrive",
            ViewerPresentation.backupAction(UploadState.Cancelled) is ViewerBackupAction.Busy,
        )
    }

    @Test
    fun aFileThatSaysNothingGetsFourRowsAndNoInvention() {
        val plain = photo(sizeBytes = 1024L, taken = null, width = 0, height = 0)

        assertEquals(
            listOf(
                ViewerField.TakenDateUnavailable,
                ViewerField.Location,
                ViewerField.FileName,
                ViewerField.FileSize,
                ViewerField.FileType,
            ),
            ViewerPresentation.detailFields(plain, metadata = null),
        )
    }

    @Test
    fun aVideoAddsItsLengthAndNothingElse() {
        val clip = photo(sizeBytes = 4096L, taken = 1_700_000_000L).copy(
            type = MediaType.Video,
            mimeType = "video/mp4",
            durationMillis = 12_000L,
        )

        val fields = ViewerPresentation.detailFields(clip, metadata = null)

        assertEquals(ViewerField.Duration, fields.last())
        assertTrue(ViewerField.TakenDate in fields)
        assertEquals(
            "an MP4 has no EXIF block, so there is no camera to name and no row for one",
            false,
            ViewerField.Camera in fields,
        )
    }

    @Test
    fun aCameraTurnsIntoTheRowsItActuallyNamed() {
        val picture = photo(sizeBytes = 4_404_000L, taken = 1_700_000_000L)
        val onlyModel = metadata(model = "iPhone 15 Pro")

        val fields = ViewerPresentation.detailFields(picture, onlyModel)

        assertTrue(ViewerField.Camera in fields)
        assertFalse("no lens was written, so no lens row exists", ViewerField.Lens in fields)
        assertFalse("no exposure was written, so no shutter row exists", ViewerField.Shutter in fields)
    }

    @Test
    fun theMapOfferDependsOnTheCoordinatesAndNothingElse() {
        assertFalse(ViewerPresentation.canShowOnMap(null))
        assertFalse(ViewerPresentation.canShowOnMap(metadata(model = "Pixel 9")))
        assertTrue(ViewerPresentation.canShowOnMap(metadata(location = MediaLocation(1.0, 2.0))))
    }

    @Test
    fun absentValuesAreAbsentRatherThanZeroOrBlank() {
        val picture = photo(sizeBytes = 4_404_000L, taken = 1_700_000_000L)
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US)

        assertNull(ViewerFormatting.fieldValue(ViewerField.Camera, picture, null, formatter))
        assertNull(ViewerFormatting.fieldValue(ViewerField.Dimensions, picture.copy(width = 0), null, formatter))
        assertNull(
            "an empty lens is not a lens",
            ViewerFormatting.fieldValue(ViewerField.Lens, picture, metadata(lens = "  "), formatter),
        )
        assertEquals("4.2 MB", ViewerFormatting.fieldValue(ViewerField.FileSize, picture, null, formatter))
        assertEquals("JPEG", ViewerFormatting.fieldValue(ViewerField.FileType, picture, null, formatter))
        assertEquals(
            "1.00000, 2.00000",
            ViewerFormatting.fieldValue(
                ViewerField.Location,
                picture,
                metadata(location = MediaLocation(1.0, 2.0)),
                formatter,
            ),
        )
    }

    @Test
    fun shutterDurationsAreWrittenTheWayCamerasWriteThem() {
        assertEquals("1/250", ViewerFormatting.formatShutter(0.004))
        assertEquals("1/60", ViewerFormatting.formatShutter(1.0 / 60.0))
        assertEquals("2 s", ViewerFormatting.formatShutter(2.0))
        assertEquals("a zero is not an exposure", "", ViewerFormatting.formatShutter(0.0))
        assertEquals("", ViewerFormatting.formatShutter(Double.NaN))
    }

    @Test
    fun byteSizesLoseTheirDecimalWhenItStopsMeaningAnything() {
        assertEquals("512 B", ViewerFormatting.formatByteSize(512L))
        assertEquals("4.2 MB", ViewerFormatting.formatByteSize(4_404_000L))
        assertEquals("12 MB", ViewerFormatting.formatByteSize(12_582_912L))
        assertEquals("1.4 GB", ViewerFormatting.formatByteSize(1_503_238_553L))
    }

    private fun photo(sizeBytes: Long, taken: Long?, width: Int = 4032, height: Int = 3024) = Media(
        id = 1L,
        contentUri = "content://media/external/images/media/1",
        type = MediaType.Photo,
        mimeType = "image/jpeg",
        displayName = "IMG_0001.jpg",
        relativePath = "DCIM/Camera/",
        sizeBytes = sizeBytes,
        dateAddedSeconds = 1_700_000_000L,
        dateModifiedSeconds = 1_700_000_000L,
        width = width,
        height = height,
        durationMillis = null,
        dateTakenSeconds = taken,
    )

    private fun metadata(
        location: MediaLocation? = null,
        model: String? = null,
        lens: String? = null,
    ) = MediaMetadata(
        location = location,
        altitudeMeters = null,
        cameraMake = null,
        cameraModel = model,
        lensModel = lens,
        focalLengthMm = null,
        apertureF = null,
        isoSpeed = null,
        shutterSeconds = null,
    )
}
