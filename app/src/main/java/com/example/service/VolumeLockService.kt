package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.controller.VolumeManagerHelper
import com.example.data.ProtectionMode
import com.example.data.VolumeDatabase
import com.example.data.VolumeLogEntity
import com.example.data.VolumeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VolumeLockService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var volumeHelper: VolumeManagerHelper
    private lateinit var repository: VolumeSettingsRepository
    private lateinit var database: VolumeDatabase
    private var watchdogJob: Job? = null
    private var contentObserver: ContentObserver? = null

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                checkAndEnforceVolume("Broadcast")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        volumeHelper = VolumeManagerHelper(this)
        repository = VolumeSettingsRepository.getInstance(this)
        database = VolumeDatabase.getInstance(this)

        createNotificationChannel()
        registerObservers()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP_SERVICE -> {
                repository.setLockActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LOCK_CURRENT -> {
                val current = volumeHelper.getCurrentVolume(repository.settings.value.streamType)
                repository.setTargetVolume(current)
                repository.setLockActive(true)
                updateNotification()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                checkAndEnforceVolume("Start")
            }
        }
        return START_STICKY
    }

    private fun registerObservers() {
        try {
            contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    checkAndEnforceVolume("Observer")
                }
            }
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error registering observer", e)
        }

        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            registerReceiver(volumeChangeReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver", e)
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                val settings = repository.settings.value
                val interval = settings.watchdogIntervalMs.coerceAtLeast(300L)
                delay(interval)
                if (settings.isLockActive && !volumeHelper.isUserInteracting()) {
                    launch(Dispatchers.Main) {
                        checkAndEnforceVolume("Watchdog")
                    }
                }
            }
        }
    }

    private fun checkAndEnforceVolume(triggerSource: String) {
        val settings = repository.settings.value
        if (!settings.isLockActive) return
        if (volumeHelper.isUserInteracting()) return

        volumeHelper.checkAndEnforce(
            settings = settings,
            onVolumeRestored = { dropped, restored, reason ->
                serviceScope.launch(Dispatchers.IO) {
                    val log = VolumeLogEntity(
                        streamType = settings.streamType,
                        streamName = volumeHelper.getStreamName(settings.streamType),
                        droppedVolume = dropped,
                        restoredVolume = restored,
                        maxVolume = volumeHelper.getMaxVolume(settings.streamType),
                        reason = reason
                    )
                    database.volumeLogDao().insertLog(log)
                }
                updateNotification()
            },
            onVolumeAccepted = { newVolume ->
                repository.setTargetVolume(newVolume)
                updateNotification()
            }
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Volume Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Volume lock protection status"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val settings = repository.settings.value
        val max = volumeHelper.getMaxVolume(settings.streamType)
        val target = if (settings.targetVolume >= 0) settings.targetVolume else volumeHelper.getCurrentVolume(settings.streamType)
        val percent = if (max > 0) ((target.toFloat() / max) * 100).toInt() else 0

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VolumeLockService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeName = if (settings.protectionMode == ProtectionMode.BLOCK_ALL) "Block All" else "Block System"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume: $percent% ($target/$max) • Active")
            .setContentText("Mode: $modeName")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        serviceScope.cancel()
        try {
            contentObserver?.let { contentResolver.unregisterContentObserver(it) }
            unregisterReceiver(volumeChangeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error during unregister", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "VolumeLockService"
        const val CHANNEL_ID = "volume_lock_channel"
        const val NOTIFICATION_ID = 4040

        const val ACTION_START_SERVICE = "com.example.action.START_VOLUME_LOCK"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_VOLUME_LOCK"
        const val ACTION_LOCK_CURRENT = "com.example.action.LOCK_CURRENT"

        fun startService(context: Context) {
            val intent = Intent(context, VolumeLockService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VolumeLockService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
