package com.ruyyyyan.cleanscreen.utils

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

object MisclickShieldManager {

    private const val TAG = "MisclickShieldManager"
    private var shieldView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    private val dismissRunnable = Runnable {
        removeShield()
    }

    /**
     * 在开屏前2秒在屏幕中下部覆盖一层透明安全遮罩，防止误触跳转
     */
    fun showSplashShield(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(context)) return

        mainHandler.post {
            try {
                if (shieldView != null) {
                    removeShield()
                }

                if (windowManager == null) {
                    windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                }

                val displayMetrics = context.resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                val screenWidth = displayMetrics.widthPixels

                // 遮罩覆盖屏幕从 18% 高度向下到屏幕底部（避开顶部跳过按钮区域）
                val shieldHeight = (screenHeight * 0.82).toInt()

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    shieldHeight,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM
                }

                val view = View(context).apply {
                    // 纯透明，并消耗点击事件
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnTouchListener { _, _ ->
                        Log.d(TAG, "Shield absorbed an accidental touch!")
                        true // Consume touch to prevent ad jump
                    }
                }

                windowManager?.addView(view, layoutParams)
                shieldView = view

                // 最多展示 1.8 秒后自动移除，不影响正常使用
                mainHandler.removeCallbacks(dismissRunnable)
                mainHandler.postDelayed(dismissRunnable, 1800)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show splash shield", e)
                shieldView = null
            }
        }
    }

    fun removeShield() {
        mainHandler.post {
            try {
                if (shieldView != null && windowManager != null) {
                    windowManager?.removeViewImmediate(shieldView)
                    shieldView = null
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to remove shield", e)
                shieldView = null
            }
        }
    }
}
