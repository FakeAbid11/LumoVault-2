package com.lumovault.app.data.metadata

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.lumovault.app.domain.metadata.ExifFacts
import com.lumovault.app.domain.metadata.MediaContentMetadataReader
import com.lumovault.app.domain.metadata.MetadataFailure
import com.lumovault.app.domain.metadata.MetadataRead
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads a photo's EXIF through a file descriptor, which is the only shape of this job that does not load
 * the file.
 *
 * `ExifInterface` over a `FileDescriptor` parses a JPEG's leading APP1 segment and stops: the EXIF block
 * sits ahead of the pixel data, so a 48-megapixel photo costs a few kilobytes of read rather than its whole
 * self. PRD section 18's "avoid unnecessarily reading complete large files during ordinary gallery
 * scanning" is why this is its own bounded pass instead of a line in the scanner, and why a descriptor is
 * the argument and a `ByteArray` never is.
 *
 * Two things about Android's media permissions decide what can be trusted from here:
 *
 *  - An app that does not hold `ACCESS_MEDIA_LOCATION` is handed a **redacted** copy with the GPS fields
 *    already stripped by MediaProvider. Reading it is still worth doing — camera, lens and exposure
 *    survive — so that is what happens, and the resulting "no coordinates" is knowingly uncertain rather
 *    than reported as absence. That is why the map's grant path discards location-less reads and lets them
 *    be re-read: see `MediaMetadataDao.discardUnlocatedReads`.
 *  - `MediaStore.setRequireOriginal` is the opt-in that asks for the real bytes, and it throws
 *    `UnsupportedOperationException` *when the returned uri is opened* if the permission is not held — so it
 *    is applied only while [locationAccessGranted] says so. That is a live read rather than a remembered
 *    decision, because the grant can be revoked from system settings while LumoVault is backgrounded.
 *
 * A malformed or truncated EXIF block is not an error here. AndroidX's parser swallows its own parse
 * exceptions and returns an attribute set that is simply empty, so a corrupt file arrives as
 * [MetadataRead.NothingRecorded] and the library keeps moving — which is the difference between one bad
 * JPEG and a gallery that will not open.
 */
class ExifMediaMetadataReader(
    private val resolver: ContentResolver,
    private val locationAccessGranted: () -> Boolean,
) : MediaContentMetadataReader {

    override suspend fun read(contentUri: String): MetadataRead = withContext(Dispatchers.IO) {
        val uri = Uri.parse(contentUri)
        val target = if (locationAccessGranted()) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }

        try {
            val descriptor = resolver.openFileDescriptor(target, READ_MODE)
                ?: return@withContext MetadataRead.Unreadable(MetadataFailure.SourceMissing)
            descriptor.use {
                currentCoroutineContext().ensureActive()
                val exif = ExifInterface(it.fileDescriptor)
                currentCoroutineContext().ensureActive()
                exif.toFacts().toMediaMetadata()?.let { found -> MetadataRead.Found(found) }
                    ?: MetadataRead.NothingRecorded
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (denied: UnsupportedOperationException) {
            // The grant went away between the check and the open. Nothing was learned about the file, so
            // nothing is recorded: the item stays a candidate and a later pass reads it honestly.
            MetadataRead.Unreadable(MetadataFailure.Unreadable)
        } catch (failed: IOException) {
            // Only the shape of the failure travels onward. A MediaStore or ExifInterface message can quote
            // the file's path, and a filename is personal data.
            MetadataRead.Unreadable(MetadataFailure.Unreadable)
        }
    }

    /**
     * The tags this app shows, and nothing else.
     *
     * Deliberately short: a white balance, a flash code or a megapixel count would each need a decision
     * about how to say it, and PRD section 28's rule is that a field appears only when the file states it.
     * So this set is exactly what the details panel was asked to draw, no more.
     */
    private fun ExifInterface.toFacts(): ExifFacts {
        val position = getLatLong()
        return ExifFacts(
            make = getAttribute(ExifInterface.TAG_MAKE),
            model = getAttribute(ExifInterface.TAG_MODEL),
            lensModel = getAttribute(ExifInterface.TAG_LENS_MODEL),
            focalLengthMm = getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, NOT_RECORDED).asRecorded(),
            fNumber = getAttributeDouble(ExifInterface.TAG_F_NUMBER, NOT_RECORDED).asRecorded(),
            isoSpeed = getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, NOT_RECORDED_INT).asRecorded(),
            // Two tags answer the same question in different units, and the difference is a trap:
            // `ExposureTime` is seconds, `ShutterSpeedValue` is the apex (its base-2 logarithm). Prefer the
            // seconds, and let the caller convert the apex only when a camera wrote nothing else.
            exposureTimeSeconds = getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, NOT_RECORDED).asRecorded(),
            shutterValueApex = getAttributeDouble(ExifInterface.TAG_SHUTTER_SPEED_VALUE, NOT_RECORDED).asApex(),
            latitude = position?.getOrNull(LATITUDE_INDEX),
            longitude = position?.getOrNull(LONGITUDE_INDEX),
            // `getAltitude` cannot express "absent" — its default argument is the only signal — so the tag
            // is checked first rather than reading a 0 back as "sea level".
            altitudeMeters = if (hasAttribute(ExifInterface.TAG_GPS_ALTITUDE)) {
                getAltitude(NOT_RECORDED).asRecorded()
            } else {
                null
            },
        )
    }

    /** Turns the `getX(tag, default)` sentinel into the absence it stands for, so nothing downstream reads a 0 as a value. */
    private fun Double.asRecorded(): Double? = if (this == NOT_RECORDED || !isFinite()) null else this

    private fun Int.asRecorded(): Int? = if (this == NOT_RECORDED_INT) null else this

    /**
     * The apex keeps its zero.
     *
     * Apex 0.0 genuinely means one second, so it is not a safe sentinel — but it is also what the
     * two-argument getter answers when the tag is *missing*, and inventing "1 s" for every photo that never
     * wrote the tag is the worse error. A real one-second exposure still arrives through `ExposureTime`,
     * which every camera that writes the apex writes too.
     */
    private fun Double.asApex(): Double? = if (this == NOT_RECORDED || !isFinite()) null else this

    private companion object {
        const val READ_MODE = "r"

        /** What the `getX(tag, default)` family is told to answer when a tag is not there. */
        const val NOT_RECORDED = 0.0
        const val NOT_RECORDED_INT = 0

        /** `getLatLong()` is documented as latitude first, longitude second. */
        const val LATITUDE_INDEX = 0
        const val LONGITUDE_INDEX = 1
    }
}
