package com.example.ui

import android.app.Application
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controller.VolumeManagerHelper
import com.example.data.ProtectionMode
import com.example.data.VolumeDatabase
import com.example.data.VolumeLogEntity
import com.example.data.VolumeSettings
import com.example.data.VolumeSettingsRepository
import com.example.service.VolumeLockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiVolumeState(
    val currentVolume: Int = 10,
    val targetVolume: Int = 10,
    val maxVolume: Int = 15,
    val minVolume: Int = 0,
    val isProtectionActive: Boolean = false,
    val protectionMode: ProtectionMode = ProtectionMode.BLOCK_SYSTEM,
    val allowIncrease: Boolean = true,
    val allowDecrease: Boolean = false,
    val streamType: Int = AudioManager.STREAM_MUSIC,
    val startOnBoot: Boolean = true,
    val testStatusMessage: String? = null
)

class VolumeLockViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VolumeSettingsRepository.getInstance(application)
    private val database = VolumeDatabase.getInstance(application)
    val volumeHelper = VolumeManagerHelper(application)

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<UiVolumeState> = _uiState.asStateFlow()

    val preventedCount: StateFlow<Int> = database.volumeLogDao().getLogCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                syncState(settings)
            }
        }

        // Live volume poller
        viewModelScope.launch {
            while (true) {
                delay(800)
                if (!volumeHelper.isUserInteracting()) {
                    val stream = _uiState.value.streamType
                    val curr = volumeHelper.getCurrentVolume(stream)
                    val max = volumeHelper.getMaxVolume(stream)
                    if (curr != _uiState.value.currentVolume || max != _uiState.value.maxVolume) {
                        _uiState.value = _uiState.value.copy(
                            currentVolume = curr,
                            maxVolume = max
                        )
                    }
                }
            }
        }
    }

    private fun buildInitialState(): UiVolumeState {
        val stream = AudioManager.STREAM_MUSIC
        val max = volumeHelper.getMaxVolume(stream)
        val min = volumeHelper.getMinVolume(stream)
        val current = volumeHelper.getCurrentVolume(stream)
        val settings = repository.settings.value
        val initialTarget = if (settings.targetVolume >= 0) settings.targetVolume.coerceIn(min, max) else current

        if (settings.targetVolume < 0) {
            repository.setTargetVolume(initialTarget)
        }

        return UiVolumeState(
            currentVolume = current,
            targetVolume = initialTarget,
            maxVolume = max,
            minVolume = min,
            isProtectionActive = settings.isLockActive,
            protectionMode = settings.protectionMode,
            allowIncrease = settings.allowIncrease,
            allowDecrease = settings.allowDecrease,
            streamType = settings.streamType,
            startOnBoot = settings.startOnBoot
        )
    }

    private fun syncState(settings: VolumeSettings) {
        val max = volumeHelper.getMaxVolume(settings.streamType)
        val min = volumeHelper.getMinVolume(settings.streamType)
        val current = volumeHelper.getCurrentVolume(settings.streamType)
        val target = if (settings.targetVolume >= 0) settings.targetVolume.coerceIn(min, max) else current

        _uiState.value = _uiState.value.copy(
            currentVolume = current,
            targetVolume = target,
            maxVolume = max,
            minVolume = min,
            isProtectionActive = settings.isLockActive,
            protectionMode = settings.protectionMode,
            allowIncrease = settings.allowIncrease,
            allowDecrease = settings.allowDecrease,
            streamType = settings.streamType,
            startOnBoot = settings.startOnBoot
        )
    }

    fun toggleProtection() {
        val next = !_uiState.value.isProtectionActive
        if (next) {
            val curr = volumeHelper.getCurrentVolume(_uiState.value.streamType)
            repository.setTargetVolume(curr)
            repository.setLockActive(true)
            VolumeLockService.startService(getApplication())
        } else {
            repository.setLockActive(false)
            VolumeLockService.stopService(getApplication())
        }
    }

    fun setTargetVolumeFromUser(volume: Int) {
        val clamped = volume.coerceIn(_uiState.value.minVolume, _uiState.value.maxVolume)
        // Mark user interaction window so background service doesn't revert user action
        volumeHelper.notifyUserInteracting(1500L)
        _uiState.value = _uiState.value.copy(
            targetVolume = clamped,
            currentVolume = clamped
        )
        repository.setTargetVolume(clamped)
        volumeHelper.setVolume(_uiState.value.streamType, clamped, showSystemUi = true)
    }

    fun adjustTargetVolumeBy(step: Int) {
        setTargetVolumeFromUser(_uiState.value.targetVolume + step)
    }

    fun setProtectionMode(mode: ProtectionMode) {
        repository.setProtectionMode(mode)
    }

    fun setAllowIncrease(allow: Boolean) {
        repository.setAllowIncrease(allow)
    }

    fun setAllowDecrease(allow: Boolean) {
        repository.setAllowDecrease(allow)
    }

    fun setStartOnBoot(enabled: Boolean) {
        repository.setStartOnBoot(enabled)
    }

    fun testVolumeDropProtection() {
        viewModelScope.launch(Dispatchers.Default) {
            val stream = _uiState.value.streamType
            val currentTarget = _uiState.value.targetVolume
            val dropAmount = (currentTarget / 2).coerceAtLeast(1)
            val lowered = (currentTarget - dropAmount).coerceAtLeast(_uiState.value.minVolume)

            _uiState.value = _uiState.value.copy(
                testStatusMessage = "Dropping to $lowered..."
            )

            // Force drop
            volumeHelper.setVolume(stream, lowered, showSystemUi = false)
            _uiState.value = _uiState.value.copy(currentVolume = lowered)

            delay(600)

            // Service or helper check
            volumeHelper.checkAndEnforce(
                settings = repository.settings.value.copy(isLockActive = true, allowDecrease = false),
                onVolumeRestored = { dropped, restoredVol, reason ->
                    viewModelScope.launch(Dispatchers.IO) {
                        database.volumeLogDao().insertLog(
                            VolumeLogEntity(
                                streamType = stream,
                                streamName = volumeHelper.getStreamName(stream),
                                droppedVolume = dropped,
                                restoredVolume = restoredVol,
                                maxVolume = _uiState.value.maxVolume,
                                reason = "Test: Drop blocked"
                            )
                        )
                    }
                },
                onVolumeAccepted = {}
            )

            val currentAfter = volumeHelper.getCurrentVolume(stream)
            _uiState.value = _uiState.value.copy(
                currentVolume = currentAfter,
                testStatusMessage = "Restored: $lowered ➔ $currentAfter"
            )

            delay(2500)
            _uiState.value = _uiState.value.copy(testStatusMessage = null)
        }
    }
}
