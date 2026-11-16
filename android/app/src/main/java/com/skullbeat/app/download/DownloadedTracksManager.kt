package com.skullbeat.app.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.WorkManager
import com.skullbeat.app.data.db.DownloadedTrackDao
import com.skullbeat.app.data.db.TrackDao
import com.skullbeat.app.domain.models.DownloadedTrack
import com.skullbeat.app.domain.models.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadedTracksManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val trackDao: TrackDao
) {
    companion object {
        private const val TAG = "DownloadedTracksManager"

        @Volatile
        private var instance: DownloadedTracksManager? = null

        fun getInstance(context: Context, dao: DownloadedTrackDao, trackDao: TrackDao): DownloadedTracksManager {
            return instance ?: synchronized(this) {
                instance ?: DownloadedTracksManager(context.applicationContext, dao, trackDao).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val registeredDownloads = ConcurrentHashMap<String, String>()

    private val _activeDownloads = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloads: StateFlow<Set<String>> = _activeDownloads.asStateFlow()

    init {
        instance = this
        scope.launch {
            refreshCache()
        }
    }

    suspend fun refreshCache() {
        try {
            val tracks = downloadedTrackDao.getAllDownloadedTracks()
            registeredDownloads.clear()
            for (track in tracks) {
                registeredDownloads[track.videoId] = track.localFilePath
            }
            Log.d(TAG, "Cached ${registeredDownloads.size} registered download tracks.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh download cache", e)
        }
    }

    /**
     * O(1) in-memory check with file verification.
     * Prevents searching disk or internet every time a song is played.
     */
    fun getLocalFilePath(videoId: String): String? {
        val path = registeredDownloads[videoId] ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) {
            path
        } else {
            null
        }
    }

    /**
     * Returns true if track is registered as downloaded but file is missing from disk.
     */
    fun isDownloadMissing(videoId: String): Boolean {
        val path = registeredDownloads[videoId] ?: return false
        val file = File(path)
        return !file.exists() || file.length() == 0L
    }

    fun isDownloaded(videoId: String): Boolean {
        return getLocalFilePath(videoId) != null
    }

    fun isDownloading(videoId: String): Boolean {
        return _activeDownloads.value.contains(videoId)
    }

    fun markDownloadStarted(videoId: String) {
        _activeDownloads.value = _activeDownloads.value + videoId
    }

    fun markDownloadFinished(videoId: String) {
        _activeDownloads.value = _activeDownloads.value - videoId
    }

    fun cancelDownload(videoId: String) {
        try {
            WorkManager.getInstance(context).cancelAllWorkByTag("download_$videoId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel WorkManager job", e)
        }
        markDownloadFinished(videoId)
        try {
            val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SkullBeat")
            musicDir.listFiles { file -> file.name.contains(videoId) && file.name.endsWith(".tmp") }?.forEach {
                it.delete()
            }
        } catch (_: Exception) {}
    }

    suspend fun registerDownloadedTrack(track: DownloadedTrack) {
        downloadedTrackDao.insertDownloadedTrack(track)
        trackDao.insertTrack(track.toTrack())
        registeredDownloads[track.videoId] = track.localFilePath
        markDownloadFinished(track.videoId)
        Log.d(TAG, "Registered downloaded track: ${track.title} at ${track.localFilePath}")
    }

    suspend fun deleteDownloadedTrack(videoId: String) {
        val path = registeredDownloads.remove(videoId)
        if (path != null) {
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
        downloadedTrackDao.deleteDownloadedTrack(videoId)
        Log.d(TAG, "Deleted downloaded track: $videoId")
    }

    fun getDownloadedTracksFlow(): Flow<List<DownloadedTrack>> {
        return downloadedTrackDao.getAllDownloadedTracksFlow()
    }

    fun isDownloadedFlow(videoId: String): Flow<Boolean> {
        return downloadedTrackDao.getAllDownloadedTracksFlow().map { list ->
            list.any { it.videoId == videoId && File(it.localFilePath).exists() && File(it.localFilePath).length() > 0 }
        }
    }
}
