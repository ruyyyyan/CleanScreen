package com.ruyyyyan.cleanscreen.data.model

data class InterceptRecord(
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: Int = TYPE_SPLASH_SKIP, // 0: 开屏跳过, 1: 误触跳转快速返回
    val description: String = ""
) {
    companion object {
        const val TYPE_SPLASH_SKIP = 0
        const val TYPE_MISCLICK_RETURN = 1
    }
}
