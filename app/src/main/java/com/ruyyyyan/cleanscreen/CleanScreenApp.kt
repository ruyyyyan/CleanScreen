package com.ruyyyyan.cleanscreen

import android.app.Application
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.service.KeepAliveService

class CleanScreenApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val repository = InterceptRepository.getInstance(this)
        if (repository.isMasterEnabled) {
            KeepAliveService.startService(this)
        }
    }
}
