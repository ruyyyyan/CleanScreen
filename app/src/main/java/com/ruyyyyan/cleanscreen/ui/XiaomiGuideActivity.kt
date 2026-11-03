package com.ruyyyyan.cleanscreen.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ruyyyyan.cleanscreen.databinding.ActivityXiaomiGuideBinding
import com.ruyyyyan.cleanscreen.utils.PermissionUtils
import com.ruyyyyan.cleanscreen.utils.XiaomiOptimizer

class XiaomiGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXiaomiGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXiaomiGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnOpenAccessibility.setOnClickListener {
            XiaomiOptimizer.showHyperOSWarningTip(this)
            PermissionUtils.openAccessibilitySettings(this)
        }

        binding.btnOpenAutoStart.setOnClickListener {
            XiaomiOptimizer.openAutoStartSettings(this)
        }

        binding.btnOpenBatterySaver.setOnClickListener {
            XiaomiOptimizer.openBatterySaverSettings(this)
        }
    }
}
