package com.ruyyyyan.cleanscreen.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ruyyyyan.cleanscreen.data.db.DatabaseHelper
import com.ruyyyyan.cleanscreen.data.model.AppInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord

class InterceptRepository private constructor(private val context: Context) {

    private val dbHelper = DatabaseHelper(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "cleanscreen_settings"
        private const val KEY_MASTER_ENABLED = "key_master_enabled"
        private const val KEY_SHOW_TOAST = "key_show_toast"
        private const val KEY_VIBRATE = "key_vibrate"
        private const val KEY_PREVENT_MISCLICK_JUMP = "key_prevent_misclick_jump"
        private const val KEY_FAST_MODE = "key_fast_mode"

        @Volatile
        private var instance: InterceptRepository? = null

        fun getInstance(context: Context): InterceptRepository {
            return instance ?: synchronized(this) {
                instance ?: InterceptRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    var isMasterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER_ENABLED, value).apply()

    var isShowToastEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOAST, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TOAST, value).apply()

    var isVibrateEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, false)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    var isPreventMisclickJumpEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREVENT_MISCLICK_JUMP, true)
        set(value) = prefs.edit().putBoolean(KEY_PREVENT_MISCLICK_JUMP, value).apply()

    var isFastModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_FAST_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_FAST_MODE, value).apply()

    fun recordIntercept(record: InterceptRecord) {
        dbHelper.addInterceptLog(record)
    }

    fun isAppEnabled(packageName: String): Boolean {
        return dbHelper.isAppEnabled(packageName)
    }

    fun setAppEnabled(packageName: String, appName: String, enabled: Boolean) {
        dbHelper.setAppEnabled(packageName, appName, enabled)
    }

    fun getTodayInterceptCount(): Int {
        return dbHelper.getTodayInterceptCount()
    }

    fun getTotalInterceptCount(): Int {
        return dbHelper.getTotalInterceptCount()
    }

    fun getProtectedAppCount(): Int {
        return dbHelper.getProtectedAppCount()
    }

    fun getAllAppInfos(): List<AppInfo> {
        return dbHelper.getAllAppInfos()
    }

    fun getRecentLogs(limit: Int = 100): List<InterceptRecord> {
        return dbHelper.getRecentLogs(limit)
    }

    fun clearAllLogs() {
        dbHelper.clearAllLogs()
    }
}
