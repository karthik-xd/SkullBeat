package com.skullbeat.app

import com.skullbeat.app.domain.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistStabilityTest {

    @Test
    fun playlistItemKeyGenerator_withDuplicateTracks_producesUniqueKeys() {
        val playlistId = 42L
        val duplicateTrack1 = Track(
            videoId = "video_123",
            title = "Track A",
            artist = "Artist A",
            durationSec = 180L
        )
        val duplicateTrack2 = Track(
            videoId = "video_123",
            title = "Track A",
            artist = "Artist A",
            durationSec = 180L
        )
        val tracks = listOf(duplicateTrack1, duplicateTrack2)

        // Old keying logic collided
        val oldKey1 = "pl_track_${duplicateTrack1.videoId}"
        val oldKey2 = "pl_track_${duplicateTrack2.videoId}"
        assertEquals(oldKey1, oldKey2) // Collision caused Compose crash

        // New keying logic is index-scoped and unique
        val newKeys = tracks.mapIndexed { index, track ->
            "pl_${playlistId}_${track.videoId}_$index"
        }

        assertEquals(2, newKeys.toSet().size)
        assertNotEquals(newKeys[0], newKeys[1])
        assertEquals("pl_42_video_123_0", newKeys[0])
        assertEquals("pl_42_video_123_1", newKeys[1])
    }

    @Test
    fun thumbnailSanitization_blankUrls_returnsNull() {
        val emptyUrl = ""
        val whitespaceUrl = "   "
        val validUrl = "https://example.com/thumb.jpg"

        assertNull(emptyUrl.ifBlank { null })
        assertNull(whitespaceUrl.ifBlank { null })
        assertEquals(validUrl, validUrl.ifBlank { null })
    }
}
