package com.lumovault.lumovault.features.gallery.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the photo map.
 *
 * Ported from the `mapPhotosProvider` half of
 * lib/features/gallery/presentation/screens/map_screen.dart: the located
 * subset of the timeline flow. Items without GPS never get a marker, and the
 * filter runs on the Room flow so a newly scanned located photo appears
 * without a manual refresh.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    repository: GalleryRepository,
) : ViewModel() {

    /** Timeline items carrying usable coordinates. */
    val located: StateFlow<List<MediaItemEntity>> = repository.timeline
        .map { items -> items.filter { it.latitude != null && it.longitude != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
