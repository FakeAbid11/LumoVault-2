package com.lumovault.lumovault.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the application-scoped [CoroutineScope].
 *
 * Every long-lived service (TdLib client, connection manager, metadata engine)
 * takes an optional scope and falls back to a private one when none is given.
 * Injecting this shared scope instead keeps their coroutines children of one
 * supervisor, so a failure in one service cannot cancel another's, and so
 * `LumoVaultApp` has a single scope to tear down.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
