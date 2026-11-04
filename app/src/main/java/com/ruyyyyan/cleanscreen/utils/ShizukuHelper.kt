package com.ruyyyyan.cleanscreen.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku privileged system integration helper.
 * Inspired by AutoSkip and GKD architectures.
 * Provides system-level ADB capabilities without root, enabling seamless bypass of
 * OEM Restricted Settings (OriginOS 6 / Android 16) and direct accessibility service activation.
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE_SHIZUKU_PERMISSION = 2001

    data class CommandResult(val exitCode: Int, val output: String, val error: String)

    /**
     * Checks if Shizuku service is running and binder is reachable.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks if CleanScreen has been granted Shizuku authorization.
     */
    fun isPermissionGranted(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Requests Shizuku authorization from user.
     */
    fun requestPermission(requestCode: Int = REQUEST_CODE_SHIZUKU_PERMISSION) {
        if (isShizukuAvailable() && !isPermissionGranted()) {
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to request Shizuku permission", e)
            }
        }
    }

    /**
     * Executes a shell command using Shizuku privileged binder.
     */
    fun executeCommand(command: String): CommandResult {
        if (!isPermissionGranted()) {
            return CommandResult(-1, "", "Shizuku not granted")
        }

        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            CommandResult(exitCode, stdout.trim(), stderr.trim())
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing shell via Shizuku: $command", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }

    /**
     * Automates full authorization and keepalive via Shizuku:
     * 1. Unblocks Android 16 / OriginOS 6 Restricted Settings
     * 2. Enables Accessibility and binds CleanScreenAccessibilityService
     * 3. Adds CleanScreen to Doze battery whitelist
     */
    fun grantPrivilegedAccess(context: Context): Pair<Boolean, String> {
        if (!isPermissionGranted()) {
            return Pair(false, "Shizuku 未授权，无法执行高权限配置")
        }

        val pkgName = context.packageName
        val serviceName = "$pkgName/com.ruyyyyan.cleanscreen.service.CleanScreenAccessibilityService"

        val commands = listOf(
            // 1. Allow restricted settings for Android 16 (OriginOS 6)
            "appops set $pkgName android:access_restricted_settings allow",
            "appops set $pkgName ACCESS_RESTRICTED_SETTINGS allow",

            // 2. Enable accessibility globally
            "settings put secure accessibility_enabled 1",

            // 3. Register CleanScreen in enabled_accessibility_services
            "current_services=$(settings get secure enabled_accessibility_services); " +
            "if [[ \"\$current_services\" != *\"$serviceName\"* ]]; then " +
            "  if [ \"\$current_services\" = \"null\" ] || [ -z \"\$current_services\" ]; then " +
            "    settings put secure enabled_accessibility_services \"$serviceName\"; " +
            "  else " +
            "    settings put secure enabled_accessibility_services \"\$current_services:$serviceName\"; " +
            "  fi " +
            "fi",

            // 4. White-list battery optimization
            "dumpsys deviceidle whitelist +$pkgName"
        )

        val logBuilder = StringBuilder()
        var allSuccess = true

        for (cmd in commands) {
            val res = executeCommand(cmd)
            if (res.exitCode != 0 && res.error.isNotEmpty()) {
                logBuilder.append("失败: $cmd -> ${res.error}\n")
                allSuccess = false
            } else {
                logBuilder.append("成功: ${cmd.take(45)}...\n")
            }
        }

        val message = if (allSuccess) {
            "已通过 Shizuku 自动解除 OriginOS 6 限制并激活无障碍！"
        } else {
            "部分命令执行反馈：\n$logBuilder"
        }

        return Pair(allSuccess, message)
    }
}
