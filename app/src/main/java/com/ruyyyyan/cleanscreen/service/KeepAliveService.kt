package com.ruyyyyan.cleanscreen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ruyyyyan.cleanscreen.R
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.ui.MainActivity

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "channel_cleanscreen_keeper"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "KeepAliveService"

        fun startService(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to start KeepAliveService safely: ${e.message}")
            }
        }

        fun updateNotification(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java).apply {
                    action = "ACTION_UPDATE"
                }
                context.startService(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to update notification: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                context.stopService(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to stop service: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForegroundCompat()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onCreate startForegroundCompat", e)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_UPDATE") {
            try {
                val notification = buildNotification()
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: Throwable) {
                Log.e(TAG, "Error updating notification", e)
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val repo = InterceptRepository.getInstance(applicationContext)
        val todayCount = repo.getTodayInterceptCount()
        val totalCount = repo.getTotalInterceptCount()

        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle("清屏正在后台持续守护")
            .setContentText("今日已拦截 $todayCount 次 · 累计拦截 $totalCount 次")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
