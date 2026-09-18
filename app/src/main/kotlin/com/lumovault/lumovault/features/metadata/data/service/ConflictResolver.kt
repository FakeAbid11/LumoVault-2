package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import kotlinx.serialization.json.Json

/**
 * Last-write-wins conflict resolution between a local and a remote item.
 *
 * Ported from lib/features/metadata/data/repositories/conflict_resolver.dart.
 *
 * The design goal is *convergence in one round trip*: whatever rule picks a
 * winner must be a function of the two items' content alone, never of parameter
 * order or local device state, or two devices could pick opposite winners
 * forever. That is why the equal-timestamp tiebreak compares serialized content
 * rather than merging fields — the old field-merge approach OR-ed booleans (an
 * un-favorite could never win) and unioned tags (a tag deletion never
 * propagated), which is non-convergent.
 */
class ConflictResolver {

    /** Which side a resolution kept. */
    enum class Strategy { local_wins, remote_wins, merged }

    data class ResolvedConflict(
        val resolved: PartitionItem,
        val strategy: Strategy,
    )

    /**
     * Resolves [local] against [remote], or null if they are identical (a
     * no-op — this is what makes our own upload echo back harmlessly).
     */
    fun resolve(local: PartitionItem, remote: PartitionItem): ResolvedConflict? {
        if (PartitionItem.areIdentical(local, remote)) return null
        return if (local.fileHash != remote.fileHash) {
            resolveHashDivergence(local, remote)
        } else {
            resolveByTimestamp(local, remote)
        }
    }

    /**
     * Resolves a batch: pairs items by localId, skipping any local item with no
     * remote counterpart (remote-only items are adopted wholesale by the
     * caller, not resolved). Returns only items that actually changed.
     */
    fun resolveBatch(
        localItems: List<PartitionItem>,
        remoteItems: List<PartitionItem>,
    ): List<ResolvedConflict> {
        val remoteMap = remoteItems.associateBy { it.localId }
        return localItems.mapNotNull { local ->
            remoteMap[local.localId]?.let { remote -> resolve(local, remote) }
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Same localId, different file bytes (edited on one device, re-uploaded).
     * Newest [modifiedAt] wins; **ties prefer local**. The loser's telegram
     * message id is folded into the winner's [supersededMessageIds] so the
     * displaced bytes stay referenced and recoverable instead of being orphaned
     * on the channel.
     */
    private fun resolveHashDivergence(local: PartitionItem, remote: PartitionItem): ResolvedConflict {
        val (winner, loser) = if (remote.modifiedAt.isAfter(local.modifiedAt)) {
            remote to local
        } else {
            local to remote
        }

        val pointers = LinkedHashSet(winner.supersededMessageIds)
        loser.telegramMessageId?.takeIf { it.isNotEmpty() && it != winner.telegramMessageId }
            ?.let { pointers.add(it) }

        val resolved = if (pointers.size == winner.supersededMessageIds.size) {
            winner
        } else {
            winner.copy(supersededMessageIds = pointers.toList())
        }

        // supersededMessageIds is excluded from the content hash, so recording
        // one does not re-dirty the partition.
        return ResolvedConflict(resolved, labelStrategy(resolved, local, remote))
    }

    /**
     * Same file bytes, differing metadata. Strict [modifiedAt] comparison.
     * Equal timestamps fall through to the deterministic content tiebreak.
     */
    private fun resolveByTimestamp(local: PartitionItem, remote: PartitionItem): ResolvedConflict {
        return when {
            local.modifiedAt.isAfter(remote.modifiedAt) ->
                ResolvedConflict(local, Strategy.local_wins)
            remote.modifiedAt.isAfter(local.modifiedAt) ->
                ResolvedConflict(remote, Strategy.remote_wins)
            else -> resolveByFieldPriority(local, remote)
        }
    }

    /**
     * Equal timestamps and matching bytes: pick deterministically by serialized
     * content, so both devices make the same choice.
     *
     * Sticky deletion: if either side is a tombstone, the tombstone wins and the
     * later known [deletedAt] is kept.
     *
     * **Scope caveat:** stickiness applies *only* on this equal-timestamp path.
     * [resolveByTimestamp] has no tombstone awareness, so an *older* tombstone
     * still loses to a newer live edit and the item is resurrected. In practice
     * tombstones are usually newest because a delete stamps modifiedAt to now,
     * but do not "helpfully" make deletion always win — that is untested
     * territory and changes observable behavior.
     */
    private fun resolveByFieldPriority(local: PartitionItem, remote: PartitionItem): ResolvedConflict {
        if (local.isDeleted || remote.isDeleted) {
            val winner = if (local.isDeleted) local else remote
            val deletedAt = local.deletedAt ?: remote.deletedAt
            val resolved = if (deletedAt == null || winner.deletedAt == deletedAt) {
                winner
            } else {
                winner.copy(deletedAt = deletedAt)
            }
            return ResolvedConflict(resolved, labelStrategy(resolved, local, remote))
        }

        // Lexicographic comparison of the serialized form. Depends on the JSON
        // key set and omit rules being byte-faithful to the Dart original — a
        // divergent serializer picks the opposite winner and the pair diverges.
        val a = serialize(local)
        val b = serialize(remote)
        val winner = if (a <= b) local else remote
        return ResolvedConflict(winner, labelStrategy(winner, local, remote))
    }

    private fun labelStrategy(
        resolved: PartitionItem,
        local: PartitionItem,
        remote: PartitionItem,
    ): Strategy {
        val sameHash = resolved.fileHash == local.fileHash && resolved.fileHash == remote.fileHash
        val localTime = resolved.modifiedAt == local.modifiedAt
        val remoteTime = resolved.modifiedAt == remote.modifiedAt
        return when {
            sameHash && localTime && !remoteTime -> Strategy.local_wins
            sameHash && remoteTime && !localTime -> Strategy.remote_wins
            else -> Strategy.merged
        }
    }

    private fun serialize(item: PartitionItem): String =
        Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), item.toJson())
}
