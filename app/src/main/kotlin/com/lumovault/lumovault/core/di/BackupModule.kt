package com.lumovault.lumovault.core.di

import com.lumovault.lumovault.features.backup.domain.UploadQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the backup domain. [UploadQueue] is the TreeSet min-heap with O(1)
 * secondary indexes that the future backup engine will drain; it is scoped
 * singleton because the queue is the shared truth for pending uploads across
 * the dashboard UI, the engine, and the worker.
 *
 * [com.lumovault.lumovault.features.backup.domain.UploadPriorityCalculator]
 * is a stateless `object` and is used directly, so it needs no provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    @Singleton
    fun provideUploadQueue(): UploadQueue = UploadQueue()
}
