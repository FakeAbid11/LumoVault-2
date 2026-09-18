package com.lumovault.lumovault.core.di

import android.content.Context
import com.lumovault.lumovault.BuildConfig
import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.core.tdlib.TdLibConfig
import com.lumovault.lumovault.core.tdlib.TdLibConnectionManager
import com.lumovault.lumovault.core.tdlib.TdLibKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Wires the TDLib substrate that was already ported and unit-tested (51 tests)
 * but had no provider, so it could only ever be constructed by tests.
 *
 * Credentials come from BuildConfig, which is populated from
 * `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` Gradle properties. Those must be set
 * in an untracked file — `~/.gradle/gradle.properties` or CI secrets — never in
 * the repo's own `gradle.properties`, which is tracked. Until they are set,
 * [TdLibConfig.hasCredentials] is false and the client fails with a clear
 * `API_ID_INVALID` instead of an obscure TDLib error.
 */
@Module
@InstallIn(SingletonComponent::class)
object TdLibModule {

    @Provides
    @Singleton
    fun provideTdLibConfig(): TdLibConfig = TdLibConfig(
        apiId = BuildConfig.TELEGRAM_API_ID.toIntOrNull() ?: 0,
        apiHash = BuildConfig.TELEGRAM_API_HASH,
        appVersion = BuildConfig.VERSION_NAME,
    )

    @Provides
    @Singleton
    fun provideTdLibKeyStore(
        @ApplicationContext context: Context,
    ): TdLibKeyStore = TdLibKeyStore(context)

    @Provides
    @Singleton
    fun provideTdLibClient(
        @ApplicationContext context: Context,
        config: TdLibConfig,
        keyStore: TdLibKeyStore,
        @ApplicationScope scope: CoroutineScope,
    ): TdLibClient = TdLibClient(context, config, keyStore, scope)

    @Provides
    @Singleton
    fun provideTdLibConnectionManager(
        client: TdLibClient,
        @ApplicationScope scope: CoroutineScope,
    ): TdLibConnectionManager = TdLibConnectionManager(client, scope)
}
