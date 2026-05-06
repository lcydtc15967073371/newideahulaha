package com.virtuallocation.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 集成工具类
 *
 * 核心功-能：通过 Java 反射调用 Shizuku.newProcess() 执行 shell 命令。
 *
 * Shizuku v13 将 newProcess() 标记为 private，但底层实现仍在运行。
 * 将反射代码放在 ShizukuShell.java 中，绕过 Kotlin 编译器的可见性检查。
 */
object ShizukuHelper {

    private const val PROFILE_NAME = ".shizu_profile"

    // ==================== Shizuku 状态检查 ====================

    fun isShizukuAvailable(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.getVersion() > 0
        } catch (_: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) {}
    }

    // ==================== 核心：通过 Java 反射执行 appops 命令 ====================

    /**
     * 授予模拟定位权限。
     * 做了两件事：
     * 1. settings put secure mock_location_app → 开发者选项里选中本 app
     * 2. appops set mock_location allow → 开启 appops 权限
     */
    fun grantMockLocationViaShizuku(packageName: String): Pair<Boolean, String> {
        // 1. settings put 设置模拟位置应用
        val r1 = ShizukuShell.exec("settings put secure mock_location_app $packageName")
        // 2. appops set 开启权限
        val r2 = ShizukuShell.exec("appops set $packageName android:mock_location allow")
        // 验证 settings 是否生效
        val r3 = ShizukuShell.exec("settings get secure mock_location_app")
        val settingsValue = r3.output.trim()
        // 验证 appops
        val r4 = ShizukuShell.exec("appops get $packageName android:mock_location")
        val checkResult = r4.output

        val appopsOk = checkResult.contains("allow", ignoreCase = true)
        val settingsOk = settingsValue.contains("virtuallocation", ignoreCase = true)

        return when {
            appopsOk && settingsOk -> Pair(true, "✅ 模拟定位权限已开启！")
            appopsOk -> Pair(true, "✅ appops 已通过\nsettings 未更新: $settingsValue")
            else -> Pair(false, "❌ 设置失败\nappops: ${r4.output}\nsettings: ${r3.output}")
        }
    }

    /** 授予并验证 */
    fun grantAndVerify(packageName: String): Pair<Boolean, String> {
        val (ok, msg) = grantMockLocationViaShizuku(packageName)
        return if (ok) {
            Pair(true, "$msg")
        } else {
            Pair(false, msg)
        }
    }

    // ==================== 别名功能（保留） ====================

    private fun getProfilePath(): String = "/storage/emulated/0/$PROFILE_NAME"

    private fun buildAliasLine(name: String, lat: Double, lng: Double): String {
        val escapedName = name.replace("'", "'\\''")
        val cmd = "am broadcast -a com.virtuallocation.app.ADB_COMMAND --es action start --ef lat $lat --ef lng $lng"
        return "alias '$escapedName'='$cmd'"
    }

    fun setSingleAlias(context: Context, loc: SavedLocation): String {
        val lat = loc.lat.toDoubleOrNull() ?: return "❌ 坐标无效"
        val lng = loc.lng.toDoubleOrNull() ?: return "❌ 坐标无效"
        val file = File(getProfilePath())
        val aliasLine = buildAliasLine(loc.name, lat, lng)
        return try {
            val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
            val escapedName = loc.name.replace("'", "'\\''")
            lines.removeAll { it.matches(Regex("^alias '$escapedName'=.*")) }
            lines.add(aliasLine)
            file.writeText(lines.joinToString("\n") + "\n")
            val sourceCmd = "source ${getProfilePath()}"
            copyToClipboard(context, sourceCmd)
            "✅ 别名'${loc.name}'已设置！\n已复制命令到剪贴板"
        } catch (e: Exception) {
            "❌ 写入失败: ${e.message}"
        }
    }

    fun setAllAliases(context: Context, locations: List<SavedLocation>): String {
        val file = File(getProfilePath())
        val allLines = mutableListOf<String>()
        return try {
            if (file.exists()) {
                file.readLines().forEach { line ->
                    if (!line.trimStart().startsWith("alias ")) allLines.add(line)
                }
            }
            var count = 0
            for (loc in locations) {
                val lat = loc.lat.toDoubleOrNull() ?: continue
                val lng = loc.lng.toDoubleOrNull() ?: continue
                allLines.add(buildAliasLine(loc.name, lat, lng)); count++
            }
            file.writeText(allLines.joinToString("\n") + "\n")
            val sourceCmd = "source ${getProfilePath()}"
            copyToClipboard(context, sourceCmd)
            "✅ 已设置 $count 个别名\n已复制 source 命令到剪贴板！"
        } catch (e: Exception) {
            "❌ 写入失败: ${e.message}"
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("shizu_source", text))
        } catch (_: Exception) {}
    }

    fun getSourceCommand(): String = "source ${getProfilePath()}"
}

