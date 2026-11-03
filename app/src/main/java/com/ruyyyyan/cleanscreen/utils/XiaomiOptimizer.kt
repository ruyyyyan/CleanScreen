package com.ruyyyyan.cleanscreen.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

object XiaomiOptimizer {

    fun isXiaomiOrRedmi(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                brand.contains("xiaomi") || brand.contains("redmi") ||
                brand.contains("poco")
    }

    /**
     * 跳转小米 HyperOS (澎湃OS) / MIUI 自启动管理
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val intents = listOf(
            // 小米澎湃OS / MIUI 权限管理中心 - 自启动
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securityscan.MainActivity"
                )
            )
        )

        for (intent in intents) {
            try {
                intent.putExtra("extra_pkgname", context.packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Continue trying next intent
            }
        }

        // Fallback to app details
        PermissionUtils.openAppDetailsSettings(context)
        Toast.makeText(context, "请在权限管理中开启【自启动】与【关联启动】", Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * 跳转小米 HyperOS (澎湃OS) 省电策略 (设置为【无限制】)
     */
    fun openBatterySaverSettings(context: Context): Boolean {
        val intents = listOf(
            // 小米神隐模式 / 后台电量管理
            Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY").apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", "清屏")
            },
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            ).apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", "清屏")
            },
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.PowerHideManageActivity"
                )
            )
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Try next
            }
        }

        // Fallback to standard battery optimization
        PermissionUtils.requestIgnoreBatteryOptimization(context)
        Toast.makeText(context, "请在省电策略中将清屏设为【无限制】", Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * 小米 HyperOS 10秒危险权限等待说明提示
     */
    fun showHyperOSWarningTip(context: Context) {
        Toast.makeText(
            context,
            "小米澎湃OS开启无障碍需等待10秒安全倒计时，请勾选【我已知晓风险】后点击确定",
            Toast.LENGTH_LONG
        ).show()
    }
}
