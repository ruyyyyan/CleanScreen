package com.ruyyyyan.cleanscreen.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ruyyyyan.cleanscreen.data.model.AppInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import java.util.Calendar

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "cleanscreen.db"
        const val DATABASE_VERSION = 1

        const val TABLE_LOGS = "intercept_logs"
        const val COL_LOG_ID = "id"
        const val COL_LOG_PKG = "package_name"
        const val COL_LOG_APP_NAME = "app_name"
        const val COL_LOG_TIMESTAMP = "timestamp"
        const val COL_LOG_TYPE = "type"
        const val COL_LOG_DESC = "description"

        const val TABLE_APPS = "app_rules"
        const val COL_APP_PKG = "package_name"
        const val COL_APP_NAME = "app_name"
        const val COL_APP_ENABLED = "is_enabled"
        const val COL_APP_TOTAL_COUNT = "total_count"
        const val COL_APP_LAST_TIME = "last_intercept_time"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createLogsTable = """
            CREATE TABLE $TABLE_LOGS (
                $COL_LOG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LOG_PKG TEXT NOT NULL,
                $COL_LOG_APP_NAME TEXT NOT NULL,
                $COL_LOG_TIMESTAMP INTEGER NOT NULL,
                $COL_LOG_TYPE INTEGER NOT NULL,
                $COL_LOG_DESC TEXT
            )
        """.trimIndent()

        val createAppsTable = """
            CREATE TABLE $TABLE_APPS (
                $COL_APP_PKG TEXT PRIMARY KEY,
                $COL_APP_NAME TEXT NOT NULL,
                $COL_APP_ENABLED INTEGER NOT NULL DEFAULT 1,
                $COL_APP_TOTAL_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_APP_LAST_TIME INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        db.execSQL(createLogsTable)
        db.execSQL(createAppsTable)
        db.execSQL("CREATE INDEX idx_logs_timestamp ON $TABLE_LOGS($COL_LOG_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_logs_pkg ON $TABLE_LOGS($COL_LOG_PKG)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_APPS")
        onCreate(db)
    }

    fun addInterceptLog(record: InterceptRecord) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val logValues = ContentValues().apply {
                put(COL_LOG_PKG, record.packageName)
                put(COL_LOG_APP_NAME, record.appName)
                put(COL_LOG_TIMESTAMP, record.timestamp)
                put(COL_LOG_TYPE, record.type)
                put(COL_LOG_DESC, record.description)
            }
            db.insert(TABLE_LOGS, null, logValues)

            // Update app summary
            db.execSQL(
                """
                INSERT INTO $TABLE_APPS ($COL_APP_PKG, $COL_APP_NAME, $COL_APP_ENABLED, $COL_APP_TOTAL_COUNT, $COL_APP_LAST_TIME)
                VALUES (?, ?, 1, 1, ?)
                ON CONFLICT($COL_APP_PKG) DO UPDATE SET
                    $COL_APP_NAME = excluded.$COL_APP_NAME,
                    $COL_APP_TOTAL_COUNT = $COL_APP_TOTAL_COUNT + 1,
                    $COL_APP_LAST_TIME = excluded.$COL_APP_LAST_TIME
                """.trimIndent(),
                arrayOf(record.packageName, record.appName, record.timestamp)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun isAppEnabled(packageName: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_APPS,
            arrayOf(COL_APP_ENABLED),
            "$COL_APP_PKG = ?",
            arrayOf(packageName),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0) == 1
            }
        }
        return true // Default enabled
    }

    fun setAppEnabled(packageName: String, appName: String, enabled: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_APP_PKG, packageName)
            put(COL_APP_NAME, appName)
            put(COL_APP_ENABLED, if (enabled) 1 else 0)
        }
        db.insertWithOnConflict(TABLE_APPS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getTodayStartTimestamp(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun getTodayInterceptCount(): Int {
        val db = readableDatabase
        val todayStart = getTodayStartTimestamp()
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_LOGS WHERE $COL_LOG_TIMESTAMP >= ?",
            arrayOf(todayStart.toString())
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun getTotalInterceptCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_LOGS", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun getProtectedAppCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(DISTINCT $COL_APP_PKG) FROM $TABLE_LOGS", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun getAllAppInfos(): List<AppInfo> {
        val list = mutableListOf<AppInfo>()
        val db = readableDatabase
        val todayStart = getTodayStartTimestamp()

        val query = """
            SELECT a.$COL_APP_PKG, a.$COL_APP_NAME, a.$COL_APP_ENABLED, a.$COL_APP_TOTAL_COUNT, a.$COL_APP_LAST_TIME,
                   (SELECT COUNT(*) FROM $TABLE_LOGS l WHERE l.$COL_LOG_PKG = a.$COL_APP_PKG AND l.$COL_LOG_TIMESTAMP >= ?) as today_count
            FROM $TABLE_APPS a
            ORDER BY a.$COL_APP_TOTAL_COUNT DESC
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(todayStart.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    AppInfo(
                        packageName = it.getString(0),
                        appName = it.getString(1),
                        isEnabled = it.getInt(2) == 1,
                        totalCount = it.getInt(3),
                        lastInterceptTime = it.getLong(4),
                        todayCount = it.getInt(5)
                    )
                )
            }
        }
        return list
    }

    fun getRecentLogs(limit: Int = 100): List<InterceptRecord> {
        val list = mutableListOf<InterceptRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_LOGS,
            null,
            null,
            null,
            null,
            null,
            "$COL_LOG_TIMESTAMP DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    InterceptRecord(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_LOG_ID)),
                        packageName = it.getString(it.getColumnIndexOrThrow(COL_LOG_PKG)),
                        appName = it.getString(it.getColumnIndexOrThrow(COL_LOG_APP_NAME)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_LOG_TIMESTAMP)),
                        type = it.getInt(it.getColumnIndexOrThrow(COL_LOG_TYPE)),
                        description = it.getString(it.getColumnIndexOrThrow(COL_LOG_DESC)) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun clearAllLogs() {
        val db = writableDatabase
        db.delete(TABLE_LOGS, null, null)
        db.execSQL("UPDATE $TABLE_APPS SET $COL_APP_TOTAL_COUNT = 0, $COL_APP_LAST_TIME = 0")
    }
}
