package com.ruyyyyan.cleanscreen.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

object VivoOptimizer {

    fun isVivoOrIqoo(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
                brand.contains("vivo") || brand.contains("iqoo")
    }

    /**
     * 跳转 vivo / iQOO OriginOS 自启动管理设置界面
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val intents = listOf(
            // vivo 权限管理 - 自启动
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"
                )
            ),
            // iQOO 手机管家
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.MainGuideActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"
                )
            )
        )

        for (intent in intents) {
            try {
                intent.putExtra("packagename", context.packageName)
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
     * 跳转 vivo / iQOO 后台高耗电管理 (允许后台运行，防止OriginOS杀后台)
     */
    fun openBackgroundPowerSettings(context: Context): Boolean {
        val intents = listOf(
            // OriginOS 后台高耗电管理 (vivo abe)
            Intent().setComponent(
                ComponentName(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                )
            ),
            // iQOO 电池优化
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.PowerSavingManagerActivity"
                )
            )
        )

        for (intent in intents) {
            try {
                intent.putExtra("package", context.packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Try next
            }
        }

        // Fallback to standard battery optimization
        PermissionUtils.requestIgnoreBatteryOptimization(context)
        Toast.makeText(context, "请在电池设置中勾选【允许后台高耗电】", Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * 针对 Android 13/14/15 受限设置 (Restricted Settings) 提示引导
     */
    fun showRestrictedSettingsTip(context: Context) {
        Toast.makeText(
            context,
            "若无障碍开关变灰：请前往系统设置->应用->清屏->右上角三点->点击【允许受限设置】",
            Toast.LENGTH_LONG
        ).show()
    }
}
