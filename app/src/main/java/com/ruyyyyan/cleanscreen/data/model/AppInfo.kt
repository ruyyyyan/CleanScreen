package com.ruyyyyan.cleanscreen.data.model

data class AppInfo(
    val packageName: String,
    val appName: String,
    var isEnabled: Boolean = true,
    var totalCount: Int = 0,
    var todayCount: Int = 0,
    var lastInterceptTime: Long = 0
)
