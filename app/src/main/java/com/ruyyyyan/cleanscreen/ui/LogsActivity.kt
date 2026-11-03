package com.ruyyyyan.cleanscreen.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.databinding.ActivityLogsBinding
import com.ruyyyyan.cleanscreen.ui.adapter.LogAdapter

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val repository by lazy { InterceptRepository.getInstance(this) }
    private lateinit var adapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnClearLogs.setOnClickListener {
            showClearDialog()
        }

        setupRecyclerView()
        loadLogs()
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter(emptyList())
        binding.recyclerViewLogs.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewLogs.adapter = adapter
    }

    private fun loadLogs() {
        val logs = repository.getRecentLogs()
        if (logs.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerViewLogs.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerViewLogs.visibility = View.VISIBLE
            adapter.updateList(logs)
        }
    }

    private fun showClearDialog() {
        AlertDialog.Builder(this)
            .setTitle("清空日志")
            .setMessage("确定要清空所有拦截历史记录吗？")
            .setPositiveButton("清空") { _, _ ->
                repository.clearAllLogs()
                loadLogs()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
