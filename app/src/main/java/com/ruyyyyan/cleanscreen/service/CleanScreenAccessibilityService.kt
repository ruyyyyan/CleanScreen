package com.ruyyyyan.cleanscreen.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    // 冷却时间控制：包名 -> 上次跳过时间
    private val lastSkipTimes = ConcurrentHashMap<String, Long>()

    // 追踪每个 App 的冷启动时间戳：包名 -> 启动时间
    private val appLaunchTimes = ConcurrentHashMap<String, Long>()
    // 标记当前应用启动是否已完成开屏跳过
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

        // 精确匹配规则：仅匹配纯粹的“跳过”、“关闭广告”、“Skip”或带秒数的倒计时按钮
        // 杜绝匹配文章内长句，如“这个步骤可以跳过”
        private val SKIP_REGEX = Pattern.compile("^(?:\\d+s?\\s*)?(?:跳过|关闭广告|Skip|SKIP|跳\\s*过)(?:\\s*\\d+s?)?$")

        // 知名电商/导流目标包名（用于误触跳转拦截）
        private val JUMP_TARGET_PACKAGES = setOf(
            "com.taobao.taobao",           // 淘宝
            "com.jingdong.app.mall",       // 京东
            "com.xunmeng.pinduoduo",       // 拼多多
            "com.sankuai.meituan",         // 美团
            "com.tmall.wireless",          // 天猫
            "com.vipancai.vipshop",        // 唯品会
            "com.bbk.appstore",            // vivo应用商店
            "com.vivo.appstore",           // vivo应用商店
            "com.heytap.market"            // 软件商店
        )

        // 系统桌面与自身包名忽略
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.bbk.launcher2",
            "com.vivo.upslide",
            "com.android.settings",
            "com.ruyyyyan.cleanscreen"
        )

        // 仅在启动前 3 秒内安排轻量突发延迟检查
        private val BURST_DELAYS = longArrayOf(200, 500, 1000)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        updateScreenDimensions()
        Log.i(TAG, "CleanScreen Service Connected. Ultra-precise mode enabled.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        clearAllPendingScans()
        Log.i(TAG, "CleanScreen Service Destroyed.")
    }

    override fun onInterrupt() {
        Log.w(TAG, "CleanScreen Service Interrupted.")
    }

    private fun updateScreenDimensions() {
        try {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        } catch (e: Throwable) {
            // Ignore
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null || !repository.isMasterEnabled) return

            val pkgName = event.packageName?.toString() ?: return
            if (pkgName in IGNORED_PACKAGES) return

            val now = System.currentTimeMillis()

            // 核心：仅在窗口发生切换时响应
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleWindowStateChanged(pkgName, now)

                // 核心安全准则：只有在 App 启动的前 3.5 秒内才允许扫描开屏跳过按钮！
                // 一旦超过 3.5 秒或当前启动已经跳过过一次，严禁再扫描，彻底杜绝在 App 内部使用时误触任何按钮！
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

                        // 超过 3.5 秒绝不再检查
                        if (now - launchTime >= 3500 || alreadySkipped || lastForegroundPackage != pkgName) {
                            return
                        }

                        val skipped = scanAndSkipAd(pkgName, rootInActiveWindow)
                        if (skipped) {
                            clearAllPendingScans()
                        }
                    } catch (e: Throwable) {
                        // Suppress scan error
                    }
                }
            }
            pendingScanRunnables.add(runnable)
            mainHandler.postDelayed(runnable, delay)
        }
    }

    private fun handleWindowStateChanged(currentPkg: String, now: Long) {
        try {
            // 误触跨应用唤起返回防护
            if (repository.isPreventMisclickJumpEnabled && lastForegroundPackage.isNotEmpty()) {
                val lastPkg = lastForegroundPackage
                val lastLaunchTime = appLaunchTimes[lastPkg] ?: 0L

                // 启动 3 秒内若突发跨应用跳往购物平台
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

            // 新应用启动
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
            // 严谨精准匹配：只匹配真正的“跳过”节点
            val targetNode = findStrictSkipNode(rootNode)
            if (targetNode != null) {
                val clicked = clickNodeOrGesture(targetNode)
                if (clicked) {
                    lastSkipTimes[packageName] = now
                    appSkippedState[packageName] = true // 标记当前启动已完成跳过，不再进行后续扫描
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
     * 严谨深度优先检索：
     * 1. 文本长度必须 <= 8
     * 2. 严格匹配“跳过/关闭广告/Skip”正则
     * 3. 坐标必须位于屏幕顶部 25% 或底部 15% 区域（绝对不在屏幕正中间误触正文）
     */
    private fun findStrictSkipNode(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null

        val text = root.text?.toString()?.trim()
        val desc = root.contentDescription?.toString()?.trim()
        val viewId = root.viewIdResourceName?.lowercase() ?: ""

        if (isStrictSkipMatch(text) || isStrictSkipMatch(desc) || isStrictSkipViewId(viewId)) {
            // 检验屏幕坐标位置，开屏广告跳过按钮几乎都在顶部或底端，绝非正中央内容区
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

        // 精确正则匹配
        if (SKIP_REGEX.matcher(content).matches()) {
            return true
        }

        // 检查用户自定义关键词（同样限制长度，杜绝长文命中）
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
            // 优先尝试节点原生点击
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            // 向上回溯一层父容器尝试点击
            val parent = node.parent
            if (parent != null && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            // 手势坐标模拟点击
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
