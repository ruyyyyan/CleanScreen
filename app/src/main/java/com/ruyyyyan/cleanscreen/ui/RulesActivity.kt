package com.ruyyyyan.cleanscreen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ruyyyyan.cleanscreen.data.model.SkipRule
import com.ruyyyyan.cleanscreen.data.rules.RuleEngine
import com.ruyyyyan.cleanscreen.databinding.ActivityRulesBinding
import com.ruyyyyan.cleanscreen.ui.adapter.RuleAdapter

class RulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRulesBinding
    private val ruleEngine by lazy { RuleEngine.getInstance(this) }
    private lateinit var adapter: RuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupListeners()
        refreshRulesList()
    }

    private fun setupRecyclerView() {
        adapter = RuleAdapter(
            allRules = emptyList(),
            onToggle = { rule, isEnabled ->
                ruleEngine.setRuleEnabled(rule.id, isEnabled)
                updateStatsText(ruleEngine.getAllRules())
            },
            onDelete = { rule ->
                confirmDeleteRule(rule)
            }
        )
        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = adapter
    }

    private fun setupListeners() {
        binding.etSearchRule.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
                binding.tvEmptyRules.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnImportRules.setOnClickListener {
            showImportDialog()
        }

        binding.btnExportRules.setOnClickListener {
            exportCustomRules()
        }
    }

    private fun refreshRulesList() {
        val rules = ruleEngine.getAllRules()
        adapter.updateRules(rules)
        binding.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        updateStatsText(rules)
    }

    private fun updateStatsText(rules: List<SkipRule>) {
        val enabledCount = rules.count { it.isEnabled }
        binding.tvRuleStats.text = "共 ${rules.size} 条规则已装载 (已启用 $enabledCount 条)"
    }

    private fun confirmDeleteRule(rule: SkipRule) {
        AlertDialog.Builder(this)
            .setTitle("删除自定义规则")
            .setMessage("确定删除应用【${rule.appName}】的自定义规则吗？")
            .setPositiveButton("删除") { _, _ ->
                ruleEngine.deleteCustomRule(rule.id)
                refreshRulesList()
                Toast.makeText(this, "规则已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showImportDialog() {
        val editText = EditText(this).apply {
            hint = "支持粘贴 JSON 格式规则，或简易文本格式：\n包名, 控件ID, 正则\n例如：\ncom.example.app, btn_skip, ^跳过$"
            minLines = 5
            setPadding(40, 30, 40, 30)
            textSize = 13f
        }

        AlertDialog.Builder(this)
            .setTitle("导入自定义跳过规则")
            .setView(editText)
            .setPositiveButton("立即导入") { _, _ ->
                val content = editText.text.toString().trim()
                if (content.isEmpty()) {
                    Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val count = if (content.startsWith("[") || content.startsWith("{")) {
                    ruleEngine.importRulesFromJson(content)
                } else {
                    ruleEngine.importRulesFromText(content)
                }

                if (count > 0) {
                    refreshRulesList()
                    Toast.makeText(this, "成功导入 $count 条规则！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "规则解析失败，请检查格式", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCustomRules() {
        val json = ruleEngine.exportCustomRulesJson()
        if (json.isEmpty() || json == "[]") {
            Toast.makeText(this, "当前暂无自定义规则可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("CleanScreen Custom Rules", json)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已将自定义规则复制到剪贴板！", Toast.LENGTH_SHORT).show()
    }
}
