package com.lumovault.lumovault.features.gallery.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Duplicate groups, and the actions that resolve them.
 *
 * Kept apart from [MediaCollectionsViewModel] because a duplicate group is not
 * a Room flag the database can stream: it is a computed query over content
 * hashes, so this view model owns the refresh cycle. Screens call [refresh]
 * after any mutation and the groups list rebuilds from the new table state.
 *
 * Ported from `duplicateGroupsProvider` + the `_DuplicateScreen` state in
 * duplicates_screen.dart. Resolution is always deletion of the extras — there
 * is no merge — and the two named shortcuts rely on the repository's
 * newest-first group ordering.
 */
@HiltViewModel
class DuplicateViewModel @Inject constructor(
    private val repository: GalleryRepository,
) : ViewModel() {

    private val _groups = MutableStateFlow<List<List<MediaItemEntity>>>(emptyList())
    val groups: StateFlow<List<List<MediaItemEntity>>> = _groups.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _groups.value = repository.duplicateGroups()
    }

    /**
     * Delete every item in [group] except the first, which the repository
     * guarantees is the newest. Mirrors the original's `items.skip(1)`.
     */
    fun keepNewest(group: List<MediaItemEntity>) = deleteExtras(group) { it.drop(1) }

    /** Delete every item except the last — the original's `items.reversed.skip(1)`. */
    fun keepOldest(group: List<MediaItemEntity>) = deleteExtras(group) { it.dropLast(1) }

    /**
     * Delete an arbitrary selection outright. Used by the manual path, where
     * the user picked exactly which copies to discard.
     */
    fun deleteSelected(localIds: Collection<String>) = viewModelScope.launch {
        repository.deletePermanently(localIds.toList())
        refresh()
    }

    private fun deleteExtras(
        group: List<MediaItemEntity>,
        extras: (List<MediaItemEntity>) -> List<MediaItemEntity>,
    ) = viewModelScope.launch {
        if (group.size < 2) return@launch
        repository.deletePermanently(extras(group).map { it.localId })
        refresh()
    }
}
