package com.ruyyyyan.cleanscreen.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.data.rules.RuleEngine
import com.ruyyyyan.cleanscreen.utils.FloatingToast
import com.ruyyyyan.cleanscreen.utils.ServiceStateHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Core Accessibility Service:
 * - Lifecyle strictly managed by Android AccessibilityManager
 * - High-speed zero-latency Ad-skip pipeline
 * - GKD-style multi-dimensional rule engine with Activity isolation
 * - Android-Touch-Helper style relative coordinate tap fallback
 * - Asynchronous background persistence
 */
class CleanScreenAccessibilityService : AccessibilityService() {

    private val repository by lazy { InterceptRepository.getInstance(this) }
    private val ruleEngine by lazy { RuleEngine.getInstance(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cooldown control: package -> last skip timestamp
    private val lastSkipTimes = ConcurrentHashMap<String, Long>()

    // Launch tracking: package -> cold launch timestamp
    private val appLaunchTimes = ConcurrentHashMap<String, Long>()

    // Track whether the current app session has already skipped an ad
    private val appSkippedState = ConcurrentHashMap<String, Boolean>()

    private var lastForegroundPackage: String = ""
    private var currentForegroundActivity: String = ""
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    private val pendingScanRunnables = mutableListOf<Runnable>()

    companion object {
        private const val TAG = "CleanScreenService"

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var instance: CleanScreenAccessibilityService? = null
            private set

        @Volatile
        var lastActivePackage: String = ""
            private set

        @Volatile
        var lastActiveActivity: String = ""
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
        instance = this
        Log.i(TAG, "onCreate: AccessibilityService initializing on PID=${Process.myPid()}, Thread=${Thread.currentThread().name}")
        ServiceStateHolder.recordEvent("ON_CREATE", "PID=${Process.myPid()}")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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

            serviceInfo = info
            Log.d(TAG, "onServiceConnected: Reinforced serviceInfo flags=${info.flags}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to re-apply serviceInfo", e)
        }

        updateScreenDimensions()
        ServiceStateHolder.recordEvent("ON_CONNECTED", "Flags reinforced")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: System unbinding service")
        isServiceRunning = false
        instance = null
        cancelPendingScans()
        ServiceStateHolder.recordEvent("ON_UNBIND", "Service unbound by AMS")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy: CleanScreenAccessibilityService destroyed")
        isServiceRunning = false
        instance = null
        cancelPendingScans()
        ServiceStateHolder.recordEvent("ON_DESTROY", "Service destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: Accessibility service interrupted by system")
        cancelPendingScans()
        ServiceStateHolder.recordEvent("ON_INTERRUPT", "System interrupted")
    }

    /**
     * Fast-path window event dispatcher.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleWindowStateChanged(event)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling accessibility event", e)
        }
    }

    private fun updateScreenDimensions() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                val display = wm.defaultDisplay
                val point = android.graphics.Point()
                display.getRealSize(point)
                screenWidth = point.x
                screenHeight = point.y
            }
        } catch (e: Exception) {
            screenWidth = 1080
            screenHeight = 2400
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkgName = event.packageName?.toString() ?: return
        if (IGNORED_PACKAGES.contains(pkgName)) return

        val className = event.className?.toString() ?: ""
        if (className.isNotEmpty()) {
            currentForegroundActivity = className
            lastActiveActivity = className
        }
        lastActivePackage = pkgName

        // Global master switch verification (cached in memory)
        if (!repository.isMasterEnabled) {
            return
        }

        val currentPkg = pkgName
        val now = System.currentTimeMillis()

        try {
            // Anti-Misclick auto-redirect detection
            if (repository.isPreventMisclickJumpEnabled) {
                val sourceLaunchTime = appLaunchTimes[lastForegroundPackage] ?: 0L
                val isColdLaunchRecent = (now - sourceLaunchTime) < 3500
                val isSourceTargetDifferent = lastForegroundPackage.isNotEmpty() && lastForegroundPackage != currentPkg
                val isKnownAdTarget = JUMP_TARGET_PACKAGES.contains(currentPkg)

                if (isColdLaunchRecent && isSourceTargetDifferent && isKnownAdTarget) {
                    val originalAppName = getAppLabel(lastForegroundPackage)
                    val targetAppName = getAppLabel(currentPkg)

                    mainHandler.postDelayed({
                        try {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            repository.recordIntercept(
                                InterceptRecord(
                                    packageName = lastForegroundPackage,
                                    appName = originalAppName,
                                    type = InterceptRecord.TYPE_MISCLICK_RETURN,
                                    description = "已拦截开屏误触跳转至【$targetAppName】"
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

        // Cancel previous pending bursts
        cancelPendingScans()

        // Scan immediately
        val skipped = scanAndSkipAd(currentPkg, currentForegroundActivity, rootInActiveWindow)
        if (skipped) return

        // Temporal burst scan (GKD & LiTiaotiao temporal window)
        val launchTime = appLaunchTimes[currentPkg] ?: now
        if ((now - launchTime) < 3200 && (appSkippedState[currentPkg] != true)) {
            scheduleBurstScans(currentPkg)
        }
    }

    private fun scheduleBurstScans(packageName: String) {
        val isFastMode = repository.isFastModeEnabled
        val delays = if (isFastMode) {
            longArrayOf(100, 250, 500, 900)
        } else {
            BURST_DELAYS
        }

        for (delay in delays) {
            val runnable = Runnable {
                if (lastForegroundPackage == packageName && appSkippedState[packageName] != true) {
                    scanAndSkipAd(packageName, currentForegroundActivity, rootInActiveWindow)
                }
            }
            pendingScanRunnables.add(runnable)
            mainHandler.postDelayed(runnable, delay)
        }
    }

    private fun cancelPendingScans() {
        for (runnable in pendingScanRunnables) {
            mainHandler.removeCallbacks(runnable)
        }
        pendingScanRunnables.clear()
    }

    /**
     * Dual-stage Ad Skip Processor:
     * Stage 1: Specialized GKD-style rule matching with Activity isolation & Coordinate tap.
     * Stage 2: Temporal 3.5s universal strict DFS skip locator.
     */
    private fun scanAndSkipAd(packageName: String, currentActivity: String, rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        if (!repository.isAppEnabled(packageName)) return false

        val now = System.currentTimeMillis()
        val lastSkip = lastSkipTimes[packageName] ?: 0L
        if (now - lastSkip < 3000) {
            return false
        }

        try {
            // Stage 1: Specialized precision rule matching
            val action = ruleEngine.findMatchingAction(rootNode, packageName, currentActivity, screenHeight)
            if (action != null) {
                val clicked = when (action) {
                    is RuleEngine.MatchAction.NodeMatch -> clickNodeOrGesture(action.node, action.rule.clickParentIfPossible)
                    is RuleEngine.MatchAction.CoordinateMatch -> dispatchCoordinateTap(action.xRatio * screenWidth, action.yRatio * screenHeight)
                }

                if (clicked) {
                    lastSkipTimes[packageName] = now
                    appSkippedState[packageName] = true
                    val appName = if (action.rule.appName.isNotEmpty()) action.rule.appName else getAppLabel(packageName)
                    val descPrefix = if (action is RuleEngine.MatchAction.CoordinateMatch) "坐标模拟点击" else "规则引擎精准跳过"

                    repository.recordIntercept(
                        InterceptRecord(
                            packageName = packageName,
                            appName = appName,
                            type = InterceptRecord.TYPE_SPLASH_SKIP,
                            description = "$descPrefix【$appName】"
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

            // Stage 2: Universal strict DFS skip locator fallback
            val fallbackNode = findStrictSkipNode(rootNode)
            if (fallbackNode != null) {
                val clicked = clickNodeOrGesture(fallbackNode)
                if (clicked) {
                    lastSkipTimes[packageName] = now
                    appSkippedState[packageName] = true
                    val appName = getAppLabel(packageName)

                    repository.recordIntercept(
                        InterceptRecord(
                            packageName = packageName,
                            appName = appName,
                            type = InterceptRecord.TYPE_SPLASH_SKIP,
                            description = "自动跳过【$appName】开屏广告"
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

    /**
     * Public click helper for node or gesture.
     */
    fun clickTargetNode(node: AccessibilityNodeInfo, preferParent: Boolean = false): Boolean {
        return clickNodeOrGesture(node, preferParent)
    }

    /**
     * Dispatches relative coordinate tap via Android gesture API.
     */
    fun dispatchCoordinateTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo, preferParent: Boolean = false): Boolean {
        try {
            if (preferParent) {
                val parent = node.parent
                if (parent != null && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }

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
