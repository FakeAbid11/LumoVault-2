package com.lumovault.lumovault.features.gallery.presentation

import androidx.lifecycle.ViewModel
import com.lumovault.lumovault.features.gallery.data.service.GallerySaveService
import com.lumovault.lumovault.features.gallery.data.service.TelegramDownloadService
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TelegramMediaViewModel @Inject constructor(
    val downloader: TelegramDownloadService,
    val gallerySaveService: GallerySaveService,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val storageChannelId: Long
        get() = settingsRepository.load().storageChannelId ?: 0L
}
