package com.skullbeat.app.data.cache

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe local asset disk cache with a fixed capacity limit and
 * item-by-item Least Recently Used (LRU) eviction.
 *
 * @param cacheDir Directory on local storage where assets are stored
 * @param maxSizeBytes Fixed maximum capacity limit in bytes
 */
class LruAssetCache(
    val cacheDir: File,
    val maxSizeBytes: Long
) {
    data class CacheEntry(
        val key: String,
        val file: File,
        var sizeBytes: Long,
        var lastAccessedMs: Long
    )

    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<String, CacheEntry>()
    private var totalSizeBytes: Long = 0L

    init {
        require(maxSizeBytes > 0) { "maxSizeBytes must be greater than 0" }
        lock.withLock {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            scanExistingFiles()
        }
    }

    val currentSize: Long
        get() = lock.withLock { totalSizeBytes }

    val entryCount: Int
        get() = lock.withLock { entries.size }

    /**
     * Retrieves a cached asset file for the given key.
     * Updates the item's last accessed timestamp if found.
     * If the file is missing on disk (e.g., deleted externally), handles it gracefully.
     */
    fun get(key: String): File? {
        lock.withLock {
            val entry = entries[key] ?: return null
            if (!entry.file.exists() || !entry.file.isFile) {
                // File was deleted on disk externally; purge entry
                entries.remove(key)
                totalSizeBytes = (totalSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
                return null
            }

            val now = System.currentTimeMillis()
            entry.lastAccessedMs = now
            try {
                entry.file.setLastModified(now)
            } catch (_: Exception) {}

            return entry.file
        }
    }

    /**
     * Stores byte array asset into local disk storage under key.
     * If storage exceeds maxSizeBytes, evicts least recently used items one-by-one.
     */
    fun put(key: String, bytes: ByteArray): File {
        val targetFile = getFileForKey(key)
        val tempFile = File(cacheDir, "${targetFile.name}.tmp_${System.nanoTime()}")

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val size = targetFile.length()
            val now = System.currentTimeMillis()

            lock.withLock {
                val oldEntry = entries[key]
                if (oldEntry != null) {
                    totalSizeBytes = (totalSizeBytes - oldEntry.sizeBytes).coerceAtLeast(0L)
                }

                val newEntry = CacheEntry(
                    key = key,
                    file = targetFile,
                    sizeBytes = size,
                    lastAccessedMs = now
                )
                entries[key] = newEntry
                totalSizeBytes += size

                evictLruUntilWithinLimitInternal()
            }

            return targetFile
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Stores an input stream asset into local disk storage under key.
     * Evicts least recently used items one-by-one if limit is exceeded.
     */
    fun putStream(key: String, inputStream: InputStream): File {
        val targetFile = getFileForKey(key)
        val tempFile = File(cacheDir, "${targetFile.name}.tmp_${System.nanoTime()}")

        try {
            FileOutputStream(tempFile).use { fos ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
                fos.flush()
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val size = targetFile.length()
            val now = System.currentTimeMillis()

            lock.withLock {
                val oldEntry = entries[key]
                if (oldEntry != null) {
                    totalSizeBytes = (totalSizeBytes - oldEntry.sizeBytes).coerceAtLeast(0L)
                }

                val newEntry = CacheEntry(
                    key = key,
                    file = targetFile,
                    sizeBytes = size,
                    lastAccessedMs = now
                )
                entries[key] = newEntry
                totalSizeBytes += size

                evictLruUntilWithinLimitInternal()
            }

            return targetFile
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Stores an existing file into local disk storage under key.
     */
    fun put(key: String, sourceFile: File): File {
        val targetFile = getFileForKey(key)
        if (sourceFile.absolutePath != targetFile.absolutePath) {
            sourceFile.copyTo(targetFile, overwrite = true)
        }

        val size = targetFile.length()
        val now = System.currentTimeMillis()

        lock.withLock {
            val oldEntry = entries[key]
            if (oldEntry != null) {
                totalSizeBytes = (totalSizeBytes - oldEntry.sizeBytes).coerceAtLeast(0L)
            }

            val newEntry = CacheEntry(
                key = key,
                file = targetFile,
                sizeBytes = size,
                lastAccessedMs = now
            )
            entries[key] = newEntry
            totalSizeBytes += size

            evictLruUntilWithinLimitInternal()
        }

        return targetFile
    }

    /**
     * Explicitly update last accessed timestamp (e.g., during active playback stream read).
     */
    fun touch(key: String, timestampMs: Long = System.currentTimeMillis()) {
        lock.withLock {
            val entry = entries[key] ?: return
            entry.lastAccessedMs = timestampMs
            try {
                entry.file.setLastModified(timestampMs)
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if a valid cached asset exists for key.
     */
    fun contains(key: String): Boolean {
        lock.withLock {
            val entry = entries[key] ?: return false
            if (entry.file.exists() && entry.file.isFile) {
                return true
            }
            entries.remove(key)
            totalSizeBytes = (totalSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
            return false
        }
    }

    /**
     * Removes an asset from cache and deletes its file from storage.
     */
    fun remove(key: String): Boolean {
        lock.withLock {
            val entry = entries.remove(key) ?: return false
            totalSizeBytes = (totalSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
            if (entry.file.exists()) {
                entry.file.delete()
            }
            return true
        }
    }

    /**
     * Deletes all cached files and clears the registry.
     */
    fun clear() {
        lock.withLock {
            entries.values.forEach { entry ->
                if (entry.file.exists()) {
                    entry.file.delete()
                }
            }
            entries.clear()
            totalSizeBytes = 0L
        }
    }

    /**
     * Evicts the single least recently used (LRU) asset item.
     * Deletes the item's file from local storage.
     * @return The key of the evicted item, or null if cache is empty.
     */
    fun evictSingleLru(): String? {
        lock.withLock {
            if (entries.isEmpty()) return null
            val lruEntry = entries.values.minByOrNull { it.lastAccessedMs } ?: return null
            entries.remove(lruEntry.key)
            totalSizeBytes = (totalSizeBytes - lruEntry.sizeBytes).coerceAtLeast(0L)
            if (lruEntry.file.exists()) {
                lruEntry.file.delete()
            }
            return lruEntry.key
        }
    }

    /**
     * Evicts least recently used items one-by-one until total storage is <= maxSizeBytes.
     * @return List of evicted keys in the order they were evicted.
     */
    fun evictLruUntilWithinLimit(): List<String> {
        return lock.withLock {
            evictLruUntilWithinLimitInternal()
        }
    }

    private fun evictLruUntilWithinLimitInternal(): List<String> {
        val evictedKeys = mutableListOf<String>()
        while (totalSizeBytes > maxSizeBytes && entries.isNotEmpty()) {
            val lruEntry = entries.values.minByOrNull { it.lastAccessedMs } ?: break
            entries.remove(lruEntry.key)
            totalSizeBytes = (totalSizeBytes - lruEntry.sizeBytes).coerceAtLeast(0L)
            if (lruEntry.file.exists()) {
                lruEntry.file.delete()
            }
            evictedKeys.add(lruEntry.key)
        }
        return evictedKeys
    }

    private fun getFileForKey(key: String): File {
        val safeName = key.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(cacheDir, safeName)
    }

    private fun scanExistingFiles() {
        val files = cacheDir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            if (f.name.contains(".tmp")) {
                f.delete()
                continue
            }
            val key = f.name
            val size = f.length()
            val lastModified = f.lastModified()
            entries[key] = CacheEntry(
                key = key,
                file = f,
                sizeBytes = size,
                lastAccessedMs = lastModified
            )
            totalSizeBytes += size
        }
        evictLruUntilWithinLimitInternal()
    }
}
