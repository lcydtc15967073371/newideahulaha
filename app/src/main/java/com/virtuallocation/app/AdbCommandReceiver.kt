package com.virtuallocation.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * ADB 广播接收器（v3 - Shizuku shell 启动版）
 *
 * Android 12+ 后台广播不能直接 startService / startForegroundService，
 * APP 被杀死后 Service 实例也不存在，startActivity 拉起 AdbCommandActivity 的方式
 * 在部分系统上仍会因为 Activity 存活时间不足被拦截。
 *
 * 最可靠的方案：通过 Shizuku 执行 shell 命令 am start-foreground-service，
 * shell 环境不受后台启动限制。
 *
 * 如果 Service 已在运行，则直接 startService 转发。
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.virtuallocation.app.ADB_COMMAND") return

        val running = MockLocationService.running

        if (running) {
            // Service 已在运行，直接转发
            val si = Intent(context, MockLocationService::class.java).apply {
                action = "ADB"
                intent.extras?.let { putExtras(it) }
            }
            context.startService(si)
        } else {
            // Service 未运行，通过 Shizuku 执行 shell 命令启动
            // 这样可以绕过 Android 12+ 的后台启动限制
            val cmd = buildStartForegroundServiceCommand(intent)
            try {
                if (ShizukuShell.isAvailable() && ShizukuShell.isGranted()) {
                    ShizukuShell.exec(cmd)
                }
            } catch (_: Exception) {}
        }
    }

    /** 构建 am start-foreground-service 命令，保留所有 extras */
    private fun buildStartForegroundServiceCommand(intent: Intent): String {
        val sb = StringBuilder("am start-foreground-service -n com.virtuallocation.app/.MockLocationService")
        // -a ADB 设置 intent action，让 onStartCommand 进入 "ADB" 分支
        sb.append(" -a ADB")

        val extras = intent.extras ?: return sb.toString()
        for (key in extras.keySet()) {
            val value = extras.get(key) ?: continue
            when (value) {
                is String -> sb.append(" --es $key \"${value.replace("\"", "\\\"")}\"")
                is Int -> sb.append(" --ei $key $value")
                is Double -> sb.append(" --ef $key $value")
                is Float -> sb.append(" --ef $key $value")
                is Boolean -> sb.append(" --ez $key $value")
                is Long -> sb.append(" --el $key $value")
            }
        }
        return sb.toString()
    }
}
