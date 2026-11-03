package com.ruyyyyan.cleanscreen.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ruyyyyan.cleanscreen.R
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.databinding.ActivityMainBinding
import com.ruyyyyan.cleanscreen.service.CleanScreenAccessibilityService
import com.ruyyyyan.cleanscreen.service.KeepAliveService
import com.ruyyyyan.cleanscreen.utils.PermissionUtils
import com.ruyyyyan.cleanscreen.utils.VivoOptimizer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { InterceptRepository.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
    }

    private fun setupListeners() {
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            repository.isMasterEnabled = isChecked
            if (isChecked) {
                KeepAliveService.startService(this)
                if (!PermissionUtils.isAccessibilityServiceEnabled(this)) {
                    PermissionUtils.openAccessibilitySettings(this)
                    Toast.makeText(this, "请开启【清屏】无障碍服务以激活拦截功能", Toast.LENGTH_LONG).show()
                }
            }
            updateDashboard()
        }

        binding.chipAccessibility.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(this)
            VivoOptimizer.showRestrictedSettingsTip(this)
        }

        binding.chipBattery.setOnClickListener {
            if (VivoOptimizer.isVivoOrIqoo()) {
                VivoOptimizer.openBackgroundPowerSettings(this)
            } else {
                PermissionUtils.requestIgnoreBatteryOptimization(this)
            }
        }

        binding.cardVivoGuide.setOnClickListener {
            startActivity(Intent(this, VivoGuideActivity::class.java))
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

    private fun updateDashboard() {
        val isMasterOn = repository.isMasterEnabled
        binding.switchMaster.isChecked = isMasterOn

        val isAccessibilityOn = PermissionUtils.isAccessibilityServiceEnabled(this)
        val isBatteryIgnored = PermissionUtils.isIgnoringBatteryOptimizations(this)

        if (isAccessibilityOn && isMasterOn) {
            binding.tvStatusTitle.text = "清屏正在持续守护"
            binding.tvStatusSubtitle.text = "开屏广告跳过与误触防跳转运行正常"
            binding.ivShieldIcon.setImageResource(R.drawable.ic_shield_check)
            binding.chipAccessibility.text = "无障碍: 已开启"
            binding.chipAccessibility.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8FFEA"))
            binding.chipAccessibility.setTextColor(Color.parseColor("#059669"))
        } else {
            binding.tvStatusTitle.text = if (!isMasterOn) "清屏已暂停" else "无障碍服务未开启"
            binding.tvStatusSubtitle.text = if (!isMasterOn) "点击右侧开关重新开启守护" else "点击下方或前往设置开启清屏服务"
            binding.ivShieldIcon.setImageResource(R.drawable.ic_warning_circle)
            binding.chipAccessibility.text = "无障碍: 点击开启"
            binding.chipAccessibility.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF7E8"))
            binding.chipAccessibility.setTextColor(Color.parseColor("#D97706"))
        }

        if (isBatteryIgnored) {
            binding.chipBattery.text = "电池优化: 已忽略"
            binding.chipBattery.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8FFEA"))
            binding.chipBattery.setTextColor(Color.parseColor("#059669"))
        } else {
            binding.chipBattery.text = "电池优化: 需配置"
            binding.chipBattery.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F3FF"))
            binding.chipBattery.setTextColor(Color.parseColor("#0E78F9"))
        }

        // Intercept stats
        val countToday = repository.getTodayInterceptCount()
        val countTotal = repository.getTotalInterceptCount()
        val protectedApps = repository.getProtectedAppCount()

        binding.tvCountToday.text = countToday.toString()
        binding.tvCountTotal.text = countTotal.toString()

        // 每次跳过估算节省 5 秒开屏等待时间
        val savedSeconds = countTotal * 5
        binding.tvSavedTime.text = if (savedSeconds >= 60) {
            "${savedSeconds / 60} 分钟"
        } else {
            "$savedSeconds 秒"
        }

        binding.tvProtectedSummary.text = "已在 $protectedApps 个应用中成功拦截广告"
    }
}
