package com.lumovault.lumovault.features.gallery.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the search screen.
 *
 * Ported from lib/features/gallery/presentation/screens/search_screen.dart,
 * keyword mode only. The original's semantic (CLIP) and people/location/tag
 * filter chips depend on the embedding and background-scan engines, which do
 * not exist in the Kotlin app yet — this ViewModel therefore talks to
 * [GalleryRepository.search] (file name + description) and shows stored AI
 * labels on results, without promising label search.
 *
 * The query is debounced before hitting Room, as the original debounced its
 * embedding runs per keystroke — same concern, cheaper backend.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: GalleryRepository,
    val settings: StateFlow<AppSettings>,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val results: StateFlow<List<MediaItemEntity>> = _query
        .debounce(DEBOUNCE_MS)
        .flatMapLatest { q ->
            flow {
                val trimmed = q.trim()
                emit(if (trimmed.isEmpty()) emptyList() else repository.search(trimmed))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun clear() {
        _query.value = ""
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
