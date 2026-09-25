package com.lumovault.app.ui.viewer

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumovault.app.R
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaMetadata
import com.lumovault.app.util.formatDuration
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Words and marks for one item's details.
 *
 * Every function here answers a question the screen already has, and returns null instead of a substitute when
 * the file does not answer it. That is PRD section 28's "never invent metadata" carried out as code: the
 * alternatives are a dash, a zero, or a date from the wrong event, and each of those reads as a fact.
 */
object ViewerFormatting {

    /** The glyph the backup control carries. */
    fun backupIcon(status: ViewerBackupStatus): ImageVector = when (status) {
        ViewerBackupStatus.NotBackedUp -> Icons.Filled.CloudQueue
        ViewerBackupStatus.Uploading -> Icons.Filled.ArrowUpward
        ViewerBackupStatus.BackedUp -> Icons.Filled.CloudDone
        ViewerBackupStatus.Failed -> Icons.Filled.Refresh
    }

    /**
     * The backup control's sentence.
     *
     * `BACKED_UP` covers both ways a photo gets there — sent now, or recognised against a message already in
     * the channel — because what the user cares about is that it is stored. Likewise `QUEUED` and `UPLOADING`
     * share one sentence: the difference is the queue's, and the progress strip already says it.
     */
    @StringRes
    fun backupLabel(status: ViewerBackupStatus): Int = when (status) {
        ViewerBackupStatus.NotBackedUp -> R.string.viewer_backup_action
        ViewerBackupStatus.Uploading -> R.string.viewer_uploading
        ViewerBackupStatus.BackedUp -> R.string.viewer_backed_up
        ViewerBackupStatus.Failed -> R.string.viewer_retry_action
    }

    /**
     * The label of one row.
     *
     * [ViewerField.TakenDateUnavailable] is labelled "Added", not "Taken": its value is the day the file
     * entered the device, which is a true fact about the file and is not a capture time. Naming the row after
     * the date it actually holds is how the panel says "this file never recorded when it was taken" without
     * inventing one.
     */
    @StringRes
    fun fieldLabel(field: ViewerField): Int = when (field) {
        ViewerField.TakenDate -> R.string.viewer_field_taken
        ViewerField.TakenDateUnavailable -> R.string.viewer_field_added
        ViewerField.Location -> R.string.viewer_field_location
        ViewerField.Camera -> R.string.viewer_field_camera
        ViewerField.Lens -> R.string.viewer_field_lens
        ViewerField.FocalLength -> R.string.viewer_field_focal_length
        ViewerField.Aperture -> R.string.viewer_field_aperture
        ViewerField.Iso -> R.string.viewer_field_iso
        ViewerField.Shutter -> R.string.viewer_field_shutter
        ViewerField.Dimensions -> R.string.viewer_field_dimensions
        ViewerField.FileName -> R.string.viewer_field_name
        ViewerField.FileSize -> R.string.viewer_field_size
        ViewerField.FileType -> R.string.viewer_field_type
        ViewerField.Duration -> R.string.viewer_field_duration
    }

    /**
     * A row that is asked about but cannot be answered says so, and only [ViewerField.Location] is ever asked
     * about without an answer available — it is on the panel because the map is on the panel, and an empty
     * space where a place should be reads as a bug.
     */
    @StringRes
    fun fieldMissing(field: ViewerField): Int? = when (field) {
        ViewerField.Location -> R.string.viewer_location_none
        else -> null
    }

