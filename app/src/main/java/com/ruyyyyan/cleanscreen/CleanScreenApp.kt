package com.ruyyyyan.cleanscreen

import android.app.Application
import android.util.Log

class CleanScreenApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 捕获异常记录日志，并委托给系统默认处理器以保证正常的系统崩溃与恢复流程
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(
                "CleanScreenApp",
                "Uncaught exception in thread ${thread.name}",
                throwable
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

