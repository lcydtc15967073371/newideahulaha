package com.virtuallocation.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.virtuallocation.app.ui.theme.VirtualLocationTheme
import rikka.shizuku.Shizuku
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class SavedLocation(val name: String, val lat: String, val lng: String)

class MainActivity : ComponentActivity() {

    // ----- v3.0 新增：悬浮窗引用 -----
    var floatingView: FloatingDirectionView? = null

    // Shizuku 权限请求结果监听器：授权后静默执行 appops set
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            ShizukuHelper.grantMockLocationViaShizuku(packageName)
        }
    }

    fun toggleFloatingView() {
        if (floatingView != null) {
            floatingView?.dismiss()
            floatingView = null
            Toast.makeText(this, "方向浮窗已关闭", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        floatingView = FloatingDirectionView(this).also { it.show() }
        Toast.makeText(this, "方向浮窗已开启 🧭", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Toast.makeText(this, "请在设置中开启悬浮窗权限后重试", Toast.LENGTH_LONG).show()
    }

    /** 复制 ADB 命令到剪贴板（自动授权已内建，无需手动授权） */
    fun copyAdbCmd(loc: SavedLocation) {
        val cmd = "am start -n com.virtuallocation.app/.AdbCommandActivity --es action favorite --es name \"${loc.name}\""
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("adb_cmd", cmd))
            Toast.makeText(this, "📋 已复制:\n$cmd", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent().apply {
                    action = "moe.shizuku.manager.terminal"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 14+ 需要先有定位权限才能启动 foregroundServiceType=location 的 FGS
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                // 用户授权了，启动空闲保活
                if (!MockLocationService.running) {
                    MockLocationService.startIdle(this)
                    Toast.makeText(this, "💤 虚拟定位待机中", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注册 Shizuku 权限结果监听
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        // APP启动时自动处理 Shizuku 授权和权限授予（照抄 PermOpener）
        if (ShizukuHelper.isShizukuAvailable()) {
            if (ShizukuHelper.hasShizukuPermission()) {
                // 已有权限，静默执行：
                // 1. pm grant 授予运行时危险权限（定位+通知）
                ShizukuShell.exec("pm grant $packageName android.permission.ACCESS_FINE_LOCATION")
                ShizukuShell.exec("pm grant $packageName android.permission.ACCESS_COARSE_LOCATION")
                ShizukuShell.exec("pm grant $packageName android.permission.ACCESS_BACKGROUND_LOCATION")
                if (Build.VERSION.SDK_INT >= 33) {
                    ShizukuShell.exec("pm grant $packageName android.permission.POST_NOTIFICATIONS")
                }
                // 2. appops set 静默授权（mock_location + 通知 + 悬浮窗）
                ShizukuHelper.grantMockLocationViaShizuku(packageName)
                if (Build.VERSION.SDK_INT >= 33) {
                    ShizukuShell.exec("appops set $packageName android:post_notifications allow")
                }
                // 悬浮窗权限（SYSTEM_ALERT_WINDOW 不能 pm grant，只能用 appops set）
                ShizukuShell.exec("appops set $packageName android:system_alert_window allow")
            } else {
                // 没权限，自动弹出授权请求
                ShizukuHelper.requestShizukuPermission(1001)
            }
        }

        // Android 14+ 必须先有定位权限才能启动 foregroundServiceType=location 的 FGS
        if (Build.VERSION.SDK_INT >= 34) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                // 已有权限，直接启动空闲保活
                if (!MockLocationService.running) {
                    MockLocationService.startIdle(this)
                }
            } else {
                // 没权限，先请求（弹系统对话框）
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
        } else {
            // Android 13 及以下，直接启动
            if (!MockLocationService.running) {
                MockLocationService.startIdle(this)
            }
        }

        setContent { VirtualLocationTheme { VirtualLocationApp(activity = this@MainActivity) } }
    }

    override fun onDestroy() {
        floatingView?.dismiss()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualLocationApp(activity: MainActivity? = null) {
    val context = LocalContext.current
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var isMocking by remember { mutableStateOf(MockLocationService.running) }
    var savedLocations by remember { mutableStateOf(loadSavedLocations(context)) }

    var svcRunning by remember { mutableStateOf(MockLocationService.running) }
    var svcLat by remember { mutableStateOf(MockLocationService.curLat) }
    var svcLng by remember { mutableStateOf(MockLocationService.curLng) }
    var svcName by remember { mutableStateOf(MockLocationService.curName) }

    LaunchedEffect(Unit) {
        while (true) {
            svcRunning = MockLocationService.running
            svcLat = MockLocationService.curLat
            svcLng = MockLocationService.curLng
            svcName = MockLocationService.curName
            kotlinx.coroutines.delay(1000)
        }
    }

    val statusText = if (svcRunning) {
        val tag = if (svcName.isNotBlank()) " [$svcName]" else ""
        "运行中$tag\n${"%.6f".format(svcLat)}, ${"%.6f".format(svcLng)}"
    } else {
        "已停止"
    }

    var isFloatingVisible by remember { mutableStateOf(activity?.floatingView != null) }

    var mockLocationAppSelected by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        mockLocationAppSelected = MockLocationService.isMockLocationApp(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
if (granted.values.all { it }) Toast.makeText(context, "权限已获取", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "定位权限被拒绝", Toast.LENGTH_LONG).show()
    }

    fun saveLocation(name: String, lat: String, lng: String) {
        val newList = savedLocations + SavedLocation(name, lat, lng)
        savedLocations = newList; saveLocationsToPrefs(context, newList)
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    FloatingDirectionView.ACTION_SAVE_FAVORITE -> {
                        val lat = intent.getDoubleExtra("lat", 0.0)
                        val lng = intent.getDoubleExtra("lng", 0.0)
                        val name = "方向移动_${SimpleDateFormat("HHmmss", Locale.CHINA).format(Date())}"
                        saveLocation(name, String.format("%.6f", lat), String.format("%.6f", lng))
                    }
                    FloatingDirectionView.ACTION_FLOATING_CLOSED -> {
                        activity?.floatingView = null
                        isFloatingVisible = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(FloatingDirectionView.ACTION_SAVE_FAVORITE)
            addAction(FloatingDirectionView.ACTION_FLOATING_CLOSED)
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    fun fetchAndSaveCurrentLocation(onDone: (Boolean, String, String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            onDone(false, "", ""); return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (MockLocationService.running && (MockLocationService.curLat != 0.0 || MockLocationService.curLng != 0.0)) {
            val latStr = String.format("%.6f", MockLocationService.curLat)
            val lngStr = String.format("%.6f", MockLocationService.curLng)
            onDone(true, latStr, lngStr); return
        }
        var best: Location? = null
        try { best = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        if (best == null) { try { best = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) {} }
        if (best == null) { try { best = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) } catch (_: Exception) {} }
        if (best != null) {
            onDone(true, String.format("%.6f", best.latitude), String.format("%.6f", best.longitude))
        } else {
            try {
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        lm.removeUpdates(this)
                        onDone(true, String.format("%.6f", loc.latitude), String.format("%.6f", loc.longitude))
                    }
                    override fun onProviderDisabled(p0: String) {}
                    override fun onProviderEnabled(p0: String) {}
                    override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}
                }
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    try { lm.removeUpdates(listener) } catch (_: Exception) {}
                    onDone(false, "", "")
                }, 5000)
                return
            } catch (_: Exception) {
                onDone(false, "", "")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("虚拟定位") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            // Status indicator
            Surface(
                color = if (svcRunning) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Floating view toggle
            // 悬浮窗开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isFloatingVisible) {
                    FilledTonalButton(onClick = {
                        activity?.toggleFloatingView()
                        isFloatingVisible = activity?.floatingView != null
                    }) { Text("关闭悬浮窗") }
                } else {
                    FilledTonalButton(onClick = {
                        activity?.toggleFloatingView()
                        isFloatingVisible = activity?.floatingView != null
                    }) { Text("显示悬浮窗") }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("坐标", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = latitude,
                onValueChange = { if (!isMocking) latitude = it },
                label = { Text("纬度") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isMocking,
                singleLine = true
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = longitude,
                onValueChange = { if (!isMocking) longitude = it },
                label = { Text("经度") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isMocking,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        fetchAndSaveCurrentLocation { success, latStr, lngStr ->
                            if (success && latStr.isNotBlank() && lngStr.isNotBlank()) {
                                latitude = latStr; longitude = lngStr
                                var name = "位置${savedLocations.size + 1}"; var idx = 1
                                while (savedLocations.any { it.name == name }) { idx++; name = "位置$idx" }
                                saveLocation(name, latStr, lngStr)
                                Toast.makeText(context, "已保存当前位置", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "获取位置失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isMocking,
                    modifier = Modifier.weight(1f)
                ) { Text("保存当前位置", fontSize = 13.sp) }

                OutlinedButton(
                    onClick = {
                        val lat = latitude.toDoubleOrNull(); val lng = longitude.toDoubleOrNull()
                        if (lat == null || lng == null) { Toast.makeText(context, "无效坐标", Toast.LENGTH_SHORT).show(); return@OutlinedButton }
                        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) { Toast.makeText(context, "超出范围", Toast.LENGTH_SHORT).show(); return@OutlinedButton }
                        var name = "收藏${savedLocations.size + 1}"; var idx = 1
                        while (savedLocations.any { it.name == name }) { idx++; name = "收藏$idx" }
                        saveLocation(name, String.format("%.6f", lat), String.format("%.6f", lng))
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isMocking,
                    modifier = Modifier.weight(1f)
                ) { Text("保存输入", fontSize = 13.sp) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (savedLocations.isNotEmpty()) {
                Text("已保存位置", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        savedLocations.forEachIndexed { index, loc ->
                            var showRenameDialog by remember { mutableStateOf(false) }
                            var newName by remember { mutableStateOf(loc.name) }
                            // 防连点：点击后短暂禁用，避免 provider 冲突
                            var clicking by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        if (clicking) return@FilledTonalButton
                                        clicking = true
                                        try {
                                            val lat = loc.lat.toDoubleOrNull() ?: run { clicking = false; return@FilledTonalButton }
                                            val lng = loc.lng.toDoubleOrNull() ?: run { clicking = false; return@FilledTonalButton }
                                            MockLocationService.startWithName(context, lat, lng, loc.name)
                                            Toast.makeText(context, "已切换至 ${loc.name}", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "切换失败", Toast.LENGTH_SHORT).show()
                                        }
                                        android.os.Handler(context.mainLooper).postDelayed({ clicking = false }, 500)
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    enabled = !clicking,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = if (loc.name == svcName)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text("${loc.name}  ${loc.lat}, ${loc.lng}", fontSize = 12.sp, maxLines = 1)
                                }

                                IconButton(
                                    onClick = { activity?.copyAdbCmd(loc) },
                                    modifier = Modifier.size(32.dp)
                                ) { Text("复制", fontSize = 10.sp) }

                                IconButton(
                                    onClick = { newName = loc.name; showRenameDialog = true },
                                    modifier = Modifier.size(32.dp)
                                ) { Text("重命名", fontSize = 10.sp) }

                                IconButton(
                                    onClick = {
                                        savedLocations = savedLocations.toMutableList().also { it.removeAt(index) }
                                        saveLocationsToPrefs(context, savedLocations)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) { Text("删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
                            }
                            if (showRenameDialog) {
                                AlertDialog(
                                    onDismissRequest = { showRenameDialog = false },
                                    title = { Text("重命名") },
                                    text = {
                                        OutlinedTextField(
                                            value = newName,
                                            onValueChange = { newName = it },
                                            label = { Text("标签名") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            if (newName.isNotBlank()) {
                                                val list = savedLocations.toMutableList()
                                                list[index] = loc.copy(name = newName.trim())
                                                savedLocations = list
                                                saveLocationsToPrefs(context, list)
                                                showRenameDialog = false
                                            }
                                        }) { Text("确定") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return@Button
                        }
                        val lat = latitude.toDoubleOrNull(); val lng = longitude.toDoubleOrNull()
                        if (lat == null || lng == null) { Toast.makeText(context, "请输入有效坐标", Toast.LENGTH_SHORT).show(); return@Button }
                        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) { Toast.makeText(context, "坐标超出范围", Toast.LENGTH_SHORT).show(); return@Button }
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                        MockLocationService.start(context, lat, lng)
                        Toast.makeText(context, "模拟定位已启动", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = !svcRunning
                ) { Text("启动模拟") }

                OutlinedButton(
                    onClick = {
                        MockLocationService.stop(context)
                        Toast.makeText(context, "模拟定位已停止", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = svcRunning
                ) { Text("停止模拟") }
            }

            Spacer(modifier = Modifier.height(24.dp))
            // Shizuku status
            LaunchedEffect(Unit) {
                // just the usual init
            }
            Text("Shizuku 状态", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val shizukuOk = ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasShizukuPermission()
            Text(
                if (shizukuOk) "已授权" else "未就绪",
                fontSize = 11.sp,
                color = if (shizukuOk) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
    }
}

private const val PREFS = "virtual_location_prefs"
private const val KEY = "saved_locations"
private fun saveLocationsToPrefs(ctx: Context, list: List<SavedLocation>) {
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val arr = JSONArray(); for (loc in list) { val o = JSONObject(); o.put("name", loc.name); o.put("lat", loc.lat); o.put("lng", loc.lng); arr.put(o) }
    prefs.edit().putString(KEY, arr.toString()).apply()
}
private fun loadSavedLocations(ctx: Context): List<SavedLocation> {
    val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
    val arr = JSONArray(json); val result = mutableListOf<SavedLocation>()
    for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); result.add(SavedLocation(o.getString("name"), o.getString("lat"), o.getString("lng"))) }
    return result
}
