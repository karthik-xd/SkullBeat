package com.skullbeat.app.ui.screens.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skullbeat.app.domain.models.DownloadedTrack
import com.skullbeat.app.domain.models.Playlist
import com.skullbeat.app.domain.models.Track
import com.skullbeat.app.domain.repository.MusicRepository
import com.skullbeat.app.domain.util.FilterCriteria
import com.skullbeat.app.domain.util.TrackExporter
import com.skullbeat.app.domain.util.TrackFilter
import com.skullbeat.app.download.DownloadProgressInfo
import com.skullbeat.app.download.DownloadedTracksManager
import com.skullbeat.app.player.manager.PlaybackContextType
import com.skullbeat.app.player.manager.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab {
    ALL, LIKED, DOWNLOADS, PLAYLISTS, HISTORY
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val playerManager: PlayerManager,
    private val downloadedTracksManager: DownloadedTracksManager
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.ALL)
    val selectedTab: StateFlow<LibraryTab> = _selectedTab.asStateFlow()

    private val _filterCriteria = MutableStateFlow(FilterCriteria())
    val filterCriteria: StateFlow<FilterCriteria> = _filterCriteria.asStateFlow()

    val allTracks: StateFlow<List<Track>> = repository.getAllSavedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteTracks: StateFlow<List<Track>> = repository.getFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedTracks: StateFlow<List<DownloadedTrack>> = repository.getDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyTracks: StateFlow<List<Track>> = repository.getHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadProgress: StateFlow<Map<String, DownloadProgressInfo>> = downloadedTracksManager.downloadProgress

    val playbackState = playerManager.playbackState

    // Active track list based on selected tab
    private val activeTabTracks: StateFlow<List<Track>> = combine(
        _selectedTab,
        allTracks,
        favoriteTracks,
        downloadedTracks,
        historyTracks
    ) { tab, all, favs, downloaded, hist ->
        when (tab) {
            LibraryTab.ALL -> all
            LibraryTab.LIKED -> favs
            LibraryTab.DOWNLOADS -> downloaded.map { it.toTrack() }
            LibraryTab.HISTORY -> hist
            LibraryTab.PLAYLISTS -> emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live filtered tracks based on active tab and filter criteria
    val filteredTracks: StateFlow<List<Track>> = combine(
        activeTabTracks,
        _filterCriteria
    ) { tracks, criteria ->
        TrackFilter.filter(tracks, criteria)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectTab(tab: LibraryTab) {
        _selectedTab.value = tab
    }

    fun isDownloadMissing(videoId: String): Boolean {
        return downloadedTracksManager.isDownloadMissing(videoId)
    }

    fun deleteDownloadedTrack(videoId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedTrack(videoId)
        }
    }

    fun updateFilterCriteria(criteria: FilterCriteria) {
        _filterCriteria.value = criteria
    }

    fun addToQueue(track: Track) {
        playerManager.addToUserQueue(track)
    }

    fun removeFromQueue(track: Track): Boolean {
        return playerManager.removeTrackFromUserQueue(track)
    }

    fun playTrack(track: Track, list: List<Track>) {
        val contextType = when (_selectedTab.value) {
            LibraryTab.LIKED -> PlaybackContextType.PLAYLIST
            LibraryTab.DOWNLOADS -> PlaybackContextType.PLAYLIST
            else -> PlaybackContextType.QUEUE
        }
        playerManager.playTrack(track, list, contextType)
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun backupLibrary(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.backupLibrary()
            onResult(result)
        }
    }

    fun restoreLibrary(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = repository.restoreLibrary()
            onResult(count)
        }
    }

    private fun getExportPrefix(): String {
        return when (selectedTab.value) {
            LibraryTab.LIKED -> "Liked_Songs"
            LibraryTab.DOWNLOADS -> "Downloaded_Songs"
            LibraryTab.HISTORY -> "History_Songs"
            LibraryTab.ALL -> "Library_All_Songs"
            LibraryTab.PLAYLISTS -> "Playlists"
        }
    }

    fun exportFilteredM3u() {
        val list = filteredTracks.value
        val content = TrackExporter.toM3u(list)
        TrackExporter.shareExport(context, "m3u", content, "audio/x-mpegurl", getExportPrefix())
    }

    fun exportFilteredCsv() {
        val list = filteredTracks.value
        val prefix = getExportPrefix()
        val content = TrackExporter.toCsv(list, prefix.replace("_", " "))
        TrackExporter.shareExport(context, "csv", content, "text/csv", prefix)
    }

    fun exportFilteredJson() {
        val list = filteredTracks.value
        val content = TrackExporter.toJson(list)
        TrackExporter.shareExport(context, "json", content, "application/json", getExportPrefix())
    }

    fun exportLikedSongsCsv() {
        val list = favoriteTracks.value
        val content = TrackExporter.toCsv(list, "Liked Songs")
        TrackExporter.shareExport(context, "csv", content, "text/csv", "Liked_Songs")
    }

    fun exportDownloadsCsv() {
        val list = downloadedTracks.value.map { it.toTrack() }
        val content = TrackExporter.toCsv(list, "Downloads")
        TrackExporter.shareExport(context, "csv", content, "text/csv", "Downloaded_Songs")
    }

    fun exportPlaylistCsv(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = repository.getPlaylistTracksList(playlist.id)
            val content = TrackExporter.toCsv(tracks, playlist.name)
            TrackExporter.shareExport(context, "csv", content, "text/csv", playlist.name.ifBlank { "Playlist" })
        }
    }

    fun exportAllCsv() {
        val list = allTracks.value
        val content = TrackExporter.toCsv(list, "Library")
        TrackExporter.shareExport(context, "csv", content, "text/csv", "Library_All_Songs")
    }
}
