package com.skullbeat.app.data.cache

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
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
    companion object {
        private val SAFE_KEY_REGEX = Regex("^[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)*$")
        private val WINDOWS_RESERVED_NAMES = setOf(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
        )
        private val ORPHANED_TMP_REGEX = Regex(".*\\.tmp_\\d+$")
    }

    data class CacheEntry(
        val key: String,
        val file: File,
        var sizeBytes: Long,
        var lastAccessedMs: Long,
        var accessSeq: Long = 0L
    )

    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<String, CacheEntry>()
    private var totalSizeBytes: Long = 0L
    private var seqCounter: Long = 0L

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
        val fileKey = getFileForKey(key).name
        lock.withLock {
            val entry = entries[fileKey] ?: return null
            if (!entry.file.exists() || !entry.file.isFile) {
                // File was deleted on disk externally; purge entry
                entries.remove(fileKey)
                totalSizeBytes = (totalSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
                return null
            }

            val now = System.currentTimeMillis()
            entry.lastAccessedMs = now
            entry.accessSeq = ++seqCounter
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
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val targetFile = getFileForKey(key)
        val tempFile = File(cacheDir, "${targetFile.name}.lru_tmp_${UUID.randomUUID()}")

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            val size = tempFile.length()
            val now = System.currentTimeMillis()

            lock.withLock {
                val fileKey = targetFile.name
                val oldEntry = entries[fileKey]
                if (oldEntry != null) {
                    totalSizeBytes = (totalSizeBytes - oldEntry.sizeBytes).coerceAtLeast(0L)
                }

                if (size > maxSizeBytes) {
                    entries.remove(fileKey)
                    if (targetFile.exists()) {
                        try { targetFile.delete() } catch (_: Exception) {}
                    }
                    return targetFile
                }

                replaceTargetFile(tempFile, targetFile)

                val newEntry = CacheEntry(
                    key = key,
                    file = targetFile,
                    sizeBytes = size,
                    lastAccessedMs = now,
                    accessSeq = ++seqCounter
                )
                entries[fileKey] = newEntry
                totalSizeBytes += size

                evictLruUntilWithinLimitInternal()
            }

            return targetFile
        } finally {
            if (tempFile.exists()) {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Stores an input stream asset into local disk storage under key.
     * Evicts least recently used items one-by-one if limit is exceeded.
     */
    fun putStream(key: String, inputStream: InputStream): File {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val targetFile = getFileForKey(key)
        val tempFile = File(cacheDir, "${targetFile.name}.lru_tmp_${UUID.randomUUID()}")

        try {
            FileOutputStream(tempFile).use { fos ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
                fos.flush()
            }

            val size = tempFile.length()
            val now = System.currentTimeMillis()

            lock.withLock {
                val fileKey = targetFile.name
                val oldEntry = entries[fileKey]
                if (oldEntry != null) {
                    totalSizeBytes = (totalSizeBytes - oldEntry.sizeBytes).coerceAtLeast(0L)
                }

                if (size > maxSizeBytes) {
                    entries.remove(fileKey)
                    if (targetFile.exists()) {
                        try { targetFile.delete() } catch (_: Exception) {}
                    }
                    return targetFile
                }

                replaceTargetFile(tempFile, targetFile)

                val newEntry = CacheEntry(
                    key = key,
                    file = targetFile,
                    sizeBytes = size,
                    lastAccessedMs = now,
                    accessSeq = ++seqCounter
                )
                entries[fileKey] = newEntry
                totalSizeBytes += size

                evictLruUntilWithinLimitInternal()
            }

            return targetFile
        } finally {
            if (tempFile.exists()) {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Stores an existing file into local disk storage under key.
     */
    fun put(key: String, sourceFile: File): File {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val targetFile = getFileForKey(key)
        val isSameFile = sourceFile.absolutePath == targetFile.absolutePath
        val tempFile = File(cacheDir, "${targetFile.name}.lru_tmp_${UUID.randomUUID()}")

        try {
            if (!isSameFile) {
                sourceFile.copyTo(tempFile, overwrite = true)
            }

            val size = if (isSameFile) targetFile.length() else tempFile.length()
            val now = System.currentTimeMillis()

            lock.withLock {
                val fileKey = targetFile.name
                val oldEntry = entries[fileKey]
                if (oldEntry != null) {
                    totalSizeBytes = (totalSizeBytes - oldEntry.sizeBytes).coerceAtLeast(0L)
                }

                if (size > maxSizeBytes) {
                    entries.remove(fileKey)
                    if (targetFile.exists()) {
                        try { targetFile.delete() } catch (_: Exception) {}
                    }
                    return targetFile
                }

                if (!isSameFile) {
                    replaceTargetFile(tempFile, targetFile)
                }

                val newEntry = CacheEntry(
                    key = key,
                    file = targetFile,
                    sizeBytes = size,
                    lastAccessedMs = now,
                    accessSeq = ++seqCounter
                )
                entries[fileKey] = newEntry
                totalSizeBytes += size

                evictLruUntilWithinLimitInternal()
            }

            return targetFile
        } finally {
            if (tempFile.exists()) {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Explicitly update last accessed timestamp (e.g., during active playback stream read).
     */
    fun touch(key: String, timestampMs: Long = System.currentTimeMillis()) {
        val fileKey = getFileForKey(key).name
        lock.withLock {
            val entry = entries[fileKey] ?: return
            if (!entry.file.exists() || !entry.file.isFile) {
                entries.remove(fileKey)
                totalSizeBytes = (totalSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
                return
            }
            entry.lastAccessedMs = timestampMs
            entry.accessSeq = ++seqCounter
            try {
                entry.file.setLastModified(timestampMs)
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if a valid cached asset exists for key.
     */
    fun contains(key: String): Boolean {
        val fileKey = getFileForKey(key).name
        lock.withLock {
            val entry = entries[fileKey] ?: return false
            if (entry.file.exists() && entry.file.isFile) {
                return true
            }
            entries.remove(fileKey)
            totalSizeBytes = (totalSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
            return false
        }
    }

    /**
     * Removes an asset from cache and deletes its file from storage.
     */
    fun remove(key: String): Boolean {
        val fileKey = getFileForKey(key).name
        lock.withLock {
            val entry = entries.remove(fileKey) ?: return false
            totalSizeBytes = (totalSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
            if (entry.file.exists()) {
                try {
                    entry.file.delete()
                } catch (_: Exception) {}
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
                    try {
                        entry.file.delete()
                    } catch (_: Exception) {}
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
            val lruEntry = entries.values.minWithOrNull(
                compareBy<CacheEntry> { it.lastAccessedMs }.thenBy { it.accessSeq }
            ) ?: return null
            val mapKey = lruEntry.file.name
            entries.remove(mapKey)
            totalSizeBytes = (totalSizeBytes - lruEntry.sizeBytes).coerceAtLeast(0L)
            if (lruEntry.file.exists()) {
                try {
                    lruEntry.file.delete()
                } catch (_: Exception) {}
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
            val lruEntry = entries.values.minWithOrNull(
                compareBy<CacheEntry> { it.lastAccessedMs }.thenBy { it.accessSeq }
            ) ?: break
            val mapKey = lruEntry.file.name
            entries.remove(mapKey)
            totalSizeBytes = (totalSizeBytes - lruEntry.sizeBytes).coerceAtLeast(0L)
            if (lruEntry.file.exists()) {
                try {
                    lruEntry.file.delete()
                } catch (_: Exception) {}
            }
            evictedKeys.add(lruEntry.key)
        }
        return evictedKeys
    }

    private fun replaceTargetFile(tempFile: File, targetFile: File) {
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return
        } catch (_: Exception) {}

        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            if (targetFile.exists()) {
                try {
                    targetFile.delete()
                } catch (_: Exception) {}
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                try {
                    tempFile.delete()
                } catch (_: Exception) {}
            }
        }
    }

    private fun getFileForKey(key: String): File {
        val baseName = key.substringBefore('.').lowercase()
        val isSafeKey = key.isNotEmpty() &&
            key.length <= 64 &&
            SAFE_KEY_REGEX.matches(key) &&
            baseName !in WINDOWS_RESERVED_NAMES

        val filename = if (isSafeKey) {
            key
        } else {
            val hash = sha256Hex(key)
            val safePrefix = key.filter { it.isLetterOrDigit() || it == '_' }.take(16)
            if (safePrefix.isNotEmpty()) "${safePrefix}_$hash" else hash
        }
        return File(cacheDir, filename)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun scanExistingFiles() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in files) {
            if (!f.isFile) continue
            if (f.name.startsWith(".lru_tmp_") || f.name.contains(".lru_tmp_") || ORPHANED_TMP_REGEX.matches(f.name)) {
                try {
                    f.delete()
                } catch (_: Exception) {}
                continue
            }
            val key = f.name
            val size = f.length()
            val lastModified = f.lastModified()
            entries[key] = CacheEntry(
                key = key,
                file = f,
                sizeBytes = size,
                lastAccessedMs = lastModified,
                accessSeq = ++seqCounter
            )
            totalSizeBytes += size
        }
        evictLruUntilWithinLimitInternal()
    }
}
