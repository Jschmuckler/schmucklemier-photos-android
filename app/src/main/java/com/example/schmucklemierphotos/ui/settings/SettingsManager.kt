package com.example.schmucklemierphotos.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings manager to handle app settings storage and retrieval
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "schmucklemier_photos_settings"
        
        // Settings keys
        private const val KEY_LOW_BANDWIDTH_MODE = "low_bandwidth_mode"
        private const val KEY_EXTREME_LOW_BANDWIDTH_MODE = "extreme_low_bandwidth_mode"
        private const val KEY_GALLERY_CARD_SIZE = "gallery_card_size"
        private const val KEY_CACHE_SIZE_MB = "cache_size_mb"
        private const val KEY_AUTO_CLEAR_CACHE = "auto_clear_cache"
        private const val KEY_PRELOAD_IMAGES = "preload_images"
        private const val KEY_SHOW_FILENAMES = "show_filenames"
        private const val KEY_AUTO_LOGOUT_MINUTES = "auto_logout_minutes"
        private const val KEY_REMEMBER_LOGIN_MINUTES = "remember_login_minutes"
        
        // Default values
        private const val DEFAULT_LOW_BANDWIDTH_MODE = false
        private const val DEFAULT_EXTREME_LOW_BANDWIDTH_MODE = false
        private const val DEFAULT_GALLERY_CARD_SIZE = "medium"
        private const val DEFAULT_CACHE_SIZE_MB = 500
        private const val DEFAULT_AUTO_CLEAR_CACHE = false
        private const val DEFAULT_PRELOAD_IMAGES = true
        private const val DEFAULT_SHOW_FILENAMES = true
        private const val DEFAULT_AUTO_LOGOUT_MINUTES = 30
        private const val DEFAULT_REMEMBER_LOGIN_MINUTES = 0  // 0 = don't remember
        
        // Singleton instance
        @Volatile
        private var INSTANCE: SettingsManager? = null
        
        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Backing shared preferences
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    // StateFlow for settings
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    
    // Settings data class
    data class AppSettings(
        val lowBandwidthMode: Boolean,
        val extremeLowBandwidthMode: Boolean,
        val galleryCardSize: String,
        val cacheSizeMb: Int,
        val autoClearCache: Boolean,
        val preloadImages: Boolean,
        val showFilenames: Boolean,
        val autoLogoutMinutes: Int,  // Minutes until auto logout (0 = never)
        val rememberLoginMinutes: Int  // Minutes to remember login (0 = never remember)
    )
    
    // Available card sizes
    enum class GalleryCardSize(val value: String, val displayName: String) {
        SMALL("small", "Small"),
        MEDIUM("medium", "Medium"),
        LARGE("large", "Large")
    }
    
    // Available time intervals for auto logout and remember login
    enum class TimeInterval(val minutes: Int, val displayName: String) {
        NEVER(0, "Never"),
        FIVE_MINUTES(5, "5 minutes"),
        TEN_MINUTES(10, "10 minutes"),
        THIRTY_MINUTES(30, "30 minutes"),
        SIXTY_MINUTES(60, "60 minutes")
    }
    
    private fun loadSettings(): AppSettings {
        return AppSettings(
            lowBandwidthMode = sharedPreferences.getBoolean(KEY_LOW_BANDWIDTH_MODE, DEFAULT_LOW_BANDWIDTH_MODE),
            extremeLowBandwidthMode = sharedPreferences.getBoolean(KEY_EXTREME_LOW_BANDWIDTH_MODE, DEFAULT_EXTREME_LOW_BANDWIDTH_MODE),
            galleryCardSize = sharedPreferences.getString(KEY_GALLERY_CARD_SIZE, DEFAULT_GALLERY_CARD_SIZE) ?: DEFAULT_GALLERY_CARD_SIZE,
            cacheSizeMb = sharedPreferences.getInt(KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB),
            autoClearCache = sharedPreferences.getBoolean(KEY_AUTO_CLEAR_CACHE, DEFAULT_AUTO_CLEAR_CACHE),
            preloadImages = sharedPreferences.getBoolean(KEY_PRELOAD_IMAGES, DEFAULT_PRELOAD_IMAGES),
            showFilenames = sharedPreferences.getBoolean(KEY_SHOW_FILENAMES, DEFAULT_SHOW_FILENAMES),
            autoLogoutMinutes = sharedPreferences.getInt(KEY_AUTO_LOGOUT_MINUTES, DEFAULT_AUTO_LOGOUT_MINUTES),
            rememberLoginMinutes = sharedPreferences.getInt(KEY_REMEMBER_LOGIN_MINUTES, DEFAULT_REMEMBER_LOGIN_MINUTES)
        )
    }
    
    // Update low bandwidth mode setting
    fun setLowBandwidthMode(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_LOW_BANDWIDTH_MODE, enabled)
            // If extreme mode is enabled, low bandwidth mode must also be enabled
            if (!enabled && _settings.value.extremeLowBandwidthMode) {
                putBoolean(KEY_EXTREME_LOW_BANDWIDTH_MODE, false)
            }
        }
        _settings.value = loadSettings()
    }
    
    // Update extreme low bandwidth mode setting
    fun setExtremeLowBandwidthMode(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_EXTREME_LOW_BANDWIDTH_MODE, enabled)
            // If extreme mode is enabled, low bandwidth mode must also be enabled
            if (enabled) {
                putBoolean(KEY_LOW_BANDWIDTH_MODE, true)
            }
        }
        _settings.value = loadSettings()
    }
    
    // Update gallery card size
    fun setGalleryCardSize(size: String) {
        sharedPreferences.edit {
            putString(KEY_GALLERY_CARD_SIZE, size)
        }
        _settings.value = loadSettings()
    }
    
    // Update cache size
    fun setCacheSizeMb(sizeMb: Int) {
        sharedPreferences.edit {
            putInt(KEY_CACHE_SIZE_MB, sizeMb)
        }
        _settings.value = loadSettings()
    }
    
    // Update auto clear cache setting
    fun setAutoClearCache(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_AUTO_CLEAR_CACHE, enabled)
        }
        _settings.value = loadSettings()
    }
    
    // Update preload images setting
    fun setPreloadImages(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_PRELOAD_IMAGES, enabled)
        }
        _settings.value = loadSettings()
    }
    
    // High quality thumbnails setting removed as it could lead to a poor user experience
    
    // Update show filenames setting
    fun setShowFilenames(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(KEY_SHOW_FILENAMES, enabled)
        }
        _settings.value = loadSettings()
    }
    
    // Update auto logout minutes
    fun setAutoLogoutMinutes(minutes: Int) {
        sharedPreferences.edit {
            putInt(KEY_AUTO_LOGOUT_MINUTES, minutes)
        }
        _settings.value = loadSettings()
    }
    
    // Update remember login minutes
    fun setRememberLoginMinutes(minutes: Int) {
        sharedPreferences.edit {
            putInt(KEY_REMEMBER_LOGIN_MINUTES, minutes)
        }
        _settings.value = loadSettings()
    }
}