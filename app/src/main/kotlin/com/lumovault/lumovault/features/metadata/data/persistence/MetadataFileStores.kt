package com.lumovault.lumovault.features.metadata.data.persistence

import android.content.Context
import com.lumovault.lumovault.features.metadata.data.util.Timestamps
import com.lumovault.lumovault.features.metadata.domain.model.Manifest
import com.lumovault.lumovault.features.metadata.domain.model.MetadataPartition
import com.lumovault.lumovault.features.metadata.domain.model.SyncLogEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putAll
import java.io.File

/**
 * On-disk stores for the three small pieces of sync state: the partition set,
 * the manifest, and the sync log. Each is a JSON file written via temp-file +
 * rename so a crash mid-write leaves the previous file intact instead of a
 * truncated document that the loader then silently discards.
 *
 * Ported from partition_persistence.dart / manifest_persistence.dart /
 * sync_log_persistence.dart. All three use envelope `version: 1`; a file with a
 * lower version is treated as absent rather than parsed.
 */
private const val ENVELOPE_VERSION = 1

abstract class MetadataFileStore(protected val context: Context, private val fileName: String) {

    protected val file: File by lazy { File(context.filesDir, fileName) }

    protected fun readEnvelope(): JsonObject? = try {
        if (!file.exists()) return null
        val parsed = Json.parseToJsonElement(file.readText()).jsonObject
        val version = parsed["version"]?.jsonPrimitive?.intOrNull ?: 0
        if (version < ENVELOPE_VERSION) null else parsed
    } catch (_: Throwable) {
        null
    }

    /** Write [payload] atomically: temp file, flush, then rename. */
    protected fun writeEnvelope(payload: JsonObject) {
        val envelope = buildJsonObject {
            put("version", ENVELOPE_VERSION)
            put("savedAt", Timestamps.format(java.time.Instant.now()))
            putAll(payload)
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeText(Json.encodeToString(JsonObject.serializer(), envelope))
            // renameTo is atomic on the same filesystem, which both paths share.
            if (!temp.renameTo(file)) {
                temp.delete()
            }
        } catch (_: Throwable) {
            temp.delete()
        }
    }

    protected fun clearFile() {
        try {
            if (file.exists()) file.delete()
        } catch (_: Throwable) {
            // Non-critical: a stale file is re-read and replaced on next save.
        }
    }
}

/**
 * The partition set — layer 1's source of truth across restarts.
 * `<files>/metadata_partitions.json`
 */
class PartitionFileStore(context: Context) : MetadataFileStore(context, "metadata_partitions.json"), PartitionStore {

    override fun load(): List<MetadataPartition> {
        val envelope = readEnvelope() ?: return emptyList()
        return try {
            (envelope["partitions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(MetadataPartition::fromJson) }
                .orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun save(partitions: List<MetadataPartition>) {
        writeEnvelope(buildJsonObject {
            put("partitions", buildJsonArray {
                partitions.forEach { add(it.toJson()) }
            })
        })
    }

    override fun clear() = clearFile()
}

/**
 * `<files>/metadata_manifest.json`
 */
class ManifestFileStore(context: Context) : MetadataFileStore(context, "metadata_manifest.json"), ManifestStore {

    override fun load(): Manifest? {
        val envelope = readEnvelope() ?: return null
        return try {
            (envelope["manifest"] as? JsonObject)?.let(Manifest::fromJson)
        } catch (_: Throwable) {
            null
        }
    }

    override fun save(manifest: Manifest) {
        // The manifest is stored as an already-serialized string so the exact
        // bytes that go to the channel are the bytes on disk.
        writeEnvelope(buildJsonObject {
            put("manifest", Json.parseToJsonElement(manifest.toJsonString()))
        })
    }

    override fun clear() = clearFile()
}

/**
 * `<files>/sync_log.json`. Trimmed to the newest [MAX_ENTRIES] on every write.
 */
class SyncLogFileStore(context: Context) : MetadataFileStore(context, "sync_log.json"), SyncLogStore {

    override fun load(): List<SyncLogEntity> {
        val envelope = readEnvelope() ?: return emptyList()
        return try {
            (envelope["entries"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(SyncLogEntity::fromJson) }
                .orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun save(entries: List<SyncLogEntity>) {
        val trimmed = entries.takeLast(MAX_ENTRIES)
        writeEnvelope(buildJsonObject {
            put("entries", buildJsonArray {
                trimmed.forEach { add(it.toJson()) }
            })
        })
    }

    override fun clear() = clearFile()

    private companion object {
        const val MAX_ENTRIES = 1000
    }
}
