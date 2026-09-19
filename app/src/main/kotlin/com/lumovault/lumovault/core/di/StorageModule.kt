package com.lumovault.lumovault.core.di

import android.content.Context
import com.lumovault.lumovault.core.diagnostics.DiagnosticsService
import com.lumovault.lumovault.core.logging.AppLogger
import com.lumovault.lumovault.core.notifications.NotificationService
import com.lumovault.lumovault.core.storage.ThumbnailCache
import com.lumovault.lumovault.core.storage.ThumbnailWarmup
import com.lumovault.lumovault.core.storage.TransferQueuePersistence
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.features.gallery.data.service.GallerySaveService
import com.lumovault.lumovault.features.gallery.data.service.GeocodingService
import com.lumovault.lumovault.features.gallery.data.service.IncrementalScanner
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires storage, notification, logging, diagnostics, and gallery services
 * that were not part of the original Phase 1-3 DI graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideThumbnailCache(
        @ApplicationContext context: Context,
    ): ThumbnailCache = ThumbnailCache(context)

    @Provides
    @Singleton
    fun provideThumbnailWarmup(
        @ApplicationContext context: Context,
        cache: ThumbnailCache,
    ): ThumbnailWarmup = ThumbnailWarmup(context, cache)

    @Provides
    @Singleton
    fun provideTransferQueuePersistence(
        @ApplicationContext context: Context,
    ): TransferQueuePersistence = TransferQueuePersistence(context)

    @Provides
    @Singleton
    fun provideNotificationService(
        @ApplicationContext context: Context,
    ): NotificationService = NotificationService(context)

    @Provides
    @Singleton
    fun provideGeocodingService(
        @ApplicationContext context: Context,
    ): GeocodingService = GeocodingService(context)

    @Provides
    @Singleton
    fun provideGallerySaveService(
        @ApplicationContext context: Context,
    ): GallerySaveService = GallerySaveService(context)

    @Provides
    @Singleton
    fun provideIncrementalScanner(
        @ApplicationContext context: Context,
        scanner: MediaScannerService,
    ): IncrementalScanner = IncrementalScanner(context, scanner)

    @Provides
    @Singleton
    fun provideDiagnosticsService(
        @ApplicationContext context: Context,
        mediaDao: MediaDao,
        thumbnailCache: ThumbnailCache,
    ): DiagnosticsService = DiagnosticsService(context, mediaDao, thumbnailCache)

    @Provides
    @Singleton
    fun provideAppLogger(): AppLogger {
        AppLogger.install()
        return AppLogger
    }
}
