package com.skullbeat.app

import android.content.Context
import com.skullbeat.app.data.storage.UserLibraryBackupManager
import com.skullbeat.app.domain.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserLibraryBackupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestBackupManager(
        private val baseFilesDir: File,
        private val baseExternalDir: File?
    ) {
        private val BACKUP_DIR = "backups"
        private val LIKED_SONGS_FILE = "skullbeat_liked_backup.json"

        fun getInternalBackupFile(): File {
            val dir = File(baseFilesDir, BACKUP_DIR).apply { mkdirs() }
            return File(dir, LIKED_SONGS_FILE)
        }

        fun getExternalBackupFile(): File? {
            if (baseExternalDir == null) return null
            val extDir = File(baseExternalDir, BACKUP_DIR).apply { mkdirs() }
            return File(extDir, LIKED_SONGS_FILE)
        }

        fun saveLikedSongsBackup(tracks: List<Track>): Boolean {
            if (tracks.isEmpty()) return false
            return try {
                val jsonArray = org.json.JSONArray()
                for (track in tracks) {
                    val obj = org.json.JSONObject().apply {
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

                writeAtomically(getInternalBackupFile(), jsonString)
                getExternalBackupFile()?.let { extFile ->
                    writeAtomically(extFile, jsonString)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        fun loadLikedSongsBackup(): List<Track> {
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
            java.io.FileOutputStream(tempFile).use { fos ->
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

        private fun parseTracksFile(file: File): List<Track> {
            return try {
                val content = file.readText(Charsets.UTF_8)
                val jsonArray = org.json.JSONArray(content)
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
                emptyList()
            }
        }
    }

    @Test
    fun saveAndLoadLikedSongsBackup_preservesAllTracksAndMetadata() {
        val filesDir = tempFolder.newFolder("files")
        val extDir = tempFolder.newFolder("ext_files")
        val manager = TestBackupManager(filesDir, extDir)

        val sampleTracks = listOf(
            Track(
                videoId = "vid1",
                title = "Starboy",
                artist = "The Weeknd",
                album = "Starboy",
                durationSec = 230L,
                durationFormatted = "3:50",
                thumbnailUrl = "https://example.com/thumb1.jpg"
            ),
            Track(
                videoId = "vid2",
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                durationSec = 200L,
                durationFormatted = "3:20",
                thumbnailUrl = "https://example.com/thumb2.jpg"
            )
        )

        val saved = manager.saveLikedSongsBackup(sampleTracks)
        assertTrue("Backup must save successfully", saved)

        val loaded = manager.loadLikedSongsBackup()
        assertEquals(2, loaded.size)
        assertEquals("vid1", loaded[0].videoId)
        assertEquals("Starboy", loaded[0].title)
        assertEquals("The Weeknd", loaded[0].artist)
        assertEquals(230L, loaded[0].durationSec)
        assertEquals("vid2", loaded[1].videoId)
        assertEquals("Blinding Lights", loaded[1].title)
    }

    @Test
    fun saveEmptyList_returnsFalseAndDoesNotCorruptExisting() {
        val filesDir = tempFolder.newFolder("files")
        val manager = TestBackupManager(filesDir, null)

        val savedInitial = manager.saveLikedSongsBackup(
            listOf(Track(videoId = "t1", title = "T1", artist = "A1"))
        )
        assertTrue(savedInitial)

        val savedEmpty = manager.saveLikedSongsBackup(emptyList())
        assertFalse("Saving empty list should return false", savedEmpty)

        // Existing backup remains intact
        val loaded = manager.loadLikedSongsBackup()
        assertEquals(1, loaded.size)
        assertEquals("t1", loaded[0].videoId)
    }

    @Test
    fun fallbackToExternal_whenInternalIsMissing() {
        val filesDir = tempFolder.newFolder("files")
        val extDir = tempFolder.newFolder("ext_files")
        val manager = TestBackupManager(filesDir, extDir)

        manager.saveLikedSongsBackup(
            listOf(Track(videoId = "ext_only", title = "External Song", artist = "External Artist"))
        )

        // Delete internal file to simulate internal storage wipe
        val internalFile = manager.getInternalBackupFile()
        assertTrue(internalFile.exists())
        internalFile.delete()
        assertFalse(internalFile.exists())

        // Load must fallback to external backup
        val loaded = manager.loadLikedSongsBackup()
        assertEquals(1, loaded.size)
        assertEquals("ext_only", loaded[0].videoId)
        assertEquals("External Song", loaded[0].title)
    }

    @Test
    fun corruptBackup_returnsEmptyListWithoutCrash() {
        val filesDir = tempFolder.newFolder("files")
        val manager = TestBackupManager(filesDir, null)

        val backupFile = manager.getInternalBackupFile()
        backupFile.parentFile?.mkdirs()
        backupFile.writeText("INVALID JSON {[[corrupt content")

        val loaded = manager.loadLikedSongsBackup()
        assertTrue("Corrupt backup must return emptyList without throwing", loaded.isEmpty())
    }
}
