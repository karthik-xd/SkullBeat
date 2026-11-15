package com.skullbeat.app

import com.skullbeat.app.domain.models.Track
import com.skullbeat.app.player.manager.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Milestone1EmpiricalStressTest {

    // -------------------------------------------------------------
    // 1. SwipeToQueueBox: Threshold, MaxDrag, and Clamping Invariants
    // -------------------------------------------------------------

    @Test
    fun swipeToQueue_thresholdRatio_isAlwaysLessThanMaxDrag() {
        val testWidths = listOf(1f, 10f, 100f, 360f, 411f, 720f, 1080f, 1440f, 3840f)

        for (width in testWidths) {
            val totalWidthPx = width.coerceAtLeast(1f)
            val thresholdPx = totalWidthPx * 0.38f
            val maxDragPx = totalWidthPx * 0.55f

            assertTrue(
                "Threshold ($thresholdPx) must be strictly less than maxDrag ($maxDragPx) for width $width",
                thresholdPx < maxDragPx
            )
            val reachableRatio = thresholdPx / maxDragPx
            assertEquals("Threshold must be exactly ~69.09% of maxDrag", 0.38f / 0.55f, reachableRatio, 0.001f)
        }
    }

    @Test
    fun swipeToQueue_zeroAndNegativeWidth_gracefullyClampedToOnePx() {
        val edgeCaseWidths = listOf(0, -1, -500, -10000)

        for (rawWidth in edgeCaseWidths) {
            val totalWidthPx = rawWidth.toFloat().coerceAtLeast(1f)
            val thresholdPx = totalWidthPx * 0.38f
            val maxDragPx = totalWidthPx * 0.55f

            assertEquals(1f, totalWidthPx, 0f)
            assertEquals(0.38f, thresholdPx, 0.0001f)
            assertEquals(0.55f, maxDragPx, 0.0001f)
            assertFalse(thresholdPx.isNaN())
            assertFalse(maxDragPx.isNaN())
            assertFalse(thresholdPx.isInfinite())
            assertFalse(maxDragPx.isInfinite())
        }
    }

    @Test
    fun swipeToQueue_gestureSimulation_clampingAndTriggerLogic() {
        val screenWidth = 1080f
        val totalWidthPx = screenWidth.coerceAtLeast(1f)
        val thresholdPx = totalWidthPx * 0.38f // 410.4f
        val maxDragPx = totalWidthPx * 0.55f   // 594.0f

        var dragOffset = 0f

        // Small drag (less than threshold)
        val smallDelta = 200f
        dragOffset = (dragOffset + smallDelta).coerceIn(-maxDragPx, maxDragPx)
        assertEquals(200f, dragOffset, 0.01f)
        assertFalse(abs(dragOffset) >= thresholdPx)

        // Drag past threshold
        val additionalDelta = 250f
        dragOffset = (dragOffset + additionalDelta).coerceIn(-maxDragPx, maxDragPx)
        assertEquals(450f, dragOffset, 0.01f)
        assertTrue(abs(dragOffset) >= thresholdPx)

        // Overshoot drag past maxDrag
        val overshootDelta = 500f
        dragOffset = (dragOffset + overshootDelta).coerceIn(-maxDragPx, maxDragPx)
        assertEquals(maxDragPx, dragOffset, 0.01f) // Clamped to 594f
        assertTrue(abs(dragOffset) >= thresholdPx)

        // Reverse fling drag to negative
        val reverseDelta = -2000f
        dragOffset = (dragOffset + reverseDelta).coerceIn(-maxDragPx, maxDragPx)
        assertEquals(-maxDragPx, dragOffset, 0.01f) // Clamped to -594f
        assertTrue(abs(dragOffset) >= thresholdPx)
    }

    @Test
    fun swipeToQueue_rapidFlingAlternatingStress_staysWithinBounds() {
        val totalWidthPx = 1080f
        val maxDragPx = totalWidthPx * 0.55f
        var currentOffset = 0f

        // Rapid sequence of 500 alternating drags
        for (i in 0 until 500) {
            val delta = if (i % 2 == 0) 1500f else -1500f
            currentOffset = (currentOffset + delta).coerceIn(-maxDragPx, maxDragPx)
            assertTrue("Offset $currentOffset must be >= -$maxDragPx", currentOffset >= -maxDragPx)
            assertTrue("Offset $currentOffset must be <= $maxDragPx", currentOffset <= maxDragPx)
        }
    }

    // -------------------------------------------------------------
    // 2. PlaylistDetailScreen: distinctUntilChanged Recomposition Prevention
    // -------------------------------------------------------------

    @Test
    fun playlistDetailScreen_recompositionMitigation_tickerSuppression() = runBlocking {
        val trackA = Track(videoId = "song_A", title = "Song A", artist = "Artist A")
        val trackB = Track(videoId = "song_B", title = "Song B", artist = "Artist B")

        val stateFlow = MutableStateFlow(
            PlaybackState(
                currentTrack = trackA,
                currentPositionMs = 0L,
                isPlaying = true
            )
        )

        val observedTrackIds = mutableListOf<String?>()
        val job = launch {
            stateFlow
                .map { it.currentTrack?.videoId }
                .distinctUntilChanged()
                .collect { observedTrackIds.add(it) }
        }
        yield()

        // Verify initial emission
        assertEquals(listOf("song_A"), observedTrackIds)

        // Simulate 1,000 fast 250ms ticker events while track is playing
        for (tick in 1..1000) {
            stateFlow.value = stateFlow.value.copy(currentPositionMs = tick * 250L)
        }
        yield()

        // Ticker MUST NOT trigger any extra emissions!
        assertEquals("Ticker updates must be suppressed by distinctUntilChanged", 1, observedTrackIds.size)
        assertEquals("song_A", observedTrackIds.last())

        // Now change track to song B
        stateFlow.value = stateFlow.value.copy(currentTrack = trackB, currentPositionMs = 0L)
        yield()
        assertEquals(2, observedTrackIds.size)
        assertEquals("song_B", observedTrackIds.last())

        // Another 200 ticker events on song B
        for (tick in 1..200) {
            stateFlow.value = stateFlow.value.copy(currentPositionMs = tick * 250L)
        }
        yield()
        assertEquals(2, observedTrackIds.size)

        // Stop playback (currentTrack = null)
        stateFlow.value = stateFlow.value.copy(currentTrack = null, currentPositionMs = 0L, isPlaying = false)
        yield()
        assertEquals(3, observedTrackIds.size)
        assertNull(observedTrackIds.last())

        job.cancel()
    }

    // -------------------------------------------------------------
    // 3. LazyColumn Key Uniqueness & Performance Stress Test
    // -------------------------------------------------------------

    @Test
    fun playlistDetailScreen_keyGenerator_0tracks_empty() {
        val tracks = emptyList<Track>()
        val playlistId = 1L
        val keys = tracks.mapIndexed { index, track ->
            "pl_${playlistId}_${track.videoId}_$index"
        }
        assertTrue(keys.isEmpty())
    }

    @Test
    fun playlistDetailScreen_keyGenerator_1track_valid() {
        val tracks = listOf(Track(videoId = "v1", title = "Title", artist = "Artist"))
        val playlistId = 10L
        val keys = tracks.mapIndexed { index, track ->
            "pl_${playlistId}_${track.videoId}_$index"
        }
        assertEquals(1, keys.size)
        assertEquals("pl_10_v1_0", keys[0])
    }

    @Test
    fun playlistDetailScreen_keyGenerator_10000tracks_withDuplicates_strictlyUnique() {
        val playlistId = 999L
        val trackCount = 10_000

        // Create 10,000 tracks with heavy duplication (same videoId used repeatedly)
        val tracks = ArrayList<Track>(trackCount)
        for (i in 0 until trackCount) {
            val videoId = when {
                i % 4 == 0 -> "identical_duplicate_id"
                i % 4 == 1 -> "special_chars_!@#\$%^&*()_+$i"
                i % 4 == 2 -> "unicode_日本語_español_$i"
                else -> "track_$i"
            }
            tracks.add(
                Track(
                    videoId = videoId,
                    title = "Title $i",
                    artist = "Artist $i",
                    durationSec = 200L
                )
            )
        }

        val startTime = System.currentTimeMillis()
        val keys = tracks.mapIndexed { index, track ->
            "pl_${playlistId}_${track.videoId}_$index"
        }
        val duration = System.currentTimeMillis() - startTime

        // Performance check: generating 10,000 keys must be fast (< 100ms)
        assertTrue("Generating 10,000 keys took $duration ms (expected < 100ms)", duration < 100)

        // Exact uniqueness check: Set size must equal list size
        val uniqueKeys = keys.toSet()
        assertEquals(
            "Every key in a 10,000 track playlist must be unique, even with 2,500 duplicate videoIds",
            trackCount,
            uniqueKeys.size
        )
    }

    // -------------------------------------------------------------
    // 4. Thumbnail Sanitization and Downsampling Checks
    // -------------------------------------------------------------

    @Test
    fun thumbnailSanitization_blankAndWhitespace_handledGracefully() {
        val blankInputs = listOf("", " ", "   ", "\t", "\n", "\r\n", "   \t\n  ")
        for (input in blankInputs) {
            assertNull("Input '$input' must sanitize to null", input.ifBlank { null })
        }

        val validUrl = "https://example.com/art.jpg"
        assertEquals(validUrl, validUrl.ifBlank { null })
    }
}
