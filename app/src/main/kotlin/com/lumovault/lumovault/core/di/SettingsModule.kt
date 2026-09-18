package com.lumovault.lumovault.core.di

import android.content.Context
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository = SettingsRepository(context)

    /**
     * The reactive view of settings.
     *
     * Ported from the Riverpod `appSettingsProvider` / `SettingsNotifier` pair:
     * a single source of truth that loads once at construction and then follows
     * the repository's change flow, so a write from a background worker reaches
     * every screen. The original subscribed in `_init` for exactly this reason.
     */
    @Provides
    @Singleton
    fun provideSettingsStateFlow(
        repository: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): StateFlow<AppSettings> {
        val flow = MutableStateFlow(repository.load())
        scope.launch {
            repository.changes.collect { flow.value = it }
        }
        return flow.asStateFlow()
    }
}
