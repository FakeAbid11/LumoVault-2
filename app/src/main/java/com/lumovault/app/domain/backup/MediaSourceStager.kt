package com.lumovault.app.domain.backup

/** The result of trying to put a media file where TDLib can read it. */
sealed interface StagedSource {
    data class Ready(val path: String, val sizeBytes: Long) : StagedSource
    data class Unavailable(val failure: BackupFailure) : StagedSource
}

/**
 * Copies MediaStore content to a path TDLib accepts.
 *
 * This exists because `inputFileLocal` takes a path and MediaStore hands out a URI, so some layer has
 * to bridge that, and because the bridge can fail in ways an upload cannot: the file may be gone, may
 * not be readable under the current permission grant, or may not fit. Those are three different
 * answers to the user — deleted, not shared, and out of space — and collapsing them into "upload
 * failed" would be the least useful thing this layer could do.
 *
 * The copy must be streamed. A phone video is routinely hundreds of megabytes, and reading one into
 * memory to write it out again would fail on exactly the low-storage devices this app targets.
 */
interface MediaSourceStager {
    /** Free space TDLib's own directory lives on, used to refuse a copy that cannot fit. */
    fun usableSpaceBytes(): Long

    suspend fun stage(contentUri: String, displayName: String): StagedSource

    /** Best-effort delete after a send finishes; a leftover is cleaned up by [purgeStale]. */
    fun discard(path: String)

    /**
     * Empties the staging directory at start-up.
     *
     * A staged file is a copy — the original is still in MediaStore — so anything found here that no
     * queue row refers to is waste from a previous process, and holding it would cost the user disk
     * for nothing.
     */
    fun purgeStale()
}
