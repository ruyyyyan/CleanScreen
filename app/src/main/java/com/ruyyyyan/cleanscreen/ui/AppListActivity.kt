package com.ruyyyyan.cleanscreen.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ruyyyyan.cleanscreen.data.model.AppInfo
import com.ruyyyyan.cleanscreen.data.repository.InterceptRepository
import com.ruyyyyan.cleanscreen.databinding.ActivityAppListBinding
import com.ruyyyyan.cleanscreen.ui.adapter.AppListAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        loadInstalledApps()
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

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val dbAppsMap = repository.getAllAppInfos().associateBy { it.packageName }
                val installedPackages = packageManager.getInstalledPackages(0)

                val result = mutableListOf<AppInfo>()
                for (pkg in installedPackages) {
                    val appInfo = pkg.applicationInfo ?: continue
                    val pkgName = pkg.packageName

                    // Filter out our own app
                    if (pkgName == packageName) continue

                    // Include apps with a launcher intent or non-system user apps
                    val isUserApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    val hasLaunchIntent = packageManager.getLaunchIntentForPackage(pkgName) != null

                    if (isUserApp || hasLaunchIntent) {
                        val appName = try {
                            packageManager.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            pkgName
                        }

                        val existing = dbAppsMap[pkgName]
                        if (existing != null) {
                            result.add(existing)
                        } else {
                            result.add(
                                AppInfo(
                                    packageName = pkgName,
                                    appName = appName,
                                    isEnabled = repository.isAppEnabled(pkgName),
                                    totalCount = 0,
                                    todayCount = 0,
                                    lastInterceptTime = 0
                                )
                            )
                        }
                    }
                }

                // Sort: apps with interception counts first, then alphabetical by name
                result.sortedWith(
                    compareByDescending<AppInfo> { it.totalCount }
                        .thenBy { it.appName }
                )
            }

            allApps = list
            if (allApps.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerViewApps.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerViewApps.visibility = View.VISIBLE
                adapter.updateList(allApps)
            }
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
