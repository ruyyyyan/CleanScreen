package com.ruyyyyan.cleanscreen.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ruyyyyan.cleanscreen.data.model.AppInfo
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.databinding.ActivityAppListBinding
import com.ruyyyyan.cleanscreen.ui.adapter.AppListAdapter

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private val repository by lazy { InterceptRepository.getInstance(this) }
    private lateinit var adapter: AppListAdapter
    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        setupRecyclerView()
        setupSearch()
        loadApps()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter(emptyList(), packageManager) { app, isEnabled ->
            repository.setAppEnabled(app.packageName, app.appName, isEnabled)
        }
        binding.recyclerViewApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewApps.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadApps() {
        allApps = repository.getAllAppInfos()
        if (allApps.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerViewApps.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerViewApps.visibility = View.VISIBLE
            adapter.updateList(allApps)
        }
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            adapter.updateList(allApps)
            return
        }
        val filtered = allApps.filter {
            it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        adapter.updateList(filtered)
    }
}
