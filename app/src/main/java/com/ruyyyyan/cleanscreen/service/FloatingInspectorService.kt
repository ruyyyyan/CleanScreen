package com.ruyyyyan.cleanscreen.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.ruyyyyan.cleanscreen.R
import com.ruyyyyan.cleanscreen.data.model.SkipRule
import com.ruyyyyan.cleanscreen.data.rules.RuleEngine
import com.ruyyyyan.cleanscreen.databinding.LayoutFloatingBubbleBinding
import com.ruyyyyan.cleanscreen.databinding.LayoutInspectorDialogBinding
import kotlin.math.abs

/**
 * Floating Window Node Inspector Service.
 * Inspired by GuoXiCheng/SKIP interactive inspection tool.
 * Allows on-screen node capture, click testing, and 1-click rule generation.
 */
class FloatingInspectorService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleBinding: LayoutFloatingBubbleBinding? = null
    private var dialogBinding: LayoutInspectorDialogBinding? = null
    private val ruleEngine by lazy { RuleEngine.getInstance(this) }

    private var screenWidth = 1080
    private var screenHeight = 2400

    companion object {
        private const val TAG = "FloatingInspector"

        @Volatile
        var isServiceRunning = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请先授予清屏【悬浮窗】权限", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, FloatingInspectorService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingInspectorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenDimensions()
        createFloatingBubble()
        isServiceRunning = true
        Log.i(TAG, "FloatingInspectorService started")
    }

    override fun onDestroy() {
        dismissDialog()
        bubbleBinding?.root?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing bubbleView", e)
            }
        }
        bubbleBinding = null
        isServiceRunning = false
        Log.i(TAG, "FloatingInspectorService stopped")
        super.onDestroy()
    }

    private fun updateScreenDimensions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                val display = windowManager.defaultDisplay
                val point = Point()
                display.getRealSize(point)
                screenWidth = point.x
                screenHeight = point.y
            }
        } catch (e: Exception) {
            screenWidth = 1080
            screenHeight = 2400
        }
    }

    private fun createFloatingBubble() {
        bubbleBinding = LayoutFloatingBubbleBinding.inflate(LayoutInflater.from(this))

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 160
            y = screenHeight / 3
        }

        bubbleBinding?.root?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isMoving = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(bubbleBinding?.root, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "Update layout error", e)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) {
                            performScreenInspection()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleBinding?.root, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bubbleView to window manager", e)
        }
    }

    private fun performScreenInspection() {
        val service = CleanScreenAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "无障碍服务未连接，请先在清屏主界面激活", Toast.LENGTH_SHORT).show()
            return
        }

        val rootNode = service.rootInActiveWindow
        if (rootNode == null) {
            Toast.makeText(this, "未能捕获当前窗口节点，请确保目标应用在屏幕前台", Toast.LENGTH_SHORT).show()
            return
        }

        val currentPkg = CleanScreenAccessibilityService.lastActivePackage.ifEmpty { "未知应用" }
        val currentAct = CleanScreenAccessibilityService.lastActiveActivity.ifEmpty { "未知页面" }

        val candidates = ruleEngine.extractCandidateNodes(rootNode, screenWidth, screenHeight)
        val bestCandidate = candidates.firstOrNull()

        showInspectorDialog(currentPkg, currentAct, bestCandidate)
    }

    private fun showInspectorDialog(
        packageName: String,
        activityName: String,
        candidate: RuleEngine.CandidateNode?
    ) {
        dismissDialog()

        dialogBinding = LayoutInspectorDialogBinding.inflate(LayoutInflater.from(this))
        val binding = dialogBinding ?: return

        val appLabel = getAppLabel(packageName)
        binding.tvInspectorAppName.text = "目标应用: $appLabel ($packageName)"
        binding.tvInspectorActivity.text = "当前 Activity: ${activityName.substringAfterLast(".")}"

        if (candidate != null) {
            val idText = if (candidate.viewId.isNotEmpty()) candidate.viewId else "未分配 ViewID (通过文本/坐标捕获)"
            binding.tvDetectedNodeId.text = "控件 ID: $idText"

            val displayText = if (candidate.text.isNotEmpty()) candidate.text else "纯图标/无文字"
            binding.tvDetectedNodeText.text = "识别文本: $displayText"

            val rect = candidate.bounds
            val xRatio = (rect.centerX().toFloat() / screenWidth.coerceAtLeast(1) * 100).toInt()
            val yRatio = (rect.centerY().toFloat() / screenHeight.coerceAtLeast(1) * 100).toInt()
            binding.tvDetectedBounds.text = "坐标热区: [${rect.left}, ${rect.top}] - [${rect.right}, ${rect.bottom}] (屏幕 $xRatio%, $yRatio%)"

            binding.btnTestClick.setOnClickListener {
                val service = CleanScreenAccessibilityService.instance
                if (service != null) {
                    val clicked = service.clickTargetNode(candidate.node)
                    Toast.makeText(this, if (clicked) "已执行模拟点击！" else "模拟点击失败", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "无障碍服务断开", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnSaveRule.setOnClickListener {
                val ruleX = rect.centerX().toFloat() / screenWidth.coerceAtLeast(1)
                val ruleY = rect.centerY().toFloat() / screenHeight.coerceAtLeast(1)

                val newRule = SkipRule(
                    id = "custom:$packageName:inspect_${System.currentTimeMillis()}",
                    packageName = packageName,
                    appName = appLabel,
                    description = "悬浮窗审查自动生成",
                    activityIds = if (activityName.isNotEmpty() && !activityName.contains("未知")) listOf(activityName) else emptyList(),
                    targetId = candidate.viewId,
                    targetTextRegex = if (candidate.text.isNotEmpty()) "^(?:\\d+s?\\s*)?${candidate.text}(?:\\s*\\d+s?)?$" else "",
                    coordinateX = ruleX,
                    coordinateY = ruleY,
                    positionZone = SkipRule.PositionZone.EDGE,
                    isBuiltin = false
                )

                ruleEngine.addCustomRule(newRule)
                Toast.makeText(this, "已为【$appLabel】生成并激活跳过规则！", Toast.LENGTH_SHORT).show()
                dismissDialog()
            }
        } else {
            binding.tvDetectedNodeId.text = "未能自动锁定明确的跳过按钮"
            binding.tvDetectedNodeText.text = "提示: 该页面可能为 Webview 或自绘 Canvas"
            binding.tvDetectedBounds.text = "建议使用右侧屏幕坐标兜底规则"
            binding.btnTestClick.visibility = View.GONE
            binding.btnSaveRule.text = "保存右上角坐标兜底规则"
            binding.btnSaveRule.setOnClickListener {
                val newRule = SkipRule(
                    id = "custom:$packageName:coord_${System.currentTimeMillis()}",
                    packageName = packageName,
                    appName = appLabel,
                    description = "右上角坐标模拟跳过",
                    activityIds = if (activityName.isNotEmpty() && !activityName.contains("未知")) listOf(activityName) else emptyList(),
                    coordinateX = 0.88f,
                    coordinateY = 0.06f,
                    isBuiltin = false
                )
                ruleEngine.addCustomRule(newRule)
                Toast.makeText(this, "已为【$appLabel】添加右上角坐标跳过规则！", Toast.LENGTH_SHORT).show()
                dismissDialog()
            }
        }

        binding.btnCloseInspector.setOnClickListener {
            dismissDialog()
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dialogParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 80
        }

        try {
            windowManager.addView(binding.root, dialogParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying inspector dialog", e)
        }
    }

    private fun dismissDialog() {
        dialogBinding?.root?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dialogView", e)
            }
        }
        dialogBinding = null
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
