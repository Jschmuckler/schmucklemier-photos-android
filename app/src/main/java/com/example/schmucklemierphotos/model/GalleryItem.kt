package com.example.schmucklemierphotos.model

import java.util.Date

/**
 * Base abstract class representing an item in the gallery
 */
sealed class GalleryItem {
    abstract val name: String
    abstract val path: String
    abstract val lastModified: Date?
    
    /**
     * Returns the display name of the item (without path information)
     */
    fun getDisplayName(): String {
        // Remove any trailing slash for folders
        val cleanName = name.trimEnd('/')
        
        // Get the last part of the path
        return cleanName.substringAfterLast('/')
    }
    
    /**
     * Represents a folder in the gallery
     */
    data class Folder(
        override val name: String,
        override val path: String,
        override val lastModified: Date? = null,
        val itemCount: Int = 0
    ) : GalleryItem()
    
    /**
     * Base class for file items in the gallery
     */
    sealed class File(
        open override val name: String,
        open override val path: String,
        open override val lastModified: Date?,
        open val size: Long,
        open val mimeType: String?
    ) : GalleryItem() {
        /**
         * Determines if the file is one that should be ignored/hidden
         */
        fun shouldIgnore(): Boolean {
            val ignoredFileNames = listOf(
                ".DS_Store",
                "Thumbs.db",
                ".thumbnails",
                ".picasa.ini",
                "._.DS_Store"
            )
            
            val ignoredExtensions = listOf(
                ".thm",  // Thumbnail files
                ".THM"   // Uppercase variant
            )
            
            return ignoredFileNames.any { name.endsWith(it) } ||
                   ignoredExtensions.any { name.endsWith(it) }
        }
    }
    
    /**
     * Represents an image file in the gallery
     */
    data class ImageFile(
        override val name: String,
        override val path: String,
        override val lastModified: Date?,
        override val size: Long,
        override val mimeType: String?,
        val width: Int? = null,
        val height: Int? = null,
        val thumbnailPath: String? = null
    ) : File(name, path, lastModified, size, mimeType)
    
    /**
     * Represents a document file in the gallery
     */
    data class DocumentFile(
        override val name: String,
        override val path: String,
        override val lastModified: Date?,
        override val size: Long,
        override val mimeType: String?
    ) : File(name, path, lastModified, size, mimeType)
    
    /**
     * Represents a video file in the gallery
     */
    data class VideoFile(
        override val name: String,
        override val path: String,
        override val lastModified: Date?,
        override val size: Long,
        override val mimeType: String?,
        val duration: Long? = null,
        val thumbnailPath: String? = null
    ) : File(name, path, lastModified, size, mimeType)
    
    /**
     * Represents an unknown file type in the gallery
     */
    data class OtherFile(
        override val name: String,
        override val path: String,
        override val lastModified: Date?,
        override val size: Long,
        override val mimeType: String?
    ) : File(name, path, lastModified, size, mimeType)
}
