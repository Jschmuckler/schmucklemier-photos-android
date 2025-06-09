package com.example.schmucklemierphotos.utils

import android.util.Log

/**
 * Utility class for thumbnail-related operations
 */
object ThumbnailUtils {
    private const val TAG = "ThumbnailUtils"
    
    /**
     * Generates a thumbnail path from an original file path
     * Thumbnail will be in the same subfolder under a THUMBS directory with same name but webp extension
     * @param originalPath The original file path
     * @return The corresponding thumbnail path
     */
    fun generateThumbnailPath(originalPath: String): String {
        val path = originalPath.trim() 
        if (path.isEmpty()) return ""
        
        return try {
            val lastSlashIndex = path.lastIndexOf('/')
            if (lastSlashIndex == -1) {
                // No slashes, it's in the root folder
                "THUMBS/${path.substringBeforeLast('.', path)}.webp"
            } else {
                // Extract directory path and filename
                val dir = path.substring(0, lastSlashIndex)
                val filename = path.substring(lastSlashIndex + 1)
                "$dir/THUMBS/${filename.substringBeforeLast('.', filename)}.webp"
            }
        } catch (e: Exception) {
            // Fallback if there's an error in the path processing
            Log.e(TAG, "Error generating thumbnail path for $originalPath", e)
            "$originalPath.thumb.webp"
        }
    }
    
    /**
     * Checks if a folder is a thumbnail folder and should be hidden
     * @param path The folder path to check
     * @return True if the folder should be hidden, false otherwise
     */
    fun isThumbnailFolder(path: String): Boolean {
        return path.endsWith("THUMBS/") || path.contains("/THUMBS/")
    }
    
    /**
     * Checks if a file is a thumbnail
     * @param path The file path to check
     * @return True if the file is a thumbnail, false otherwise
     */
    fun isThumbnailFile(path: String): Boolean {
        return path.contains("/THUMBS/") || path.startsWith("THUMBS/")
    }
    
    /**
     * Determines the MIME type based on file extension
     * @param filePath The file path
     * @return The MIME type or null if unknown
     */
    fun getMimeTypeFromPath(filePath: String): String? {
        return when {
            filePath.endsWith(".jpg", ignoreCase = true) 
                || filePath.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            filePath.endsWith(".png", ignoreCase = true) -> "image/png"
            filePath.endsWith(".gif", ignoreCase = true) -> "image/gif"
            filePath.endsWith(".webp", ignoreCase = true) -> "image/webp"
            filePath.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            filePath.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            filePath.endsWith(".webm", ignoreCase = true) -> "video/webm"
            filePath.endsWith(".ts", ignoreCase = true) -> "video/mp2t"
            filePath.endsWith(".m2ts", ignoreCase = true) -> "video/mp2t"
            filePath.endsWith(".mts", ignoreCase = true) -> "video/mp2t"
            else -> null
        }
    }
}