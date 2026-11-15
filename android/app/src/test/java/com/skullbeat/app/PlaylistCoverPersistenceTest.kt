package com.skullbeat.app

import com.skullbeat.app.domain.models.Playlist
import com.skullbeat.app.domain.util.ImageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistCoverPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Mirrors the validation logic in PlaylistCover.kt:
     * val hasCustomCover = !coverPath.isNullOrBlank() && File(coverPath).exists()
     */
    private fun hasCustomCover(coverPath: String?): Boolean {
        return !coverPath.isNullOrBlank() && File(coverPath).exists()
    }

    // =========================================================================
    // 1. PLAYLIST ENTITY & SERIALIZATION TESTS
    // =========================================================================

    @Test
    fun playlist_defaultConstructor_coverPathIsNull() {
        val playlist = Playlist(
            id = 1L,
            name = "Chill Beats"
        )
        assertNull("Default coverPath should be null", playlist.coverPath)
        assertEquals("🎵", playlist.emoji)
        assertEquals("#3D8BFF", playlist.colorHex)
    }

    @Test
    fun playlist_withCustomCoverPath_retainsPathCorrectly() {
        val expectedPath = "/data/user/0/com.skullbeat.app/files/playlist_covers/pl_1_1710000000000.webp"
        val playlist = Playlist(
            id = 1L,
            name = "Workout Mix",
            emoji = "🔥",
            colorHex = "#FF3B5C",
            coverPath = expectedPath
        )
        assertEquals(expectedPath, playlist.coverPath)
        assertEquals("Workout Mix", playlist.name)
        assertEquals("🔥", playlist.emoji)
        assertEquals("#FF3B5C", playlist.colorHex)
    }

    @Test
    fun playlist_copy_updatesCoverPathCorrectly() {
        val initial = Playlist(id = 5L, name = "Synthwave", coverPath = null)
        val newCoverPath = "/data/user/0/com.skullbeat.app/files/playlist_covers/pl_5_1710000001000.webp"

        val updated = initial.copy(coverPath = newCoverPath)

        assertEquals(newCoverPath, updated.coverPath)
        assertEquals(initial.id, updated.id)
        assertEquals(initial.name, updated.name)
        assertEquals(initial.emoji, updated.emoji)
        assertEquals(initial.colorHex, updated.colorHex)
    }

    @Test
    fun playlist_copy_removesCoverPathWhenNull() {
        val initial = Playlist(
            id = 10L,
            name = "Lo-Fi Cafe",
            coverPath = "/data/user/0/com.skullbeat.app/files/playlist_covers/pl_10_1710000002000.webp"
        )
        assertNotNull(initial.coverPath)

        val updated = initial.copy(coverPath = null)

        assertNull("Updated playlist coverPath should be null after removal", updated.coverPath)
        assertEquals(10L, updated.id)
    }

    @Test
    fun playlist_equality_differentCoverPath_areNotEqual() {
        val pl1 = Playlist(id = 1L, name = "Pop", coverPath = "/path/a.webp")
        val pl2 = Playlist(id = 1L, name = "Pop", coverPath = "/path/b.webp")

        assertNotEquals("Playlists with different coverPaths must not be equal", pl1, pl2)
        assertEquals(pl1, pl1.copy())
    }

    @Test
    fun playlist_preservesEmojiAndColor_whenUpdatingCover() {
        val pl = Playlist(
            id = 3L,
            name = "Night Drive",
            emoji = "🌙",
            colorHex = "#8B5CFF",
            coverPath = null
        )

        val withCover = pl.copy(coverPath = "/storage/pl_3_123.webp")
        assertEquals("🌙", withCover.emoji)
        assertEquals("#8B5CFF", withCover.colorHex)

        val withoutCover = withCover.copy(coverPath = null)
        assertEquals("🌙", withoutCover.emoji)
        assertEquals("#8B5CFF", withoutCover.colorHex)
        assertNull(withoutCover.coverPath)
    }

    // =========================================================================
    // 2. LOCAL FILE PATH VALIDATION & FALLBACK BEHAVIOR TESTS
    // =========================================================================

    @Test
    fun filePathValidation_nullCoverPath_returnsFallback() {
        assertFalse(hasCustomCover(null))
    }

    @Test
    fun filePathValidation_emptyCoverPath_returnsFallback() {
        assertFalse(hasCustomCover(""))
    }

    @Test
    fun filePathValidation_blankCoverPath_returnsFallback() {
        assertFalse(hasCustomCover("   "))
        assertFalse(hasCustomCover("\t\n"))
    }

    @Test
    fun filePathValidation_nonExistentFile_returnsFallback() {
        val nonExistentPath = File(tempFolder.root, "does_not_exist.webp").absolutePath
        assertFalse(hasCustomCover(nonExistentPath))
    }

    @Test
    fun filePathValidation_existingFile_rendersCustomCover() {
        val coverFile = tempFolder.newFile("pl_1_test.webp")
        coverFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertTrue(hasCustomCover(coverFile.absolutePath))
    }

    @Test
    fun filePathValidation_deletedFile_revertsToFallbackImmediately() {
        val coverFile = tempFolder.newFile("pl_1_temporary.webp")
        coverFile.writeBytes(byteArrayOf(10, 20, 30))

        assertTrue(hasCustomCover(coverFile.absolutePath))

        // Simulate file deletion
        coverFile.delete()

        assertFalse("After deleting cover file, validation must immediately fall back", hasCustomCover(coverFile.absolutePath))
    }

    // =========================================================================
    // 3. COVER PHOTO UPDATE, REPLACEMENT & REMOVAL FLOW TESTS
    // =========================================================================

    @Test
    fun coverPhotoFlow_newCoverCreated_persistsFileOnDisk() {
        val coversDir = tempFolder.newFolder("playlist_covers")
        val playlistId = 7L
        val timestamp = System.currentTimeMillis()
        val newCoverFile = File(coversDir, "pl_${playlistId}_$timestamp.webp")
        newCoverFile.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) // WEBP header bytes

        assertTrue("New cover file must exist on disk", newCoverFile.exists())
        assertEquals(4L, newCoverFile.length())
    }

    @Test
    fun coverPhotoFlow_coverReplaced_deletesOldCoverAndKeepsNew() {
        val coversDir = tempFolder.newFolder("playlist_covers")
        val playlistId = 7L

        val oldCoverFile = File(coversDir, "pl_${playlistId}_1000000.webp")
        oldCoverFile.writeBytes("old-image-data".toByteArray())
        assertTrue("Old cover must exist initially", oldCoverFile.exists())

        val newCoverFile = File(coversDir, "pl_${playlistId}_2000000.webp")
        newCoverFile.writeBytes("new-image-data".toByteArray())
        assertTrue("New cover must exist", newCoverFile.exists())

        // Execute cleanup of old cover
        ImageUtils.deleteCoverFile(oldCoverFile.absolutePath)

        assertFalse("Old cover file must be deleted from disk", oldCoverFile.exists())
        assertTrue("New cover file must remain intact", newCoverFile.exists())
    }

    @Test
    fun coverPhotoFlow_coverRemoved_cleansUpFileAndSetsCoverNull() {
        val coversDir = tempFolder.newFolder("playlist_covers")
        val playlistId = 12L

        val coverFile = File(coversDir, "pl_${playlistId}_1710000000.webp")
        coverFile.writeBytes("cover-data".toByteArray())
        assertTrue(coverFile.exists())

        var playlist = Playlist(id = playlistId, name = "Favorites", coverPath = coverFile.absolutePath)
        assertTrue(hasCustomCover(playlist.coverPath))

        // Remove cover photo
        ImageUtils.deleteCoverFile(playlist.coverPath)
        playlist = playlist.copy(coverPath = null)

        assertFalse("Cover file should be deleted on removal", coverFile.exists())
        assertNull("Playlist coverPath should be null in state", playlist.coverPath)
        assertFalse("hasCustomCover must now return false for fallback", hasCustomCover(playlist.coverPath))
    }

    @Test
    fun coverPhotoFlow_rapidMultipleUpdates_cleansUpAllIntermediateFiles() {
        val coversDir = tempFolder.newFolder("playlist_covers")
        val playlistId = 99L

        val files = (1..5).map { step ->
            val file = File(coversDir, "pl_${playlistId}_${step * 1000}.webp")
            file.writeBytes("version-$step".toByteArray())
            file
        }

        // Simulate sequentially replacing 1 with 2, 2 with 3, 3 with 4, 4 with 5
        var currentPath = files[0].absolutePath
        for (i in 1 until files.size) {
            val nextPath = files[i].absolutePath
            ImageUtils.deleteCoverFile(currentPath)
            currentPath = nextPath
        }

        // Files 0..3 should be deleted, file 4 (the latest) must exist
        for (i in 0 until files.size - 1) {
            assertFalse("Intermediate file $i must be cleaned up", files[i].exists())
        }
        assertTrue("Latest cover file must remain on disk", files.last().exists())
    }

    @Test
    fun coverPhotoFlow_idempotentDelete_doesNotCrashOnNullOrMissing() {
        // Must not throw on null, blank, or non-existent files
        ImageUtils.deleteCoverFile(null)
        ImageUtils.deleteCoverFile("")
        ImageUtils.deleteCoverFile("   ")
        ImageUtils.deleteCoverFile("/does/not/exist/pl_none.webp")
    }

    @Test
    fun coverPhotoFlow_fileNameConformsToNamingContract() {
        val playlistId = 42L
        val timestamp = 1715000000000L
        val filename = "pl_${playlistId}_$timestamp.webp"

        val regex = Regex("^pl_\\d+_\\d+\\.webp$")
        assertTrue("Filename '$filename' must match pattern pl_<id>_<timestamp>.webp", regex.matches(filename))
    }

    @Test
    fun coverPhotoFlow_differentPlaylistsDoNotInterfere() {
        val coversDir = tempFolder.newFolder("playlist_covers")

        val pl1File = File(coversDir, "pl_1_100.webp")
        pl1File.writeBytes("pl1".toByteArray())

        val pl2File = File(coversDir, "pl_2_200.webp")
        pl2File.writeBytes("pl2".toByteArray())

        // Delete pl1 cover
        ImageUtils.deleteCoverFile(pl1File.absolutePath)

        assertFalse("Playlist 1 cover should be deleted", pl1File.exists())
        assertTrue("Playlist 2 cover must not be affected", pl2File.exists())
    }

    // =========================================================================
    // 4. SIMULATED REPOSITORY & ROOM PERSISTENCE FLOW TESTS
    // =========================================================================

    /**
     * In-memory mock representing Room PlaylistDao persistence operations
     */
    private class FakePlaylistDao(initialPlaylists: List<Playlist> = emptyList()) {
        private val db = initialPlaylists.associateBy { it.id }.toMutableMap()

        fun getPlaylist(id: Long): Playlist? = db[id]

        fun getCoverPath(id: Long): String? = db[id]?.coverPath

        fun updatePlaylistCover(id: Long, coverPath: String?) {
            val current = db[id] ?: return
            db[id] = current.copy(coverPath = coverPath)
        }

        fun deletePlaylist(id: Long) {
            db.remove(id)
        }
    }

    @Test
    fun roomSimulation_updatePlaylistCover_updatesCoverPathInDatabaseState() {
        val initialCover = tempFolder.newFile("pl_1_initial.webp")
        initialCover.writeBytes("initial".toByteArray())

        val dao = FakePlaylistDao(
            listOf(
                Playlist(id = 1L, name = "Favorites", coverPath = initialCover.absolutePath)
            )
        )

        assertEquals(initialCover.absolutePath, dao.getCoverPath(1L))

        // Create new cover
        val newCover = tempFolder.newFile("pl_1_updated.webp")
        newCover.writeBytes("updated".toByteArray())

        // Simulate repository updatePlaylistCover logic
        val oldCover = dao.getCoverPath(1L)
        if (oldCover != null && oldCover != newCover.absolutePath) {
            ImageUtils.deleteCoverFile(oldCover)
        }
        dao.updatePlaylistCover(1L, newCover.absolutePath)

        assertFalse("Old cover file should be deleted", initialCover.exists())
        assertTrue("New cover file should exist", newCover.exists())
        assertEquals(newCover.absolutePath, dao.getCoverPath(1L))
        assertEquals(newCover.absolutePath, dao.getPlaylist(1L)?.coverPath)
    }

    @Test
    fun roomSimulation_updateCoverWithSamePath_doesNotDeleteActiveFile() {
        val cover = tempFolder.newFile("pl_2_same.webp")
        cover.writeBytes("same".toByteArray())

        val dao = FakePlaylistDao(
            listOf(
                Playlist(id = 2L, name = "Rock", coverPath = cover.absolutePath)
            )
        )

        val oldCover = dao.getCoverPath(2L)
        if (oldCover != null && oldCover != cover.absolutePath) {
            ImageUtils.deleteCoverFile(oldCover)
        }
        dao.updatePlaylistCover(2L, cover.absolutePath)

        assertTrue("Updating with identical path must NOT delete the active file", cover.exists())
        assertEquals(cover.absolutePath, dao.getCoverPath(2L))
    }

    @Test
    fun roomSimulation_deletePlaylist_cleansUpAssociatedCoverFile() {
        val cover = tempFolder.newFile("pl_3_to_delete.webp")
        cover.writeBytes("data".toByteArray())

        val dao = FakePlaylistDao(
            listOf(
                Playlist(id = 3L, name = "Temp Playlist", coverPath = cover.absolutePath)
            )
        )

        // Simulate repository deletePlaylist logic
        val oldCover = dao.getCoverPath(3L)
        ImageUtils.deleteCoverFile(oldCover)
        dao.deletePlaylist(3L)

        assertFalse("Cover file must be deleted when playlist is deleted", cover.exists())
        assertNull("Playlist row must be removed from Room", dao.getPlaylist(3L))
    }
}
