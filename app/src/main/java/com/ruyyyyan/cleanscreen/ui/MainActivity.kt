package com.ruyyyyan.cleanscreen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.ruyyyyan.cleanscreen.R
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.databinding.ActivityMainBinding
import com.ruyyyyan.cleanscreen.service.CleanScreenAccessibilityService
import com.ruyyyyan.cleanscreen.utils.PermissionUtils
import com.ruyyyyan.cleanscreen.utils.VivoOptimizer
import com.ruyyyyan.cleanscreen.utils.XiaomiOptimizer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { InterceptRepository.getInstance(this) }
    private var isUpdatingUi = false

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupBrandGuideBanner()
            setupListeners()
            checkAndRequestNotificationPermission()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateDashboard()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS
                )
            }
        }
    }

    private fun setupBrandGuideBanner() {
        val isXiaomi = XiaomiOptimizer.isXiaomiOrRedmi()
        val isVivo = VivoOptimizer.isVivoOrIqoo()

        if (isXiaomi) {
            binding.tvBrandGuideTitle.text = "小米 14 / 15 旗舰 (HyperOS) 专属保活配置"
            binding.tvBrandGuideSubtitle.text = "配置自启动与【无限制】省电策略，防止后台被杀"
            binding.cardBrandGuide.setOnClickListener {
                startActivity(Intent(this, XiaomiGuideActivity::class.java))
            }
        } else if (isVivo) {
            binding.tvBrandGuideTitle.text = "iQOO Neo 11 / OriginOS 深度保活配置"
            binding.tvBrandGuideSubtitle.text = "配置自启动与允许后台高耗电，防止无障碍被关"
            binding.cardBrandGuide.setOnClickListener {
                startActivity(Intent(this, VivoGuideActivity::class.java))
            }
        } else {
            binding.tvBrandGuideTitle.text = "小米 / iQOO 旗舰深度保活配置"
            binding.tvBrandGuideSubtitle.text = "支持小米 HyperOS 与 vivo OriginOS 专属保活指引"
            binding.cardBrandGuide.setOnClickListener {
                showBrandChoiceDialog()
            }
        }
    }

    private fun showBrandChoiceDialog() {
        val options = arrayOf("小米旗舰机 (HyperOS 澎湃OS) 保活指南", "iQOO / vivo (OriginOS) 保活指南")
        AlertDialog.Builder(this)
            .setTitle("请选择您的手机系统")
            .setItems(options) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, XiaomiGuideActivity::class.java))
                } else {
                    startActivity(Intent(this, VivoGuideActivity::class.java))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupListeners() {
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            repository.isMasterEnabled = isChecked
            if (isChecked) {
                if (!PermissionUtils.isAccessibilityServiceEnabled(this)) {
                    Toast.makeText(this, "请点击下方【无障碍: 点击开启】激活服务", Toast.LENGTH_SHORT).show()
                }
            }
            updateDashboard()
        }

        binding.chipAccessibility.setOnClickListener {
            handleAccessibilityChipClick()
        }

        binding.chipBattery.setOnClickListener {
            if (XiaomiOptimizer.isXiaomiOrRedmi()) {
                XiaomiOptimizer.openBatterySaverSettings(this)
            } else if (VivoOptimizer.isVivoOrIqoo()) {
                VivoOptimizer.openBackgroundPowerSettings(this)
            } else {
                PermissionUtils.requestIgnoreBatteryOptimization(this)
            }
        }

        binding.cardAppList.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        binding.cardLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun handleAccessibilityChipClick() {
        val isConfigured = PermissionUtils.isAccessibilityServiceEnabled(this)
        val isRunning = PermissionUtils.isAccessibilityServiceRunning(this)

        if (isConfigured && !isRunning) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ 无障碍服务已授权但未成功连接")
                .setMessage(
                    "系统设置显示已开启，但无障碍服务未成功绑定运行。\n\n" +
                    "可能原因与解决步骤：\n" +
                    "1. 【受限制的设置】：Android 13+ 对浏览器或本地安装的 APK 限制敏感权限。请长按本软件图标或前往【应用信息】，点击右上角三点菜单，选择【允许受限制的设置】。\n" +
                    "2. 【服务挂起】：请前往系统设置将清屏无障碍开关【关闭后再重新开启一次】。"
                )
                .setPositiveButton("前往无障碍重新开启") { _, _ ->
                    PermissionUtils.openAccessibilitySettings(this)
                }
                .setNeutralButton("前往解除受限制设置") { _, _ ->
                    PermissionUtils.openAppDetailsSettings(this)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (XiaomiOptimizer.isXiaomiOrRedmi()) {
            XiaomiOptimizer.showHyperOSWarningTip(this)
        } else {
            VivoOptimizer.showRestrictedSettingsTip(this)
        }
        PermissionUtils.openAccessibilitySettings(this)
    }

    private fun updateDashboard() {
        isUpdatingUi = true
        try {
            val isMasterOn = repository.isMasterEnabled
            if (binding.switchMaster.isChecked != isMasterOn) {
                binding.switchMaster.isChecked = isMasterOn
            }

            val isAccessibilityConfigured = PermissionUtils.isAccessibilityServiceEnabled(this)
            val isAccessibilityRunning = PermissionUtils.isAccessibilityServiceRunning(this)
            val isBatteryIgnored = PermissionUtils.isIgnoringBatteryOptimizations(this)

            if (isAccessibilityConfigured && isAccessibilityRunning && isMasterOn) {
                binding.tvStatusTitle.text = "清屏正在持续守护"
                binding.tvStatusSubtitle.text = "开屏广告跳过与误触防跳转运行正常"
                binding.ivShieldIcon.setImageResource(R.drawable.ic_shield_check)
                binding.chipAccessibility.text = "无障碍: 运行正常"
                binding.chipAccessibility.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8FFEA"))
                binding.chipAccessibility.setTextColor(Color.parseColor("#059669"))
            } else if (isAccessibilityConfigured && !isAccessibilityRunning) {
                binding.tvStatusTitle.text = "无障碍服务异常断开"
                binding.tvStatusSubtitle.text = "系统已授权但服务未连接，点击修复或检查受限设置"
                binding.ivShieldIcon.setImageResource(R.drawable.ic_warning_circle)
                binding.chipAccessibility.text = "无障碍: 授权未连接"
                binding.chipAccessibility.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF7E8"))
                binding.chipAccessibility.setTextColor(Color.parseColor("#D97706"))
            } else {
                binding.tvStatusTitle.text = if (!isMasterOn) "清屏已暂停" else "无障碍服务未开启"
                binding.tvStatusSubtitle.text = if (!isMasterOn) "点击右侧开关重新开启守护" else "点击下方或前往设置开启清屏服务"
                binding.ivShieldIcon.setImageResource(R.drawable.ic_warning_circle)
                binding.chipAccessibility.text = "无障碍: 点击开启"
                binding.chipAccessibility.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF7E8"))
                binding.chipAccessibility.setTextColor(Color.parseColor("#D97706"))
            }

            if (isBatteryIgnored) {
                binding.chipBattery.text = "省电策略: 已优化"
                binding.chipBattery.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8FFEA"))
                binding.chipBattery.setTextColor(Color.parseColor("#059669"))
            } else {
                binding.chipBattery.text = "省电策略: 需配置"
                binding.chipBattery.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F3FF"))
                binding.chipBattery.setTextColor(Color.parseColor("#0E78F9"))
            }

            val countToday = repository.getTodayInterceptCount()
            val countTotal = repository.getTotalInterceptCount()
            val protectedApps = repository.getProtectedAppCount()

            binding.tvCountToday.text = countToday.toString()
            binding.tvCountTotal.text = countTotal.toString()

            val savedSeconds = countTotal * 5
            binding.tvSavedTime.text = if (savedSeconds >= 60) {
                "${savedSeconds / 60} 分钟"
            } else {
                "$savedSeconds 秒"
            }

            binding.tvProtectedSummary.text = "已在 $protectedApps 个应用中成功拦截广告"
        } finally {
            isUpdatingUi = false
        }
    }
}