    /** The value of one row, or null when the file does not hold it. */
    fun fieldValue(
        field: ViewerField,
        media: Media,
        metadata: MediaMetadata?,
        dateTime: DateFormat,
    ): String? = when (field) {
        ViewerField.TakenDate -> media.dateTakenSeconds?.let { dateTime.format(Date(it * 1000L)) }

        ViewerField.TakenDateUnavailable -> dateTime.format(Date(media.dateAddedSeconds * 1000L))

        ViewerField.Location -> metadata?.location?.let {
            "${formatCoordinate(it.latitude)}, ${formatCoordinate(it.longitude)}"
        }

        ViewerField.Camera -> metadata?.cameraLabel
        ViewerField.Lens -> metadata?.lensModel?.takeIf { it.isNotBlank() }
        ViewerField.FocalLength -> metadata?.focalLengthMm?.let { "${formatNumber(it)} mm" }
        ViewerField.Aperture -> metadata?.apertureF?.let { "f/${formatNumber(it)}" }
        ViewerField.Iso -> metadata?.isoSpeed?.let { "ISO $it" }
        ViewerField.Shutter -> metadata?.shutterSeconds?.let { formatShutter(it) }
        ViewerField.Dimensions -> if (media.hasDimensions) "${media.width} × ${media.height}" else null
        ViewerField.FileName -> media.displayName.takeIf { it.isNotBlank() }
        ViewerField.FileSize -> formatByteSize(media.sizeBytes)
        ViewerField.FileType -> fileTypeLabel(media)
        ViewerField.Duration -> media.durationMillis?.let { formatDuration(it) }
    }

    /**
     * Latitude and longitude to five decimals.
     *
     * About one metre, which is finer than a phone GPS and coarser than a printed map, and always in a
     * decimal-point locale: a coordinate pair that reads as "52,520, 13,405" in one locale and
     * "52.520, 13.405" in another is a value the user cannot copy out reliably.
     */
    fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

    /**
     * A shutter duration the way a camera prints it.
     *
     * Under a second as a fraction — an exposure of 0.004 s is 1/250, and writing "0.004" asks the reader to
     * do the division. At or over a second, as seconds. A zero or non-finite value is not an exposure at all
     * and answers with nothing.
     */
    fun formatShutter(seconds: Double): String {
        if (seconds <= 0.0 || !seconds.isFinite()) return ""
        if (seconds >= 1.0) return "${formatNumber(seconds)} s"
        val denominator = (1.0 / seconds).roundToInt()
        return if (denominator <= 1) "${formatNumber(seconds)} s" else "1/$denominator"
    }

    /** Whole numbers without a trailing ".0", and one decimal when there is a fraction worth reading. */
    fun formatNumber(value: Double): String =
        String.format(Locale.US, if (value % 1.0 == 0.0) "%.0f" else "%.1f", value)

    /**
     * The MIME subtype, upper-cased — `image/jpeg` is JPEG and `video/mp4` is MP4.
     *
     * Not a guess from the filename: PRD section 3's types are what the media row says it is, and an
     * extension can be wrong while the MIME type MediaStore reported cannot be.
     */
    fun fileTypeLabel(media: Media): String {
        val subtype = media.mimeType.substringAfter('/', media.mimeType).uppercase(Locale.US)
        return subtype.ifBlank { media.mimeType }
    }

    /**
     * Bytes the way a person compares them: one decimal below 10 MB, none above.
     *
     * At 4.2 MB the decimal separates two files; at 1,204.0 MB it is noise. Written here rather than
     * delegated to a platform formatter because those disagree about whether "MB" means 1,000,000 or
     * 1,048,576 bytes, and the panel's own example — 4.2 MB for a phone photo — is the binary reading.
     */
    fun formatByteSize(bytes: Long): String {
        if (bytes < 0L) return ""
        val kilobytes = bytes / 1024.0
        val megabytes = kilobytes / 1024.0
        val gigabytes = megabytes / 1024.0
        return when {
            gigabytes >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gigabytes)
            megabytes >= 1.0 -> String.format(
                Locale.getDefault(),
                if (megabytes < 10.0) "%.1f MB" else "%.0f MB",
                megabytes,
            )

            kilobytes >= 1.0 -> String.format(Locale.getDefault(), "%.0f KB", kilobytes)
            else -> String.format(Locale.getDefault(), "%d B", bytes)
        }
    }
}
