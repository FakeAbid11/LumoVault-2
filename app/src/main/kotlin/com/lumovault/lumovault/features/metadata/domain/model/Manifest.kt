package com.lumovault.lumovault.features.metadata.domain.model

import com.lumovault.lumovault.features.metadata.data.util.Timestamps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The library manifest: the table of contents uploaded to the storage channel.
 * Lists every partition as a chunk id + item count + content hash so a reader
 * can diff against its own partitions and pull only what differs.
 *
 * Ported from lib/features/metadata/data/models/manifest.dart.
 */
data class ManifestChunk(
    /** Partition key `YYYY/MM`. */
    val id: String,
    val count: Int,
    /** sha256 of the partition's items, lowercase hex. */
    val hash: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("count", count)
        put("hash", hash)
    }

    companion object {
        fun fromJson(obj: JsonObject): ManifestChunk? = try {
            ManifestChunk(
                id = obj["id"]?.jsonPrimitive?.content ?: return null,
                count = obj["count"]?.jsonPrimitive?.intOrNull ?: 0,
                hash = obj["hash"]?.jsonPrimitive?.content ?: return null,
            )
        } catch (_: Throwable) {
            null
        }
    }
}

data class Manifest(
    val created: Instant,
    val deviceHash: String,
    val totalMedia: Long,
    val totalSizeBytes: Long,
    val lastSync: Instant?,
    val chunks: List<ManifestChunk>,
    /** Format version. v1 predates per-item tombstones. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    fun toJsonString(): String {
        val obj = buildJsonObject {
            put("app", APP_NAME)
            put("schema_version", schemaVersion)
            put("created", Timestamps.format(created))
            put("device_hash", deviceHash)
            put("total_media", totalMedia)
            put("total_size_bytes", totalSizeBytes)
            lastSync?.let { put("last_sync", Timestamps.format(it)) }
            put("chunks", buildJsonArray {
                // Chunks are always sorted by id so two manifests describing the
                // same library serialize identically.
                chunks.sortedBy { it.id }.forEach { add(it.toJson()) }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    /** A v1 manifest is readable by a v2 app: the format is additive. */
    fun isCompatibleWith(target: Int): Boolean = schemaVersion <= target

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val APP_NAME = "lumovault"

        /**
         * Parses a manifest, or null if it is not one. A missing
         * `schema_version` means v1 — the field was not always present.
         */
        fun fromJsonString(text: String): Manifest? = try {
            fromJson(Json.parseToJsonElement(text).jsonObject)
        } catch (_: Throwable) {
            null
        }

        fun fromJson(obj: JsonObject): Manifest? = try {
            val app = obj["app"]?.jsonPrimitive?.content
            if (app != null && app != APP_NAME) return null
            val chunks = (obj["chunks"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(ManifestChunk::fromJson) }
                .orEmpty()
            Manifest(
                schemaVersion = obj["schema_version"]?.jsonPrimitive?.intOrNull ?: 1,
                created = Timestamps.parseOrNull(obj["created"]?.jsonPrimitive?.content)
                    ?: return null,
                deviceHash = obj["device_hash"]?.jsonPrimitive?.content ?: return null,
                totalMedia = obj["total_media"]?.jsonPrimitive?.longOrNull ?: 0L,
                totalSizeBytes = obj["total_size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                lastSync = Timestamps.parseOrNull(obj["last_sync"]?.jsonPrimitive?.content),
                chunks = chunks,
            )
        } catch (_: Throwable) {
            null
        }
    }
}
