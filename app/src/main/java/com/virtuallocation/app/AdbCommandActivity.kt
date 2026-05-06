package com.virtuallocation.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

/**
 * 透明无界面 Activity，用于 APP 被杀死后通过 am start 唤醒并执行定位命令。
 *
 * 用法示例：
 *   am start -n com.virtuallocation.app/.AdbCommandActivity --es action favorite --es name "学校"
 *   am start -n com.virtuallocation.app/.AdbCommandActivity --es action start --ef lat 24.4391 --ef lng 118.0832
 *   am start -n com.virtuallocation.app/.AdbCommandActivity --es action stop
 *
 * action 支持: start / favorite / fav / name / stop
 */
class AdbCommandActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra("action")?.lowercase() ?: run {
            toast("⚠️ 缺少 action 参数")
            finish()
            return
        }

        // 先自动授予模拟位置权限（stop 命令不需要）
        if (action != "stop") {
            grantMockLocation()
        }

        try {
            when (action) {
                "start" -> handleStart()
                "favorite", "fav", "name" -> handleFavorite()
                "stop" -> handleStop()
                else -> toast("⚠️ 未知命令: $action")
            }
        } catch (e: Exception) {
            toast("❌ ${e.message}")
        }

        // 延迟关闭，确保 Service 完全启动（Android 12+ 需要在 Activity 存活时启动前台 Service）
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
    }

    /** 通过 Shizuku 自动授予模拟定位权限 */
    private fun grantMockLocation() {
        var msg = "grantMockLocation start"
        try {
            val available = ShizukuShell.isAvailable()
            val granted = ShizukuShell.isGranted()
            msg += " | available=$available granted=$granted"
            if (available && granted) {
                val r = ShizukuShell.exec("appops set $packageName android:mock_location allow")
                msg += " | exec1 exitCode=${r.exitCode} output='${r.output}'"
                if (r.exitCode == 0) {
                    val r2 = ShizukuShell.exec("settings put secure mock_location_app $packageName")
                    msg += " | exec2 exitCode=${r2.exitCode}"
                }
            }
        } catch (e: Exception) {
            msg += " | EXCEPTION: ${e.javaClass.name}: ${e.message}"
        }
        // 写入 /sdcard/Download/ 供调试查看
        try {
            java.io.FileWriter("/sdcard/Download/adb_cmd_debug.txt", true).use { w ->
                w.write("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.CHINA).format(java.util.Date())} $msg\n")
            }
        } catch (_: Exception) {}
    }

    /** 处理 start 命令：直接定位到指定坐标 */
    private fun handleStart() {
        val lat = intent.getDoubleExtra("lat", 0.0)
        val lng = intent.getDoubleExtra("lng", 0.0)
        val latF = intent.getFloatExtra("lat", 0f).toDouble()
        val lngF = intent.getFloatExtra("lng", 0f).toDouble()
        val finalLat = if (lat != 0.0 || lng != 0.0) lat else latF
        val finalLng = if (lat != 0.0 || lng != 0.0) lng else lngF

        if (finalLat == 0.0 && finalLng == 0.0) {
            toast("⚠️ 缺少经纬度参数")
            return
        }

        MockLocationService.start(this, finalLat, finalLng)
        toast("🟢 %.4f, %.4f".format(finalLat, finalLng))
    }

    /** 处理 favorite 命令：从收藏中按标签名定位 */
    private fun handleFavorite() {
        val name = intent.getStringExtra("name") ?: run {
            toast("⚠️ 缺少 name 参数")
            return
        }

        val json = getSharedPreferences("virtual_location_prefs", MODE_PRIVATE)
            .getString("saved_locations", null)
        if (json.isNullOrBlank()) {
            toast("⚠️ 收藏为空")
            return
        }

        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val savedName = o.getString("name")
            if (savedName.equals(name, ignoreCase = true) || savedName.contains(name)) {
                val lat = o.getString("lat").toDoubleOrNull() ?: continue
                val lng = o.getString("lng").toDoubleOrNull() ?: continue
                MockLocationService.startWithName(this, lat, lng, savedName)
                toast("✅ 已切换至：$savedName")
                return
            }
        }
        toast("⚠️ 未找到: $name")
    }

    /** 处理 stop 命令 */
    private fun handleStop() {
        MockLocationService.stop(this)
        toast("🔴 已停止")
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this@AdbCommandActivity, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    companion object {
        /** 检查当前是否有定位权限 */
        fun hasLocationPermission(ctx: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
