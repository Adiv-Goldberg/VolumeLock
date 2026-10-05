package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProtectionMode {
    BLOCK_ALL,     // Blocks ANY external change; rigidly preserves target volume
    BLOCK_SYSTEM   // Blocks automated system / safe-volume drops, but allows user adjustments
}

data class VolumeSettings(
    val isLockActive: Boolean = false,
    val targetVolume: Int = -1, // -1 means uninitialized, will read device current volume
    val protectionMode: ProtectionMode = ProtectionMode.BLOCK_SYSTEM,
    val allowIncrease: Boolean = true,
    val allowDecrease: Boolean = false,
    val streamType: Int = AudioManager.STREAM_MUSIC,
    val startOnBoot: Boolean = true,
    val watchdogIntervalMs: Long = 800L
)

class VolumeSettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("volume_lock_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<VolumeSettings> = _settings.asStateFlow()

    private fun loadSettings(): VolumeSettings {
        val isLockActive = prefs.getBoolean(KEY_IS_ACTIVE, false)
        val targetVolume = prefs.getInt(KEY_TARGET_VOLUME, -1)
        val modeStr = prefs.getString(KEY_PROTECTION_MODE, ProtectionMode.BLOCK_SYSTEM.name)
        val mode = try {
            ProtectionMode.valueOf(modeStr ?: ProtectionMode.BLOCK_SYSTEM.name)
        } catch (e: Exception) {
            ProtectionMode.BLOCK_SYSTEM
        }
        val allowInc = prefs.getBoolean(KEY_ALLOW_INCREASE, true)
        val allowDec = prefs.getBoolean(KEY_ALLOW_DECREASE, false)
        val streamType = prefs.getInt(KEY_STREAM_TYPE, AudioManager.STREAM_MUSIC)
        val startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, true)
        val watchdogInterval = prefs.getLong(KEY_WATCHDOG_INTERVAL, 800L)

        return VolumeSettings(
            isLockActive = isLockActive,
            targetVolume = targetVolume,
            protectionMode = mode,
            allowIncrease = allowInc,
            allowDecrease = allowDec,
            streamType = streamType,
            startOnBoot = startOnBoot,
            watchdogIntervalMs = watchdogInterval
        )
    }

    fun updateSettings(newSettings: VolumeSettings) {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVE, newSettings.isLockActive)
            .putInt(KEY_TARGET_VOLUME, newSettings.targetVolume)
            .putString(KEY_PROTECTION_MODE, newSettings.protectionMode.name)
            .putBoolean(KEY_ALLOW_INCREASE, newSettings.allowIncrease)
            .putBoolean(KEY_ALLOW_DECREASE, newSettings.allowDecrease)
            .putInt(KEY_STREAM_TYPE, newSettings.streamType)
            .putBoolean(KEY_START_ON_BOOT, newSettings.startOnBoot)
            .putLong(KEY_WATCHDOG_INTERVAL, newSettings.watchdogIntervalMs)
            .apply()

        _settings.value = newSettings
    }

    fun setLockActive(active: Boolean) {
        updateSettings(_settings.value.copy(isLockActive = active))
    }

    fun setTargetVolume(volume: Int) {
        updateSettings(_settings.value.copy(targetVolume = volume))
    }

    fun setProtectionMode(mode: ProtectionMode) {
        updateSettings(_settings.value.copy(protectionMode = mode))
    }

    fun setAllowIncrease(allow: Boolean) {
        updateSettings(_settings.value.copy(allowIncrease = allow))
    }

    fun setAllowDecrease(allow: Boolean) {
        updateSettings(_settings.value.copy(allowDecrease = allow))
    }

    fun setStartOnBoot(enabled: Boolean) {
        updateSettings(_settings.value.copy(startOnBoot = enabled))
    }

    companion object {
        private const val KEY_IS_ACTIVE = "key_is_active"
        private const val KEY_TARGET_VOLUME = "key_target_volume"
        private const val KEY_PROTECTION_MODE = "key_protection_mode"
        private const val KEY_ALLOW_INCREASE = "key_allow_increase"
        private const val KEY_ALLOW_DECREASE = "key_allow_decrease"
        private const val KEY_STREAM_TYPE = "key_stream_type"
        private const val KEY_START_ON_BOOT = "key_start_on_boot"
        private const val KEY_WATCHDOG_INTERVAL = "key_watchdog_interval"

        @Volatile
        private var INSTANCE: VolumeSettingsRepository? = null

        fun getInstance(context: Context): VolumeSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = VolumeSettingsRepository(context)
                INSTANCE = instance
                instance
            }
        }
    }
}
