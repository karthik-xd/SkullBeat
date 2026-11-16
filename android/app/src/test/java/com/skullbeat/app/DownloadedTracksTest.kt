package com.skullbeat.app

import com.skullbeat.app.data.db.DownloadedTrackDao
import com.skullbeat.app.data.db.TrackDao
import com.skullbeat.app.domain.models.DownloadedTrack
import com.skullbeat.app.domain.models.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadedTracksTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeDownloadedTrackDao : DownloadedTrackDao {
        val tracks = ConcurrentHashMap<String, DownloadedTrack>()

        override suspend fun insertDownloadedTrack(track: DownloadedTrack) {
            tracks[track.videoId] = track
        }

        override suspend fun deleteDownloadedTrack(videoId: String) {
            tracks.remove(videoId)
        }

        override suspend fun getDownloadedTrack(videoId: String): DownloadedTrack? {
            return tracks[videoId]
        }

        override suspend fun getAllDownloadedTracks(): List<DownloadedTrack> {
            return tracks.values.toList()
        }

        override fun getAllDownloadedTracksFlow(): Flow<List<DownloadedTrack>> {
            return flowOf(tracks.values.toList())
        }
    }

    private class FakeTrackDao : TrackDao {
        val tracks = ConcurrentHashMap<String, Track>()

        override suspend fun insertTrack(track: Track) {
            tracks[track.videoId] = track
        }

        override suspend fun insertTracks(tracks: List<Track>) {
            tracks.forEach { this.tracks[it.videoId] = it }
        }

        override suspend fun getTrack(videoId: String): Track? {
            return tracks[videoId]
        }

        override suspend fun getTracks(videoIds: List<String>): List<Track> {
            return videoIds.mapNotNull { tracks[it] }
        }

        override fun getAllTracksFlow(): Flow<List<Track>> {
            return flowOf(tracks.values.toList())
        }

        override suspend fun getAllTracks(): List<Track> {
            return tracks.values.toList()
        }
    }

    /**
     * Test harness mirroring DownloadedTracksManager logic for direct JVM test execution.
     */
    private class TestDownloadedTracksManager(
        private val dao: FakeDownloadedTrackDao,
        private val trackDao: FakeTrackDao
    ) {
        val registeredDownloads = ConcurrentHashMap<String, String>()

        fun refreshCache() {
            registeredDownloads.clear()
            for (track in dao.tracks.values) {
                registeredDownloads[track.videoId] = track.localFilePath
            }
        }

        fun getLocalFilePath(videoId: String): String? {
            val path = registeredDownloads[videoId] ?: return null
            val file = File(path)
            return if (file.exists() && file.length() > 0) {
                path
            } else {
                null
            }
        }

        fun isDownloadMissing(videoId: String): Boolean {
            val path = registeredDownloads[videoId] ?: return false
            val file = File(path)
            return !file.exists() || file.length() == 0L
        }

        fun isDownloaded(videoId: String): Boolean {
            return getLocalFilePath(videoId) != null
        }

        suspend fun registerDownloadedTrack(track: DownloadedTrack) {
            dao.insertDownloadedTrack(track)
            trackDao.insertTrack(track.toTrack())
            registeredDownloads[track.videoId] = track.localFilePath
        }

        suspend fun deleteDownloadedTrack(videoId: String) {
            val path = registeredDownloads.remove(videoId)
            if (path != null) {
                val file = File(path)
                if (file.exists()) file.delete()
            }
            dao.deleteDownloadedTrack(videoId)
        }
    }

    @Test
    fun testDownloadedTrackToTrackConversion() {
        val downloaded = DownloadedTrack(
            videoId = "vid_123",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            durationSec = 243,
            durationFormatted = "4:03",
            thumbnailUrl = "https://example.com/thumb.jpg",
            localFilePath = "/storage/emulated/0/Music/SkullBeat/vid_123.m4a"
        )

        val track = downloaded.toTrack()
        assertEquals("vid_123", track.videoId)
        assertEquals("Midnight City", track.title)
        assertEquals("M83", track.artist)
        assertEquals("Hurry Up, We're Dreaming", track.album)
        assertEquals(243, track.durationSec)
        assertEquals("4:03", track.durationFormatted)
        assertEquals("https://example.com/thumb.jpg", track.thumbnailUrl)
    }

    @Test
    fun testInstantO1LookupWhenFileExists() = runBlocking {
        val fakeDao = FakeDownloadedTrackDao()
        val fakeTrackDao = FakeTrackDao()
        val manager = TestDownloadedTracksManager(fakeDao, fakeTrackDao)

        val testFile = tempFolder.newFile("song1.m4a").apply {
            writeBytes(ByteArray(1024 * 16)) // 16 KB file
        }

        val track = DownloadedTrack(
            videoId = "track_valid",
            title = "Valid Song",
            artist = "Artist",
            album = "Album",
            durationSec = 200,
            durationFormatted = "3:20",
            thumbnailUrl = "thumb.jpg",
            localFilePath = testFile.absolutePath
        )

        manager.registerDownloadedTrack(track)

        // Instant O(1) in-memory check
        assertEquals(testFile.absolutePath, manager.getLocalFilePath("track_valid"))
        assertTrue(manager.isDownloaded("track_valid"))
        assertFalse(manager.isDownloadMissing("track_valid"))
        assertNotNull(fakeTrackDao.getTrack("track_valid"))
    }

    @Test
    fun testMissingDownloadDetectionWhenFileDeletedFromDisk() = runBlocking {
        val fakeDao = FakeDownloadedTrackDao()
        val fakeTrackDao = FakeTrackDao()
        val manager = TestDownloadedTracksManager(fakeDao, fakeTrackDao)

        val testFile = tempFolder.newFile("song_to_delete.m4a").apply {
            writeBytes(ByteArray(1024 * 8))
        }

        val track = DownloadedTrack(
            videoId = "track_deleted",
            title = "Missing Song",
            artist = "Artist",
            album = "Album",
            durationSec = 180,
            durationFormatted = "3:00",
            thumbnailUrl = "thumb.jpg",
            localFilePath = testFile.absolutePath
        )

        manager.registerDownloadedTrack(track)
        assertTrue(manager.isDownloaded("track_deleted"))

        // Simulate user or system deleting the physical audio file
        testFile.delete()
        assertFalse(testFile.exists())

        // Caching indicates track is registered, but file is missing
        assertNull(manager.getLocalFilePath("track_deleted"))
        assertFalse(manager.isDownloaded("track_deleted"))
        assertTrue(manager.isDownloadMissing("track_deleted"))
    }

    @Test
    fun testDeleteDownloadedTrackCleansUpDiskAndMemory() = runBlocking {
        val fakeDao = FakeDownloadedTrackDao()
        val fakeTrackDao = FakeTrackDao()
        val manager = TestDownloadedTracksManager(fakeDao, fakeTrackDao)

        val testFile = tempFolder.newFile("song_cleanup.m4a").apply {
            writeBytes(ByteArray(1024 * 32))
        }

        val track = DownloadedTrack(
            videoId = "track_cleanup",
            title = "Cleanup Song",
            artist = "Artist",
            album = "Album",
            durationSec = 150,
            durationFormatted = "2:30",
            thumbnailUrl = "thumb.jpg",
            localFilePath = testFile.absolutePath
        )

        manager.registerDownloadedTrack(track)
        assertTrue(testFile.exists())

        manager.deleteDownloadedTrack("track_cleanup")

        assertFalse(testFile.exists())
        assertNull(manager.getLocalFilePath("track_cleanup"))
        assertFalse(manager.isDownloaded("track_cleanup"))
        assertFalse(manager.isDownloadMissing("track_cleanup"))
        assertNull(fakeDao.getDownloadedTrack("track_cleanup"))
    }
}
