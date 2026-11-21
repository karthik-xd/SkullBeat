package com.skullbeat.app.data.repository

import android.util.Log
import com.skullbeat.app.data.db.AppDatabase
import com.skullbeat.app.data.extractor.YouTubeExtractor
import com.skullbeat.app.data.network.SpotifyImportResult
import com.skullbeat.app.data.network.SpotifyScraper
import com.skullbeat.app.data.storage.PlayerSettings
import com.skullbeat.app.data.storage.RepeatState
import com.skullbeat.app.data.storage.UserLibraryBackupManager
import com.skullbeat.app.data.storage.UserPreferences
import com.skullbeat.app.domain.models.DownloadedTrack
import com.skullbeat.app.domain.models.Favorite
import com.skullbeat.app.domain.models.Playlist
import com.skullbeat.app.domain.models.Track
import com.skullbeat.app.domain.repository.MusicRepository
import com.skullbeat.app.domain.util.TrackMatcher
import com.skullbeat.app.download.DownloadedTracksManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val extractor: YouTubeExtractor,
    private val spotifyScraper: SpotifyScraper,
    private val preferences: UserPreferences,
    private val backupManager: UserLibraryBackupManager,
    private val downloadedTracksManager: DownloadedTracksManager
) : MusicRepository {

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                autoRestoreIfNeeded()
            } catch (e: Exception) {
                Log.e("MusicRepository", "Auto-restore check failed", e)
            }
        }
    }

    private suspend fun autoRestoreIfNeeded() {
        val count = db.favoriteDao().getFavoriteCount()
        if (count == 0) {
            val backedUpTracks = backupManager.loadLikedSongsBackup()
            if (backedUpTracks.isNotEmpty()) {
                db.trackDao().insertTracks(backedUpTracks)
                val favorites = backedUpTracks.map { Favorite(videoId = it.videoId) }
                db.favoriteDao().addFavorites(favorites)
                Log.d("MusicRepository", "Auto-restored ${backedUpTracks.size} liked songs from backup.")
            }
        } else {
            val currentFavs = db.favoriteDao().getFavoriteTracks()
            if (currentFavs.isNotEmpty()) {
                backupManager.saveLikedSongsBackup(currentFavs)
            }
        }
    }

    override suspend fun search(query: String): List<Track> {
        val results = extractor.searchSongs(query)
        if (results.isNotEmpty()) {
            db.trackDao().insertTracks(results)
        }
        return results
    }

    override suspend fun getArtistSongs(artist: String, currentVideoId: String?, limit: Int): List<Track> {
        val results = extractor.getArtistSongs(artist, currentVideoId, limit)
        if (results.isNotEmpty()) {
            db.trackDao().insertTracks(results)
        }
        return results
    }

    override suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean): String {
        return extractor.resolveStreamUrl(videoId, forceRefresh)
    }

    override fun invalidateStreamCache(videoId: String) {
        extractor.invalidateCache(videoId)
    }

    override suspend fun importYouTubePlaylist(urlOrId: String): Pair<String, List<Track>> {
        val (title, tracks) = extractor.getYouTubePlaylist(urlOrId)
        if (tracks.isNotEmpty()) {
            db.trackDao().insertTracks(tracks)
        }
        return Pair(title, tracks)
    }

    override suspend fun importSpotify(
        urlOrUri: String,
        onProgress: (Int, Int) -> Unit
    ): SpotifyImportResult {
        val result = spotifyScraper.importSpotify(urlOrUri, onProgress)
        if (result.tracks.isNotEmpty()) {
            db.trackDao().insertTracks(result.tracks)
        }
        return result
    }

    override suspend fun importCsvSongs(
        songs: List<Pair<String, String>>,
        onProgress: (matched: Int, total: Int, currentSong: String) -> Unit
    ): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        val total = songs.size
        var matchedCount = 0
        var processedCount = 0
        val resolvedTracks = mutableListOf<Track>()

        for ((title, artist) in songs) {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            try {
                val candidates = extractor.searchSongs(query, limit = 5)
                val hit = TrackMatcher.findBestMatch(title, artist, candidates, minScoreThreshold = 0.35)
                if (hit != null) {
                    resolvedTracks.add(hit)
                    matchedCount++
                }
            } catch (_: Exception) {
            } finally {
                processedCount++
                onProgress(processedCount, total, title)
            }
        }

        if (resolvedTracks.isNotEmpty()) {
            db.trackDao().insertTracks(resolvedTracks)
        }

        val playlistTitle = "CSV Import (${resolvedTracks.size} songs)"
        Pair(playlistTitle, resolvedTracks)
    }

    override suspend fun saveTrack(track: Track) {
        db.trackDao().insertTrack(track)
    }

    override suspend fun saveTracks(tracks: List<Track>) {
        db.trackDao().insertTracks(tracks)
    }

    override suspend fun getTrack(videoId: String): Track? {
        return db.trackDao().getTrack(videoId)
    }

    override fun getAllSavedTracks(): Flow<List<Track>> {
        return db.trackDao().getAllTracksFlow()
    }

    override fun isFavorite(videoId: String): Flow<Boolean> {
        return db.favoriteDao().isFavoriteFlow(videoId)
    }

    override suspend fun toggleFavorite(track: Track) {
        db.trackDao().insertTrack(track)
        val isFav = db.favoriteDao().isFavorite(track.videoId)
        if (isFav) {
            db.favoriteDao().removeFavorite(track.videoId)
        } else {
            db.favoriteDao().addFavorite(Favorite(videoId = track.videoId))
        }
        try {
            val currentFavs = db.favoriteDao().getFavoriteTracks()
            backupManager.saveLikedSongsBackup(currentFavs)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Failed to update backup after toggleFavorite", e)
        }
    }

    override fun getFavorites(): Flow<List<Track>> {
        return db.favoriteDao().getFavoriteTracksFlow()
    }

    override suspend fun createPlaylist(name: String, emoji: String, colorHex: String, coverPath: String?): Long {
        val playlist = Playlist(name = name, emoji = emoji, colorHex = colorHex, coverPath = coverPath)
        return db.playlistDao().insertPlaylist(playlist)
    }

    override suspend fun updatePlaylist(
        playlistId: Long,
        name: String,
        emoji: String,
        colorHex: String,
        coverPath: String?
    ) {
        val oldCover = db.playlistDao().getCoverPath(playlistId)
        if (oldCover != null && oldCover != coverPath) {
            com.skullbeat.app.domain.util.ImageUtils.deleteCoverFile(oldCover)
        }
        db.playlistDao().updatePlaylistFull(playlistId, name, emoji, colorHex, coverPath)
    }

    override suspend fun updatePlaylistCover(playlistId: Long, coverPath: String?) {
        val oldCover = db.playlistDao().getCoverPath(playlistId)
        if (oldCover != null && oldCover != coverPath) {
            com.skullbeat.app.domain.util.ImageUtils.deleteCoverFile(oldCover)
        }
        db.playlistDao().updatePlaylistCover(playlistId, coverPath)
    }

    override suspend fun updatePlaylistName(playlistId: Long, name: String) {
        db.playlistDao().updatePlaylistName(playlistId, name)
    }

    override suspend fun isTrackInPlaylist(playlistId: Long, videoId: String): Boolean {
        return db.playlistDao().isTrackInPlaylist(playlistId, videoId) > 0
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        val oldCover = db.playlistDao().getCoverPath(playlistId)
        com.skullbeat.app.domain.util.ImageUtils.deleteCoverFile(oldCover)
        db.playlistDao().deletePlaylist(playlistId)
    }

    override fun getPlaylists(): Flow<List<Playlist>> {
        return db.playlistDao().getAllPlaylistsFlow()
    }

    override fun getPlaylist(playlistId: Long): Flow<Playlist?> {
        return db.playlistDao().getPlaylistFlow(playlistId)
    }

    override fun getPlaylistTracks(playlistId: Long): Flow<List<Track>> {
        return db.playlistDao().getPlaylistTracksFlow(playlistId)
    }

    override suspend fun addTrackToPlaylist(playlistId: Long, track: Track): Boolean {
        db.trackDao().insertTrack(track)
        val isAlreadyIn = isTrackInPlaylist(playlistId, track.videoId)
        if (isAlreadyIn) {
            // Remove previous position so it is promoted to the very first position
            db.playlistDao().removeTrackFromPlaylist(playlistId, track.videoId)
        }
        db.playlistDao().addTrackToPlaylist(playlistId, track.videoId)
        return !isAlreadyIn
    }

    override suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        val newTracks = tracks.filter { !isTrackInPlaylist(playlistId, it.videoId) }
        if (newTracks.isEmpty()) return false
        db.trackDao().insertTracks(newTracks)
        db.playlistDao().addTracksToPlaylist(playlistId, newTracks.map { it.videoId })
        return true
    }

    override suspend fun removeTrackFromPlaylist(playlistId: Long, videoId: String) {
        db.playlistDao().removeTrackFromPlaylist(playlistId, videoId)
    }

    override suspend fun recordPlay(track: Track) {
        db.trackDao().insertTrack(track)
        db.historyDao().recordPlay(track.videoId)
    }

    override fun getHistory(): Flow<List<Track>> {
        return db.historyDao().getHistoryTracksFlow()
    }

    override suspend fun clearHistory() {
        db.historyDao().clearHistory()
    }

    override suspend fun saveQueue(tracks: List<Track>) {
        db.trackDao().insertTracks(tracks)
        db.queueDao().setQueue(tracks.map { it.videoId })
    }

    override fun getQueue(): Flow<List<Track>> {
        return db.queueDao().getQueueTracksFlow()
    }

    override val settingsFlow: Flow<PlayerSettings> = preferences.settingsFlow

    override suspend fun savePlaybackState(trackId: String?, positionMs: Long) {
        preferences.savePlaybackState(trackId, positionMs)
    }

    override suspend fun setShuffle(enabled: Boolean) {
        preferences.setShuffle(enabled)
    }

    override suspend fun setRepeatMode(repeatState: RepeatState) {
        preferences.setRepeatMode(repeatState)
    }

    override suspend fun setVolume(volume: Float) {
        preferences.setVolume(volume)
    }

    override suspend fun backupLibrary(): Boolean = withContext(Dispatchers.IO) {
        val favs = db.favoriteDao().getFavoriteTracks()
        backupManager.saveLikedSongsBackup(favs)
    }

    override suspend fun restoreLibrary(): Int = withContext(Dispatchers.IO) {
        val backedUp = backupManager.loadLikedSongsBackup()
        if (backedUp.isNotEmpty()) {
            db.trackDao().insertTracks(backedUp)
            db.favoriteDao().addFavorites(backedUp.map { Favorite(videoId = it.videoId) })
            Log.d("MusicRepository", "Manual restore loaded ${backedUp.size} liked songs.")
        }
        backedUp.size
    }

    override fun getDownloadedTracks(): Flow<List<DownloadedTrack>> {
        return downloadedTracksManager.getDownloadedTracksFlow()
    }

    override suspend fun deleteDownloadedTrack(videoId: String) = withContext(Dispatchers.IO) {
        downloadedTracksManager.deleteDownloadedTrack(videoId)
    }

    override val themeColor: Flow<String> = preferences.themeColorFlow
    override val hasSelectedThemeOnce: Flow<Boolean> = preferences.hasSelectedThemeOnce

    override suspend fun setThemeColor(themeKey: String) {
        preferences.setThemeColor(themeKey)
    }

    override suspend fun markThemeSelectedOnce() {
        preferences.markThemeSelectedOnce()
    }
}
