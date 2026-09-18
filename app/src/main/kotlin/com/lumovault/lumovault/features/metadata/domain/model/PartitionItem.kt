package com.lumovault.lumovault.features.metadata.domain.model

import com.lumovault.lumovault.features.metadata.data.util.Timestamps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanStrictOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant

/**
 * One media item as recorded in a metadata partition document.
 *
 * Ported from lib/features/metadata/data/models/metadata_partition.dart.
 * The serialization and hashing are byte-sensitive — see [toJson] and
 * [hashItems] for the exact rules that must not drift from the Dart original.
 */
data class PartitionItem(
    val localId: String,
    val fileHash: String,
    val telegramMessageId: String? = null,
    val telegramFileId: String? = null,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val backedUpAt: Instant? = null,
    val mimeType: String? = null,
    val fileSize: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Instant? = null,
    /** Permanent-delete tombstone. Stays in the partition so it re-syncs and
     * survives a restore instead of resurrecting the item. */
    val isDeleted: Boolean = false,
    val deletedAt: Instant? = null,
    val albumName: String? = null,
    val deviceFolder: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val isDateUserSet: Boolean = false,
    val locationName: String? = null,
    val aiLabels: List<String> = emptyList(),
    val status: MediaStatus = MediaStatus.pending,
    val fileName: String? = null,
    /** Telegram message ids of file copies this item displaced during a
     * file-hash conflict; the loser's bytes stay recoverable instead of
     * orphaned on the channel. Excluded from the content hash. */
    val supersededMessageIds: List<String> = emptyList(),
) {

    /**
     * Sparse JSON. Fields are written only when truthy or non-default, matching
     * the Dart original key-for-key and omit-rule-for-omit-rule. The conflict
     * resolver's tiebreak compares this serialization lexicographically, so a
     * different key set or a different omission rule picks the *opposite*
     * winner and the pair never converges.
     */
    fun toJson(): JsonObject = buildJsonObject {
        put("lid", localId)
        put("h", fileHash)
        put("ct", Timestamps.format(createdAt))
        put("mod", Timestamps.format(modifiedAt))
        fileName?.let { put("fn", it) }
        telegramMessageId?.let { put("tmid", it) }
        telegramFileId?.let { put("tfid", it) }
        backedUpAt?.let { put("bu", Timestamps.format(it)) }
        mimeType?.let { put("mime", it) }
        if (fileSize > 0L) put("sz", fileSize)
        if (width > 0) put("w", width)
        if (height > 0) put("ht", height)
        durationMs?.let { put("d", it) }
        if (isFavorite) put("fav", true)
        if (isHidden) put("hid", true)
        if (isArchived) put("arc", true)
        if (isTrashed) put("trash", true)
        trashedAt?.let { put("trasht", Timestamps.format(it)) }
        if (isDeleted) put("del", true)
        deletedAt?.let { put("delt", Timestamps.format(it)) }
        albumName?.let { put("alb", it) }
        deviceFolder?.let { put("fol", it) }
        description?.let { put("desc", it) }
        if (tags.isNotEmpty()) putTags("tags", tags)
        if (isDateUserSet) put("dus", true)
        locationName?.let { put("locn", it) }
        if (aiLabels.isNotEmpty()) putTags("ail", aiLabels)
        if (supersededMessageIds.isNotEmpty()) putTags("smids", supersededMessageIds)
        put("st", status.index)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTags(
        key: String,
        values: List<String>,
    ) {
        put(key, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        private val now: Instant get() = Instant.now()

        /**
         * Parses one item, or null on any malformed field. The partition's
         * `fromJsonString` gives up on a thrown parse error for the *whole*
         * document, so every field here must fail soft.
         *
         * `ct`/`mod` fall back to now when unparseable (matching the Dart
         * original) so a corrupt timestamp degrades one item rather than the
         * partition; optional timestamps fall back to null.
         */
        fun fromJson(obj: JsonObject): PartitionItem? {
            return try {
                val localId = obj["lid"]?.jsonPrimitive?.content ?: return null
                val fileHash = obj["h"]?.jsonPrimitive?.content ?: return null
                val createdAt = Timestamps.parseOrNull(obj["ct"]?.jsonPrimitive?.content) ?: now
                val modifiedAt = Timestamps.parseOrNull(obj["mod"]?.jsonPrimitive?.content) ?: now
                PartitionItem(
                    localId = localId,
                    fileHash = fileHash,
                    telegramMessageId = obj["tmid"]?.jsonPrimitive?.contentOrNull,
                    telegramFileId = obj["tfid"]?.jsonPrimitive?.contentOrNull,
                    createdAt = createdAt,
                    modifiedAt = modifiedAt,
                    backedUpAt = Timestamps.parseOrNull(obj["bu"]?.jsonPrimitive?.contentOrNull),
                    mimeType = obj["mime"]?.jsonPrimitive?.contentOrNull,
                    fileSize = obj["sz"]?.jsonPrimitive?.longOrNull ?: 0L,
                    width = obj["w"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = obj["ht"]?.jsonPrimitive?.intOrNull ?: 0,
                    durationMs = obj["d"]?.jsonPrimitive?.longOrNull,
                    isFavorite = obj["fav"]?.jsonPrimitive?.booleanStrictOrNull ?: false,
                    isHidden = obj["hid"]?.jsonPrimitive?.booleanStrictOrNull ?: false,
                    isArchived = obj["arc"]?.jsonPrimitive?.booleanStrictOrNull ?: false,
                    isTrashed = obj["trash"]?.jsonPrimitive?.booleanStrictOrNull ?: false,
                    trashedAt = Timestamps.parseOrNull(obj["trasht"]?.jsonPrimitive?.contentOrNull),
                    isDeleted = obj["del"]?.jsonPrimitive?.booleanStrictOrNull ?: false,
                    deletedAt = Timestamps.parseOrNull(obj["delt"]?.jsonPrimitive?.contentOrNull),
                    albumName = obj["alb"]?.jsonPrimitive?.contentOrNull,
                    deviceFolder = obj["fol"]?.jsonPrimitive?.contentOrNull,
                    description = obj["desc"]?.jsonPrimitive?.contentOrNull,
                    tags = obj.stringList("tags"),
                    isDateUserSet = obj["dus"]?.jsonPrimitive?.booleanStrictOrNull ?: false,
                    locationName = obj["locn"]?.jsonPrimitive?.contentOrNull,
                    aiLabels = obj.stringList("ail"),
                    status = MediaStatus.fromIndex(obj["st"]?.jsonPrimitive?.intOrNull ?: 0),
                    fileName = obj["fn"]?.jsonPrimitive?.contentOrNull,
                    supersededMessageIds = obj.stringList("smids"),
                )
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * The content digest of a set of items — the basis of every dirty check
         * and of the manifest's chunk hashes, so this must be byte-identical to
         * the Dart `hashItems` or the two implementations can never agree.
         *
         * 18 fields per record joined by [FIELD_SEPARATOR] (0x01); records are
         * sorted (as their joined strings, code-unit order) and joined by
         * [RECORD_SEPARATOR] (0x02); then sha256 → lowercase hex.
         *
         * The separators are literal control characters that render as blank in
         * most editors. They must be control characters: they cannot occur in a
         * file hash, an ISO-8601 timestamp, or a flag digit, so joining with an
         * empty string would let field boundaries shift and two different items
         * could serialize to identical bytes.
         *
         * Deliberately excluded: telegramMessageId, telegramFileId,
         * supersededMessageIds, backedUpAt, status. These are set on upload
         * completion; including them would mark a freshly-synced partition
         * dirty again and force a redundant second re-upload.
         */
        fun hashItems(items: List<PartitionItem>): String {
            val records = items.map { item ->
                listOf(
                    item.fileHash,
                    Timestamps.format(item.createdAt),
                    Timestamps.format(item.modifiedAt),
                    if (item.isDateUserSet) "1" else "0",
                    if (item.isFavorite) "1" else "0",
                    if (item.isHidden) "1" else "0",
                    if (item.isArchived) "1" else "0",
                    if (item.isTrashed) "1" else "0",
                    item.trashedAt?.let(Timestamps::format) ?: "",
                    if (item.isDeleted) "1" else "0",
                    item.deletedAt?.let(Timestamps::format) ?: "",
                    item.albumName ?: "",
                    item.deviceFolder ?: "",
                    item.description ?: "",
                    item.fileName ?: "",
                    item.tags.sorted().joinToString(","),
                    item.locationName ?: "",
                    item.aiLabels.sorted().joinToString(","),
                ).joinToString(FIELD_SEPARATOR.toString())
            }.sorted()

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(records.joinToString(RECORD_SEPARATOR.toString()).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** Two items are identical iff their content digests match. */
        fun areIdentical(a: PartitionItem, b: PartitionItem): Boolean =
            a.localId == b.localId && hashItems(listOf(a)) == hashItems(listOf(b))

        const val FIELD_SEPARATOR: Char = '\u0001'
        const val RECORD_SEPARATOR: Char = '\u0002'
    }
}

private fun JsonObject.stringList(key: String): List<String> {
    val arr = this[key]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
}

/** Content of a primitive, or null if the element is missing or not a primitive. */
private val kotlinx.serialization.json.JsonElement.contentOrNull: String?
    get() = (this as? JsonPrimitive)?.content
