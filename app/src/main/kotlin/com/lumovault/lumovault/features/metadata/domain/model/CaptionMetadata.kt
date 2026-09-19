package com.lumovault.lumovault.features.metadata.domain.model

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.lumovault.lumovault.features.metadata.data.util.Timestamps
import com.lumovault.lumovault.features.metadata.data.util.strictBooleanOrNull

/**
 * The per-media caption carried by every uploaded file.
 *
 * Ported from lib/features/gallery/data/models/caption_metadata.dart. This is
 * the other half of the restore contract: the manifest describes *months*, this
 * describes one file. A second device rebuilds albums, flags and descriptions
 * by parsing these captions, so the short keys below are a wire format and must
 * not be renamed.
 *
 * **Deliberate difference from the Dart version.** Dart's `fromCaptionString`
 * caught every error and returned a blank `CaptionMetadata` (empty ids), so
 * callers had to remember to test `mediaItemId.isEmpty()` to tell "not our
 * caption" apart from "corrupt caption". Here the parse returns null unless the
 * document carries both a version tag and a non-empty media id, so that check
 * cannot be forgotten. Field defaults still absorb a *missing* optional field,
 * which is what makes the format additive.
 */
data class CaptionMetadata(
    /** Schema version; `"1"` is the only version written so far. */
    val version: String = CURRENT_VERSION,
    val mediaItemId: String,
    val fileHash: String,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val backedUpAt: Instant,
    val mimeType: String? = null,
    val fileSize: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Int? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Instant? = null,
    val albumName: String? = null,
    val deviceFolder: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val isDateUserSet: Boolean = false,
    /** Forward-compatibility bag; written as `x`, never interpreted here. */
    val customFields: JsonObject? = null,
) {

    /**
     * Serializes to the caption string.
     *
     * Optional fields are omitted rather than written as null/false, matching
     * the original: a caption stays small, and an absent key and a
     * false-valued key parse identically.
     */
    fun toCaptionString(): String {
        val obj = buildJsonObject {
            put("v", version)
            put("mid", mediaItemId)
            put("h", fileHash)
            put("ct", Timestamps.format(createdAt))
            put("mod", Timestamps.format(modifiedAt))
            put("bu", Timestamps.format(backedUpAt))
            mimeType?.let { put("mime", it) }
            if (fileSize > 0) put("sz", fileSize)
            if (width > 0) put("w", width)
            if (height > 0) put("ht", height)
            durationMs?.let { put("d", it) }
            if (isFavorite) put("fav", true)
            if (isHidden) put("hid", true)
            if (isArchived) put("arc", true)
            if (isTrashed) put("trash", true)
            trashedAt?.let { put("trasht", Timestamps.format(it)) }
            albumName?.let { put("alb", it) }
            deviceFolder?.let { put("fol", it) }
            description?.let { put("desc", it) }
            if (tags.isNotEmpty()) putJsonArray("tags") { tags.forEach { add(it) } }
            if (isDateUserSet) put("dus", true)
            customFields?.takeIf { it.isNotEmpty() }?.let { put("x", it) }
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        const val CURRENT_VERSION = "1"

        /**
         * Parses a caption, or null when [text] is not a LumoVault caption.
         *
         * Null covers a non-JSON caption, a caption with no version, or one
         * with no media id. Every one of those means "this is not ours", and
         * treating it as a catalog entry would import a file we cannot address.
         */
        fun fromCaptionString(text: String): CaptionMetadata? {
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: Throwable) {
                null
            } ?: return null
            return fromJson(obj)
        }

        fun fromJson(obj: JsonObject): CaptionMetadata? {
            val version = obj.str("v") ?: return null
            val mediaItemId = obj.str("mid") ?: return null

            // The creation timestamp is the one field we cannot invent: a
            // partition key is derived from it, so an unparseable value would
            // silently file the item in the wrong month.
            val created = Timestamps.parseOrNull(obj.str("ct")) ?: return null

            return CaptionMetadata(
                version = version,
                mediaItemId = mediaItemId,
                fileHash = obj.str("h").orEmpty(),
                createdAt = created,
                modifiedAt = Timestamps.parseOrNull(obj.str("mod")) ?: created,
                backedUpAt = Timestamps.parseOrNull(obj.str("bu")) ?: created,
                mimeType = obj.str("mime"),
                fileSize = obj.num("sz")?.toLong() ?: 0L,
                width = obj.num("w")?.toInt() ?: 0,
                height = obj.num("ht")?.toInt() ?: 0,
                durationMs = obj.num("d")?.toInt(),
                isFavorite = obj.bool("fav") ?: false,
                isHidden = obj.bool("hid") ?: false,
                isArchived = obj.bool("arc") ?: false,
                isTrashed = obj.bool("trash") ?: false,
                trashedAt = Timestamps.parseOrNull(obj.str("trasht")),
                albumName = obj.str("alb"),
                deviceFolder = obj.str("fol"),
                description = obj.str("desc"),
                tags = (obj["tags"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
                isDateUserSet = obj.bool("dus") ?: false,
                customFields = obj["x"] as? JsonObject,
            )
        }
    }
}

/** Reads a non-null, non-empty string, tolerating a JsonNull. */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** Reads a number as a string-free numeric; JSON has one number type. */
private fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.strictBooleanOrNull
