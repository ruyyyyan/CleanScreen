package com.ruyyyyan.cleanscreen.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ruyyyyan.cleanscreen.data.db.DatabaseHelper
import com.ruyyyyan.cleanscreen.data.model.AppInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class InterceptRepository private constructor(private val context: Context) {

    private val dbHelper = DatabaseHelper(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Dedicated single-thread executor to keep all DB writes off the UI/Binder thread
    private val dbExecutor = Executors.newSingleThreadExecutor()

    // High-performance in-memory cache to prevent disk I/O on every accessibility event
    private val appEnabledCache = ConcurrentHashMap<String, Boolean>()

    init {
        // Asynchronously warm up the appEnabledCache from database
        dbExecutor.execute {
            try {
                val appInfos = dbHelper.getAllAppInfos()
                for (app in appInfos) {
                    appEnabledCache[app.packageName] = app.isEnabled
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Cache warmup error: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "InterceptRepository"
        private const val PREFS_NAME = "cleanscreen_settings"
        private const val KEY_MASTER_ENABLED = "key_master_enabled"
        private const val KEY_SHOW_TOAST = "key_show_toast"
        private const val KEY_VIBRATE = "key_vibrate"
        private const val KEY_PREVENT_MISCLICK_JUMP = "key_prevent_misclick_jump"
        private const val KEY_FAST_MODE = "key_fast_mode"
        private const val KEY_SHIELD_OVERLAY = "key_shield_overlay"
        private const val KEY_CUSTOM_KEYWORDS = "key_custom_keywords"

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

    var isShieldOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIELD_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_SHIELD_OVERLAY, value).apply()

    fun getCustomKeywords(): MutableSet<String> {
        val defaultSet = setOf("跳过", "关闭广告", "Skip", "SKIP")
        return prefs.getStringSet(KEY_CUSTOM_KEYWORDS, defaultSet)?.toMutableSet() ?: defaultSet.toMutableSet()
    }

    fun addCustomKeyword(keyword: String) {
        val current = getCustomKeywords()
        current.add(keyword.trim())
        prefs.edit().putStringSet(KEY_CUSTOM_KEYWORDS, current).apply()
    }

    fun removeCustomKeyword(keyword: String) {
        val current = getCustomKeywords()
        current.remove(keyword.trim())
        prefs.edit().putStringSet(KEY_CUSTOM_KEYWORDS, current).apply()
    }

    /**
     * Persists intercept log asynchronously without blocking caller thread.
     */
    fun recordIntercept(record: InterceptRecord) {
        dbExecutor.execute {
            try {
                dbHelper.addInterceptLog(record)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to persist intercept record", e)
            }
        }
    }

    /**
     * Checks if protection is enabled for a given package with zero-latency in-memory cache.
     */
    fun isAppEnabled(packageName: String): Boolean {
        val cached = appEnabledCache[packageName]
        if (cached != null) {
            return cached
        }

        // Cache miss: default to enabled and query in background to update cache
        dbExecutor.execute {
            try {
                val enabled = dbHelper.isAppEnabled(packageName)
                appEnabledCache[packageName] = enabled
            } catch (e: Throwable) {
                // Keep default true on error
            }
        }
        return true
    }

    /**
     * Updates app rule in memory and schedules asynchronous disk write.
     */
    fun setAppEnabled(packageName: String, appName: String, enabled: Boolean) {
        appEnabledCache[packageName] = enabled
        dbExecutor.execute {
            try {
                dbHelper.setAppEnabled(packageName, appName, enabled)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to update app rule in DB", e)
            }
        }
    }

    fun getTodayInterceptCount(): Int {
        return try {
            dbHelper.getTodayInterceptCount()
        } catch (e: Throwable) {
            0
        }
    }

    fun getTotalInterceptCount(): Int {
        return try {
            dbHelper.getTotalInterceptCount()
        } catch (e: Throwable) {
            0
        }
    }

    fun getProtectedAppCount(): Int {
        return try {
            dbHelper.getProtectedAppCount()
        } catch (e: Throwable) {
            0
        }
    }

    fun getAllAppInfos(): List<AppInfo> {
        return try {
            dbHelper.getAllAppInfos()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun getRecentLogs(limit: Int = 100): List<InterceptRecord> {
        return try {
            dbHelper.getRecentLogs(limit)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun clearAllLogs() {
        dbExecutor.execute {
            try {
                dbHelper.clearAllLogs()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }
}
