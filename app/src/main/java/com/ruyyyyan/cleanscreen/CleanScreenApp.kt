package com.ruyyyyan.cleanscreen

import android.app.Application
import android.util.Log

class CleanScreenApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 全局未捕获异常守护，防止闪退
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CleanScreenApp", "Uncaught exception in thread ${thread.name}", throwable)
        }
    }
}
