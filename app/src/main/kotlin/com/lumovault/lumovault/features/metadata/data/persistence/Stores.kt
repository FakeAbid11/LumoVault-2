package com.lumovault.lumovault.features.metadata.data.persistence

import com.lumovault.lumovault.features.metadata.domain.model.Manifest
import com.lumovault.lumovault.features.metadata.domain.model.MetadataPartition
import com.lumovault.lumovault.features.metadata.domain.model.SyncLogEntity

/**
 * Storage contracts, kept as interfaces so the services can be constructed and
 * tested without any filesystem. An in-memory or no-op store is a legitimate
 * implementation — the services must stay correct with one, not just with the
 * file-backed default.
 */
interface PartitionStore {
    fun load(): List<MetadataPartition>
    fun save(partitions: List<MetadataPartition>)
    fun clear()
}

interface ManifestStore {
    fun load(): Manifest?
    fun save(manifest: Manifest)
    fun clear()
}

interface SyncLogStore {
    fun load(): List<SyncLogEntity>
    fun save(entries: List<SyncLogEntity>)
    fun clear()
}

/** In-memory store, for tests and headless construction. */
class InMemoryPartitionStore : PartitionStore {
    private var partitions: List<MetadataPartition> = emptyList()
    override fun load(): List<MetadataPartition> = partitions
    override fun save(partitions: List<MetadataPartition>) { this.partitions = partitions }
    override fun clear() { partitions = emptyList() }
}

class InMemoryManifestStore : ManifestStore {
    @Volatile private var manifest: Manifest? = null
    override fun load(): Manifest? = manifest
    override fun save(manifest: Manifest) { this.manifest = manifest }
    override fun clear() { manifest = null }
}

class InMemorySyncLogStore : SyncLogStore {
    private val entries = mutableListOf<SyncLogEntity>()
    override fun load(): List<SyncLogEntity> = entries.toList()
    override fun save(entries: List<SyncLogEntity>) {
        this.entries.clear()
        this.entries.addAll(entries)
    }
    override fun clear() { entries.clear() }
}
