package com.example.schmucklemierphotos.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.example.schmucklemierphotos.ui.theme.SchmucklemierPhotosTheme
import kotlin.math.roundToInt

/**
 * Settings fragment that displays app settings using Jetpack Compose
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                SchmucklemierPhotosTheme(darkTheme = true, dynamicColor = false) {
                    val settingsManager = remember { SettingsManager.getInstance(context) }
                    val settings by settingsManager.settings.collectAsState()
                    
                    SettingsScreen(
                        settings = settings,
                        onNavigateBack = {
                            // Go back to the previous screen
                            requireActivity().supportFragmentManager.popBackStack()
                        },
                        onLowBandwidthModeChanged = settingsManager::setLowBandwidthMode,
                        onExtremeLowBandwidthModeChanged = settingsManager::setExtremeLowBandwidthMode,
                        onGalleryCardSizeChanged = settingsManager::setGalleryCardSize,
                        onCacheSizeChanged = settingsManager::setCacheSizeMb,
                        onAutoClearCacheChanged = settingsManager::setAutoClearCache,
                        onPreloadImagesChanged = settingsManager::setPreloadImages,
                        onShowFilenamesChanged = settingsManager::setShowFilenames,
                        onAutoLogoutMinutesChanged = settingsManager::setAutoLogoutMinutes,
                        onRememberLoginMinutesChanged = settingsManager::setRememberLoginMinutes
                    )
                }
            }
        }
    }
}

/**
 * Settings screen UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsManager.AppSettings,
    onNavigateBack: () -> Unit,
    onLowBandwidthModeChanged: (Boolean) -> Unit,
    onExtremeLowBandwidthModeChanged: (Boolean) -> Unit,
    onGalleryCardSizeChanged: (String) -> Unit,
    onCacheSizeChanged: (Int) -> Unit,
    onAutoClearCacheChanged: (Boolean) -> Unit,
    onPreloadImagesChanged: (Boolean) -> Unit,
    onShowFilenamesChanged: (Boolean) -> Unit,
    onAutoLogoutMinutesChanged: (Int) -> Unit,
    onRememberLoginMinutesChanged: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                // Network settings
                SettingsCategory("Network Settings")
                
                // Low bandwidth mode
                SettingsSwitch(
                    title = "Low Bandwidth Mode",
                    description = "Only load thumbnails for full-screen viewing to save data",
                    checked = settings.lowBandwidthMode,
                    onCheckedChange = onLowBandwidthModeChanged
                )
                
                // Extreme low bandwidth mode
                SettingsSwitch(
                    title = "Extreme Low Bandwidth Mode",
                    description = "Don't load thumbnails while scrolling (requires Low Bandwidth Mode)",
                    checked = settings.extremeLowBandwidthMode,
                    enabled = settings.lowBandwidthMode,
                    onCheckedChange = onExtremeLowBandwidthModeChanged
                )
                
                Divider(modifier = Modifier.padding(vertical = 16.dp))
                
                // Display settings
                SettingsCategory("Display Settings")
                
                // Gallery card size
                Text(
                    text = "Gallery Card Size",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                val cardSizes = listOf(
                    SettingsManager.GalleryCardSize.SMALL,
                    SettingsManager.GalleryCardSize.MEDIUM,
                    SettingsManager.GalleryCardSize.LARGE
                )
                
                Column(Modifier.selectableGroup()) {
                    cardSizes.forEach { size ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (size.value == settings.galleryCardSize),
                                    onClick = { onGalleryCardSizeChanged(size.value) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (size.value == settings.galleryCardSize),
                                onClick = null // null because we're handling the click on the parent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = size.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Show filenames
                SettingsSwitch(
                    title = "Show Filenames",
                    description = "Display filenames under images and videos with thumbnails (folders and other files will always show names)",
                    checked = settings.showFilenames,
                    onCheckedChange = onShowFilenamesChanged
                )
                
                Divider(modifier = Modifier.padding(vertical = 16.dp))
                
                // Security settings
                SettingsCategory("Security Settings")
                
                // Auto logout timer
                Text(
                    text = "Auto Logout Timer",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                val autoLogoutOptions = listOf(
                    SettingsManager.TimeInterval.NEVER,
                    SettingsManager.TimeInterval.FIVE_MINUTES,
                    SettingsManager.TimeInterval.TEN_MINUTES,
                    SettingsManager.TimeInterval.THIRTY_MINUTES,
                    SettingsManager.TimeInterval.SIXTY_MINUTES
                )
                
                Column(Modifier.selectableGroup()) {
                    autoLogoutOptions.forEach { interval ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (interval.minutes == settings.autoLogoutMinutes),
                                    onClick = { onAutoLogoutMinutesChanged(interval.minutes) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (interval.minutes == settings.autoLogoutMinutes),
                                onClick = null // null because we're handling the click on the parent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = interval.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                Text(
                    text = "Automatically log out after the specified period of inactivity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Remember login
                Text(
                    text = "Remember Login",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                val rememberLoginOptions = listOf(
                    SettingsManager.TimeInterval.NEVER,
                    SettingsManager.TimeInterval.FIVE_MINUTES,
                    SettingsManager.TimeInterval.TEN_MINUTES,
                    SettingsManager.TimeInterval.THIRTY_MINUTES,
                    SettingsManager.TimeInterval.SIXTY_MINUTES
                )
                
                Column(Modifier.selectableGroup()) {
                    rememberLoginOptions.forEach { interval ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (interval.minutes == settings.rememberLoginMinutes),
                                    onClick = { onRememberLoginMinutesChanged(interval.minutes) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (interval.minutes == settings.rememberLoginMinutes),
                                onClick = null // null because we're handling the click on the parent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = interval.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                Text(
                    text = "Skip authentication for the specified period after closing the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
                
                Divider(modifier = Modifier.padding(vertical = 16.dp))
                
                // Cache settings
                SettingsCategory("Cache Settings")
                
                // Cache size
                Text(
                    text = "Cache Size: ${settings.cacheSizeMb} MB",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                val sliderPosition = remember(settings.cacheSizeMb) { 
                    mutableFloatStateOf(settings.cacheSizeMb.toFloat()) 
                }
                var currentValue by remember { mutableFloatStateOf(settings.cacheSizeMb.toFloat()) }
                
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "100 MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "2000 MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Slider(
                        value = currentValue,
                        onValueChange = { currentValue = it },
                        onValueChangeFinished = {
                            // Only update the setting when the user stops dragging
                            val roundedValue = currentValue.roundToInt()
                            onCacheSizeChanged(roundedValue)
                            sliderPosition.floatValue = roundedValue.toFloat()
                        },
                        valueRange = 100f..2000f,
                        steps = 19, // 100 MB steps (2000-100)/100
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                Text(
                    text = "Set the maximum amount of storage used for caching images and thumbnails",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Auto clear cache
                SettingsSwitch(
                    title = "Auto-clear Cache",
                    description = "Automatically clear image cache when exiting the app",
                    checked = settings.autoClearCache,
                    onCheckedChange = onAutoClearCacheChanged
                )
                
                // Preload images
                SettingsSwitch(
                    title = "Preload Images",
                    description = "Preload nearby images for faster viewing in full-screen mode",
                    checked = settings.preloadImages,
                    onCheckedChange = onPreloadImagesChanged
                )
                
                Divider(modifier = Modifier.padding(vertical = 16.dp))
                
                // About section
                SettingsCategory("About")
                Text(
                    text = "Version 1.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                Text(
                    text = "© 2024 Schmucklemier Photos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Add extra space at the bottom
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Setting category header
 */
@Composable
fun SettingsCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * Setting switch item with title and description
 */
@Composable
fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) 
                    MaterialTheme.colorScheme.onSurface 
                else 
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled)
                MaterialTheme.colorScheme.onSurfaceVariant
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
}