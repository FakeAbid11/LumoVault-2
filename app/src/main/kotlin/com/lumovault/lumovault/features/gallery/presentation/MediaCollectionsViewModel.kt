package com.lumovault.lumovault.features.gallery.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads the four flag-filtered collections (favorites, trash, hidden, archive).
 *
 * Each is a Room flow, so a change anywhere in the pipeline reaches the screen
 * without a manual refresh. Mutations are fire-and-forget from the UI's
 * perspective: the flow re-emits, which is the confirmation the user sees.
 */
@HiltViewModel
class MediaCollectionsViewModel @Inject constructor(
    private val repository: GalleryRepository,
    val settings: StateFlow<AppSettings>,
) : ViewModel() {

    val favorites: StateFlow<List<MediaItemEntity>> = repository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashed: StateFlow<List<MediaItemEntity>> = repository.trashed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hidden: StateFlow<List<MediaItemEntity>> = repository.hidden
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val archived: StateFlow<List<MediaItemEntity>> = repository.archived
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFavorite(localId: String, favorite: Boolean) = viewModelScope.launch {
        repository.setFavorite(localId, favorite)
    }

    fun moveToTrash(localId: String) = viewModelScope.launch {
        repository.moveToTrash(localId)
    }

    fun restoreFromTrash(localId: String) = viewModelScope.launch {
        repository.restoreFromTrash(localId)
    }

    fun deletePermanently(localId: String) = viewModelScope.launch {
        repository.deletePermanently(localId)
    }

    fun setHidden(localId: String, hidden: Boolean) = viewModelScope.launch {
        repository.setHidden(localId, hidden)
    }

    fun setArchived(localId: String, archived: Boolean) = viewModelScope.launch {
        repository.setArchived(localId, archived)
    }
}
