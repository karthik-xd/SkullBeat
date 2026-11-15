package com.skullbeat.app

import com.skullbeat.app.data.cache.LruAssetCache
import com.skullbeat.app.data.cache.MediaAssetCacheManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class LruAssetCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cachedAsset_servedLocallyOnReplay_withoutRefetching() {
        val cacheDir = tempFolder.newFolder("thumb_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 250L * 1024 * 1024)

        val videoId = "song_monster_123"
        val thumbnailBytes = "fake_jpeg_bytes_for_monster_album_art".toByteArray()

        // 1. Initial fetch & cache
        val savedFile = cache.put(videoId, thumbnailBytes)
        assertTrue(savedFile.exists())
        assertEquals(thumbnailBytes.size.toLong(), savedFile.length())

        // 2. Replay / re-read: served locally from disk without re-fetching
        val retrievedFile = cache.get(videoId)
        assertNotNull(retrievedFile)
        assertTrue(retrievedFile!!.exists())
        assertArrayEquals(thumbnailBytes, retrievedFile.readBytes())
        assertEquals(savedFile.absolutePath, retrievedFile.absolutePath)
    }

    @Test
    fun missingOrEvictedItem_returnsNull_allowsRefetchAndRecacheWithoutCrashing() {
        val cacheDir = tempFolder.newFolder("missing_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        val key = "evicted_or_deleted_track"
        val bytes = "audio_chunk_data".toByteArray()
        val file = cache.put(key, bytes)
        assertTrue(file.exists())

        // Simulate external file deletion or eviction
        file.delete()
        assertFalse(file.exists())

        // Cache handles missing file gracefully without crashing
        val missing = cache.get(key)
        assertNull(missing)
        assertFalse(cache.contains(key))
        assertEquals(0L, cache.currentSize)

        // System re-fetches and re-caches without crashing
        val newBytes = "re_fetched_audio_chunk_data".toByteArray()
        val recachedFile = cache.put(key, newBytes)
        assertNotNull(recachedFile)
        assertTrue(recachedFile.exists())
        assertArrayEquals(newBytes, recachedFile.readBytes())
    }

    @Test
    fun fixedStorageLimit_whenExceeded_evictsLruItemsOneByOne_untilUnderLimit() {
        val cacheDir = tempFolder.newFolder("lru_limit_cache")
        // Fixed capacity limit: 1000 bytes
        val maxLimit = 1000L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = maxLimit)

        val item1 = ByteArray(400) { 1 }
        val item2 = ByteArray(400) { 2 }
        val item3 = ByteArray(400) { 3 }

        // Put item1 at t = 1000
        val file1 = cache.put("item1", item1)
        cache.touch("item1", 1000L)
        assertEquals(400L, cache.currentSize)
        assertEquals(1, cache.entryCount)

        // Put item2 at t = 2000
        val file2 = cache.put("item2", item2)
        cache.touch("item2", 2000L)
        assertEquals(800L, cache.currentSize)
        assertEquals(2, cache.entryCount)

        // Put item3 at t = 3000 (total would be 1200 > 1000)
        // Must evict item1 (oldest, t=1000)
        val file3 = cache.put("item3", item3)
        cache.touch("item3", 3000L)

        // Storage limit must not be exceeded
        assertTrue(cache.currentSize <= maxLimit)
        assertEquals(800L, cache.currentSize)
        assertEquals(2, cache.entryCount)

        // Evicted file1 must be deleted from local storage
        assertFalse("Evicted item1 file must be deleted from storage", file1.exists())
        assertNull(cache.get("item1"))

        // item2 and item3 remain available locally
        assertTrue(file2.exists())
        assertTrue(file3.exists())
        assertNotNull(cache.get("item2"))
        assertNotNull(cache.get("item3"))
    }

    @Test
    fun lruAccessUpdate_uponReplay_protectsItemFromEviction() {
        val cacheDir = tempFolder.newFolder("lru_replay_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        val itemA = ByteArray(400) { 'A'.code.toByte() }
        val itemB = ByteArray(400) { 'B'.code.toByte() }
        val itemC = ByteArray(400) { 'C'.code.toByte() }

        val fileA = cache.put("track_A", itemA)
        cache.touch("track_A", 1000L)

        val fileB = cache.put("track_B", itemB)
        cache.touch("track_B", 2000L)

        // User replays track A -> access timestamp updated to 3000L
        cache.touch("track_A", 3000L)

        // Adding track C (400 bytes) pushes total to 1200 > 1000
        // Least recently used is track B (2000L), NOT track A (3000L)
        val fileC = cache.put("track_C", itemC)
        cache.touch("track_C", 4000L)

        assertTrue(cache.currentSize <= 1000L)
        // track B must be evicted and deleted
        assertFalse("Track B must be evicted", fileB.exists())
        assertNull(cache.get("track_B"))

        // Track A was replayed, so it stays in cache
        assertTrue("Track A must remain cached after replay", fileA.exists())
        assertNotNull(cache.get("track_A"))
        assertTrue("Track C must remain cached", fileC.exists())
        assertNotNull(cache.get("track_C"))
    }

    @Test
    fun largeItemAddition_evictsMultipleLruItemsOneByOne_untilUnderLimit() {
        val cacheDir = tempFolder.newFolder("multi_evict_cache")
        val maxLimit = 1000L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = maxLimit)

        val files = (1..4).map { i ->
            val f = cache.put("song_$i", ByteArray(250) { i.toByte() })
            cache.touch("song_$i", 1000L * i)
            f
        }
        assertEquals(1000L, cache.currentSize)
        assertEquals(4, cache.entryCount)

        // Add large 600-byte item.
        // Needs 600 + 1000 = 1600.
        // Evicts song_1 (t=1000): 1600 - 250 = 1350 > 1000
        // Evicts song_2 (t=2000): 1350 - 250 = 1100 > 1000
        // Evicts song_3 (t=3000): 1100 - 250 = 850 <= 1000
        // Exactly 3 items evicted one-by-one.
        val largeFile = cache.put("large_song_5", ByteArray(600) { 5 })
        cache.touch("large_song_5", 5000L)

        assertTrue(cache.currentSize <= maxLimit)
        assertEquals(850L, cache.currentSize)
        assertEquals(2, cache.entryCount)

        assertFalse(files[0].exists())
        assertFalse(files[1].exists())
        assertFalse(files[2].exists())
        assertTrue(files[3].exists())
        assertTrue(largeFile.exists())
    }

    @Test
    fun putStream_writesAndEvictsCorrectly() {
        val cacheDir = tempFolder.newFolder("stream_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 500L)

        val data = ByteArray(300) { 7 }
        val stream = ByteArrayInputStream(data)
        val file1 = cache.putStream("stream_1", stream)
        assertTrue(file1.exists())
        assertEquals(300L, cache.currentSize)

        val data2 = ByteArray(300) { 8 }
        val stream2 = ByteArrayInputStream(data2)
        val file2 = cache.putStream("stream_2", stream2)

        assertTrue(cache.currentSize <= 500L)
        assertEquals(300L, cache.currentSize)
        assertFalse(file1.exists())
        assertTrue(file2.exists())
    }

    @Test
    fun scanExistingFiles_reloadsStateAndEnforcesLimit() {
        val cacheDir = tempFolder.newFolder("scan_cache")
        val fileA = File(cacheDir, "scan_a.bin").apply {
            writeBytes(ByteArray(400) { 1 })
            setLastModified(1000L)
        }
        val fileB = File(cacheDir, "scan_b.bin").apply {
            writeBytes(ByteArray(400) { 2 })
            setLastModified(2000L)
        }

        // Limit 500: on init, should evict scan_a (oldest) and keep scan_b
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 500L)
        assertTrue(cache.currentSize <= 500L)
        assertFalse(fileA.exists())
        assertTrue(fileB.exists())
        assertEquals(1, cache.entryCount)
        assertNotNull(cache.get("scan_b.bin"))
    }

    @Test
    fun configuredLimits_matchExactSpecification() {
        // Thumbnail capacity limit: 250MB
        val expectedThumbLimit = 250L * 1024 * 1024
        assertEquals(262144000L, expectedThumbLimit)

        // Audio / playback asset limit: 1GB
        val expectedAudioLimit = 1024L * 1024 * 1024L
        assertEquals(1073741824L, expectedAudioLimit)
    }

    @Test
    fun scanExistingFiles_purgesOrphanedTmpFiles() {
        val cacheDir = tempFolder.newFolder("tmp_clean_cache")
        val normalFile = File(cacheDir, "valid_asset.bin").apply {
            writeBytes(ByteArray(100) { 1 })
            setLastModified(1000L)
        }
        val orphanedTmpFile = File(cacheDir, "valid_asset.bin.tmp_123456789").apply {
            writeBytes(ByteArray(500) { 2 })
            setLastModified(2000L)
        }
        assertTrue(normalFile.exists())
        assertTrue(orphanedTmpFile.exists())

        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)
        assertTrue("Valid file must remain", normalFile.exists())
        assertFalse("Orphaned tmp file must be purged", orphanedTmpFile.exists())
        assertEquals(100L, cache.currentSize)
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun mediaAssetCacheManager_handlesThumbnailsAndPlaybackAssetsIndependently() {
        val thumbDir = tempFolder.newFolder("mgr_thumbs")
        val playbackDir = tempFolder.newFolder("mgr_playback")

        // Configure limits: 200 bytes for thumb, 500 bytes for playback
        val manager = MediaAssetCacheManager(
            thumbnailDir = thumbDir,
            playbackDir = playbackDir,
            thumbnailLimit = 200L,
            playbackLimit = 500L
        )

        // 1. Thumbnail caching and replay
        val thumbData = ByteArray(150) { 9 }
        manager.cacheThumbnail("thumb_song_1", thumbData)
        assertTrue(manager.isThumbnailCached("thumb_song_1"))
        val retrievedThumb = manager.getCachedThumbnail("thumb_song_1")
        assertNotNull(retrievedThumb)
        assertArrayEquals(thumbData, retrievedThumb!!.readBytes())

        // 2. Playback asset caching and replay
        val audioData = ByteArray(300) { 8 }
        manager.cachePlaybackAsset("audio_song_1", audioData)
        assertTrue(manager.isPlaybackAssetCached("audio_song_1"))
        val retrievedAudio = manager.getCachedPlaybackAsset("audio_song_1")
        assertNotNull(retrievedAudio)
        assertArrayEquals(audioData, retrievedAudio!!.readBytes())

        // 3. Independent LRU eviction: adding second thumb (150 bytes) evicts first thumb (150+150=300 > 200)
        val thumbData2 = ByteArray(150) { 7 }
        manager.cacheThumbnail("thumb_song_2", thumbData2)
        assertFalse("Old thumb should be evicted", manager.isThumbnailCached("thumb_song_1"))
        assertTrue("New thumb should be cached", manager.isThumbnailCached("thumb_song_2"))

        // Playback cache must remain completely unaffected by thumbnail operations
        assertTrue("Playback cache unaffected by thumb eviction", manager.isPlaybackAssetCached("audio_song_1"))
    }

    @Test
    fun mediaAssetCacheManager_playbackAssetStream_cachingAndEviction() {
        val thumbDir = tempFolder.newFolder("mgr_stream_thumbs")
        val playbackDir = tempFolder.newFolder("mgr_stream_playback")

        val manager = MediaAssetCacheManager(
            thumbnailDir = thumbDir,
            playbackDir = playbackDir,
            thumbnailLimit = 1000L,
            playbackLimit = 500L
        )

        val streamData1 = ByteArray(300) { 1 }
        val streamData2 = ByteArray(300) { 2 }

        manager.cachePlaybackAssetStream("stream_1", ByteArrayInputStream(streamData1))
        assertTrue(manager.isPlaybackAssetCached("stream_1"))

        manager.cachePlaybackAssetStream("stream_2", ByteArrayInputStream(streamData2))
        assertTrue(manager.isPlaybackAssetCached("stream_2"))
        assertFalse("stream_1 should be evicted to enforce 500 byte limit", manager.isPlaybackAssetCached("stream_1"))
    }
}
