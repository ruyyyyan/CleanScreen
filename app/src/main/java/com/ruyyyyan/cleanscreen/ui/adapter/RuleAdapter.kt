package com.ruyyyyan.cleanscreen.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ruyyyyan.cleanscreen.data.model.SkipRule
import com.ruyyyyan.cleanscreen.databinding.ItemSkipRuleBinding

class RuleAdapter(
    private var allRules: List<SkipRule>,
    private val onToggle: (SkipRule, Boolean) -> Unit,
    private val onDelete: (SkipRule) -> Unit
) : RecyclerView.Adapter<RuleAdapter.RuleViewHolder>() {

    private var filteredRules = allRules.toList()

    fun updateRules(newRules: List<SkipRule>) {
        allRules = newRules
        filteredRules = newRules
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        filteredRules = if (q.isEmpty()) {
            allRules
        } else {
            allRules.filter {
                it.appName.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q) ||
                it.targetId.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemSkipRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(filteredRules[position])
    }

    override fun getItemCount(): Int = filteredRules.size

    inner class RuleViewHolder(private val binding: ItemSkipRuleBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: SkipRule) {
            val initial = if (rule.appName.isNotEmpty()) rule.appName.take(1) else "规"
            binding.tvAppInitial.text = initial
            binding.tvRuleAppName.text = rule.appName
            binding.tvRulePackage.text = rule.packageName

            if (rule.isBuiltin) {
                binding.tvRuleTypeBadge.text = "内置"
                binding.tvRuleTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F3FF"))
                binding.tvRuleTypeBadge.setTextColor(Color.parseColor("#0E78F9"))
                binding.btnDeleteRule.visibility = View.GONE
            } else {
                binding.tvRuleTypeBadge.text = "自定义"
                binding.tvRuleTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8FFEA"))
                binding.tvRuleTypeBadge.setTextColor(Color.parseColor("#059669"))
                binding.btnDeleteRule.visibility = View.VISIBLE
                binding.btnDeleteRule.setOnClickListener {
                    onDelete(rule)
                }
            }

            val criteria = buildString {
                if (rule.activityIds.isNotEmpty()) {
                    val actName = rule.activityIds.first().substringAfterLast(".")
                    append("[$actName] ")
                }
                if (rule.targetId.isNotEmpty()) {
                    val idPart = rule.targetId.substringAfterLast(":")
                    append("ID:$idPart ")
                }
                if (rule.targetTextRegex.isNotEmpty()) {
                    append("Text:${rule.targetTextRegex} ")
                }
                when (rule.positionZone) {
                    SkipRule.PositionZone.TOP_ZONE -> append("· 顶栏过滤")
                    SkipRule.PositionZone.BOTTOM_ZONE -> append("· 底栏过滤")
                    SkipRule.PositionZone.EDGE -> append("· 边缘时空锁定")
                    else -> {}
                }
            }
            binding.tvRuleCriteria.text = criteria.trim().ifEmpty { rule.description }

            binding.switchRule.setOnCheckedChangeListener(null)
            binding.switchRule.isChecked = rule.isEnabled
            binding.switchRule.setOnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            }
        }
    }
}
