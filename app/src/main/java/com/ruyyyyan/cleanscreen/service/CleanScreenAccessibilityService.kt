package com.ruyyyyan.cleanscreen.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.utils.FloatingToast
import com.ruyyyyan.cleanscreen.utils.MisclickShieldManager
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class CleanScreenAccessibilityService : AccessibilityService() {

    private val repository by lazy { InterceptRepository.getInstance(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cooldown map: packageName -> last skip timestamp
    private val lastSkipTimes = ConcurrentHashMap<String, Long>()

    // Tracking app launches for misclick jump detection: packageName -> launch timestamp
    private val appLaunchTimes = ConcurrentHashMap<String, Long>()
    private var lastForegroundPackage: String = ""

    // Screen dimensions
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    companion object {
        private const val TAG = "CleanScreenService"

        @Volatile
        var isServiceRunning = false
            private set

        // Keywords for skip detection
        private val SKIP_PATTERNS = listOf(
            Pattern.compile(".*跳\\s*过.*"),
            Pattern.compile(".*关闭广告.*"),
            Pattern.compile(".*\\d+\\s*s?\\s*跳过.*"),
            Pattern.compile(".*跳过\\s*\\d+\\s*s?.*"),
            Pattern.compile("(?i).*skip.*")
        )

        // Known common ad landing target packages (for misclick jump prevention)
        private val JUMP_TARGET_PACKAGES = setOf(
            "com.taobao.taobao",           // 淘宝
            "com.jingdong.app.mall",       // 京东
            "com.xunmeng.pinduoduo",       // 拼多多
            "com.sankuai.meituan",         // 美团
            "com.tmall.wireless",          // 天猫
            "com.vipancai.vipshop",        // 唯品会
            "com.suning.mobile.ebuy",      // 苏宁
            "com.bbk.appstore",            // vivo应用商店
            "com.vivo.appstore",           // vivo应用商店
            "com.heytap.market"            // 软件商店
        )

        // System packages to ignore
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.bbk.launcher2",
            "com.vivo.upslide",
            "com.android.settings",
            "com.ruyyyyan.cleanscreen"
        )

        // Staged burst delays (ms) for asynchronously loaded ads
        private val SCAN_DELAYS = longArrayOf(120, 280, 550, 950, 1500, 2200)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        updateScreenDimensions()
        Log.i(TAG, "CleanScreen Accessibility Service Connected successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        MisclickShieldManager.removeShield()
        Log.i(TAG, "CleanScreen Accessibility Service Destroyed")
    }

    override fun onInterrupt() {
        Log.w(TAG, "CleanScreen Accessibility Service Interrupted")
    }

    private fun updateScreenDimensions() {
        try {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null || !repository.isMasterEnabled) return

            val pkgName = event.packageName?.toString() ?: return
            if (pkgName in IGNORED_PACKAGES) return

            val now = System.currentTimeMillis()

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(pkgName, now)
                    scanAndSkipAd(pkgName, rootInActiveWindow)
                    scheduleBurstScans(pkgName)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val launchTime = appLaunchTimes[pkgName] ?: 0L
                    if (now - launchTime < 4500) {
                        scanAndSkipAd(pkgName, rootInActiveWindow)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling accessibility event", e)
        }
    }

    /**
     * 针对网络延迟加载的开屏广告，执行多轮异步突发扫描
     */
    private fun scheduleBurstScans(pkgName: String) {
        if (!repository.isFastModeEnabled || !repository.isAppEnabled(pkgName)) return

        for (delay in SCAN_DELAYS) {
            mainHandler.postDelayed({
                try {
                    val now = System.currentTimeMillis()
                    val lastSkip = lastSkipTimes[pkgName] ?: 0L
                    if (now - lastSkip >= 3000 && lastForegroundPackage == pkgName) {
                        scanAndSkipAd(pkgName, rootInActiveWindow)
                    }
                } catch (e: Throwable) {
                    // Suppress scan error
                }
            }, delay)
        }
    }

    private fun handleWindowStateChanged(currentPkg: String, now: Long) {
        try {
            // Check for misclick cross-app jump if enabled
            if (repository.isPreventMisclickJumpEnabled && lastForegroundPackage.isNotEmpty()) {
                val lastPkg = lastForegroundPackage
                val lastLaunchTime = appLaunchTimes[lastPkg] ?: 0L

                // If user opened an app within 3 seconds, and it jumped to shopping/ad app:
                if (currentPkg != lastPkg &&
                    now - lastLaunchTime < 3200 &&
                    currentPkg in JUMP_TARGET_PACKAGES &&
                    lastPkg !in JUMP_TARGET_PACKAGES
                ) {
                    val originalAppName = getAppLabel(lastPkg)
                    val targetAppName = getAppLabel(currentPkg)

                    mainHandler.postDelayed({
                        try {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            repository.recordIntercept(
                                InterceptRecord(
                                    packageName = lastPkg,
                                    appName = originalAppName,
                                    type = InterceptRecord.TYPE_MISCLICK_RETURN,
                                    description = "拦截从【$originalAppName】跳转至【$targetAppName】的误触广告"
                                )
                            )

                            if (repository.isShowToastEnabled) {
                                FloatingToast.showToast(applicationContext, "已拦截误触跳转，已自动返回【$originalAppName】")
                            }
                            if (repository.isVibrateEnabled) {
                                FloatingToast.vibrateFeedback(applicationContext)
                            }
                            KeepAliveService.updateNotification(applicationContext)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error performing auto-back", e)
                        }
                    }, 80)

                    return
                }
            }

            if (currentPkg != lastForegroundPackage) {
                appLaunchTimes[currentPkg] = now
                lastForegroundPackage = currentPkg

                // Show anti-misclick shield if enabled
                if (repository.isShieldOverlayEnabled && repository.isAppEnabled(currentPkg)) {
                    MisclickShieldManager.showSplashShield(applicationContext)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in handleWindowStateChanged", e)
        }
    }

    private fun scanAndSkipAd(packageName: String, rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return
        if (!repository.isAppEnabled(packageName)) return

        val now = System.currentTimeMillis()
        val lastSkip = lastSkipTimes[packageName] ?: 0L
        if (now - lastSkip < 3000) {
            return
        }

        try {
            // 1. 尝试规则与关键词匹配
            var targetNode = findSkipButtonNode(rootNode)

            // 2. 若文本未匹配到，尝试右上角几何启发式探测（应对纯图无文字跳过按钮）
            if (targetNode == null) {
                targetNode = findTopRightCornerAdNode(rootNode)
            }

            if (targetNode != null) {
                val clicked = clickNodeOrGesture(targetNode)
                if (clicked) {
                    lastSkipTimes[packageName] = now
                    val appName = getAppLabel(packageName)

                    // 成功跳过后立即移除防误触遮罩
                    MisclickShieldManager.removeShield()

                    repository.recordIntercept(
                        InterceptRecord(
                            packageName = packageName,
                            appName = appName,
                            type = InterceptRecord.TYPE_SPLASH_SKIP,
                            description = "自动跳过开屏广告"
                        )
                    )

                    if (repository.isShowToastEnabled) {
                        FloatingToast.showToast(applicationContext, "已跳过【$appName】开屏广告")
                    }
                    if (repository.isVibrateEnabled) {
                        FloatingToast.vibrateFeedback(applicationContext)
                    }

                    KeepAliveService.updateNotification(applicationContext)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in scanAndSkipAd", e)
        }
    }

    private fun findSkipButtonNode(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 14) return null

        val text = root.text?.toString()?.trim()
        val desc = root.contentDescription?.toString()?.trim()
        val viewId = root.viewIdResourceName?.lowercase() ?: ""

        if (isSkipMatch(text) || isSkipMatch(desc) || isSkipViewId(viewId)) {
            return root
        }

        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            val found = findSkipButtonNode(child, depth + 1)
            if (found != null) {
                return found
            }
        }

        return null
    }

    /**
     * 智能右上角区域启发式探测（专门解决自绘或纯图片跳过按钮）
     */
    private fun findTopRightCornerAdNode(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null

        val rect = Rect()
        root.getBoundsInScreen(rect)

        // 位于屏幕右上角 35% 宽度，顶部 16% 高度之内
        val inTopRightZone = rect.left >= screenWidth * 0.65 &&
                rect.top <= screenHeight * 0.16 &&
                rect.bottom > 20 &&
                rect.width() in 40..500 &&
                rect.height() in 25..250

        if (inTopRightZone && (root.isClickable || root.childCount == 0)) {
            val text = root.text?.toString() ?: ""
            // 如果带数字倒计时或简短文字或空文字纯图按钮
            if (text.length <= 8) {
                return root
            }
        }

        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            val found = findTopRightCornerAdNode(child, depth + 1)
            if (found != null) {
                return found
            }
        }

        return null
    }

    private fun isSkipMatch(content: String?): Boolean {
        if (content.isNullOrEmpty() || content.length > 25) return false

        // 检查系统内置正则
        for (pattern in SKIP_PATTERNS) {
            if (pattern.matcher(content).matches()) {
                return true
            }
        }

        // 检查用户自定义关键词
        val customKeywords = repository.getCustomKeywords()
        for (keyword in customKeywords) {
            if (content.contains(keyword, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    private fun isSkipViewId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        return viewId.contains("skip") ||
                viewId.contains("splash_tv") ||
                viewId.contains("btn_skip") ||
                viewId.contains("tt_splash_skip") ||
                viewId.contains("ksad_splash_skip")
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo): Boolean {
        try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            var parent = node.parent
            var attempts = 0
            while (parent != null && attempts < 3) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
                attempts++
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val clickPath = Path().apply {
                        moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 40))
                        .build()
                    return dispatchGesture(gesture, null, null)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error clicking node", e)
        }

        return false
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
