package com.ruyyyyan.cleanscreen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ruyyyyan.cleanscreen.R
import com.ruyyyyan.cleanscreen.databinding.ActivityDiagnosticBinding
import com.ruyyyyan.cleanscreen.service.CleanScreenAccessibilityService
import com.ruyyyyan.cleanscreen.utils.AccessibilityServiceState
import com.ruyyyyan.cleanscreen.utils.PermissionUtils
import com.ruyyyyan.cleanscreen.utils.RestrictedSettingsStatus
import com.ruyyyyan.cleanscreen.utils.ServiceStateHolder
import com.ruyyyyan.cleanscreen.utils.ShizukuHelper

class DiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticBinding

    private val stateChangeListener = {
        runOnUiThread {
            refreshDiagnosticInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        ServiceStateHolder.addListener(stateChangeListener)
    }

    override fun onStop() {
        super.onStop()
        ServiceStateHolder.removeListener(stateChangeListener)
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnosticInfo()
    }

    private fun setupListeners() {
        binding.btnOpenAccessibility.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(this)
        }

        binding.btnFixRestricted.setOnClickListener {
            showRestrictedSettingsDialog()
        }

        binding.btnClearEvents.setOnClickListener {
            ServiceStateHolder.clearHistory()
            refreshDiagnosticInfo()
            Toast.makeText(this, "生命周期记录已清空", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyDiagnosticReport.setOnClickListener {
            copyDiagnosticReport()
        }
    }

    private fun refreshDiagnosticInfo() {
        val currentState = PermissionUtils.getAccessibilityServiceState(this)
        val restrictedStatus = PermissionUtils.checkRestrictedSettingsStatus(this)

        renderOverallState(currentState)
        renderRestrictedStatus(restrictedStatus)
        renderFrameworkParameters()
        renderLifecycleTimeline()
    }

    /**
     * Renders the overall state card with precise 5-state indicators.
     */
    private fun renderOverallState(state: AccessibilityServiceState) {
        binding.tvStateCode.text = state.name

        when (state) {
            AccessibilityServiceState.RUNNING -> {
                binding.ivStatusBadge.setImageResource(R.drawable.ic_shield_check)
                binding.tvStateTitle.text = "服务运行正常 (RUNNING)"
                binding.tvStateTitle.setTextColor(Color.parseColor("#059669"))
                binding.tvStateSubtitle.text = "AccessibilityManager 已建立绑定，事件监听活跃"
                binding.tvStateDescription.text = "✅ 核心无障碍通道正常工作。开屏广告识别与误触跨应用返回防护已完全激活。"
                binding.tvStateCode.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8FFEA"))
                binding.tvStateCode.setTextColor(Color.parseColor("#059669"))
            }
            AccessibilityServiceState.CONFIGURED_NOT_BOUND -> {
                binding.ivStatusBadge.setImageResource(R.drawable.ic_warning_circle)
                binding.tvStateTitle.text = "已授权但未成功连接 (NOT_BOUND)"
                binding.tvStateTitle.setTextColor(Color.parseColor("#D97706"))
                binding.tvStateSubtitle.text = "系统设置显示已开启，但系统框架未连接服务进程"
                binding.tvStateDescription.text = "⚠️ 典型排查建议：\n" +
                    "1.【Android 16 受限制设置】：若安装包为本地侧载安装，系统可能因敏感权限安全策略阻止绑定。请点击下方【解除限制指引】；\n" +
                    "2.【服务挂起】：前往系统无障碍设置，将清屏开关【关闭后再重新开启一次】；\n" +
                    "3.【厂商清理】：确认应用未被系统电池优化或安全中心阻止自启动。"
                binding.tvStateCode.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF7E8"))
                binding.tvStateCode.setTextColor(Color.parseColor("#D97706"))
            }
            AccessibilityServiceState.CONNECTION_LOST -> {
                binding.ivStatusBadge.setImageResource(R.drawable.ic_warning_circle)
                binding.tvStateTitle.text = "连接已丢失 (CONNECTION_LOST)"
                binding.tvStateTitle.setTextColor(Color.parseColor("#DC2626"))
                binding.tvStateSubtitle.text = "服务曾正常连接，但随后被系统注销或异常退出"
                binding.tvStateDescription.text = "❌ 服务的连接被系统撤销。\n" +
                    "原因通常为：系统多任务清理、内存回收杀进程、或高耗电被策略管控。\n" +
                    "请配置【允许后台高耗电】并在多任务卡片加锁，随后重新打开无障碍开关。"
                binding.tvStateCode.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEE2E2"))
                binding.tvStateCode.setTextColor(Color.parseColor("#DC2626"))
            }
            AccessibilityServiceState.NOT_CONFIGURED -> {
                binding.ivStatusBadge.setImageResource(R.drawable.ic_warning_circle)
                binding.tvStateTitle.text = "无障碍服务未授权 (NOT_CONFIGURED)"
                binding.tvStateTitle.setTextColor(Color.parseColor("#4B5563"))
                binding.tvStateSubtitle.text = "系统无障碍列表中尚未开启清屏"
                binding.tvStateDescription.text = "ℹ️ 请点击下方【前往系统无障碍设置】开启【清屏 - 广告智能跳过服务】。"
                binding.tvStateCode.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3F4F6"))
                binding.tvStateCode.setTextColor(Color.parseColor("#4B5563"))
            }
            AccessibilityServiceState.UNKNOWN -> {
                binding.ivStatusBadge.setImageResource(R.drawable.ic_warning_circle)
                binding.tvStateTitle.text = "状态暂时未知 (UNKNOWN)"
                binding.tvStateTitle.setTextColor(Color.parseColor("#6B7280"))
                binding.tvStateSubtitle.text = "无法读取系统无障碍设置数据"
                binding.tvStateDescription.text = "无法读取 Settings.Secure 记录，请确认已授予基础权限。"
                binding.tvStateCode.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3F4F6"))
                binding.tvStateCode.setTextColor(Color.parseColor("#6B7280"))
            }
        }
    }

    private fun renderRestrictedStatus(status: RestrictedSettingsStatus) {
        when (status) {
            RestrictedSettingsStatus.ALLOWED -> {
                binding.tvRestrictedStatus.text = "✅ 允许配置 (MODE_ALLOWED)"
                binding.tvRestrictedStatus.setTextColor(Color.parseColor("#059669"))
            }
            RestrictedSettingsStatus.RESTRICTED -> {
                binding.tvRestrictedStatus.text = "⚠️ 受到系统限制 (MODE_ERRORED / IGNORED)"
                binding.tvRestrictedStatus.setTextColor(Color.parseColor("#DC2626"))
            }
            RestrictedSettingsStatus.NOT_RESTRICTED -> {
                binding.tvRestrictedStatus.text = "ℹ️ 系统无需此受限检测 (< Android 13)"
                binding.tvRestrictedStatus.setTextColor(Color.parseColor("#6B7280"))
            }
            RestrictedSettingsStatus.UNKNOWN -> {
                binding.tvRestrictedStatus.text = "❓ 状态未能明确确定"
                binding.tvRestrictedStatus.setTextColor(Color.parseColor("#6B7280"))
            }
        }
    }

    private fun renderFrameworkParameters() {
        val enabledServicesStr = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "(None)"

        val isAccessibilityGlobalOn = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1

        val isListedInAms = PermissionUtils.isServiceListedInAms(this)
        val isInstanceRunning = CleanScreenAccessibilityService.isServiceRunning
        val pid = Process.myPid()

        val sb = StringBuilder()
        sb.append("• 设备型号: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("• Android版本: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("• 应用进程 PID: ").append(pid).append("\n")
        val shizukuDesc = if (ShizukuHelper.isPermissionGranted()) "GRANTED (已授权高权限)" else if (ShizukuHelper.isShizukuAvailable()) "AVAILABLE (运行中但未授权)" else "NOT_RUNNING (未运行)"
        sb.append("• Shizuku 特权状态: ").append(shizukuDesc).append("\n")
        sb.append("• 全局无障碍开关 (accessibility_enabled): ").append(if (isAccessibilityGlobalOn) "1 (开启)" else "0 (关闭)").append("\n")
        sb.append("• AMS 已绑定列表中是否存在本服务: ").append(if (isListedInAms) "YES (已绑定)" else "NO (未列入)").append("\n")
        sb.append("• 服务内存实例状态: ").append(if (isInstanceRunning) "ACTIVE (运行中)" else "INACTIVE (空闲/未实例化)").append("\n")
        sb.append("• 系统启用服务记录:\n  ").append(enabledServicesStr.replace(":", "\n  "))

        binding.tvFrameworkDetails.text = sb.toString()
    }

    private fun renderLifecycleTimeline() {
        val events = ServiceStateHolder.getEventHistory()
        if (events.isEmpty()) {
            binding.tvLifecycleEvents.text = "本进程启动以来暂无生命周期事件回调记录\n(提示: 仅当系统触发服务生命周期方法时此处会产生记录)"
            return
        }

        val sb = StringBuilder()
        for (event in events) {
            sb.append("[").append(event.getFormattedTime()).append("] ")
                .append(event.eventName)
                .append(" (T=").append(event.threadName).append(")")
            if (event.details.isNotEmpty()) {
                sb.append(" -> ").append(event.details)
            }
            sb.append("\n")
        }
        binding.tvLifecycleEvents.text = sb.toString().trimEnd()
    }

    private fun showRestrictedSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("解除 Android 16【受限制的设置】")
            .setMessage(
                "Android 13+ 对浏览器或本地直接安装的 APK 强制限制敏感权限，导致无障碍开关刚开启就被系统秒关。\n\n" +
                "【解除步骤】：\n" +
                "1. 点击下方【前往应用信息】；\n" +
                "2. 在应用信息页，点击右上角【三点菜单 (⋮)】；\n" +
                "3. 点击【允许受限制的设置】(Allow restricted settings)；\n" +
                "4. 验证手机密码/指纹后返回；\n" +
                "5. 重新进入系统无障碍设置开启 CleanScreen。"
            )
            .setPositiveButton("前往应用信息") { _, _ ->
                PermissionUtils.openAppDetailsSettings(this)
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun copyDiagnosticReport() {
        val report = buildDiagnosticReport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("CleanScreen Diagnostic Report", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "诊断报告已复制到剪贴板，可粘贴反馈", Toast.LENGTH_SHORT).show()
    }

    private fun buildDiagnosticReport(): String {
        val state = PermissionUtils.getAccessibilityServiceState(this)
        val restricted = PermissionUtils.checkRestrictedSettingsStatus(this)
        val enabledServicesStr = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "(None)"
        val isAmsListed = PermissionUtils.isServiceListedInAms(this)

        val sb = StringBuilder()
        sb.append("=== CleanScreen Accessibility Diagnostic Report ===\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("OS Version: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("App Version: 1.0.8 (Code 9)\n")
        sb.append("Shizuku Status: ").append(if (ShizukuHelper.isPermissionGranted()) "Authorized" else if (ShizukuHelper.isShizukuAvailable()) "Running (Not Authorized)" else "Not Running").append("\n")
        sb.append("Service State: ").append(state.name).append(" (").append(state.description).append(")\n")
        sb.append("Restricted Settings: ").append(restricted.label).append("\n")
        sb.append("Process PID: ").append(Process.myPid()).append("\n")
        sb.append("Service Instance Running: ").append(CleanScreenAccessibilityService.isServiceRunning).append("\n")
        sb.append("Listed in AMS Bound Services: ").append(isAmsListed).append("\n")
        sb.append("Settings.Secure.enabled_accessibility_services: ").append(enabledServicesStr).append("\n\n")
        sb.append("--- Lifecycle Event History ---\n")
        val events = ServiceStateHolder.getEventHistory()
        if (events.isEmpty()) {
            sb.append("(No lifecycle events recorded yet)\n")
        } else {
            for (e in events) {
                sb.append(e.getFormattedTime()).append(" | ").append(e.eventName).append(" | ").append(e.details).append("\n")
            }
        }
        sb.append("==================================================\n")
        return sb.toString()
    }
}
