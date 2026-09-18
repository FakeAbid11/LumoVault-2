package com.lumovault.lumovault.core.di

import android.content.Context
import com.lumovault.lumovault.core.ml.OnnxModelHost
import com.lumovault.lumovault.features.gallery.data.service.ClipEmbeddingService
import com.lumovault.lumovault.features.gallery.data.service.ImageClassifierService
import com.lumovault.lumovault.features.people.data.service.FaceClusteringService
import com.lumovault.lumovault.features.people.data.service.FaceDetectionService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the on-device ML layer.
 *
 * All five ONNX models ship in the APK (`app/src/main/assets/models`) and are
 * the same files the Flutter app uses — no re-export, no substitution. Sessions
 * load lazily on first use rather than at construction, so a device that never
 * opens People or Search never pays the ~151 MB model-parse cost or loads
 * `libonnxruntime.so`.
 *
 * No service here takes a coroutine scope: the Dart original spread this work
 * across isolates (a worker isolate for face preprocessing, `compute()` for
 * CLIP and classifier preprocessing), and in Kotlin every entry point is
 * `suspend`, so the caller's dispatcher plays that role.
 */
@Module
@InstallIn(SingletonComponent::class)
object MlModule {

    @Provides
    @Singleton
    fun provideOnnxModelHost(
        @ApplicationContext context: Context,
    ): OnnxModelHost = OnnxModelHost(context)

    @Provides
    @Singleton
    fun provideFaceDetectionService(
        @ApplicationContext context: Context,
        modelHost: OnnxModelHost,
    ): FaceDetectionService = FaceDetectionService(context, modelHost)

    @Provides
    @Singleton
    fun provideClipEmbeddingService(
        @ApplicationContext context: Context,
        modelHost: OnnxModelHost,
    ): ClipEmbeddingService = ClipEmbeddingService(context, modelHost)

    @Provides
    @Singleton
    fun provideImageClassifierService(
        @ApplicationContext context: Context,
        modelHost: OnnxModelHost,
    ): ImageClassifierService = ImageClassifierService(context, modelHost)
}
