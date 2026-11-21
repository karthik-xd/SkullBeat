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

    override suspend fun importCsvRows(
        rows: List<com.skullbeat.app.domain.util.CsvTrackParser.CsvRow>,
        onProgress: (matched: Int, total: Int, currentSong: String) -> Unit
    ): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        val total = rows.size
        var matchedCount = 0
        var processedCount = 0
        val resolvedTracks = mutableListOf<Track>()

        for (row in rows) {
            val title = row.title
            val artist = row.artist
            val videoId = row.videoId

            try {
                if (videoId.isNotBlank()) {
                    val existing = db.trackDao().getTrack(videoId)
                    if (existing != null) {
                        resolvedTracks.add(existing)
                        matchedCount++
                    } else {
                        val duration: Long = if (row.durationSec > 0) row.durationSec.toLong() else 210L
                        val mins = duration / 60
                        val secs = duration % 60
                        val track = Track(
                            videoId = videoId,
                            title = title,
                            artist = artist.ifBlank { "Unknown Artist" },
                            album = row.album.ifBlank { "Single" },
                            durationSec = duration,
                            durationFormatted = String.format(java.util.Locale.US, "%d:%02d", mins, secs),
                            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        )
                        resolvedTracks.add(track)
                        matchedCount++
                    }
                } else {
                    val query = if (artist.isNotBlank()) "$title $artist" else title
                    val candidates = extractor.searchSongs(query, limit = 5)
                    val hit = TrackMatcher.findBestMatch(title, artist, candidates, minScoreThreshold = 0.35)
                    if (hit != null) {
                        resolvedTracks.add(hit)
                        matchedCount++
                    }
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

    override suspend fun importCsvSongs(
        songs: List<Pair<String, String>>,
        onProgress: (matched: Int, total: Int, currentSong: String) -> Unit
    ): Pair<String, List<Track>> = importCsvRows(
        songs.map { com.skullbeat.app.domain.util.CsvTrackParser.CsvRow(title = it.first, artist = it.second) },
        onProgress
    )

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

    override suspend fun addTracksToFavorites(tracks: List<Track>) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        db.trackDao().insertTracks(tracks)
        val now = System.currentTimeMillis()
        val favorites = tracks.map { Favorite(videoId = it.videoId, addedAt = now) }
        db.favoriteDao().addFavorites(favorites)
        try {
            val currentFavs = db.favoriteDao().getFavoriteTracks()
            backupManager.saveLikedSongsBackup(currentFavs)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Failed to update backup after addTracksToFavorites", e)
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

    override suspend fun getPlaylistTracksList(playlistId: Long): List<Track> {
        return db.playlistDao().getPlaylistTracks(playlistId)
    }

    override suspend fun addTrackToPlaylist(playlistId: Long, track: Track): Boolean {
        db.trackDao().insertTrack(track)
        val isAlreadyIn = isTrackInPlaylist(playlistId, track.videoId)
        // Multi-copy support: Always add a new instance without deleting existing copies
        db.playlistDao().addTrackToPlaylist(playlistId, track.videoId)
        return !isAlreadyIn
    }

    override suspend fun addTracksToPlaylist(playlistId: Long, tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        // Multi-copy support: Preserve all tracks including duplicates from CSV, folder, or link import
        db.trackDao().insertTracks(tracks)
        db.playlistDao().addTracksToPlaylist(playlistId, tracks.map { it.videoId })
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
