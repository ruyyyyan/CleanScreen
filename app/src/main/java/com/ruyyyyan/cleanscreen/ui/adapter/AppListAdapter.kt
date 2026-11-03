package com.ruyyyyan.cleanscreen.ui.adapter

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.ruyyyyan.cleanscreen.R
import com.ruyyyyan.cleanscreen.data.model.AppInfo
import java.util.concurrent.ConcurrentHashMap

class AppListAdapter(
    private var apps: List<AppInfo>,
    private val packageManager: PackageManager,
    private val onToggleChanged: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val iconCache = ConcurrentHashMap<String, Drawable>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        val tvTotalCountBadge: TextView = itemView.findViewById(R.id.tvTotalCountBadge)
        val tvTodayCountBadge: TextView = itemView.findViewById(R.id.tvTodayCountBadge)
        val switchAppEnable: MaterialSwitch = itemView.findViewById(R.id.switchAppEnable)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_info, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = apps[position]
        holder.tvAppName.text = item.appName
        holder.tvPackageName.text = item.packageName
        holder.tvTotalCountBadge.text = "累计 ${item.totalCount} 次"
        holder.tvTodayCountBadge.text = "今日 ${item.todayCount} 次"

        holder.switchAppEnable.setOnCheckedChangeListener(null)
        holder.switchAppEnable.isChecked = item.isEnabled
        holder.switchAppEnable.setOnCheckedChangeListener { _, isChecked ->
            item.isEnabled = isChecked
            onToggleChanged(item, isChecked)
        }

        // Load icon with cache
        val cached = iconCache[item.packageName]
        if (cached != null) {
            holder.ivAppIcon.setImageDrawable(cached)
        } else {
            try {
                val icon = packageManager.getApplicationIcon(item.packageName)
                iconCache[item.packageName] = icon
                holder.ivAppIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                holder.ivAppIcon.setImageResource(R.drawable.ic_launcher)
            }
        }
    }

    override fun getItemCount(): Int = apps.size

    fun updateList(newList: List<AppInfo>) {
        apps = newList
        notifyDataSetChanged()
    }
}
