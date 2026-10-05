package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.VolumeSettingsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("BootReceiver", "Boot completed detected. Checking volume protection settings...")
            val repo = VolumeSettingsRepository.getInstance(context)
            val settings = repo.settings.value
            if (settings.startOnBoot && settings.isLockActive) {
                Log.d("BootReceiver", "Starting VolumeLockService on device boot.")
                VolumeLockService.startService(context)
            }
        }
    }
}
