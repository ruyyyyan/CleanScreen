package com.ruyyyyan.cleanscreen.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.ruyyyyan.cleanscreen.service.CleanScreenAccessibilityService

/**
 * Android 13+ (API 33+) Restricted Settings operational status.
 */
enum class RestrictedSettingsStatus(val label: String) {
    ALLOWED("Allowed (Normal)"),
    RESTRICTED("Restricted by System (Blocked)"),
    NOT_RESTRICTED("Not Applicable (< Android 13)"),
    UNKNOWN("Indeterminate")
}

object PermissionUtils {

    /**
     * Checks if the Accessibility Service is registered as enabled in Settings.Secure.
     */
    fun isAccessibilityServiceConfigured(context: Context): Boolean {
        return try {
            val expectedServiceName = "${context.packageName}/${CleanScreenAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks whether the service is actively registered in AccessibilityManager bound list.
     */
    fun isServiceListedInAms(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) ?: return false
            val targetName = CleanScreenAccessibilityService::class.java.name
            enabledServices.any {
                it.resolveInfo?.serviceInfo?.name == targetName &&
                it.resolveInfo?.serviceInfo?.packageName == context.packageName
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Accurately determines the operational state among the five required categories:
     * - NOT_CONFIGURED
     * - CONFIGURED_NOT_BOUND
     * - RUNNING
     * - CONNECTION_LOST
     * - UNKNOWN
     */
    fun getAccessibilityServiceState(context: Context): AccessibilityServiceState {
        return try {
            val isConfigured = isAccessibilityServiceConfigured(context)
            if (!isConfigured) {
                return AccessibilityServiceState.NOT_CONFIGURED
            }

            val isProcessAliveAndBound = CleanScreenAccessibilityService.isServiceRunning
            val isAmsActive = isServiceListedInAms(context)

            if (isProcessAliveAndBound && isAmsActive) {
                return AccessibilityServiceState.RUNNING
            }

            // If configured in Settings, but the service previously connected and was disconnected
            if (ServiceStateHolder.lastDisconnectedTime > 0 &&
                ServiceStateHolder.lastConnectedTime > 0 &&
                ServiceStateHolder.lastDisconnectedTime >= ServiceStateHolder.lastConnectedTime
            ) {
                return AccessibilityServiceState.CONNECTION_LOST
            }

            // Configured in settings, but never successfully bound into running state
            AccessibilityServiceState.CONFIGURED_NOT_BOUND
        } catch (e: Throwable) {
            AccessibilityServiceState.UNKNOWN
        }
    }

    /**
     * Legacy boolean check for backward compatibility.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return isAccessibilityServiceConfigured(context)
    }

    /**
     * Checks whether the application is affected by Android 13+ (API 33+) Restricted Settings.
     */
    fun checkRestrictedSettingsStatus(context: Context): RestrictedSettingsStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return RestrictedSettingsStatus.NOT_RESTRICTED
        }

        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return RestrictedSettingsStatus.UNKNOWN

            val opStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS
            } else {
                "android:access_restricted_settings"
            }

            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(opStr, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(opStr, Process.myUid(), context.packageName)
            }

            when (mode) {
                AppOpsManager.MODE_ALLOWED -> RestrictedSettingsStatus.ALLOWED
                AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_IGNORED -> RestrictedSettingsStatus.RESTRICTED
                else -> RestrictedSettingsStatus.UNKNOWN
            }
        } catch (e: Throwable) {
            RestrictedSettingsStatus.UNKNOWN
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isOverlayPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
