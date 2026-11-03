package com.ruyyyyan.cleanscreen.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.databinding.ActivitySettingsBinding
import com.ruyyyyan.cleanscreen.utils.PermissionUtils

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repository by lazy { InterceptRepository.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupSwitchStates()
        setupListeners()
        updateKeywordSummary()
    }

    override fun onResume() {
        super.onResume()
        // If user came back from overlay permission settings, check permission
        if (repository.isShieldOverlayEnabled && !PermissionUtils.isOverlayPermissionGranted(this)) {
            repository.isShieldOverlayEnabled = false
            binding.switchShieldOverlay.isChecked = false
        }
    }

    private fun setupSwitchStates() {
        binding.switchMisclick.isChecked = repository.isPreventMisclickJumpEnabled
        binding.switchShieldOverlay.isChecked = repository.isShieldOverlayEnabled && PermissionUtils.isOverlayPermissionGranted(this)
        binding.switchFastMode.isChecked = repository.isFastModeEnabled
        binding.switchToast.isChecked = repository.isShowToastEnabled
        binding.switchVibrate.isChecked = repository.isVibrateEnabled
    }

    private fun setupListeners() {
        binding.switchMisclick.setOnCheckedChangeListener { _, isChecked ->
            repository.isPreventMisclickJumpEnabled = isChecked
        }

        binding.switchShieldOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!PermissionUtils.isOverlayPermissionGranted(this)) {
                    Toast.makeText(this, "需要授予悬浮窗权限以启用开屏安全遮罩", Toast.LENGTH_LONG).show()
                    PermissionUtils.requestOverlayPermission(this)
                    repository.isShieldOverlayEnabled = true
                } else {
                    repository.isShieldOverlayEnabled = true
                }
            } else {
                repository.isShieldOverlayEnabled = false
            }
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

        binding.cardCustomKeywords.setOnClickListener {
            showKeywordsManagerDialog()
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

    private fun updateKeywordSummary() {
        val keywords = repository.getCustomKeywords()
        binding.tvCustomKeywordsSummary.text = "已启用 ${keywords.size} 个词: " + keywords.take(4).joinToString(", ") + (if (keywords.size > 4) "..." else "")
    }

    private fun showKeywordsManagerDialog() {
        val keywords = repository.getCustomKeywords().toList()
        val items = keywords.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("自定义跳过关键词库")
            .setItems(items) { _, which ->
                val selected = items[which]
                AlertDialog.Builder(this)
                    .setTitle("删除关键词")
                    .setMessage("确定删除关键词【$selected】吗？")
                    .setPositiveButton("删除") { _, _ ->
                        repository.removeCustomKeyword(selected)
                        updateKeywordSummary()
                        Toast.makeText(this, "已删除【$selected】", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setPositiveButton("添加关键词") { _, _ ->
                showAddKeywordDialog()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAddKeywordDialog() {
        val input = EditText(this).apply {
            hint = "例如：跳过广告、知道了、离开"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("添加新关键词")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    repository.addCustomKeyword(text)
                    updateKeywordSummary()
                    Toast.makeText(this, "已添加关键词【$text】", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
