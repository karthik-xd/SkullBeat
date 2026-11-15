package com.skullbeat.app.ui.screens.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skullbeat.app.domain.models.Playlist
import com.skullbeat.app.domain.models.Track
import com.skullbeat.app.domain.repository.MusicRepository
import com.skullbeat.app.player.manager.PlaybackContextType
import com.skullbeat.app.player.manager.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val playerManager: PlayerManager
) : ViewModel() {

    val playlistId: Long = savedStateHandle.get<String>("playlistId")?.toLongOrNull() ?: 0L

    val playlist: StateFlow<Playlist?> = repository.getPlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tracks: StateFlow<List<Track>> = repository.getPlaylistTracks(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<Track>> = repository.getFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackState = playerManager.playbackState

    fun playAll(shuffle: Boolean = false) {
        val list = tracks.value
        if (list.isNotEmpty()) {
            playerManager.setShuffle(shuffle)
            val plName = playlist.value?.name ?: "Playlist"
            val startTrack = if (shuffle) list.random() else list[0]
            playerManager.playTrack(startTrack, list, PlaybackContextType.PLAYLIST, plName)
        }
    }

    fun addToQueue(track: Track) {
        playerManager.addToUserQueue(track)
    }

    fun removeFromQueue(track: Track): Boolean {
        return playerManager.removeTrackFromUserQueue(track)
    }

    fun playTrack(track: Track) {
        val plName = playlist.value?.name ?: "Playlist"
        playerManager.playTrack(track, tracks.value, PlaybackContextType.PLAYLIST, plName)
    }

    fun removeTrack(videoId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, videoId)
        }
    }

    fun deletePlaylist(onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            onComplete()
        }
    }

    fun updatePlaylist(name: String, emoji: String, colorHex: String, coverPath: String? = null) {
        viewModelScope.launch {
            repository.updatePlaylist(playlistId, name, emoji, colorHex, coverPath)
        }
    }

    fun updateCoverPhoto(
        context: android.content.Context,
        uri: android.net.Uri,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                val oldCover = playlist.value?.coverPath
                val newPath = com.skullbeat.app.domain.util.ImageUtils.processAndSavePlaylistCover(
                    context = context,
                    sourceUri = uri,
                    playlistId = playlistId,
                    oldCoverPath = oldCover
                )
                if (newPath != null) {
                    repository.updatePlaylistCover(playlistId, newPath)
                    onSuccess?.invoke()
                } else {
                    onError?.invoke("Failed to process cover image")
                }
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "Failed to update cover photo")
            }
        }
    }

    fun removeCoverPhoto(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val oldCover = playlist.value?.coverPath
                com.skullbeat.app.domain.util.ImageUtils.deleteCoverFile(oldCover)
                repository.updatePlaylistCover(playlistId, null)
                onComplete?.invoke()
            } catch (e: Exception) {
                // Ignore or handle
            }
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
        }
    }

    fun addTrack(track: Track) {
        viewModelScope.launch {
            try {
                repository.addTrackToPlaylist(playlistId, track)
            } catch (e: Exception) {
                android.util.Log.e("PlaylistViewModel", "Failed to add track", e)
            }
        }
    }

    suspend fun searchTracks(query: String): List<Track> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        return try {
            repository.search(clean)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
