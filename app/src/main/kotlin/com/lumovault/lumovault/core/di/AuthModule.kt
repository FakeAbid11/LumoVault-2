package com.lumovault.lumovault.core.di

import com.lumovault.lumovault.core.auth.AuthService
import com.lumovault.lumovault.core.auth.TelegramAuthRepository
import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.core.tdlib.TdLibConnectionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Binds [TelegramAuthRepository] as the app's [AuthService].
 *
 * The `ensureConnected` lambda is the seam that used to be filled by
 * `ensureTdLibConnected` in the Flutter container: authentication needs a live
 * client, so it connects through the connection manager rather than reaching
 * into the client directly. That layering matters — the manager owns the
 * reconnect ladder and the heartbeat, and bypassing it would leave auth
 * failures looking like auth problems when the transport had actually died.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideAuthService(
        client: TdLibClient,
        connectionManager: TdLibConnectionManager,
        @ApplicationScope scope: CoroutineScope,
    ): AuthService = TelegramAuthRepository(
        client = client,
        ensureConnected = { connectionManager.connect() },
        coroutineScope = scope,
    )
}
