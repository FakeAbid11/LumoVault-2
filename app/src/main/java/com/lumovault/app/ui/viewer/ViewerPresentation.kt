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
     * Where the pager opens: the position of the tapped id in the loaded window, or -1.
     *
     * A route carries an id and the list comes from Room, so the two can disagree. -1 is answered by the
     * caller, not here — the window is widened to cover the tapped item first ([MediaViewerViewModel.open]
     * probes for that), and only an id that survives every probe is genuinely absent, which the screen says
     * out loud rather than silently opening a different photograph at index zero.
     */
    fun pageIndex(ids: List<Long>, mediaStoreId: Long): Int = ids.indexOf(mediaStoreId)

    /**
     * The id that takes the shown item's slot when archive or trash hides it from the source's query.
     *
     * [retiredIndex] is where the pager sits; [survivorIds] is the window after the item left, so the
     * neighbour is simply the id now standing at that same index — clamped, because retiring the last
     * item of the window moves the boundary up by one. Null means the list is empty: nothing can stay
     * put in nothing, and the caller lets the honest "this album is empty" copy speak.
     */
    fun survivorAfterRetirement(survivorIds: List<Long>, retiredIndex: Int): Long? =
        survivorIds.getOrNull(retiredIndex.coerceAtMost(survivorIds.size - 1))

    /**
     * The backup control for one state.
     *
     * [inCloudIndex] is the cloud's own word for the file, and it is consulted exactly once, before the
     * queue is: a photograph opened from the Cloud tab was named by a row in the user's channel, so when
     * the local queue has nothing recorded about it, "not backed up" would contradict the badge the Cloud
     * grid drew on the very cell that was tapped. A queue row that *does* speak still wins — an upload
     * this user started and watched fail is a failure, and cloud history must not paper over it.
     */
    fun backupAction(state: UploadState?, inCloudIndex: Boolean = false): ViewerBackupAction =
        if (state == null && inCloudIndex) {
            ViewerBackupAction.Show(
                status = ViewerBackupStatus.BackedUp,
                enabled = true,
            )
        } else when (backupCellStatus(state)) {
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
     * Where the file's bytes are, as the details panel is allowed to say it.
     *
     * Two independent signals for one question — "is there a second copy somewhere" — because they are
     * gathered by different parts of the app and neither is a superset of the other: the cloud index
     * knows about files the queue never saw (anything uploaded before the queue existed, or dropped in
     * by hand), and the queue knows about uploads the index is not allowed to read during a browse.
     * A file that answers neither really is on this device alone, which is worth saying out loud.
     */
    fun storageLocation(status: ViewerBackupStatus, fromCloudIndex: Boolean): ViewerStorage =
        if (fromCloudIndex || status == ViewerBackupStatus.BackedUp) {
            ViewerStorage.DeviceAndCloud
        } else {
            ViewerStorage.DeviceOnly
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

/** Whether a second copy of the file exists outside this phone, as the details panel may report it. */
enum class ViewerStorage { DeviceOnly, DeviceAndCloud }

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
