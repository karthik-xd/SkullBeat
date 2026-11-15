package com.skullbeat.app.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages local storage and caching of thumbnails and related song/playback assets
 * with fixed storage limits and item-by-item Least Recently Used (LRU) eviction.
 *
 * Defaults:
 * - Thumbnail cache: 250MB limit
 * - Playback asset cache: 1GB limit
 */
@Singleton
class MediaAssetCacheManager(
    val thumbnailCache: LruAssetCache,
    val playbackAssetCache: LruAssetCache
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        thumbnailCache = LruAssetCache(
            cacheDir = File(context.cacheDir, "media_thumbnail_cache"),
            maxSizeBytes = 250L * 1024 * 1024 // 250MB
        ),
        playbackAssetCache = LruAssetCache(
            cacheDir = File(context.cacheDir, "media_playback_cache"),
            maxSizeBytes = 1024L * 1024 * 1024 // 1GB
        )
    )

    constructor(
        thumbnailDir: File,
        playbackDir: File,
        thumbnailLimit: Long = 250L * 1024 * 1024,
        playbackLimit: Long = 1024L * 1024 * 1024
    ) : this(
        thumbnailCache = LruAssetCache(thumbnailDir, thumbnailLimit),
        playbackAssetCache = LruAssetCache(playbackDir, playbackLimit)
    )

    fun getCachedThumbnail(videoId: String): File? = thumbnailCache.get(videoId)

    fun cacheThumbnail(videoId: String, bytes: ByteArray): File = thumbnailCache.put(videoId, bytes)

    fun cacheThumbnailStream(videoId: String, stream: InputStream): File = thumbnailCache.putStream(videoId, stream)

    fun cacheThumbnailFile(videoId: String, file: File): File = thumbnailCache.put(videoId, file)

    fun getCachedPlaybackAsset(videoId: String): File? = playbackAssetCache.get(videoId)

    fun cachePlaybackAsset(videoId: String, bytes: ByteArray): File = playbackAssetCache.put(videoId, bytes)

    fun cachePlaybackAssetStream(videoId: String, stream: InputStream): File = playbackAssetCache.putStream(videoId, stream)

    fun cachePlaybackAssetFile(videoId: String, file: File): File = playbackAssetCache.put(videoId, file)

    fun isThumbnailCached(videoId: String): Boolean = thumbnailCache.contains(videoId)

    fun isPlaybackAssetCached(videoId: String): Boolean = playbackAssetCache.contains(videoId)

    fun evictSingleThumbnail(): String? = thumbnailCache.evictSingleLru()

    fun evictSinglePlaybackAsset(): String? = playbackAssetCache.evictSingleLru()

    fun touchThumbnail(videoId: String, timestampMs: Long = System.currentTimeMillis()) {
        thumbnailCache.touch(videoId, timestampMs)
    }

    fun touchPlaybackAsset(videoId: String, timestampMs: Long = System.currentTimeMillis()) {
        playbackAssetCache.touch(videoId, timestampMs)
    }

    fun removeThumbnail(videoId: String): Boolean = thumbnailCache.remove(videoId)

    fun removePlaybackAsset(videoId: String): Boolean = playbackAssetCache.remove(videoId)

    fun clearThumbnails() = thumbnailCache.clear()

    fun clearPlaybackAssets() = playbackAssetCache.clear()

    fun clearAll() {
        thumbnailCache.clear()
        playbackAssetCache.clear()
    }

    val currentThumbnailCacheSize: Long
        get() = thumbnailCache.currentSize

    val currentPlaybackAssetCacheSize: Long
        get() = playbackAssetCache.currentSize

    val maxThumbnailCacheSize: Long
        get() = thumbnailCache.maxSizeBytes

    val maxPlaybackAssetCacheSize: Long
        get() = playbackAssetCache.maxSizeBytes
}
