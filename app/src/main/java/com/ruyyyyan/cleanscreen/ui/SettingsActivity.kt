package com.ruyyyyan.cleanscreen.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repository by lazy { InterceptRepository.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // Setup switch states
        binding.switchMisclick.isChecked = repository.isPreventMisclickJumpEnabled
        binding.switchFastMode.isChecked = repository.isFastModeEnabled
        binding.switchToast.isChecked = repository.isShowToastEnabled
        binding.switchVibrate.isChecked = repository.isVibrateEnabled

        binding.switchMisclick.setOnCheckedChangeListener { _, isChecked ->
            repository.isPreventMisclickJumpEnabled = isChecked
        }

        binding.switchFastMode.setOnCheckedChangeListener { _, isChecked ->
            repository.isFastModeEnabled = isChecked
        }

        binding.switchToast.setOnCheckedChangeListener { _, isChecked ->
            repository.isShowToastEnabled = isChecked
        }

        binding.switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            repository.isVibrateEnabled = isChecked
        }

        binding.btnResetData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重置统计数据")
                .setMessage("确定要清空所有拦截历史记录和统计数据吗？此操作无法撤销。")
                .setPositiveButton("重置") { _, _ ->
                    repository.clearAllLogs()
                    Toast.makeText(this, "数据已成功重置", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
