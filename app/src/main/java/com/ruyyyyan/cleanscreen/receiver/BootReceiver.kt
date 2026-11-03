package com.ruyyyyan.cleanscreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.service.KeepAliveService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val repository = InterceptRepository.getInstance(context)
            if (repository.isMasterEnabled) {
                // Restart keep-alive foreground service
                KeepAliveService.startService(context)
            }
        }
    }
}
