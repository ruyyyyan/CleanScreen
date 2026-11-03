package com.ruyyyyan.cleanscreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i("BootReceiver", "Device boot completed, CleanScreen initialized.")
            // 确保数据库与首选项初始化；无障碍服务由系统框架自动恢复绑定
            val repository = InterceptRepository.getInstance(context)
            if (repository.isMasterEnabled) {
                Log.d("BootReceiver", "Master switch is enabled.")
            }
        }
    }
}
