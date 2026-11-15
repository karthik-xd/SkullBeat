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
class MediaAssetCacheManager @Inject constructor(
    @ApplicationContext context: Context
) {
    val thumbnailCache: LruAssetCache = LruAssetCache(
        cacheDir = File(context.cacheDir, "thumbnail_cache"),
        maxSizeBytes = 250L * 1024 * 1024 // 250MB
    )

    val playbackAssetCache: LruAssetCache = LruAssetCache(
        cacheDir = File(context.cacheDir, "playback_assets"),
        maxSizeBytes = 1024L * 1024 * 1024 // 1GB
    )

    fun getCachedThumbnail(videoId: String): File? = thumbnailCache.get(videoId)

    fun cacheThumbnail(videoId: String, bytes: ByteArray): File = thumbnailCache.put(videoId, bytes)

    fun getCachedPlaybackAsset(videoId: String): File? = playbackAssetCache.get(videoId)

    fun cachePlaybackAsset(videoId: String, bytes: ByteArray): File = playbackAssetCache.put(videoId, bytes)

    fun cachePlaybackAssetStream(videoId: String, stream: InputStream): File = playbackAssetCache.putStream(videoId, stream)

    fun isThumbnailCached(videoId: String): Boolean = thumbnailCache.contains(videoId)

    fun isPlaybackAssetCached(videoId: String): Boolean = playbackAssetCache.contains(videoId)
}
