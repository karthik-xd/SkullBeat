package com.skullbeat.app.data.storage

import android.content.Context
import android.util.Log
import com.skullbeat.app.domain.models.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserLibraryBackupManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UserLibraryBackup"
        private const val BACKUP_DIR = "backups"
        private const val LIKED_SONGS_FILE = "skullbeat_liked_backup.json"
    }

    fun getInternalBackupFile(): File {
        val dir = File(context.filesDir, BACKUP_DIR).apply { mkdirs() }
        return File(dir, LIKED_SONGS_FILE)
    }

    fun getExternalBackupFile(): File? {
        return try {
            val extDir = context.getExternalFilesDir(BACKUP_DIR) ?: return null
            extDir.mkdirs()
            File(extDir, LIKED_SONGS_FILE)
        } catch (e: Exception) {
            null
        }
    }

    fun saveLikedSongsBackup(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        return try {
            val jsonArray = JSONArray()
            for (track in tracks) {
                val obj = JSONObject().apply {
                    put("videoId", track.videoId)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("durationSec", track.durationSec)
                    put("durationFormatted", track.durationFormatted)
                    put("thumbnailUrl", track.thumbnailUrl)
                }
                jsonArray.put(obj)
            }
            val jsonString = jsonArray.toString(2)

            // 1. Write internal file atomically
            writeAtomically(getInternalBackupFile(), jsonString)

            // 2. Write external file if available
            getExternalBackupFile()?.let { extFile ->
                writeAtomically(extFile, jsonString)
            }

            Log.d(TAG, "Successfully backed up ${tracks.size} liked songs to disk.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to back up liked songs", e)
            false
        }
    }

    fun loadLikedSongsBackup(): List<Track> {
        // Try internal file first, then fallback to external
        val internalFile = getInternalBackupFile()
        if (internalFile.exists() && internalFile.length() > 0) {
            val tracks = parseTracksFile(internalFile)
            if (tracks.isNotEmpty()) return tracks
        }

        val externalFile = getExternalBackupFile()
        if (externalFile != null && externalFile.exists() && externalFile.length() > 0) {
            val tracks = parseTracksFile(externalFile)
            if (tracks.isNotEmpty()) return tracks
        }

        return emptyList()
    }

    private fun writeAtomically(targetFile: File, content: String) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
        }
        if (tempFile.exists()) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
        }
    }

    fun parseTracksFile(file: File): List<Track> {
        return try {
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            val list = mutableListOf<Track>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    Track(
                        videoId = obj.getString("videoId"),
                        title = obj.optString("title", "Unknown"),
                        artist = obj.optString("artist", "Unknown"),
                        album = obj.optString("album", "Single"),
                        durationSec = obj.optLong("durationSec", 0L),
                        durationFormatted = obj.optString("durationFormatted", "0:00"),
                        thumbnailUrl = obj.optString("thumbnailUrl", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing backup file: ${file.absolutePath}", e)
            emptyList()
        }
    }
}
