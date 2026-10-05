package com.example.controller

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.data.ProtectionMode
import com.example.data.VolumeSettings

class VolumeManagerHelper(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var userInteractingUntilTimestamp: Long = 0L

    fun notifyUserInteracting(durationMs: Long = 1200L) {
        userInteractingUntilTimestamp = System.currentTimeMillis() + durationMs
    }

    fun isUserInteracting(): Boolean {
        return System.currentTimeMillis() < userInteractingUntilTimestamp
    }

    fun getMaxVolume(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        return try {
            audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        } catch (e: Exception) {
            10
        }
    }

    fun getMinVolume(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                audioManager.getStreamMinVolume(streamType)
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }
    }

    fun getCurrentVolume(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        return try {
            audioManager.getStreamVolume(streamType)
        } catch (e: Exception) {
            0
        }
    }

    fun setVolume(
        streamType: Int = AudioManager.STREAM_MUSIC,
        volume: Int,
        showSystemUi: Boolean = false
    ) {
        try {
            val max = getMaxVolume(streamType)
            val min = getMinVolume(streamType)
            val clamped = volume.coerceIn(min, max)
            val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
            audioManager.setStreamVolume(streamType, clamped, flags)
        } catch (e: Exception) {
            Log.e("VolumeManagerHelper", "Error setting stream volume", e)
        }
    }

    /**
     * Checks current volume against settings and enforces lock policy.
     * Returns true if volume was corrected, false otherwise.
     */
    fun checkAndEnforce(
        settings: VolumeSettings,
        onVolumeRestored: (dropped: Int, restored: Int, reason: String) -> Unit,
        onVolumeAccepted: (newVolume: Int) -> Unit
    ): Boolean {
        if (!settings.isLockActive) return false
        if (isUserInteracting()) return false

        val stream = settings.streamType
        val current = getCurrentVolume(stream)
        val target = settings.targetVolume

        if (target < 0) {
            // Uninitialized target, sync with current
            onVolumeAccepted(current)
            return false
        }

        if (current == target) return false

        when (settings.protectionMode) {
            ProtectionMode.BLOCK_ALL -> {
                // Blocks any change
                setVolume(stream, target, showSystemUi = false)
                val reason = if (current < target) "Volume drop blocked" else "Volume change blocked"
                onVolumeRestored(current, target, reason)
                return true
            }

            ProtectionMode.BLOCK_SYSTEM -> {
                if (current > target) {
                    if (settings.allowIncrease) {
                        // User turned volume up via remote / button: accept and update target
                        onVolumeAccepted(current)
                        return false
                    } else {
                        // Volume increase blocked
                        setVolume(stream, target, showSystemUi = false)
                        onVolumeRestored(current, target, "Increase blocked")
                        return true
                    }
                } else {
                    // current < target: Volume was decreased (e.g. system safe-volume warning or auto drop)
                    if (settings.allowDecrease) {
                        // Allowed by user toggle
                        onVolumeAccepted(current)
                        return false
                    } else {
                        // Block decrease! Force volume back to target
                        setVolume(stream, target, showSystemUi = false)
                        onVolumeRestored(current, target, "Auto-drop blocked")
                        return true
                    }
                }
            }
        }
    }

    fun getStreamName(streamType: Int): String {
        return when (streamType) {
            AudioManager.STREAM_MUSIC -> "Media"
            AudioManager.STREAM_SYSTEM -> "System"
            AudioManager.STREAM_RING -> "Ring"
            AudioManager.STREAM_NOTIFICATION -> "Notification"
            AudioManager.STREAM_ALARM -> "Alarm"
            else -> "Stream $streamType"
        }
    }
}
