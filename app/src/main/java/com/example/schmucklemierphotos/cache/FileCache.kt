package com.example.schmucklemierphotos.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A file cache system that stores downloaded files on device
 * Uses LRU (Least Recently Used) approach for managing cache size
 */
class FileCache(
    private val context: Context,
    maxSizeMb: Int? = null
) {
    companion object {
        private const val TAG = "FileCache"
        private const val CACHE_DIR_NAME = "gcp_file_cache"
        private const val DEFAULT_MAX_SIZE_MB = 500 // 500MB default
        private const val METADATA_EXTENSION = ".meta"
        
        // Convert max size from MB to bytes
        private fun mbToBytes(mb: Int): Long = mb.toLong() * 1024 * 1024
    }
    
    // Get max size from settings or use default
    private val maxSizeBytes: Long by lazy {
        val settingsManager = com.example.schmucklemierphotos.ui.settings.SettingsManager.getInstance(context)
        val sizeMb = maxSizeMb ?: settingsManager.settings.value.cacheSizeMb
        mbToBytes(sizeMb)
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdir()
            }
        }
    }

    /**
     * Gets a file from the cache if available, otherwise returns null
     * Also updates the last accessed time for the file
     * @param key The cache key (usually a file path or URL)
     * @return The cached file or null if not found
     */
    suspend fun getFile(key: String): File? = withContext(Dispatchers.IO) {
        val cacheFile = getCacheFile(key)
        if (cacheFile.exists()) {
            // Update last accessed time
            cacheFile.setLastModified(System.currentTimeMillis())
            updateMetadataLastAccessed(cacheFile)
            Log.d(TAG, "Cache hit for key: $key")
            return@withContext cacheFile
        }
        Log.d(TAG, "Cache miss for key: $key")
        null
    }

    /**
     * Puts a file into the cache and ensures we stay under the max size
     * @param key The cache key (usually a file path or URL)
     * @param data The file data to cache
     * @param mimeType Optional MIME type of the file
     */
    suspend fun putFile(key: String, data: ByteArray, mimeType: String? = null): File = withContext(Dispatchers.IO) {
        // Check file size - skip very large files to prevent memory issues
        val fileSize = data.size.toLong()
        
        // Skip caching extremely large files (over 40% of max cache size)
        val maxSingleFileSize = maxSizeBytes * 0.4
        if (fileSize > maxSingleFileSize) {
            Log.w(TAG, "File too large to cache: $key, size: $fileSize bytes exceeds $maxSingleFileSize bytes")
            throw IOException("File too large to cache: $fileSize bytes")
        }
        
        // Ensure we have space
        ensureCacheSizeLimit(fileSize)

        // Write the file to cache
        val cacheFile = getCacheFile(key)
        try {
            cacheFile.writeBytes(data)
            cacheFile.setLastModified(System.currentTimeMillis())
            
            // Write metadata
            writeMetadata(cacheFile, mimeType)
            
            Log.d(TAG, "Cached file for key: $key, size: ${data.size} bytes")
            return@withContext cacheFile
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write cache file for key: $key", e)
            throw e
        }
    }

    /**
     * Checks if a file exists in the cache
     * @param key The cache key to check
     * @return True if the file is cached
     */
    suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        getCacheFile(key).exists()
    }

    /**
     * Gets the MIME type for a cached file if available
     * @param key The cache key
     * @return The MIME type or null if not available
     */
    suspend fun getMimeType(key: String): String? = withContext(Dispatchers.IO) {
        val metadataFile = getMetadataFile(getCacheFile(key))
        if (metadataFile.exists()) {
            try {
                val lines = metadataFile.readLines()
                for (line in lines) {
                    if (line.startsWith("mime-type:")) {
                        return@withContext line.substringAfter("mime-type:").trim()
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read metadata for key: $key", e)
            }
        }
        null
    }

    /**
     * Clears the entire cache
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { file ->
            file.delete()
        }
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Gets the current cache size in bytes
     */
    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        var size = 0L
        cacheDir.listFiles()?.forEach { file ->
            size += file.length()
        }
        size
    }

    /**
     * Converts a key to a filename using MD5 hashing
     */
    private fun keyToFilename(key: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Gets the cache file for a given key
     */
    private fun getCacheFile(key: String): File {
        return File(cacheDir, keyToFilename(key))
    }

    /**
     * Gets the metadata file for a cache file
     */
    private fun getMetadataFile(cacheFile: File): File {
        return File(cacheFile.absolutePath + METADATA_EXTENSION)
    }

    /**
     * Writes metadata for a cache file
     */
    private fun writeMetadata(cacheFile: File, mimeType: String?) {
        val metadataFile = getMetadataFile(cacheFile)
        val content = buildString {
            append("created:").append(System.currentTimeMillis()).append("\n")
            append("last-accessed:").append(System.currentTimeMillis()).append("\n")
            append("original-key:").append(cacheFile.name).append("\n")
            if (mimeType != null) {
                append("mime-type:").append(mimeType).append("\n")
            }
        }
        metadataFile.writeText(content)
    }

    /**
     * Updates the last accessed time in the metadata file
     */
    private fun updateMetadataLastAccessed(cacheFile: File) {
        val metadataFile = getMetadataFile(cacheFile)
        if (metadataFile.exists()) {
            try {
                val lines = metadataFile.readLines().toMutableList()
                for (i in lines.indices) {
                    if (lines[i].startsWith("last-accessed:")) {
                        lines[i] = "last-accessed:${System.currentTimeMillis()}"
                        break
                    }
                }
                metadataFile.writeText(lines.joinToString("\n"))
            } catch (e: IOException) {
                Log.e(TAG, "Failed to update metadata for file: ${cacheFile.name}", e)
            }
        }
    }

    /**
     * Ensures the cache stays under the size limit by removing least recently used files
     */
    private fun ensureCacheSizeLimit(newFileSize: Long) {
        // Get current cache size
        var currentSize = 0L
        val files = mutableListOf<Pair<File, Long>>() // File and last accessed time

        cacheDir.listFiles()?.forEach { file ->
            // Skip metadata files
            if (!file.name.endsWith(METADATA_EXTENSION)) {
                currentSize += file.length()
                
                // Get last accessed time from metadata if possible
                val metadataFile = getMetadataFile(file)
                var lastAccessed = file.lastModified()
                
                if (metadataFile.exists()) {
                    try {
                        val lines = metadataFile.readLines()
                        for (line in lines) {
                            if (line.startsWith("last-accessed:")) {
                                lastAccessed = line.substringAfter("last-accessed:").toLongOrNull() ?: lastAccessed
                                break
                            }
                        }
                    } catch (e: IOException) {
                        // Fallback to file system time
                        Log.e(TAG, "Failed to read metadata for file: ${file.name}", e)
                    }
                }
                
                files.add(Pair(file, lastAccessed))
            }
        }

        // If adding this file would exceed our limit, remove files until we have enough space
        val targetSize = maxSizeBytes - newFileSize
        if (currentSize > targetSize) {
            // Sort files by last accessed time (oldest first)
            val sortedFiles = files.sortedBy { it.second }
            
            // Remove files until we're under the limit
            var sizeToFree = currentSize - targetSize
            for (filePair in sortedFiles) {
                val file = filePair.first
                val fileSize = file.length()
                
                // Delete the file and its metadata
                file.delete()
                getMetadataFile(file).delete()
                
                Log.d(TAG, "Removed from cache: ${file.name}, size: $fileSize bytes")
                
                sizeToFree -= fileSize
                if (sizeToFree <= 0) {
                    break
                }
            }
        }
    }
}