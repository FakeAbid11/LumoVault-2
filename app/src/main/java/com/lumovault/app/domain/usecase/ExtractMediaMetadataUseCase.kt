package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.metadata.MediaContentMetadataReader
import com.lumovault.app.domain.metadata.MetadataCandidate
import com.lumovault.app.domain.metadata.MetadataRead
import com.lumovault.app.domain.repository.MediaMetadataRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads EXIF out of photos in bounded passes, so the map and the details panel can answer without the app
 * ever opening the whole library.
 *
 * This is deliberately not part of the scan. MediaStore answers size, dates and dimensions from its own
 * index, which is why indexing 100,000 items costs one query; a GPS fix is not indexed, so getting it means
 * opening each file — and doing that inside a sync would turn a pull-to-refresh into an all-afternoon read
 * of somebody's camera roll. It is the same layered-identity argument Phase 6 settled for content hashes:
 * cheap facts from the index, expensive facts only for the items that need them, one bounded stage at a
 * time.
 *
 * Three properties this file's shape exists to hold:
 *
 *  - **It records an answer every time it can.** "No EXIF in this file" and "this file is gone" are written
 *    down as done, so the candidate list shrinks monotonically and a corrupt JPEG does not occupy a pass
 *    forever. Only a transient read failure leaves an item pending.
 *  - **It terminates.** A time budget, a stage size, and a set of ids already attempted in this run. That
 *    set is not tidiness: candidates are re-queried per stage, so the ones that were *not* recorded — the
 *    transient failures — would otherwise come back on the next stage and the pass would spin on them until
 *    the budget expired.
 *  - **It stops when asked.** The coroutine is checked between files, so leaving the map abandons the reads
 *    instead of letting them run behind a screen nobody is looking at. And only one pass runs per process,
 *    because opening the map twice is an ordinary thing to do and two passes would read the same files.
 */
class ExtractMediaMetadataUseCase(
    private val metadata: MediaMetadataRepository,
    private val reader: MediaContentMetadataReader,
    private val nowSeconds: () -> Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val stageSize: Int = STAGE_SIZE,
    private val budgetMillis: Long = BUDGET_MILLIS,
) {
    /**
     * What one pass did.
     *
     * [read] counts files opened, [recorded] how many now have a row (so the next pass starts smaller), and
     * [located] how many gained a position — which is the figure the map's "still finding photo locations"
     * line can honestly show, and is not the same number as any of the others.
     */
    data class Run(
        val read: Int = 0,
        val recorded: Int = 0,
        val located: Int = 0,
        val stoppedEarly: Boolean = false,
    )

    private var running = false

    /** Runs one bounded pass, or returns an empty [Run] when another is already in flight. */
    suspend fun run(): Run {
        if (running) return Run(stoppedEarly = true)
        running = true
        try {
            return readStage()
        } finally {
            running = false
        }
    }

    /**
     * Reads exactly one item, for the viewer opening a photo whose metadata was never needed.
     *
     * The same recording rules as the pass, so a lazily-read item cannot leave a different shape of row than
     * a pass-read one — and a transient failure is again recorded as nothing, which lets the next visit try
     * rather than showing "no location" for a file that was merely busy.
     */
    suspend fun readOne(candidate: MetadataCandidate): Outcome {
        val read = reader.read(candidate.contentUri)
        currentCoroutineContext().ensureActive()

        return when (read) {
            is MetadataRead.Found -> {
                metadata.record(candidate.mediaStoreId, read.metadata, nowSeconds())
                Outcome.Recorded(location = read.metadata.hasLocation)
            }

            MetadataRead.NothingRecorded -> {
                metadata.record(candidate.mediaStoreId, null, nowSeconds())
                Outcome.Recorded(location = false)
            }

            is MetadataRead.Unreadable -> Outcome.Deferred
        }
    }

    /** What one read ended as. */
    sealed interface Outcome {
        /** A row was written, so this item will not be a candidate again. */
        data class Recorded(val location: Boolean) : Outcome

        /** Nothing was learned; leave it pending for a later pass. */
        data object Deferred : Outcome
    }

    private suspend fun readStage(): Run {
        val startedAt = nowMillis()
        val attempted = mutableSetOf<Long>()
        var read = 0
        var recorded = 0
        var located = 0
        var stoppedEarly = false

        outer@ while (true) {
            // Over-asked on purpose. A file whose read failed transiently is *still* a candidate — that is
            // the point of not recording it — so asking for exactly `stageSize` would keep handing the pass
            // the same dozen unreadable files at the head of the list and never reach the ones behind them.
            val window = (stageSize + attempted.size).coerceAtMost(MAX_CANDIDATE_WINDOW)
            val stage = metadata.extractionCandidates(window)
                .filterNot { it.mediaStoreId in attempted }
                .take(stageSize)
            if (stage.isEmpty()) break

            attempted += stage.map { it.mediaStoreId }

            for (candidate in stage) {
                currentCoroutineContext().ensureActive()
                if (nowMillis() - startedAt >= budgetMillis) {
                    stoppedEarly = true
                    break@outer
                }
                read++
                when (val outcome = readOne(candidate)) {
                    is Outcome.Recorded -> {
                        recorded++
                        if (outcome.location) located++
                    }

                    Outcome.Deferred -> Unit
                }
            }
        }

        return Run(read = read, recorded = recorded, located = located, stoppedEarly = stoppedEarly)
    }

    private companion object {
        /**
         * Files opened per stage. Small enough that abandoning a pass loses little work and the map fills in
         * visible steps, large enough that a stage is a handful of milliseconds of query rather than one.
         */
        const val STAGE_SIZE = 12

        /**
         * How long one pass may run. Deliberately modest: this is I/O against somebody's camera roll, and a
         * pass that outlasts a glance at the map has no business continuing — the next time the map is
         * opened, it picks up where this one stopped.
         */
        const val BUDGET_MILLIS = 4_000L

        /**
         * How far the pass may look ahead for files it has not already tried in this run.
         *
         * A ceiling rather than unbounded, because a library whose newest few hundred photos are all
         * temporarily unreadable should end the pass with a clear "stopped early" rather than turn the query
         * into a scan of everything.
         */
        const val MAX_CANDIDATE_WINDOW = 500
    }
}
