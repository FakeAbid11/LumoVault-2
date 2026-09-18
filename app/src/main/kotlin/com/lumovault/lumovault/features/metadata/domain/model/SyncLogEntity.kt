package com.lumovault.lumovault.features.metadata.domain.model

import com.lumovault.lumovault.features.metadata.data.util.Timestamps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanStrictOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * One sync operation in the diagnostics log. Ported from
 * lib/features/metadata/data/models/sync_log_entity.dart.
 */
data class SyncLogEntity(
    val id: Long? = null,
    val mediaItemId: String,
    val operation: String,
    val timestamp: Instant,
    val details: String? = null,
    val success: Boolean = true,
    val error: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        put("mediaItemId", mediaItemId)
        put("operation", operation)
        put("timestamp", Timestamps.format(timestamp))
        details?.let { put("details", it) }
        put("success", success)
        error?.let { put("error", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): SyncLogEntity? = try {
            SyncLogEntity(
                id = obj["id"]?.jsonPrimitive?.longOrNull,
                mediaItemId = obj["mediaItemId"]?.jsonPrimitive?.content ?: "",
                operation = obj["operation"]?.jsonPrimitive?.content ?: "",
                timestamp = Timestamps.parseOrNull(obj["timestamp"]?.jsonPrimitive?.content)
                    ?: Instant.now(),
                details = obj["details"]?.jsonPrimitive?.content,
                success = obj["success"]?.jsonPrimitive?.booleanStrictOrNull ?: true,
                error = obj["error"]?.jsonPrimitive?.content,
            )
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * One queued local change awaiting a debounced flush. Ported from
 * SyncService.SyncChange.
 */
data class SyncChange(
    val mediaItemId: String,
    val operation: String,
    val timestamp: Instant = Instant.now(),
)
