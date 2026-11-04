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
 * Intelligent Rule Matching Engine inspired by GKD, LiTiaotiao, SKIP, and Android-Touch-Helper.
 * Features:
 * - O(1) package-indexed rule resolution
 * - Activity-level isolation to eliminate in-app false positives
 * - Position bounding-box verification
 * - Relative coordinate tap fallback
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

    sealed class MatchAction(val rule: SkipRule) {
        class NodeMatch(val node: AccessibilityNodeInfo, rule: SkipRule) : MatchAction(rule)
        class CoordinateMatch(val xRatio: Float, val yRatio: Float, rule: SkipRule) : MatchAction(rule)
    }

    data class CandidateNode(
        val node: AccessibilityNodeInfo,
        val text: String,
        val viewId: String,
        val bounds: Rect,
        val isLikelySkip: Boolean
    )

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
     * Finds matching action (either node-based or coordinate-based) for the foreground app.
     */
    fun findMatchingAction(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        currentActivity: String,
        screenHeight: Int
    ): MatchAction? {
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

            // 1. Relative coordinate matching (Android-Touch-Helper style)
            if (rule.hasCoordinateTap()) {
                return MatchAction.CoordinateMatch(rule.coordinateX, rule.coordinateY, rule)
            }

            // 2. Node search according to rule criteria
            val matchedNode = findNodeMatchingRule(rootNode, rule, screenHeight)
            if (matchedNode != null) {
                return MatchAction.NodeMatch(matchedNode, rule)
            }
        }

        return null
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
        val action = findMatchingAction(rootNode, packageName, currentActivity, screenHeight)
        return if (action is MatchAction.NodeMatch) {
            Pair(action.node, action.rule)
        } else null
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
     * Extracts candidate skip nodes for the Floating Node Inspector.
     */
    fun extractCandidateNodes(rootNode: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): List<CandidateNode> {
        val list = mutableListOf<CandidateNode>()
        collectCandidateNodes(rootNode, screenWidth, screenHeight, list, 0)
        return list.sortedByDescending { it.isLikelySkip }
    }

    private fun collectCandidateNodes(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        outList: MutableList<CandidateNode>,
        depth: Int
    ) {
        if (depth > 12) return

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val hasContent = text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty()
        if (hasContent && rect.width() > 0 && rect.height() > 0) {
            val content = "$text $desc"
            val isLikely = content.contains("跳过") ||
                    content.contains("关闭") ||
                    content.contains("Skip", ignoreCase = true) ||
                    viewId.contains("skip", ignoreCase = true) ||
                    viewId.contains("close", ignoreCase = true) ||
                    (rect.top <= screenHeight * 0.25 && (node.isClickable || viewId.isNotEmpty()))

            outList.add(
                CandidateNode(
                    node = node,
                    text = if (text.isNotEmpty()) text else desc,
                    viewId = viewId,
                    bounds = rect,
                    isLikelySkip = isLikely
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCandidateNodes(child, screenWidth, screenHeight, outList, depth + 1)
        }
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
     * Imports simple line-based text rules (LiTiaotiao & Android-Touch-Helper compatible format):
     * Supports both:
     * 1) "com.example.app, btn_skip, ^跳过$, 应用名"
     * 2) "com.example.app, 0.88, 0.06, 应用名" (coordinate tap)
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
                    val isCoordX = parts.size > 1 && parts[1].toFloatOrNull() != null && parts[1].toFloat() in 0f..1f
                    val isCoordY = parts.size > 2 && parts[2].toFloatOrNull() != null && parts[2].toFloat() in 0f..1f

                    val rule = if (isCoordX && isCoordY) {
                        val coordX = parts[1].toFloat()
                        val coordY = parts[2].toFloat()
                        val appName = if (parts.size > 3) parts[3] else pkg
                        SkipRule(
                            id = "custom:$pkg:coord_${System.currentTimeMillis()}_$count",
                            packageName = pkg,
                            appName = appName,
                            coordinateX = coordX,
                            coordinateY = coordY,
                            isBuiltin = false
                        )
                    } else {
                        val targetId = if (parts.size > 1) parts[1] else ""
                        val targetText = if (parts.size > 2) parts[2] else "^跳过$"
                        val appName = if (parts.size > 3) parts[3] else pkg
                        SkipRule(
                            id = "custom:$pkg:${System.currentTimeMillis()}_$count",
                            packageName = pkg,
                            appName = appName,
                            targetId = targetId,
                            targetTextRegex = targetText,
                            isBuiltin = false
                        )
                    }

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
