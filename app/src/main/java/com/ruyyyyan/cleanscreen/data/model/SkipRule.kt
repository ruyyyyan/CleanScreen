package com.ruyyyyan.cleanscreen.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Advanced skip rule model inspired by GKD and LiTiaotiao.
 * Supports multi-dimensional matching: package, activity, viewId, text regex, and screen position.
 */
data class SkipRule(
    val id: String, // Unique rule identifier, e.g. "builtin:com.sina.weibo:splash"
    val packageName: String,
    val appName: String,
    val description: String = "开屏广告跳过",
    val activityIds: List<String> = emptyList(), // Specific splash activities (empty means any activity in 3.5s window)
    val targetId: String = "", // Exact or suffix viewIdResourceName
    val targetTextRegex: String = "", // Regex for node text
    val targetDescRegex: String = "", // Regex for node contentDescription
    val positionZone: PositionZone = PositionZone.EDGE, // TOP_ZONE, BOTTOM_ZONE, EDGE, or ANY
    val clickParentIfPossible: Boolean = false, // If true, click parent container if node is small
    val isEnabled: Boolean = true,
    val isBuiltin: Boolean = true
) {
    enum class PositionZone {
        ANY,
        TOP_ZONE,     // Top 25% of screen
        BOTTOM_ZONE,  // Bottom 15% of screen
        EDGE          // Top 25% or bottom 15%
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("packageName", packageName)
            put("appName", appName)
            put("description", description)
            put("activityIds", JSONArray(activityIds))
            put("targetId", targetId)
            put("targetTextRegex", targetTextRegex)
            put("targetDescRegex", targetDescRegex)
            put("positionZone", positionZone.name)
            put("clickParentIfPossible", clickParentIfPossible)
            put("isEnabled", isEnabled)
            put("isBuiltin", isBuiltin)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SkipRule {
            val activities = mutableListOf<String>()
            val actArray = json.optJSONArray("activityIds")
            if (actArray != null) {
                for (i in 0 until actArray.length()) {
                    activities.add(actArray.optString(i))
                }
            }

            val zoneName = json.optString("positionZone", PositionZone.EDGE.name)
            val zone = try {
                PositionZone.valueOf(zoneName)
            } catch (e: Exception) {
                PositionZone.EDGE
            }

            return SkipRule(
                id = json.optString("id", System.currentTimeMillis().toString()),
                packageName = json.optString("packageName", ""),
                appName = json.optString("appName", "自定义规则"),
                description = json.optString("description", "开屏广告跳过"),
                activityIds = activities,
                targetId = json.optString("targetId", ""),
                targetTextRegex = json.optString("targetTextRegex", ""),
                targetDescRegex = json.optString("targetDescRegex", ""),
                positionZone = zone,
                clickParentIfPossible = json.optBoolean("clickParentIfPossible", false),
                isEnabled = json.optBoolean("isEnabled", true),
                isBuiltin = json.optBoolean("isBuiltin", false)
            )
        }
    }
}
