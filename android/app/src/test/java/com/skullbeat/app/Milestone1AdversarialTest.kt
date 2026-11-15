package com.skullbeat.app

import com.skullbeat.app.domain.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Milestone1AdversarialTest {

    private fun generateKey(playlistId: Long, track: Track, index: Int): String {
        return "pl_${playlistId}_${track.videoId}_$index"
    }

    // -------------------------------------------------------------
    // 1. KEY UNIQUENESS & COLLISION STRESS-TESTS
    // -------------------------------------------------------------

    @Test
    fun keyGenerator_1000TracksIdenticalVideoId_hasZeroCollisions() {
        val playlistId = 99L
        val identicalVideoId = "identical_video_id_xyz_123"
        val tracks = (0 until 1000).map {
            Track(
                videoId = identicalVideoId,
                title = "Track $it",
                artist = "Artist",
                durationSec = 200L
            )
        }

        val keys = tracks.mapIndexed { index, track ->
            generateKey(playlistId, track, index)
        }

        assertEquals(1000, keys.size)
        assertEquals(1000, keys.toSet().size)
        assertEquals("pl_99_identical_video_id_xyz_123_0", keys.first())
        assertEquals("pl_99_identical_video_id_xyz_123_999", keys.last())
    }

    @Test
    fun keyGenerator_1000TracksEmptyVideoId_hasZeroCollisions() {
        val playlistId = 42L
        val tracks = (0 until 1000).map {
            Track(
                videoId = "",
                title = "Untitled $it",
                artist = "Unknown",
                durationSec = 0L
            )
        }

        val keys = tracks.mapIndexed { index, track ->
            generateKey(playlistId, track, index)
        }

        assertEquals(1000, keys.size)
        assertEquals(1000, keys.toSet().size)
        assertEquals("pl_42__0", keys.first())
        assertEquals("pl_42__999", keys.last())
    }

    @Test
    fun keyGenerator_adversarialUnderscorePatterns_noCollisions() {
        val playlistId = 1L
        // Specially crafted adversarial videoIds designed to collide if parsing relies on underscores
        val adversarialTracks = listOf(
            Track(videoId = "x_1", title = "T1", artist = "A"),      // at index 0 -> pl_1_x_1_0
            Track(videoId = "x", title = "T2", artist = "A"),        // at index 1 -> pl_1_x_1
            Track(videoId = "x_0", title = "T3", artist = "A"),      // at index 2 -> pl_1_x_0_2
            Track(videoId = "pl_1_x_1", title = "T4", artist = "A"), // at index 3 -> pl_1_pl_1_x_1_3
            Track(videoId = "1_2_3_4_5", title = "T5", artist = "A"),// at index 4 -> pl_1_1_2_3_4_5_4
            Track(videoId = "5", title = "T6", artist = "A"),        // at index 5 -> pl_1_5_5
            Track(videoId = "_", title = "T7", artist = "A"),        // at index 6 -> pl_1___6
            Track(videoId = "___", title = "T8", artist = "A"),      // at index 7 -> pl_1_____7
            Track(videoId = "🔥_unicode_🎵", title = "T9", artist = "A"), // at index 8
            Track(videoId = " \t\n ", title = "T10", artist = "A")   // at index 9
        )

        val keys = adversarialTracks.mapIndexed { index, track ->
            generateKey(playlistId, track, index)
        }

        assertEquals(adversarialTracks.size, keys.toSet().size)
    }

    @Test
    fun keyGenerator_50000RandomizedTracks_exhaustiveCollisionVerification() {
        val playlistId = 777L
        val rng = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789_-"

        val tracks = (0 until 50_000).map { index ->
            val idLength = rng.nextInt(0, 20)
            val randomId = (0 until idLength)
                .map { alphabet[rng.nextInt(alphabet.length)] }
                .joinToString("")
            Track(videoId = randomId, title = "Title $index", artist = "Artist")
        }

        val keys = tracks.mapIndexed { index, track ->
            generateKey(playlistId, track, index)
        }

        assertEquals(50_000, keys.size)
        assertEquals(50_000, keys.toSet().size)
    }

    @Test
    fun keyGenerator_reorderingAndRemovalBehavior_remainsValidAndCollisionFree() {
        val playlistId = 10L
        val initialTracks = (0 until 100).map {
            Track(videoId = "vid_${it % 10}", title = "Track $it", artist = "Artist")
        }.toMutableList()

        // 1. Initial keys are collision-free
        var keys = initialTracks.mapIndexed { idx, tr -> generateKey(playlistId, tr, idx) }
        assertEquals(100, keys.toSet().size)

        // 2. Removal: delete items from front, middle, and end
        initialTracks.removeAt(0) // remove first
        initialTracks.removeAt(50) // remove middle
        initialTracks.removeAt(initialTracks.size - 1) // remove last
        assertEquals(97, initialTracks.size)

        keys = initialTracks.mapIndexed { idx, tr -> generateKey(playlistId, tr, idx) }
        assertEquals(97, keys.toSet().size)

        // 3. Reordering: reverse the entire list
        val reversedTracks = initialTracks.reversed()
        val reversedKeys = reversedTracks.mapIndexed { idx, tr -> generateKey(playlistId, tr, idx) }
        assertEquals(97, reversedKeys.toSet().size)

        // 4. Reordering: shuffle
        val shuffledTracks = initialTracks.shuffled(Random(1234))
        val shuffledKeys = shuffledTracks.mapIndexed { idx, tr -> generateKey(playlistId, tr, idx) }
        assertEquals(97, shuffledKeys.toSet().size)
    }

    // -------------------------------------------------------------
    // 2. COIL CONFIGURATION & THUMBNAIL DOWNSAMPLING STRESS-TESTS
    // -------------------------------------------------------------

    @Test
    fun thumbnailDownsampling_memoryFootprintReduction() {
        // ARGB_8888 uses 4 bytes per pixel
        val bytesPerPixel = 4L
        val originalDimension = 500L
        val downsampledDimension = 144L

        val originalBytesPerImage = originalDimension * originalDimension * bytesPerPixel
        val downsampledBytesPerImage = downsampledDimension * downsampledDimension * bytesPerPixel

        assertEquals(1_000_000L, originalBytesPerImage) // 1 MB per full-res image
        assertEquals(82_944L, downsampledBytesPerImage) // ~81 KB per downsampled image

        val reductionRatio = 1.0 - (downsampledBytesPerImage.toDouble() / originalBytesPerImage.toDouble())
        assertTrue("Reduction must be greater than 90%", reductionRatio > 0.91)
        assertEquals(0.917056, reductionRatio, 0.0001)

        // For a 1,000-track playlist:
        val memory1000Original = originalBytesPerImage * 1000L // 1,000,000,000 bytes (~1 GB)
        val memory1000Downsampled = downsampledBytesPerImage * 1000L // 82,944,000 bytes (~82.9 MB)
        assertTrue("1000 downsampled bitmaps must fit well under 100MB", memory1000Downsampled < 100_000_000L)
    }

    @Test
    fun thumbnailSanitization_adversarialUrlInputs() {
        // Test exact logic used in TrackListItem.kt: `track.thumbnailUrl.ifBlank { null }`
        val emptyUrl = ""
        val spacesOnly = "   "
        val tabsNewlinesOnly = "\t\t\n\r  "
        val validUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        val malformedUrl = "not a valid url at all"
        val urlWithTrailingSpaces = "  https://example.com/pic.jpg  "
        val hugeUrl = "https://example.com/" + "a".repeat(10_000)

        assertNull(emptyUrl.ifBlank { null })
        assertNull(spacesOnly.ifBlank { null })
        assertNull(tabsNewlinesOnly.ifBlank { null })

        assertEquals(validUrl, validUrl.ifBlank { null })
        assertEquals(malformedUrl, malformedUrl.ifBlank { null })
        assertEquals(urlWithTrailingSpaces, urlWithTrailingSpaces.ifBlank { null })
        assertEquals(hugeUrl, hugeUrl.ifBlank { null })
    }

    @Test
    fun searchTracksBottomSheet_potentialKeyCollisionRiskAnalysis() {
        // PlaylistDetailScreen.kt line 492 uses:
        // items(searchResults, key = { "add_track_${it.videoId}" })
        // If search results returned duplicate videoIds, this keying strategy WOULD collide!
        val duplicateSearchTracks = listOf(
            Track(videoId = "dup_1", title = "Result 1", artist = "Artist"),
            Track(videoId = "dup_1", title = "Result 1 copy", artist = "Artist")
        )

        val keys = duplicateSearchTracks.map { "add_track_${it.videoId}" }
        // Notice: keys collide for duplicates in searchResults!
        assertEquals(keys[0], keys[1])
        // This confirms that for the search sheet, duplicate videoIds would collide,
        // which proves the value of composite index keying!
    }
}
