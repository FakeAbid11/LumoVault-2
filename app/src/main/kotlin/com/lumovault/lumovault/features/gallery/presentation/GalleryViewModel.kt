package com.lumovault.lumovault.features.gallery.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: GalleryRepository,
) : ViewModel() {

    val timeline: StateFlow<List<MediaItemEntity>> = repository.timeline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress.asStateFlow()

    fun refreshFromDevice() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0
            runCatching {
                repository.refreshFromDevice { loaded -> _scanProgress.value = loaded }
            }
            _isScanning.value = false
        }
    }

    fun toggleFavorite(item: MediaItemEntity) {
        viewModelScope.launch {
            repository.setFavorite(item.localId, !item.isFavorite)
        }
    }

    fun moveToTrash(item: MediaItemEntity) {
        viewModelScope.launch { repository.moveToTrash(item.localId) }
    }
}
