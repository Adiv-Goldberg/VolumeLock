package com.example

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.controller.VolumeManagerHelper
import com.example.data.ProtectionMode
import com.example.data.VolumeDatabase
import com.example.data.VolumeLogEntity
import com.example.data.VolumeSettings
import com.example.data.VolumeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppNameString() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VolumeLock", appName)
    }

    @Test
    fun testSettingsRepositoryDefaultsAndUpdates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = VolumeSettingsRepository.getInstance(context)

        // Test updating lock state
        repo.setLockActive(true)
        assertTrue(repo.settings.value.isLockActive)

        // Test updating target volume
        repo.setTargetVolume(8)
        assertEquals(8, repo.settings.value.targetVolume)

        // Test updating mode
        repo.setProtectionMode(ProtectionMode.BLOCK_ALL)
        assertEquals(ProtectionMode.BLOCK_ALL, repo.settings.value.protectionMode)

        repo.setProtectionMode(ProtectionMode.BLOCK_SYSTEM)
        assertEquals(ProtectionMode.BLOCK_SYSTEM, repo.settings.value.protectionMode)

        // Test toggles
        repo.setAllowIncrease(true)
        assertTrue(repo.settings.value.allowIncrease)

        repo.setAllowDecrease(false)
        assertFalse(repo.settings.value.allowDecrease)

        // Test start on boot
        repo.setStartOnBoot(true)
        assertTrue(repo.settings.value.startOnBoot)
    }

    @Test
    fun testUserInteractionFlag() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = VolumeManagerHelper(context)

        assertFalse(helper.isUserInteracting())
        helper.notifyUserInteracting(5000L)
        assertTrue(helper.isUserInteracting())
    }

    @Test
    fun testVolumeHelperCalculations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = VolumeManagerHelper(context)

        val maxVol = helper.getMaxVolume(AudioManager.STREAM_MUSIC)
        assertTrue("Max volume should be greater than 0", maxVol > 0)

        val streamName = helper.getStreamName(AudioManager.STREAM_MUSIC)
        assertNotNull(streamName)
        assertTrue(streamName.contains("Media"))
    }

    @Test
    fun testDatabaseLogInsertionAndRetrieval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = VolumeDatabase.getInstance(context)
        val dao = database.volumeLogDao()

        dao.clearAllLogs()

        val log = VolumeLogEntity(
            streamType = AudioManager.STREAM_MUSIC,
            streamName = "Media",
            droppedVolume = 4,
            restoredVolume = 10,
            maxVolume = 15,
            reason = "Test Drop Blocked"
        )
        dao.insertLog(log)

        val logs = dao.getAllLogs().first()
        assertTrue("Database should contain at least 1 log", logs.isNotEmpty())
        assertEquals(4, logs.first().droppedVolume)
        assertEquals(10, logs.first().restoredVolume)
        assertEquals("Test Drop Blocked", logs.first().reason)
    }
}
