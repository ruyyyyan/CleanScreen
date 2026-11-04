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
import android.os.SystemClock
import android.content.pm.PackageManager
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.data.rules.RuleEngine
import com.ruyyyyan.cleanscreen.data.rules.ScanBudget
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

    private var scanRunnable: Runnable? = null
    private var lastScanTime = 0L
    private val launcherPackages by lazy {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        ).map { it.activityInfo.packageName }.toSet()
    }

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

        // A fixed session deadline applies to both rule matching and generic detection.
        private const val SCAN_WINDOW_MS = 3500L
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
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
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
        ServiceStateHolder.markServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: System unbinding service")
        isServiceRunning = false
        instance = null
        cancelPendingScans()
        mainHandler.removeCallbacksAndMessages(null)
        ServiceStateHolder.markServiceUnbound("Framework unbind callback; cause not provided")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy: CleanScreenAccessibilityService destroyed")
        isServiceRunning = false
        instance = null
        cancelPendingScans()
        mainHandler.removeCallbacksAndMessages(null)
        ServiceStateHolder.markServiceDestroyed()
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
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (event.packageName?.toString() == lastForegroundPackage) {
                        scanCurrentWindow(lastForegroundPackage)
                    }
                }
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
        if (IGNORED_PACKAGES.contains(pkgName) || pkgName in launcherPackages) {
            cancelPendingScans()
            // Home/settings must end the old session so re-entering the same app can scan again.
            if (pkgName != "com.android.systemui" && pkgName != "com.vivo.upslide") {
                lastForegroundPackage = ""
                currentForegroundActivity = ""
            }
            return
        }

        val className = event.className?.toString() ?: ""
        if (pkgName != lastForegroundPackage) currentForegroundActivity = ""
        if (className.isNotEmpty() && !className.startsWith("android.") && !className.startsWith("androidx.")) {
            currentForegroundActivity = className
            lastActiveActivity = className
        }
        lastActivePackage = pkgName

        // Stop queued work when protection is disabled.
        if (!repository.isMasterEnabled) {
            cancelPendingScans()
            lastForegroundPackage = ""
            return
        }

        val currentPkg = pkgName
        val now = SystemClock.elapsedRealtime()

        try {
            // Anti-Misclick auto-redirect detection
            if (repository.isPreventMisclickJumpEnabled &&
                repository.isAppEnabled(lastForegroundPackage) && repository.isAppEnabled(currentPkg)) {
                val sourceLaunchTime = appLaunchTimes[lastForegroundPackage] ?: 0L
                val isColdLaunchRecent = (now - sourceLaunchTime) < 3500
                val isSourceTargetDifferent = lastForegroundPackage.isNotEmpty() && lastForegroundPackage != currentPkg
                val isKnownAdTarget = JUMP_TARGET_PACKAGES.contains(currentPkg)

                if (isColdLaunchRecent && isSourceTargetDifferent && isKnownAdTarget) {
                    cancelPendingScans()
                    val sourcePackage = lastForegroundPackage
                    val originalAppName = getAppLabel(sourcePackage)
                    val targetAppName = getAppLabel(currentPkg)

                    mainHandler.postDelayed({
                        try {
                            if (!isServiceRunning || !repository.isMasterEnabled ||
                                !repository.isPreventMisclickJumpEnabled ||
                                !repository.isAppEnabled(sourcePackage) || !repository.isAppEnabled(currentPkg) ||
                                rootInActiveWindow?.packageName?.toString() != currentPkg) return@postDelayed
                            if (!performGlobalAction(GLOBAL_ACTION_BACK)) return@postDelayed
                            repository.recordIntercept(
                                InterceptRecord(
                                    packageName = sourcePackage,
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
                cancelPendingScans()
                appLaunchTimes.clear()
                appSkippedState.clear()
                appLaunchTimes[currentPkg] = now
                lastScanTime = 0L
                updateScreenDimensions()
                appSkippedState[currentPkg] = false
                lastForegroundPackage = currentPkg
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in handleWindowStateChanged", e)
        }

        scanCurrentWindow(currentPkg)
        scheduleBurstScans(currentPkg)
    }

    private fun isScanAllowed(packageName: String): Boolean {
        val launchTime = appLaunchTimes[packageName] ?: return false
        return isServiceRunning && repository.isMasterEnabled &&
            packageName == lastForegroundPackage && repository.isAppEnabled(packageName) &&
            appSkippedState[packageName] != true &&
            SystemClock.elapsedRealtime() - launchTime in 0 until SCAN_WINDOW_MS
    }

    private fun scanCurrentWindow(packageName: String) {
        if (!isScanAllowed(packageName)) return
        val now = SystemClock.elapsedRealtime()
        val interval = if (repository.isFastModeEnabled) 150L else 300L
        if (now - lastScanTime < interval) return
        lastScanTime = now
        try {
            scanAndSkipAd(packageName, currentForegroundActivity, rootInActiveWindow)
        } catch (e: Exception) {
            Log.w(TAG, "Window scan failed", e)
        }
    }

    private fun scheduleBurstScans(packageName: String) {
        if (scanRunnable != null || !isScanAllowed(packageName)) return
        val runnable = object : Runnable {
            override fun run() {
                if (!isScanAllowed(packageName)) {
                    scanRunnable = null
                    return
                }
                scanCurrentWindow(packageName)
                if (isScanAllowed(packageName)) {
                    mainHandler.postDelayed(this, if (repository.isFastModeEnabled) 200L else 400L)
                } else {
                    scanRunnable = null
                }
            }
        }
        scanRunnable = runnable
        mainHandler.postDelayed(runnable, 200L)
    }

    private fun cancelPendingScans() {
        scanRunnable?.let { mainHandler.removeCallbacks(it) }
        scanRunnable = null
    }

    /**
     * Dual-stage Ad Skip Processor:
     * Stage 1: Specialized GKD-style rule matching with Activity isolation & Coordinate tap.
     * Stage 2: Temporal 3.5s universal strict DFS skip locator.
     */
    private fun scanAndSkipAd(packageName: String, currentActivity: String, rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null || !isScanAllowed(packageName)) return false
        // Events and the active window can disagree during transitions. Never click another app.
        if (rootNode.packageName?.toString() != packageName) return false

        val now = SystemClock.elapsedRealtime()
        val lastSkip = lastSkipTimes[packageName] ?: 0L
        if (now - lastSkip < 3000) {
            return false
        }

        try {
            // Stage 1: Specialized precision rule matching
            val budget = ScanBudget()
            val action = ruleEngine.findMatchingAction(rootNode, packageName, currentActivity, screenHeight, budget)
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
            val fallbackNode = findStrictSkipNode(rootNode, budget)
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
    private fun findStrictSkipNode(root: AccessibilityNodeInfo, budget: ScanBudget, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12 || !budget.visit() || !root.isVisibleToUser) return null

        val text = root.text?.toString()?.trim()
        val desc = root.contentDescription?.toString()?.trim()
        val viewId = root.viewIdResourceName?.lowercase() ?: ""

        if (isStrictSkipMatch(text) || isStrictSkipMatch(desc) || isStrictSkipViewId(viewId)) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            val isTopZone = rect.top <= screenHeight * 0.25
            val isBottomZone = rect.bottom >= screenHeight * 0.85

            if (!rect.isEmpty && rect.intersects(0, 0, screenWidth, screenHeight) && (isTopZone || isBottomZone)) {
                return root
            }
        }

        val childCount = root.childCount
        for (i in 0 until childCount) {
            if (!budget.hasRemaining()) break
            val child = root.getChild(i) ?: continue
            val found = findStrictSkipNode(child, budget, depth + 1)
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
            if (!node.isVisibleToUser || !node.isEnabled) return false
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
