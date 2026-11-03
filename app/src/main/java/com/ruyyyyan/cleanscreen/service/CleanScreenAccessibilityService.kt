package com.ruyyyyan.cleanscreen.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.utils.FloatingToast
import com.ruyyyyan.cleanscreen.utils.ServiceStateHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class CleanScreenAccessibilityService : AccessibilityService() {

    private val repository by lazy { InterceptRepository.getInstance(applicationContext) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cooldown control: package -> last skip timestamp
    private val lastSkipTimes = ConcurrentHashMap<String, Long>()

    // Launch tracking: package -> cold launch timestamp
    private val appLaunchTimes = ConcurrentHashMap<String, Long>()

    // Track whether the current app session has already skipped an ad
    private val appSkippedState = ConcurrentHashMap<String, Boolean>()

    private var lastForegroundPackage: String = ""
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private val pendingScanRunnables = mutableListOf<Runnable>()

    companion object {
        private const val TAG = "CleanScreenService"

        @Volatile
        var isServiceRunning = false
            private set

        // Strict skip regex: matches only pure "跳过", "关闭广告", "Skip", or countdown buttons
        private val SKIP_REGEX = Pattern.compile("^(?:\\d+s?\\s*)?(?:跳过|关闭广告|Skip|SKIP|跳\\s*过)(?:\\s*\\d+s?)?$")

        // Known shopping/e-commerce redirect target packages
        private val JUMP_TARGET_PACKAGES = setOf(
            "com.taobao.taobao",           // Taobao
            "com.jingdong.app.mall",       // JD
            "com.xunmeng.pinduoduo",       // Pinduoduo
            "com.sankuai.meituan",         // Meituan
            "com.tmall.wireless",          // Tmall
            "com.vipancai.vipshop",        // Vipshop
            "com.bbk.appstore",            // vivo App Store
            "com.vivo.appstore",           // vivo App Store
            "com.heytap.market"            // Heytap Market
        )

        // System packages to ignore
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.bbk.launcher2",
            "com.vivo.upslide",
            "com.android.settings",
            "com.ruyyyyan.cleanscreen"
        )

        // Lightweight burst scan delays scheduled only within the first 3 seconds
        private val BURST_DELAYS = longArrayOf(200, 500, 1000)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: AccessibilityService initializing on PID=${Process.myPid()}, Thread=${Thread.currentThread().name}")
        ServiceStateHolder.recordEvent("ON_CREATE", "PID=${Process.myPid()}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "onServiceConnected: System bound CleanScreenAccessibilityService")

        // Dynamically reinforce service info configuration to prevent OEM XML parsing discrepancies
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 100
            serviceInfo = info
            Log.i(TAG, "onServiceConnected: Dynamic serviceInfo applied successfully, flags=${info.flags}")
        } catch (e: Throwable) {
            Log.e(TAG, "onServiceConnected: Failed to configure dynamic serviceInfo", e)
        }

        updateScreenDimensions()
        ServiceStateHolder.markServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: System unbinding CleanScreenAccessibilityService, action=${intent?.action}")
        isServiceRunning = false
        clearAllPendingScans()
        ServiceStateHolder.markServiceUnbound(intent?.action ?: "Normal system unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: CleanScreenAccessibilityService being destroyed")
        isServiceRunning = false
        clearAllPendingScans()
        ServiceStateHolder.markServiceDestroyed()
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: CleanScreen Accessibility feedback interrupted by system")
        ServiceStateHolder.recordEvent("ON_INTERRUPT", "Accessibility feedback interrupted")
    }

    private fun updateScreenDimensions() {
        try {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        } catch (e: Throwable) {
            // Suppress fallback
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null || !repository.isMasterEnabled) return

            val pkgName = event.packageName?.toString() ?: return
            if (pkgName in IGNORED_PACKAGES) return

            val now = System.currentTimeMillis()

            // Process only window state transitions
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleWindowStateChanged(pkgName, now)

                // Temporal guard: scan for skip buttons only within the first 3.5 seconds of app launch
                val launchTime = appLaunchTimes[pkgName] ?: 0L
                val alreadySkipped = appSkippedState[pkgName] ?: false

                if (now - launchTime < 3500 && !alreadySkipped) {
                    clearAllPendingScans()
                    val skipped = scanAndSkipAd(pkgName, rootInActiveWindow)
                    if (!skipped) {
                        scheduleBurstScans(pkgName)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling accessibility event", e)
        }
    }

    private fun clearAllPendingScans() {
        for (runnable in pendingScanRunnables) {
            mainHandler.removeCallbacks(runnable)
        }
        pendingScanRunnables.clear()
    }

    private fun scheduleBurstScans(pkgName: String) {
        if (!repository.isFastModeEnabled || !repository.isAppEnabled(pkgName)) return

        for (delay in BURST_DELAYS) {
            val runnable = object : Runnable {
                override fun run() {
                    try {
                        val now = System.currentTimeMillis()
                        val launchTime = appLaunchTimes[pkgName] ?: 0L
                        val alreadySkipped = appSkippedState[pkgName] ?: false

                        // Stop scanning if window time expired or ad was already skipped
                        if (now - launchTime >= 3500 || alreadySkipped || lastForegroundPackage != pkgName) {
                            return
                        }

                        val skipped = scanAndSkipAd(pkgName, rootInActiveWindow)
                        if (skipped) {
                            clearAllPendingScans()
                        }
                    } catch (e: Throwable) {
                        // Suppress background scan errors
                    }
                }
            }
            pendingScanRunnables.add(runnable)
            mainHandler.postDelayed(runnable, delay)
        }
    }

    private fun handleWindowStateChanged(currentPkg: String, now: Long) {
        try {
            // Anti-misclick auto return logic
            if (repository.isPreventMisclickJumpEnabled && lastForegroundPackage.isNotEmpty()) {
                val lastPkg = lastForegroundPackage
                val lastLaunchTime = appLaunchTimes[lastPkg] ?: 0L

                // If redirected to shopping app within 3 seconds of cold launch
                if (currentPkg != lastPkg &&
                    now - lastLaunchTime < 3000 &&
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
                                    description = "拦截从【$originalAppName】误触跳转至【$targetAppName】"
                                )
                            )

                            if (repository.isShowToastEnabled) {
                                FloatingToast.showToast(applicationContext, "已拦截误触跳转，已自动返回【$originalAppName】")
                            }
                            if (repository.isVibrateEnabled) {
                                FloatingToast.vibrateFeedback(applicationContext)
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error performing auto-back", e)
                        }
                    }, 80)

                    return
                }
            }

            // New app in foreground
            if (currentPkg != lastForegroundPackage) {
                appLaunchTimes[currentPkg] = now
                appSkippedState[currentPkg] = false
                lastForegroundPackage = currentPkg
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in handleWindowStateChanged", e)
        }
    }

    private fun scanAndSkipAd(packageName: String, rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        if (!repository.isAppEnabled(packageName)) return false

        val now = System.currentTimeMillis()
        val lastSkip = lastSkipTimes[packageName] ?: 0L
        if (now - lastSkip < 3000) {
            return false
        }

        try {
            val targetNode = findStrictSkipNode(rootNode)
            if (targetNode != null) {
                val clicked = clickNodeOrGesture(targetNode)
                if (clicked) {
                    lastSkipTimes[packageName] = now
                    appSkippedState[packageName] = true
                    val appName = getAppLabel(packageName)

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

                    return true
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in scanAndSkipAd", e)
        }

        return false
    }

    /**
     * Strict depth-first node locator:
     * 1. Text length must be <= 8
     * 2. Matches strict skip regex pattern
     * 3. Bounds must reside within top 25% or bottom 15% of screen
     */
    private fun findStrictSkipNode(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null

        val text = root.text?.toString()?.trim()
        val desc = root.contentDescription?.toString()?.trim()
        val viewId = root.viewIdResourceName?.lowercase() ?: ""

        if (isStrictSkipMatch(text) || isStrictSkipMatch(desc) || isStrictSkipViewId(viewId)) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            val isTopZone = rect.top <= screenHeight * 0.25
            val isBottomZone = rect.bottom >= screenHeight * 0.85

            if (isTopZone || isBottomZone) {
                return root
            }
        }

        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            val found = findStrictSkipNode(child, depth + 1)
            if (found != null) {
                return found
            }
        }

        return null
    }

    private fun isStrictSkipMatch(content: String?): Boolean {
        if (content.isNullOrEmpty() || content.length > 8) return false

        if (SKIP_REGEX.matcher(content).matches()) {
            return true
        }

        val customKeywords = repository.getCustomKeywords()
        for (keyword in customKeywords) {
            if (keyword.length <= 6 && content.equals(keyword, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    private fun isStrictSkipViewId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        return viewId.contains("tt_splash_skip") ||
                viewId.contains("ksad_splash_skip") ||
                viewId.endsWith(":id/skip_view") ||
                viewId.endsWith(":id/btn_skip") ||
                viewId.endsWith(":id/splash_skip_tv")
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo): Boolean {
        try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            val parent = node.parent
            if (parent != null && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() in 20..600 && rect.height() in 20..300) {
                    val clickPath = Path().apply {
                        moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 30))
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
