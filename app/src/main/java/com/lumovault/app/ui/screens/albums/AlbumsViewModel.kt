package com.lumovault.app.ui.screens.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.organization.Album
import com.lumovault.app.domain.organization.SystemAlbumCounts
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the Albums screen shows, as one value.
 *
 * System albums are always all eight, whatever their counts: a grid whose tiles appear and disappear as
 * the library changes moves under the user's finger, and "Screenshots · 0" is both true and stable.
 */
data class AlbumsUiState(
    val counts: SystemAlbumCounts = SystemAlbumCounts(emptyMap()),
    val userAlbums: List<Album> = emptyList(),
)

/**
 * The Albums screen's reads and the two writes it can make.
 *
 * It holds no album logic — [com.lumovault.app.domain.organization.AlbumRepository] decides what a valid
 * name is and what deleting an album touches — and no remembered copy of either list. Both flows come out
 * of Room, so a folder that appeared while the screen was open is there on the next frame without
 * anything here being told about it.
 */
class AlbumsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    private val counts = container.mediaOrganizationRepository.observeCounts()
        .stateIn(viewModelScope, STOP_POLICY, SystemAlbumCounts(emptyMap()))

    private val userAlbums = container.albumRepository.observeAlbums()
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    val uiState: StateFlow<AlbumsUiState> = combine(counts, userAlbums) { current, albums ->
        AlbumsUiState(counts = current, userAlbums = albums)
    }.stateIn(viewModelScope, STOP_POLICY, AlbumsUiState())

    /**
     * Creates the album and then reports its id to [onCreated], which is what opens it.
     *
     * A blank name never gets here — the dialog keeps its own button disabled until there is text — so a
     * null from the repository is the *other* refusal: a name that was only whitespace, which the field
     * accepted and the repository trimmed away. Nothing is created in that case, and the screen stays put
     * rather than opening an album the user cannot find again.
     */
    fun create(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            container.albumRepository.create(name)?.let(onCreated)
        }
    }

    companion object {
        private val STOP_POLICY = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
