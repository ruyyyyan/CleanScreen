package com.ruyyyyan.cleanscreen.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.utils.FloatingToast
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

    companion object {
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        KeepAliveService.startService(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
    }

    override fun onInterrupt() {
        // Accessibility service interrupted
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !repository.isMasterEnabled) return

        val pkgName = event.packageName?.toString() ?: return
        if (pkgName in IGNORED_PACKAGES) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(pkgName, now)
                scanAndSkipAd(pkgName, rootInActiveWindow)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // If this app is in its splash window (launched within 4 seconds)
                val launchTime = appLaunchTimes[pkgName] ?: 0L
                if (now - launchTime < 4500) {
                    scanAndSkipAd(pkgName, rootInActiveWindow)
                }
            }
        }
    }

    private fun handleWindowStateChanged(currentPkg: String, now: Long) {
        // Check for misclick cross-app jump if enabled
        if (repository.isPreventMisclickJumpEnabled && lastForegroundPackage.isNotEmpty()) {
            val lastPkg = lastForegroundPackage
            val lastLaunchTime = appLaunchTimes[lastPkg] ?: 0L

            // If the user opened an app within 3 seconds, and it suddenly jumped to a shopping/ad app:
            if (currentPkg != lastPkg &&
                now - lastLaunchTime < 3000 &&
                currentPkg in JUMP_TARGET_PACKAGES &&
                lastPkg !in JUMP_TARGET_PACKAGES
            ) {
                // Abnormal jump detected! Perform auto-back to return to original app
                val originalAppName = getAppLabel(lastPkg)
                val targetAppName = getAppLabel(currentPkg)

                mainHandler.postDelayed({
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
                }, 100)

                return
            }
        }

        // Track launch time for the new package
        if (currentPkg != lastForegroundPackage) {
            appLaunchTimes[currentPkg] = now
            lastForegroundPackage = currentPkg
        }
    }

    private fun scanAndSkipAd(packageName: String, rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return
        if (!repository.isAppEnabled(packageName)) return

        val now = System.currentTimeMillis()
        val lastSkip = lastSkipTimes[packageName] ?: 0L
        if (now - lastSkip < 3000) {
            // Cooldown within 3 seconds
            return
        }

        val targetNode = findSkipButtonNode(rootNode)
        if (targetNode != null) {
            val clicked = clickNodeOrGesture(targetNode)
            if (clicked) {
                lastSkipTimes[packageName] = now
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

                KeepAliveService.updateNotification(applicationContext)
            }
        }
    }

    /**
     * Recursively find node that matches "跳过" / "Skip" or ad skip button patterns
     */
    private fun findSkipButtonNode(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null // Guard against deep recursion

        // Check current node text & description
        val text = root.text?.toString()?.trim()
        val desc = root.contentDescription?.toString()?.trim()
        val viewId = root.viewIdResourceName?.lowercase() ?: ""

        if (isSkipMatch(text) || isSkipMatch(desc) || isSkipViewId(viewId)) {
            return root
        }

        // Check child nodes
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

    private fun isSkipMatch(content: String?): Boolean {
        if (content.isNullOrEmpty() || content.length > 20) return false
        for (pattern in SKIP_PATTERNS) {
            if (pattern.matcher(content).matches()) {
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
                viewId.contains("tt_splash_skip")
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo): Boolean {
        // Try direct click on node
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // Try clicking parent
        var parent = node.parent
        var attempts = 0
        while (parent != null && attempts < 3) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            attempts++
        }

        // Fallback: Dispatch simulated gesture click at node center
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val clickPath = Path().apply {
                    moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                    .build()
                return dispatchGesture(gesture, null, null)
            }
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
