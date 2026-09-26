package com.lumovault.app.ui.viewer

import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaMetadata
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.ui.screens.photos.BackupCellStatus
import com.lumovault.app.ui.screens.photos.backupCellStatus

/**
 * The viewer's decisions, away from its pixels.
 *
 * A viewer is mostly things a build server cannot check: a pinch, a swipe, a surface drawing a frame. What
 * can be checked is every judgement underneath them — which renderer a type gets, whether the backup control
 * is offering to do something or reporting that it has, and which lines the details panel is allowed to draw
 * for a file that says almost nothing. Each one is a function here, so the part that is logic is tested and
 * the part that is touch is at least small.
 */
object ViewerPresentation {

    /**
     * Which renderer a page gets.
     *
     * GIF is its own case even though it is an image, because a still-decoded GIF is a silent lie about the
     * file: it is the one type where showing the wrong thing looks exactly like showing the right one.
     */
    fun rendererFor(type: MediaType): ViewerRenderer = when (type) {
        MediaType.Video -> ViewerRenderer.Video
        MediaType.Gif -> ViewerRenderer.AnimatedImage
        MediaType.Photo -> ViewerRenderer.ZoomableImage
    }

    /**
     * Where the pager opens.
     *
     * A route carries an id and the list comes from Room, so the two can disagree: the photo was deleted
     * outside the app, or it fell outside the window the source loaded. Neither is a reason to show a blank
     * screen, and neither is a reason to silently open a different photograph — so an id that is not in the
     * list becomes a one-item viewer about that id, and the screen says the file is unavailable for it.
     */
    fun pageIndex(ids: List<Long>, mediaStoreId: Long): Int = ids.indexOf(mediaStoreId)

    /** The backup control for one state. */
    fun backupAction(state: UploadState?): ViewerBackupAction =
        when (backupCellStatus(state)) {
            // `BACKED_UP` still offers something, because PRD section 13's own panel example is a
            // "Backup again" button. Offering it is a second copy in the user's channel, and Phase 6's
            // duplicate recognition is what refuses it — so the control exists, and the queue absorbs it.
            BackupCellStatus.BackedUp -> ViewerBackupAction.Show(
                status = ViewerBackupStatus.BackedUp,
                enabled = true,
            )

            BackupCellStatus.Unbacked -> ViewerBackupAction.Show(
                status = ViewerBackupStatus.NotBackedUp,
                enabled = true,
            )

            BackupCellStatus.Failed -> ViewerBackupAction.Show(
                status = ViewerBackupStatus.Failed,
                enabled = true,
            )

            // Three in-flight marks, one sentence: nothing is left to offer the user but to wait, and a
            // control that re-queues mid-upload is how a second copy of a photo gets made.
            BackupCellStatus.Queued, BackupCellStatus.Preparing, BackupCellStatus.Uploading ->
                ViewerBackupAction.Busy(status = ViewerBackupStatus.Uploading)
        }

    /**
     * Which lines the details panel may draw, in the order it draws them.
     *
     * Absent means absent: a photo with no GPS gets a "Location unavailable" row because the panel is asked
     * about location, and it never gets a Camera row at all for a file that named nothing. That is PRD
     * section 28's rule carried out as a list builder rather than as a hope.
     */
    fun detailFields(media: Media, metadata: MediaMetadata?): List<ViewerField> = buildList {
        add(if (media.dateTakenSeconds != null) ViewerField.TakenDate else ViewerField.TakenDateUnavailable)
        add(ViewerField.Location)
        metadata?.cameraLabel?.let { add(ViewerField.Camera) }
        metadata?.lensModel?.let { add(ViewerField.Lens) }
        if (metadata?.focalLengthMm != null) add(ViewerField.FocalLength)
        if (metadata?.apertureF != null) add(ViewerField.Aperture)
        if (metadata?.isoSpeed != null) add(ViewerField.Iso)
        if (metadata?.shutterSeconds != null) add(ViewerField.Shutter)
        if (media.hasDimensions) add(ViewerField.Dimensions)
        add(ViewerField.FileName)
        add(ViewerField.FileSize)
        add(ViewerField.FileType)
        if (media.type == MediaType.Video) add(ViewerField.Duration)
    }

    /**
     * Whether "view on map" is an offer at all.
     *
     * Without a stored position there is nothing to centre the map on, and a tappable control that goes
     * nowhere is the kind of thing PRD section 85 counts as clutter.
     */
    fun canShowOnMap(metadata: MediaMetadata?): Boolean = metadata?.hasLocation == true
}

/** Which of the three renderers draws a page. */
enum class ViewerRenderer { ZoomableImage, AnimatedImage, Video }

/** What the viewer's backup control currently means. */
enum class ViewerBackupStatus { NotBackedUp, Uploading, BackedUp, Failed }

/** The control's two shapes: an action the user can take, and a state they can only watch. */
sealed interface ViewerBackupAction {
    /** What the glyph and the sentence say. Both shapes have one, which is why it is on the interface. */
    val status: ViewerBackupStatus

    /** A labelled button. */
    data class Show(override val status: ViewerBackupStatus, val enabled: Boolean) : ViewerBackupAction

    /** No button: the queue is already working on this item. */
    data class Busy(override val status: ViewerBackupStatus) : ViewerBackupAction
}

/** A row of the details panel. Deliberately carries no text: the strings belong to the screen that draws them. */
enum class ViewerField {
    TakenDate,
    TakenDateUnavailable,
    Location,
    Camera,
    Lens,
    FocalLength,
    Aperture,
    Iso,
    Shutter,
    Dimensions,
    FileName,
    FileSize,
    FileType,
    Duration,
}
