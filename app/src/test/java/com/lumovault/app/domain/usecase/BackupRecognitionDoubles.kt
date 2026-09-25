package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.ContentDigest
import com.lumovault.app.domain.backup.MediaContentHasher
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.CloudTypeCount
import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.repository.RemoteBackup
import com.lumovault.app.domain.telegram.CloudAssociation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The remote index as recognition sees it: a set of hashes, and which message each one points at.
 *
 * [savePage] and the association methods record calls rather than storing media, because no test here
 * reads the index back — the scan that *fills* it is [SynchronizeCloudUseCase]'s own subject. What this
 * fake has to be faithful about is the one answer recognition acts on: a hash either resolves to a
 * message or it does not, and getting that backwards is what would put a ✓ on an unbacked photo.
 */
class FakeCloudIndexRepository : CloudIndexRepository {
    private val byHash = LinkedHashMap<String, RemoteBackup>()

    /** Every lookup attempted, in order — a test that asserts "nothing was uploaded" also has to show
     * the check was actually made. */
    val lookups = mutableListOf<String>()
    var unrecognized = 0

    fun given(hash: String, chatId: Long, messageId: Long) {
        byHash[hash] = RemoteBackup(chatId, messageId)
    }

    override fun observeWindow(limit: Int): Flow<List<CloudMedia>> = flowOf(emptyList())

    override fun observeCount(): Flow<Int> = flowOf(byHash.size)

    override fun observeTypeCounts(): Flow<List<CloudTypeCount>> = flowOf(emptyList())

    override suspend fun currentCount(): Int = byHash.size

    override suspend fun savePage(chatId: Long, scanId: Long, items: List<CloudMedia>) = Unit

    override suspend fun finishScan(scanId: Long, association: CloudAssociation, prune: Boolean): Int = 0

    override suspend fun remoteBackupFor(contentHash: String): RemoteBackup? {
        lookups += contentHash
        return byHash[hashKey(contentHash)]
    }

    override suspend fun unrecognizedRemoteCount(): Int = unrecognized

    override suspend fun association(): CloudAssociation? = null

    override suspend fun saveAssociation(association: CloudAssociation) = Unit

    override suspend fun replaceAssociation(association: CloudAssociation) = Unit

    override suspend fun dropAssociation() = Unit

    private fun hashKey(hash: String): String = hash.lowercase()
}

/**
 * Hashes on demand from a script, and remembers what it was asked to read.
 *
 * The default answer is a fixed digest for any file: enough for a test that cares about what the queue
 * does with an identity rather than how the identity was computed. [failNext] scripts the unreadable
 * case, which is a distinct outcome rather than a null — a file that cannot be hashed must leave the item
 * not-backed-up, and that is the whole of PRD section 18's rule about failures not corrupting state.
 */
class FakeMediaContentHasher : MediaContentHasher {
    val uriReads = mutableListOf<String>()
    val fileReads = mutableListOf<String>()

    var digest = KNOWN_HASH
    var sizeBytes = 1024L

    /**
     * Per-file digests, for tests where several items must come out as different content.
     *
     * Keyed by the URI or path the hasher was handed, because that is the only thing a fake can tell two
     * files apart by — and keyed first, so a test does not have to know in which order the pass happened
     * to reach them.
     */
    val digestsBySource = mutableMapOf<String, String>()
    private val failures = ArrayDeque<BackupFailureKind>()

    fun failNext(kind: BackupFailureKind = BackupFailureKind.SourceUnreadable) {
        failures += kind
    }

    /** How many files were actually read, which is the number the layered check is supposed to keep small. */
    val reads: Int get() = uriReads.size + fileReads.size

    override suspend fun hash(contentUri: String): ContentDigest = next(contentUri, uriReads)

    override suspend fun hashFile(path: String): ContentDigest = next(path, fileReads)

    private fun next(key: String, log: MutableList<String>): ContentDigest {
        log += key
        val failure = failures.removeFirstOrNull()
        return if (failure == null) {
            val hash = digestsBySource[key] ?: digest
            ContentDigest.Computed(hash, sizeBytes)
        } else {
            ContentDigest.Unavailable(BackupFailure(failure))
        }
    }

    companion object {
        const val KNOWN_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

/** Sixty-four hex characters, which is what a SHA-256 digest looks like and what the manifest requires. */
fun shaHashOf(marker: Char): String = marker.toString().repeat(64)
