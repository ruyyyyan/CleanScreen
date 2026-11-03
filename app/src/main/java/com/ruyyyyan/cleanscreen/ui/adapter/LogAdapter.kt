package com.ruyyyyan.cleanscreen.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ruyyyyan.cleanscreen.R
import com.ruyyyyan.cleanscreen.data.model.InterceptRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private var logs: List<InterceptRecord>
) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLogTypeBadge: TextView = itemView.findViewById(R.id.tvLogTypeBadge)
        val tvLogAppName: TextView = itemView.findViewById(R.id.tvLogAppName)
        val tvLogTime: TextView = itemView.findViewById(R.id.tvLogTime)
        val tvLogDescription: TextView = itemView.findViewById(R.id.tvLogDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = logs[position]
        holder.tvLogAppName.text = item.appName
        holder.tvLogTime.text = timeFormat.format(Date(item.timestamp))
        holder.tvLogDescription.text = item.description

        if (item.type == InterceptRecord.TYPE_SPLASH_SKIP) {
            holder.tvLogTypeBadge.text = "开屏跳过"
            holder.tvLogTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8FFEA"))
            holder.tvLogTypeBadge.setTextColor(Color.parseColor("#059669"))
        } else {
            holder.tvLogTypeBadge.text = "误触拦截"
            holder.tvLogTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF7E8"))
            holder.tvLogTypeBadge.setTextColor(Color.parseColor("#D97706"))
        }
    }

    override fun getItemCount(): Int = logs.size

    fun updateList(newList: List<InterceptRecord>) {
        logs = newList
        notifyDataSetChanged()
    }
}
