package com.ruyyyyan.cleanscreen.data.rules

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ruyyyyan.cleanscreen.data.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Intelligent Rule Matching Engine inspired by GKD and LiTiaotiao.
 * Features:
 * - O(1) package-indexed rule resolution
 * - Activity-level isolation to eliminate in-app false positives
 * - Position bounding-box verification
 * - Seamless JSON / plain-text rule import & export
 */
class RuleEngine private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cached rules mapped by packageName for sub-millisecond lookups
    private val rulesByPackage = ConcurrentHashMap<String, MutableList<SkipRule>>()

    // Compiled regex cache to avoid runtime recompilation
    private val regexCache = ConcurrentHashMap<String, Pattern>()

    // In-memory set of user custom rules
    private val customRules = mutableListOf<SkipRule>()

    // Overridden enabled/disabled states: ruleId -> isEnabled
    private val ruleStates = ConcurrentHashMap<String, Boolean>()

    companion object {
        private const val TAG = "RuleEngine"
        private const val PREFS_NAME = "cleanscreen_rule_engine"
        private const val KEY_CUSTOM_RULES_JSON = "custom_rules_json"
        private const val KEY_DISABLED_RULE_IDS = "disabled_rule_ids"

        @Volatile
        private var instance: RuleEngine? = null

        fun getInstance(context: Context): RuleEngine {
            return instance ?: synchronized(this) {
                instance ?: RuleEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        loadRules()
    }

    /**
     * Initializes and indexes both built-in and user-custom rules.
     */
    @Synchronized
    fun loadRules() {
        rulesByPackage.clear()
        customRules.clear()
        ruleStates.clear()

        // 1. Load disabled rule IDs
        val disabledSet = prefs.getStringSet(KEY_DISABLED_RULE_IDS, emptySet()) ?: emptySet()
        for (id in disabledSet) {
            ruleStates[id] = false
        }

        // 2. Load built-in rules
        val builtins = BuiltinRules.getDefaultRules()
        for (rule in builtins) {
            val isEnabled = ruleStates[rule.id] ?: rule.isEnabled
            val finalRule = rule.copy(isEnabled = isEnabled)
            addRuleToIndex(finalRule)
        }

        // 3. Load custom rules from SharedPreferences
        val customJsonStr = prefs.getString(KEY_CUSTOM_RULES_JSON, null)
        if (!customJsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(customJsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rule = SkipRule.fromJson(obj)
                    val isEnabled = ruleStates[rule.id] ?: rule.isEnabled
                    val finalRule = rule.copy(isEnabled = isEnabled, isBuiltin = false)
                    customRules.add(finalRule)
                    addRuleToIndex(finalRule)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to parse custom rules JSON", e)
            }
        }

        Log.i(TAG, "RuleEngine initialized with ${getAllRules().size} rules across ${rulesByPackage.size} packages.")
    }

    private fun addRuleToIndex(rule: SkipRule) {
        val list = rulesByPackage.getOrPut(rule.packageName) { mutableListOf() }
        list.add(rule)
    }

    /**
     * Searches for a matching skip button node given the root node, current package, and foreground activity.
     */
    fun findMatchingNode(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        currentActivity: String,
        screenHeight: Int
    ): Pair<AccessibilityNodeInfo, SkipRule>? {
        val candidates = rulesByPackage[packageName] ?: return null

        for (rule in candidates) {
            if (!rule.isEnabled) continue

            // Activity-level filtering: if rule specifies activityIds, currentActivity must match
            if (rule.activityIds.isNotEmpty()) {
                val matchedActivity = rule.activityIds.any { act ->
                    currentActivity.endsWith(act) || currentActivity.equals(act, ignoreCase = true)
                }
                if (!matchedActivity) {
                    continue
                }
            }

            // Node search according to rule criteria
            val matchedNode = findNodeMatchingRule(rootNode, rule, screenHeight)
            if (matchedNode != null) {
                return Pair(matchedNode, rule)
            }
        }

        return null
    }

    private fun findNodeMatchingRule(
        rootNode: AccessibilityNodeInfo,
        rule: SkipRule,
        screenHeight: Int,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > 12) return null

        // 1. Fast path: check targetId if defined
        if (rule.targetId.isNotEmpty()) {
            val viewId = rootNode.viewIdResourceName?.lowercase() ?: ""
            if (viewId.endsWith(rule.targetId.lowercase()) || viewId.contains(rule.targetId.lowercase())) {
                if (validatePosition(rootNode, rule.positionZone, screenHeight)) {
                    return rootNode
                }
            }
        }

        // 2. Text / Description matching
        val text = rootNode.text?.toString()?.trim() ?: ""
        val desc = rootNode.contentDescription?.toString()?.trim() ?: ""

        val textMatches = if (rule.targetTextRegex.isNotEmpty()) {
            matchRegex(rule.targetTextRegex, text)
        } else false

        val descMatches = if (rule.targetDescRegex.isNotEmpty()) {
            matchRegex(rule.targetDescRegex, desc)
        } else false

        if (textMatches || descMatches) {
            if (validatePosition(rootNode, rule.positionZone, screenHeight)) {
                return rootNode
            }
        }

        // DFS through children
        val count = rootNode.childCount
        for (i in 0 until count) {
            val child = rootNode.getChild(i) ?: continue
            val found = findNodeMatchingRule(child, rule, screenHeight, depth + 1)
            if (found != null) {
                return found
            }
        }

        return null
    }

    private fun validatePosition(node: AccessibilityNodeInfo, zone: SkipRule.PositionZone, screenHeight: Int): Boolean {
        if (zone == SkipRule.PositionZone.ANY) return true

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val isTop = rect.top <= screenHeight * 0.25
        val isBottom = rect.bottom >= screenHeight * 0.85

        return when (zone) {
            SkipRule.PositionZone.TOP_ZONE -> isTop
            SkipRule.PositionZone.BOTTOM_ZONE -> isBottom
            SkipRule.PositionZone.EDGE -> isTop || isBottom
            else -> true
        }
    }

    private fun matchRegex(regexStr: String, content: String): Boolean {
        if (content.isEmpty()) return false
        val pattern = regexCache.getOrPut(regexStr) {
            Pattern.compile(regexStr, Pattern.CASE_INSENSITIVE)
        }
        return pattern.matcher(content).matches()
    }

    /**
     * Retrieves all configured rules (built-in + custom).
     */
    fun getAllRules(): List<SkipRule> {
        val result = mutableListOf<SkipRule>()
        for (list in rulesByPackage.values) {
            result.addAll(list)
        }
        return result.sortedBy { it.appName }
    }

    /**
     * Toggles an individual rule's enabled state.
     */
    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        ruleStates[ruleId] = enabled
        saveDisabledStates()

        for (list in rulesByPackage.values) {
            for (i in list.indices) {
                if (list[i].id == ruleId) {
                    list[i] = list[i].copy(isEnabled = enabled)
                }
            }
        }
    }

    private fun saveDisabledStates() {
        val disabledSet = ruleStates.filterValues { !it }.keys
        prefs.edit().putStringSet(KEY_DISABLED_RULE_IDS, disabledSet).apply()
    }

    /**
     * Adds or updates a user-defined custom rule.
     */
    fun addCustomRule(rule: SkipRule) {
        val finalRule = rule.copy(isBuiltin = false)
        customRules.removeAll { it.id == finalRule.id }
        customRules.add(finalRule)
        saveCustomRules()
        loadRules()
    }

    /**
     * Deletes a user custom rule.
     */
    fun deleteCustomRule(ruleId: String) {
        customRules.removeAll { it.id == ruleId }
        saveCustomRules()
        loadRules()
    }

    private fun saveCustomRules() {
        val array = JSONArray()
        for (r in customRules) {
            array.put(r.toJson())
        }
        prefs.edit().putString(KEY_CUSTOM_RULES_JSON, array.toString()).apply()
    }

    /**
     * Imports rules from a JSON array string.
     * Returns the count of successfully imported rules.
     */
    fun importRulesFromJson(jsonStr: String): Int {
        var count = 0
        try {
            val array = JSONArray(jsonStr.trim())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rule = SkipRule.fromJson(obj)
                if (rule.packageName.isNotEmpty()) {
                    customRules.removeAll { it.id == rule.id }
                    customRules.add(rule.copy(isBuiltin = false))
                    count++
                }
            }
            if (count > 0) {
                saveCustomRules()
                loadRules()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Import error", e)
        }
        return count
    }

    /**
     * Imports simple line-based text rules (LiTiaotiao compatible format):
     * e.g. "com.example.app, btn_skip, ^跳过$"
     */
    fun importRulesFromText(text: String): Int {
        var count = 0
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue

            val parts = trimmed.split(",").map { it.trim() }
            if (parts.isNotEmpty()) {
                val pkg = parts[0]
                if (pkg.contains(".")) {
                    val targetId = if (parts.size > 1) parts[1] else ""
                    val targetText = if (parts.size > 2) parts[2] else "^跳过$"
                    val appName = if (parts.size > 3) parts[3] else pkg

                    val rule = SkipRule(
                        id = "custom:$pkg:${System.currentTimeMillis()}_$count",
                        packageName = pkg,
                        appName = appName,
                        targetId = targetId,
                        targetTextRegex = targetText,
                        isBuiltin = false
                    )
                    customRules.add(rule)
                    count++
                }
            }
        }
        if (count > 0) {
            saveCustomRules()
            loadRules()
        }
        return count
    }

    /**
     * Exports all custom rules as a formatted JSON string.
     */
    fun exportCustomRulesJson(): String {
        val array = JSONArray()
        for (r in customRules) {
            array.put(r.toJson())
        }
        return array.toString(2)
    }
}
