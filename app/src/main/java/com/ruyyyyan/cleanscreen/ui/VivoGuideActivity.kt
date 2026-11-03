package com.ruyyyyan.cleanscreen.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ruyyyyan.cleanscreen.databinding.ActivityVivoGuideBinding
import com.ruyyyyan.cleanscreen.utils.PermissionUtils
import com.ruyyyyan.cleanscreen.utils.VivoOptimizer

class VivoGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVivoGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVivoGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnOpenAccessibility.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(this)
            VivoOptimizer.showRestrictedSettingsTip(this)
        }

        binding.btnOpenAutoStart.setOnClickListener {
            VivoOptimizer.openAutoStartSettings(this)
        }

        binding.btnOpenPowerSaving.setOnClickListener {
            VivoOptimizer.openBackgroundPowerSettings(this)
        }
    }
}
