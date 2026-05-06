package com.virtuallocation.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * 快速设置磁贴 - 点击相当于发一次 am start 广播
 * 使用活跃模式（ACTIVE_TILE），后台可更新状态
 */
class MockQSTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        // 点击磁贴 → 弹出收藏选择对话框
        try {
            val json = applicationContext.getSharedPreferences("virtual_location_prefs", MODE_PRIVATE)
                .getString("saved_locations", null)

            if (json.isNullOrBlank()) {
                // 没有收藏，直接发默认命令
                val cmd = "am start -n $packageName/.AdbCommandActivity --es action favorite --es name \"泰捷\""
                Log.w("VL_Tile", "no favorites, fallback: $cmd")
                ShizukuShell.exec(cmd)
                updateTileState()
                return
            }

            val arr = org.json.JSONArray(json)
            if (arr.length() == 0) {
                val cmd = "am start -n $packageName/.AdbCommandActivity --es action favorite --es name \"泰捷\""
                ShizukuShell.exec(cmd)
                updateTileState()
                return
            }

            // 构造收藏列表：名称数组
            val names = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                names.add(arr.getJSONObject(i).getString("name"))
            }

            // 弹出对话框选择收藏
            showDialog(android.app.AlertDialog.Builder(this)
                .setTitle("选择定位地点")
                .setItems(names.toTypedArray()) { _, which ->
                    val selectedName = names[which]
                    Log.w("VL_Tile", "selected favorite: $selectedName")
                    val cmd = "am start -n $packageName/.AdbCommandActivity --es action favorite --es name \"$selectedName\""
                    try {
                        ShizukuShell.exec(cmd)
                    } catch (e: Exception) {
                        Log.e("VL_Tile", "exec error: ${e.message}")
                    }
                    updateTileState()
                }
                .setNegativeButton("取消", null)
                .create())
        } catch (e: Exception) {
            Log.e("VL_Tile", "onClick error: ${e.message}")
            // 异常时回退到默认泰捷
            try {
                ShizukuShell.exec("am start -n $packageName/.AdbCommandActivity --es action favorite --es name \"泰捷\"")
            } catch (_: Exception) {}
            updateTileState()
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    /** 根据模拟定位是否在运行更新磁贴状态 */
    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.label = "虚拟定位"
        if (MockLocationService.running) {
            tile.state = Tile.STATE_ACTIVE
            tile.contentDescription = "虚拟定位 - 运行中"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.contentDescription = "虚拟定位 - 已停止"
        }
        tile.updateTile()
    }
}
