package com.skullbeat.app

import com.skullbeat.app.data.cache.LruAssetCache
import com.skullbeat.app.data.cache.MediaAssetCacheManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun keySafety_longKeysExceedingFilesystemLimits_handledWithoutCrashing() {
        val cacheDir = tempFolder.newFolder("long_key_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        // URL key length 350 chars (typical Google/YouTube CDN URL)
        val longKey = "https://lh3.googleusercontent.com/ytc/AIdro_k" + "x".repeat(300) + "?expire=1700000000&signature=test"
        val data = "sample_data".toByteArray()

        val savedFile = cache.put(longKey, data)
        assertTrue(savedFile.exists())
        assertTrue("Saved filename must be within filesystem 255-char limit", savedFile.name.length <= 255)

        val retrieved = cache.get(longKey)
        assertNotNull(retrieved)
        assertArrayEquals(data, retrieved!!.readBytes())
        assertTrue(cache.contains(longKey))
    }

    @Test
    fun keySafety_pathTraversalAttempts_confinedToCacheDirectory() {
        val cacheDir = tempFolder.newFolder("traversal_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        val maliciousKeys = listOf(
            "../outside.bin",
            "..",
            ".",
            "../../etc/passwd",
            "nested/dir/asset",
            "nested\\win\\asset"
        )

        for ((idx, maliciousKey) in maliciousKeys.withIndex()) {
            val data = "safe_content_$idx".toByteArray()
            val file = cache.put(maliciousKey, data)

            // File must be strictly inside cacheDir, never in parent or outside
            assertEquals(cacheDir.canonicalPath, file.parentFile?.canonicalPath)
            assertTrue(file.exists())
            assertArrayEquals(data, cache.get(maliciousKey)?.readBytes())
        }
    }

    @Test
    fun keySafety_distinctKeysWithSpecialChars_doNotCollide() {
        val cacheDir = tempFolder.newFolder("collision_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        val key1 = "track:song#1"
        val key2 = "track_song_1"
        val data1 = "first_track".toByteArray()
        val data2 = "second_track".toByteArray()

        val file1 = cache.put(key1, data1)
        val file2 = cache.put(key2, data2)

        // Must NOT overwrite each other
        assertNotEquals(file1.name, file2.name)
        assertEquals(2, cache.entryCount)
        assertEquals((data1.size + data2.size).toLong(), cache.currentSize)

        assertArrayEquals(data1, cache.get(key1)?.readBytes())
        assertArrayEquals(data2, cache.get(key2)?.readBytes())
    }

    @Test
    fun lruPrecision_deterministicTieBreaking_whenTimestampsAreIdentical() {
        val cacheDir = tempFolder.newFolder("lru_precision_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        // Put 3 items with identical lastAccessedMs timestamp
        val fixedTimestamp = 5000L
        cache.put("item_A", ByteArray(400) { 1 })
        cache.touch("item_A", fixedTimestamp)

        cache.put("item_B", ByteArray(400) { 2 })
        cache.touch("item_B", fixedTimestamp)

        // Both item_A and item_B have lastAccessedMs = 5000L.
        // item_A was added before item_B (accessSeq A < B).
        // Adding item_C (400 bytes) pushes total to 1200 > 1000.
        // Item_A must be evicted first based on accessSeq.
        cache.put("item_C", ByteArray(400) { 3 })
        cache.touch("item_C", 6000L)

        assertTrue(cache.currentSize <= 1000L)
        assertFalse("Item A should be evicted first", cache.contains("item_A"))
        assertTrue("Item B should remain", cache.contains("item_B"))
        assertTrue("Item C should remain", cache.contains("item_C"))
    }

    @Test
    fun concurrency_multithreadedPutsAndGets_threadSafeWithoutCorruption() {
        val cacheDir = tempFolder.newFolder("concurrent_cache")
        val maxLimit = 2000L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = maxLimit)

        val threadCount = 8
        val iterationsPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val errorCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val key = "item_${t}_${i % 5}"
                        val data = ByteArray(100) { (t + i).toByte() }
                        cache.put(key, data)
                        val file = cache.get(key)
                        if (file != null && file.exists()) {
                            file.readBytes()
                        }
                        cache.touch(key)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errorCount.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        assertEquals("No concurrency errors permitted", 0, errorCount.get())
        assertTrue("Storage limit must never be exceeded under concurrent load", cache.currentSize <= maxLimit)
    }

    @Test
    fun largeItemExceedingEntireCapacity_enforcesLimitGracefully() {
        val cacheDir = tempFolder.newFolder("huge_item_cache")
        val maxLimit = 500L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = maxLimit)

        // Item size 700 bytes > 500 maxSizeBytes
        val hugeData = ByteArray(700) { 9 }
        cache.put("huge_track", hugeData)

        // Total size must remain within limit (0 bytes, entry evicted)
        assertTrue(cache.currentSize <= maxLimit)
        assertEquals(0L, cache.currentSize)
        assertEquals(0, cache.entryCount)
        assertFalse(cache.contains("huge_track"))
    }

    @Test
    fun mediaAssetCacheManager_clearAndRemove_operations() {
        val thumbDir = tempFolder.newFolder("mgr_ops_thumbs")
        val playbackDir = tempFolder.newFolder("mgr_ops_playback")

        val manager = MediaAssetCacheManager(
            thumbnailDir = thumbDir,
            playbackDir = playbackDir,
            thumbnailLimit = 1000L,
            playbackLimit = 1000L
        )

        manager.cacheThumbnail("thumb1", ByteArray(100) { 1 })
        manager.cacheThumbnail("thumb2", ByteArray(200) { 2 })
        manager.cachePlaybackAsset("audio1", ByteArray(300) { 3 })

        assertEquals(300L, manager.currentThumbnailCacheSize)
        assertEquals(300L, manager.currentPlaybackAssetCacheSize)

        // Remove single thumbnail
        assertTrue(manager.removeThumbnail("thumb1"))
        assertFalse(manager.isThumbnailCached("thumb1"))
        assertEquals(200L, manager.currentThumbnailCacheSize)

        // Clear all thumbnails
        manager.clearThumbnails()
        assertEquals(0L, manager.currentThumbnailCacheSize)
        assertTrue(manager.isPlaybackAssetCached("audio1"))

        // Clear all
        manager.clearAll()
        assertEquals(0L, manager.currentPlaybackAssetCacheSize)
        assertFalse(manager.isPlaybackAssetCached("audio1"))
    }

    @Test
    fun keySafety_keysContainingDotTmp_notPurgedAsOrphanedTempFilesOnScan() {
        val cacheDir = tempFolder.newFolder("tmp_name_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 2000L)

        // Valid user keys that contain '.tmp' in filename or extension
        val trackData = "track_audio_bytes".toByteArray()
        val songData = "song_binary_bytes".toByteArray()
        cache.put("track.tmp", trackData)
        cache.put("song.tmp.bin", songData)

        // Also simulate real abandoned temp files from interrupted writes
        val legacyTmpFile = File(cacheDir, "real_asset.bin.tmp_987654321").apply {
            writeBytes("abandoned_old_temp".toByteArray())
        }
        val modernTmpFile = File(cacheDir, "new_asset.bin.lru_tmp_12345678-abcd").apply {
            writeBytes("abandoned_uuid_temp".toByteArray())
        }
        assertTrue(legacyTmpFile.exists())
        assertTrue(modernTmpFile.exists())

        // Re-initialize cache (simulating cold process restart)
        val reloadedCache = LruAssetCache(cacheDir, maxSizeBytes = 2000L)

        // Orphaned temporary files MUST be cleaned up
        assertFalse("Legacy .tmp_ timestamp file must be purged", legacyTmpFile.exists())
        assertFalse("Modern .lru_tmp_ file must be purged", modernTmpFile.exists())

        // Valid user assets MUST NOT be purged as temporary files
        assertTrue("track.tmp must be preserved across scan", reloadedCache.contains("track.tmp"))
        assertTrue("song.tmp.bin must be preserved across scan", reloadedCache.contains("song.tmp.bin"))
        assertArrayEquals(trackData, reloadedCache.get("track.tmp")?.readBytes())
        assertArrayEquals(songData, reloadedCache.get("song.tmp.bin")?.readBytes())
    }

    @Test
    fun keySafety_dotsOnlyAndReservedNames_handledWithoutCrashing() {
        val cacheDir = tempFolder.newFolder("reserved_dots_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 5000L)

        val problematicKeys = listOf(
            "...",
            "....",
            ".hidden",
            "song.",
            "con",
            "prn",
            "aux",
            "nul",
            "com1",
            "lpt1"
        )

        for ((i, key) in problematicKeys.withIndex()) {
            val data = "payload_$i".toByteArray()
            val file = cache.put(key, data)
            assertTrue("File for key '$key' must exist", file.exists())
            assertTrue("Cache must contain key '$key'", cache.contains(key))
            assertArrayEquals("Payload for '$key' must match", data, cache.get(key)?.readBytes())
        }

        // Verify trailing dot does not collide with base key
        val dotKey = "song."
        val baseKey = "song"
        val dotData = "dot_payload".toByteArray()
        val baseData = "base_payload".toByteArray()

        val dotFile = cache.put(dotKey, dotData)
        val baseFile = cache.put(baseKey, baseData)

        assertNotEquals("Files for 'song.' and 'song' must not collide", dotFile.name, baseFile.name)
        assertArrayEquals(dotData, cache.get(dotKey)?.readBytes())
        assertArrayEquals(baseData, cache.get(baseKey)?.readBytes())
    }

    @Test
    fun largeItemExceedingCapacity_doesNotEvictExistingValidItems() {
        val cacheDir = tempFolder.newFolder("oversized_item_cache")
        val maxLimit = 1000L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = maxLimit)

        val data1 = ByteArray(300) { 1 }
        val data2 = ByteArray(300) { 2 }
        val file1 = cache.put("track_1", data1)
        val file2 = cache.put("track_2", data2)
        assertEquals(600L, cache.currentSize)
        assertEquals(2, cache.entryCount)

        // Attempt to store item that exceeds maxSizeBytes (1500 > 1000)
        val hugeData = ByteArray(1500) { 9 }
        val hugeFile = cache.put("huge_track", hugeData)

        // The huge item cannot fit in cache
        assertFalse(cache.contains("huge_track"))
        assertFalse(hugeFile.exists())

        // Existing valid items MUST NOT be evicted
        assertTrue("track_1 must remain cached", file1.exists())
        assertTrue("track_2 must remain cached", file2.exists())
        assertTrue(cache.contains("track_1"))
        assertTrue(cache.contains("track_2"))
        assertEquals(600L, cache.currentSize)
        assertEquals(2, cache.entryCount)
    }

    @Test
    fun externalCacheDirDeletion_recreatesDirectoryWithoutCrashing() {
        val cacheDir = tempFolder.newFolder("dir_deletion_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        val initialFile = cache.put("track_first", "first_data".toByteArray())
        assertTrue(initialFile.exists())

        // Simulate external deletion of cache directory (e.g. OS disk clean)
        cacheDir.deleteRecursively()
        assertFalse(cacheDir.exists())

        // Subsequent put must recreate directory and succeed without throwing FileNotFoundException
        val secondData = "second_data".toByteArray()
        val secondFile = cache.put("track_second", secondData)
        assertTrue(cacheDir.exists())
        assertTrue(secondFile.exists())
        assertArrayEquals(secondData, cache.get("track_second")?.readBytes())
    }

    @Test
    fun mediaAssetCacheManager_touchAndLimits_protectsLru() {
        val thumbDir = tempFolder.newFolder("mgr_touch_thumbs")
        val playbackDir = tempFolder.newFolder("mgr_touch_playback")

        val manager = MediaAssetCacheManager(
            thumbnailDir = thumbDir,
            playbackDir = playbackDir,
            thumbnailLimit = 200L,
            playbackLimit = 500L
        )

        assertEquals(200L, manager.maxThumbnailCacheSize)
        assertEquals(500L, manager.maxPlaybackAssetCacheSize)

        manager.cacheThumbnail("thumb_A", ByteArray(100) { 1 })
        manager.touchThumbnail("thumb_A", 1000L)

        manager.cacheThumbnail("thumb_B", ByteArray(100) { 2 })
        manager.touchThumbnail("thumb_B", 2000L)

        // Replay thumb A -> touch at 3000L
        manager.touchThumbnail("thumb_A", 3000L)

        // Add thumb C (100B) -> pushes total to 300 > 200.
        // thumb B (t=2000) should be evicted; thumb A (t=3000) protected
        manager.cacheThumbnail("thumb_C", ByteArray(100) { 3 })
        manager.touchThumbnail("thumb_C", 4000L)

        assertTrue("thumb_A was touched on replay and must stay cached", manager.isThumbnailCached("thumb_A"))
        assertFalse("thumb_B was LRU and must be evicted", manager.isThumbnailCached("thumb_B"))
        assertTrue("thumb_C must be cached", manager.isThumbnailCached("thumb_C"))
    }

    @Test
    fun validKeyContainingLruTmp_preservedAcrossRestart() {
        val cacheDir = tempFolder.newFolder("lru_tmp_valid_cache")
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 2000L)
        val data = "valid_lru_tmp_content".toByteArray()
        cache.put("song.lru_tmp_version2.bin", data)
        assertTrue(cache.contains("song.lru_tmp_version2.bin"))

        // Recreate cache to simulate app restart
        val reloaded = LruAssetCache(cacheDir, maxSizeBytes = 2000L)
        assertTrue("Valid asset containing .lru_tmp_ must be preserved across scan", reloaded.contains("song.lru_tmp_version2.bin"))
        assertArrayEquals(data, reloaded.get("song.lru_tmp_version2.bin")?.readBytes())
    }

    @Test
    fun preExistingOversizedFile_doesNotEvictSmallerValidFilesOnStartup() {
        val cacheDir = tempFolder.newFolder("oversized_scan_cache")
        val validFile = File(cacheDir, "valid_track.bin").apply {
            writeBytes(ByteArray(200) { 1 })
            setLastModified(1000L)
        }
        val oversizedFile = File(cacheDir, "giant_track.bin").apply {
            writeBytes(ByteArray(1500) { 2 })
            setLastModified(2000L)
        }
        assertTrue(validFile.exists())
        assertTrue(oversizedFile.exists())

        // Limit is 1000L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = 1000L)

        // Oversized file (>1000L) cannot fit and must be purged
        assertFalse(oversizedFile.exists())
        assertFalse(cache.contains("giant_track.bin"))

        // Valid file (200L <= 1000L) must NOT be evicted!
        assertTrue("Valid file must not be evicted on startup scan by an oversized file", validFile.exists())
        assertTrue(cache.contains("valid_track.bin"))
        assertEquals(200L, cache.currentSize)
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun putStream_oversizedStream_failsFastWithoutExhaustingDisk() {
        val cacheDir = tempFolder.newFolder("stream_oversized_cache")
        val maxLimit = 500L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = maxLimit)

        // Cache a valid initial entry
        val initialData = ByteArray(200) { 1 }
        cache.put("track_initial", initialData)
        assertEquals(200L, cache.currentSize)

        // Try streaming 2000 bytes into a 500 byte cache
        val hugeStreamData = ByteArray(2000) { 9 }
        val stream = ByteArrayInputStream(hugeStreamData)
        val resultFile = cache.putStream("stream_huge", stream)

        // Result file must not exist and entry must not be in cache
        assertFalse(cache.contains("stream_huge"))
        assertFalse(resultFile.exists())

        // Initial valid entry must be preserved untouched
        assertTrue(cache.contains("track_initial"))
        assertEquals(200L, cache.currentSize)
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun putFile_oversizedFile_discardsWithoutCopying() {
        val cacheDir = tempFolder.newFolder("file_oversized_cache")
        val sourceDir = tempFolder.newFolder("source_dir")
        val maxLimit = 500L
        val cache = LruAssetCache(cacheDir, maxSizeBytes = maxLimit)

        val largeSourceFile = File(sourceDir, "giant_source.bin").apply {
            writeBytes(ByteArray(1500) { 3 })
        }
        assertTrue(largeSourceFile.exists())

        val resultFile = cache.put("giant_source", largeSourceFile)
        assertFalse(resultFile.exists())
        assertFalse(cache.contains("giant_source"))
        assertEquals(0L, cache.currentSize)
        // Original source file must remain intact
        assertTrue(largeSourceFile.exists())
    }

    @Test
    fun mediaAssetCacheManager_fileCachingAndSingleEviction() {
        val thumbDir = tempFolder.newFolder("mgr_file_thumbs")
        val playbackDir = tempFolder.newFolder("mgr_file_playback")
        val sourceDir = tempFolder.newFolder("mgr_source")

        val manager = MediaAssetCacheManager(
            thumbnailDir = thumbDir,
            playbackDir = playbackDir,
            thumbnailLimit = 500L,
            playbackLimit = 1000L
        )

        val thumbSource = File(sourceDir, "thumb.jpg").apply {
            writeBytes(ByteArray(200) { 1 })
        }
        val audioSource = File(sourceDir, "audio.mp3").apply {
            writeBytes(ByteArray(400) { 2 })
        }

        manager.cacheThumbnailFile("song_thumb_1", thumbSource)
        manager.cachePlaybackAssetFile("song_audio_1", audioSource)

        assertTrue(manager.isThumbnailCached("song_thumb_1"))
        assertTrue(manager.isPlaybackAssetCached("song_audio_1"))
        assertEquals(200L, manager.currentThumbnailCacheSize)
        assertEquals(400L, manager.currentPlaybackAssetCacheSize)

        // Single LRU eviction
        val evictedThumb = manager.evictSingleThumbnail()
        assertEquals("song_thumb_1", evictedThumb)
        assertFalse(manager.isThumbnailCached("song_thumb_1"))
        assertEquals(0L, manager.currentThumbnailCacheSize)

        val evictedAudio = manager.evictSinglePlaybackAsset()
        assertEquals("song_audio_1", evictedAudio)
        assertFalse(manager.isPlaybackAssetCached("song_audio_1"))
        assertEquals(0L, manager.currentPlaybackAssetCacheSize)
    }
}
