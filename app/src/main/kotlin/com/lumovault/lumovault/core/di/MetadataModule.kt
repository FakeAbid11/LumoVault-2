package com.lumovault.lumovault.core.di

import android.content.Context
import com.lumovault.lumovault.features.metadata.data.persistence.ManifestFileStore
import com.lumovault.lumovault.features.metadata.data.persistence.ManifestStore
import com.lumovault.lumovault.features.metadata.data.persistence.PartitionFileStore
import com.lumovault.lumovault.features.metadata.data.persistence.PartitionStore
import com.lumovault.lumovault.features.metadata.data.persistence.SyncLogFileStore
import com.lumovault.lumovault.features.metadata.data.persistence.SyncLogStore
import com.lumovault.lumovault.features.metadata.data.service.ConflictResolver
import com.lumovault.lumovault.features.metadata.data.service.ManifestService
import com.lumovault.lumovault.features.metadata.data.service.MetadataRepository
import com.lumovault.lumovault.features.metadata.data.service.MigrationService
import com.lumovault.lumovault.features.metadata.data.service.PartitionService
import com.lumovault.lumovault.features.metadata.data.service.SyncService
import com.lumovault.lumovault.features.metadata.domain.AndroidDeviceHashProvider
import com.lumovault.lumovault.features.metadata.domain.DeviceHashProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Wires the metadata sync engine (partitions, manifest, conflict resolution —
 * 50 unit tests) into the app graph.
 *
 * The store bindings are interface-to-implementation so the services stay
 * constructible with the in-memory stores in tests, exactly as they are today.
 */
@Module
@InstallIn(SingletonComponent::class)
object MetadataModule {

    @Provides
    @Singleton
    fun providePartitionStore(
        @ApplicationContext context: Context,
    ): PartitionStore = PartitionFileStore(context)

    @Provides
    @Singleton
    fun provideManifestStore(
        @ApplicationContext context: Context,
    ): ManifestStore = ManifestFileStore(context)

    @Provides
    @Singleton
    fun provideSyncLogStore(
        @ApplicationContext context: Context,
    ): SyncLogStore = SyncLogFileStore(context)

    @Provides
    @Singleton
    fun provideDeviceHashProvider(): DeviceHashProvider = AndroidDeviceHashProvider()

    @Provides
    @Singleton
    fun providePartitionService(
        store: PartitionStore,
        @ApplicationScope scope: CoroutineScope,
    ): PartitionService = PartitionService(store, scope)

    @Provides
    @Singleton
    fun provideManifestService(
        store: ManifestStore,
        @ApplicationScope scope: CoroutineScope,
    ): ManifestService = ManifestService(store, scope)

    @Provides
    @Singleton
    fun provideSyncService(
        partitionService: PartitionService,
        manifestService: ManifestService,
        store: SyncLogStore,
        deviceHashProvider: DeviceHashProvider,
        @ApplicationScope scope: CoroutineScope,
    ): SyncService = SyncService(
        partitionService = partitionService,
        manifestService = manifestService,
        store = store,
        deviceHashProvider = deviceHashProvider,
        coroutineScope = scope,
    )

    @Provides
    @Singleton
    fun provideConflictResolver(): ConflictResolver = ConflictResolver()

    @Provides
    @Singleton
    fun provideMetadataRepository(
        partitionService: PartitionService,
        manifestService: ManifestService,
        syncService: SyncService,
    ): MetadataRepository = MetadataRepository(
        partitionService = partitionService,
        manifestService = manifestService,
        syncService = syncService,
    )

    @Provides
    @Singleton
    fun provideMigrationService(): MigrationService = MigrationService()
}
