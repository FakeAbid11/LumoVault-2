package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.OnboardingRepository
import com.lumovault.app.domain.repository.PermissionRepository
import com.lumovault.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * One automatic pass: notice what is new, queue what the user agreed to, and ask for a send.
 *
 * ```
 * live permission check → settings → MediaStore scan → candidates → existing queue → existing worker
 * ```
 *
 * Every step is a refusal point, and the order is the one that costs least. Permission is read from the
 * system rather than remembered because it can be revoked outside the app, and a pass that assumed the
 * onboarding answer would scan a provider that now returns nothing and call the library empty. Settings are
 * read next because "automatic backup is off" must cost one database read, not a scan.
 *
 * Nothing here uploads. The queue is the same table the Photos selection writes to and the same worker drains
 * it, which is the whole point of routing an unattended pass through it: an item enters at `queued`, is
 * claimed one at a time, is hashed and manifest-checked before it is sent, and cannot be marked `backed_up`
 * by a request that merely started. Automatic and manual differ in who pressed the button and in which
 * constraints the work waits under — not in what the app is willing to claim about a file.
 *
 * It is also safe to run twice. [BackupQueueRepository.enqueue] adds rows only for items that have none, so
 * a worker retried after a dropped connection, a reboot, and a periodic pass overlapping a pull-to-refresh
 * all produce one queue rather than four copies of the same photo.
 *
 * And because it is the only pass that runs unattended, it is the only thing that can notice a queue left
 * behind by a process that died mid-send: the send is asked for whenever the table holds work, not only when
 * this pass added some. A phone restarted with forty items waiting has nothing *new* to discover, and a rule
 * keyed to this pass's own inserts would leave those forty waiting indefinitely.
 */
class RunAutomaticBackupUseCase(
    private val media: MediaRepository,
    private val queue: BackupQueueRepository,
    private val onboarding: OnboardingRepository,
    private val settings: SettingsRepository,
    private val permissions: PermissionRepository,
    /** Asks for a pass over the queue, under the constraints the settings allow. */
    private val scheduleUpload: () -> Unit,
    private val candidatesPerPass: Int = CANDIDATES_PER_PASS,
) {

    suspend fun run(): Outcome {
        if (!permissions.mediaStatus().allowsScanning) return Outcome.NoMediaAccess

        val preferences = settings.backupPreferences.first()
        if (!preferences.automatic) return Outcome.Disabled

        val progress = onboarding.progress.first()
        val source: BackupSource = progress.backupSource ?: return Outcome.NoSourceSelected
        if (!source.enablesBackup) return Outcome.NoSourceSelected

        media.sync()

        val candidates = queue.autoBackupCandidates(
            source = source,
            folders = progress.selectedFolders,
            limit = candidatesPerPass,
        )
        val queued = if (candidates.isEmpty()) 0 else queue.enqueue(candidates)

        // Ask for a send whenever the queue holds something, which is a different question from "did this
        // pass add anything". A phone killed between queueing and sending leaves rows waiting in `queued`
        // with no caller left to ask for them, and the next pass would find nothing *new* to add: a resume
        // rule keyed to this pass's own inserts is how the oldest items in a library end up the last ones
        // backed up, and how "the queue is working through it" quietly stops being true.
        if (queue.hasQueuedWork()) scheduleUpload()

        return Outcome.Queued(queued = queued, moreRemaining = candidates.size >= candidatesPerPass)
    }

    /** What a pass ended as, in terms the notification and the health screen can both use. */
    sealed interface Outcome {
        /** The user turned it off, so the pass did nothing at all — and said so rather than pretending. */
        data object Disabled : Outcome

        /** Media access is not held right now. Different from "there is nothing to back up". */
        data object NoMediaAccess : Outcome

        /** Backup is on but no source was ever chosen, which is not a licence to send everything. */
        data object NoSourceSelected : Outcome

        /**
         * [queued] items joined the queue.
         *
         * [moreRemaining] is the honest half: a library larger than one pass means the next run has work, so
         * a screen can say "still going" instead of showing a completed bar over a queue that is half empty
         * because the app stopped asking.
         */
        data class Queued(val queued: Int, val moreRemaining: Boolean) : Outcome
    }

    private companion object {
        /**
         * Enqueued per pass, not per library.
         *
         * The cap exists so a first automatic run on a 90,000-item phone cannot write 90,000 rows and then
         * have the recognition pass read every file behind them before a single upload starts. Queueing a
         * window and re-running is slower in theory and usable in practice — and nothing is lost by the
         * delay, because the items still on the device are still there for the next pass.
         */
        const val CANDIDATES_PER_PASS = 200
    }
}
