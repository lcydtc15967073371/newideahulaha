package com.virtuallocation.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

fun logToFile(msg: String) {
    try {
        FileWriter("/sdcard/Download/virtuallocation_debug.txt", true).use { w ->
            w.write("${SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())} [${Thread.currentThread().name}] $msg\n")
        }
    } catch (e: Exception) {
        // fallback: write to logcat
        android.util.Log.w("VL", msg)
    }
}

class MockLocationService : Service() {
    companion object {
        private val PROVS = arrayOf("gps", "network", "passive")
        var running = false; private set
        var curLat = 0.0
        var curLng = 0.0
        var curName = ""

        // ----- 内部协程和 LocationManager（由 Service 实例持有）-----
        // 静态引用，供 AdbCommandReceiver 在没有 Service 实例时使用
        // Service 实例会在 onCreate/onDestroy 时注册/注销
        private var serviceInstance: MockLocationService? = null

        // ----- 广播 Action 常量 -----
        const val ACTION_UPDATE_COORD = "com.virtuallocation.app.action.UPDATE_COORD"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val ACTION_ADB_COMMAND = "com.virtuallocation.app.ADB_COMMAND"

        // ========== 对外暴露的静态控制方法 ==========
    /** 启动模拟 */
    fun startMockStatic(ctx: Context, lat: Double, lng: Double) {
        ctx.startForegroundService(Intent(ctx, MockLocationService::class.java).apply {
            action = "S"; putExtra("lat", lat); putExtra("lng", lng)
        })
    }

    /** 仅更新坐标（Service 已运行的情况下） */
    fun updateMockStatic(lat: Double, lng: Double) {
        curLat = lat; curLng = lng
        serviceInstance?.updateMockLocationInternal(lat, lng)
    }

    /** 停止模拟 */
    fun stopMockStatic(ctx: Context) {
        ctx.startService(Intent(ctx, MockLocationService::class.java).apply { action = "T" })
    }

    /** 通过标签名从收藏中查找坐标并启动 */
    fun startByFavorite(ctx: Context, name: String) {
        val json = ctx.getSharedPreferences("virtual_location_prefs", Context.MODE_PRIVATE)
            .getString("saved_locations", null)
        if (json.isNullOrBlank()) { toast(ctx, "⚠️ 收藏为空"); return }
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val savedName = o.getString("name")
                if (savedName.equals(name, ignoreCase = true) || savedName.contains(name)) {
                    val lat = o.getString("lat").toDoubleOrNull() ?: continue
                    val lng = o.getString("lng").toDoubleOrNull() ?: continue
                    // 直接通过 Intent 传 name，让 Service 正确设置 curName
                    ctx.startForegroundService(Intent(ctx, MockLocationService::class.java).apply {
                        action = "S"
                        putExtra("lat", lat)
                        putExtra("lng", lng)
                        putExtra("name", savedName)
                    })
                    // running 由 onStartCommand("S") 在真正启动成功后设置，这里不要提前设
                    toast(ctx, "✅ 已切换至：$savedName")
                    return
                }
            }
            toast(ctx, "⚠️ 未找到: $name")
        } catch (e: Exception) {
            toast(ctx, "❌ ${e.message}")
        }
    }

    /** 启动空闲保活模式（只显示通知栏，不模拟坐标） */
    fun startIdle(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, MockLocationService::class.java).apply {
            action = "IDLE"
        })
    }

    private fun toast(ctx: Context, msg: String) {
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
    // ====================================================

        fun start(ctx: Context, lat: Double, lng: Double) {
            startWithName(ctx, lat, lng, "")
        }
        fun startWithName(ctx: Context, lat: Double, lng: Double, name: String) {
            ctx.startForegroundService(Intent(ctx, MockLocationService::class.java).apply {
                action = "S"
                putExtra("lat", lat)
                putExtra("lng", lng)
                putExtra("name", name)
            })
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MockLocationService::class.java).apply { action = "T" })
        }

        /** 检测当前APP是否被选中为模拟位置应用 */
        fun isMockLocationApp(ctx: Context): Boolean {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                lm.addTestProvider("gps_check_only", false, false, false, false, true, true, true, android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
                lm.removeTestProvider("gps_check_only")
                true
            } catch (e: SecurityException) {
                false
            } catch (_: Exception) {
                true
            }
        }
    }

    private var lm: LocationManager? = null
    private var mockScope: CoroutineScope? = null
    private val mockLock = Any()

    // 内部广播接收器（接收悬浮窗/内部广播的坐标更新）
    // ----- 广播接收器（接收悬浮窗 + ADB 命令） -----
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_COORD -> {
                    val lat = intent.getDoubleExtra(EXTRA_LAT, curLat)
                    val lng = intent.getDoubleExtra(EXTRA_LNG, curLng)
                    curLat = lat; curLng = lng
                    mockScope?.cancel()
                    startPushLoop()
                    LocalBroadcastManager.getInstance(this@MockLocationService)
                        .sendBroadcast(
                            Intent(FloatingDirectionView.ACTION_COORD_UPDATE).apply {
                                putExtra("lat", lat); putExtra("lng", lng)
                            }
                        )
                }
                ACTION_ADB_COMMAND -> {
                    handleAdbCommand(intent)
                }
            }
        }
    }

    /** 处理 ADB/Shizuku 发来的广播命令 */
    private fun handleAdbCommand(intent: Intent) {
        val cmd = intent.getStringExtra("action") ?: return
        // Service 已运行时，直接自动授权
        if (cmd.lowercase() != "stop") {
            try {
                val r = ShizukuShell.exec("appops set $packageName android:mock_location allow")
                if (r.exitCode != 0) toast("Shizuku授权失败: ${r.output}")
                else {
                    ShizukuShell.exec("settings put secure mock_location_app $packageName")
                    toast("✅ 已自动授权模拟定位")
                }
            } catch (e: Exception) {
                toast("Shizuku异常: ${e.message}")
            }
        }
        try {
            when (cmd.lowercase()) {
                "start" -> {
                    // 优先从 intent extra 读坐标；也支持写死测试（传 test=1 时使用固定坐标）
                    var finalLat = intent.getDoubleExtra("lat", 0.0)
                    var finalLng = intent.getDoubleExtra("lng", 0.0)
                    if (finalLat == 0.0 && finalLng == 0.0) {
                        // Float 类型的 extra（--ef 传入）
                        finalLat = intent.getFloatExtra("lat", 0f).toDouble()
                        finalLng = intent.getFloatExtra("lng", 0f).toDouble()
                    }
                    // 如果还是 0.0，尝试字符串解析（--es 传入）
                    if (finalLat == 0.0 && finalLng == 0.0) {
                        try { finalLat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: 0.0 } catch (_: Exception) {}
                        try { finalLng = intent.getStringExtra("lng")?.toDoubleOrNull() ?: 0.0 } catch (_: Exception) {}
                    }
                    if (finalLat == 0.0 && finalLng == 0.0) {
                        toast("⚠️ 缺少经纬度参数"); return
                    }
                    startMockInternal(finalLat, finalLng)
                    try { startForeground(1001, notif(finalLat, finalLng)) } catch (_: Exception) {}
                    running = true
                    toast("🟢 %.4f, %.4f".format(finalLat, finalLng))
                }
                "favorite", "fav", "name" -> {
                    val name = intent.getStringExtra("name") ?: return
                    val json = getSharedPreferences("virtual_location_prefs", MODE_PRIVATE)
                        .getString("saved_locations", null)
                    if (json.isNullOrBlank()) { toast("⚠️ 收藏为空"); return }
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val savedName = o.getString("name")
                        if (savedName.equals(name, ignoreCase = true) || savedName.contains(name)) {
                            val lat = o.getString("lat").toDoubleOrNull() ?: continue
                            val lng = o.getString("lng").toDoubleOrNull() ?: continue
                            startMockInternal(lat, lng, savedName)
                            startForeground(1001, notif(lat, lng, savedName))
                            running = true
                            toast("📌 $savedName")
                            return
                        }
                    }
                    toast("⚠️ 未找到: $name")
                }
                "stop" -> {
                    stopMockInternal()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    running = false
                    toast("🔴 已停止")
                }
                else -> toast("⚠️ 未知命令: $cmd")
            }
        } catch (e: Exception) {
            toast("❌ ${e.message}")
        }
    }

    private fun toast(msg: String) {
        try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
    // ----------------------------------------------------------
    override fun onCreate() {
        super.onCreate()
        logToFile("onCreate start")
        serviceInstance = this
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        logToFile("onCreate lm=$lm")
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel("mock_loc", "虚拟定位", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val filter = IntentFilter().apply { addAction(ACTION_UPDATE_COORD); addAction(ACTION_ADB_COMMAND) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logToFile("onStartCommand action=${intent?.action} running=$running lm=$lm")
        // Service 进程已稳定运行，尝试自动授权（不阻塞定位流程）
        if (intent?.action != "T" && intent?.action != "IDLE") {
            try {
                ShizukuShell.exec("appops set $packageName android:mock_location allow")
                ShizukuShell.exec("settings put secure mock_location_app $packageName")
            } catch (_: Exception) {}
        }
        try {
            when (intent?.action) {
                "S" -> {
                    val lat = intent.getDoubleExtra("lat", 0.0)
                    val lng = intent.getDoubleExtra("lng", 0.0)
                    val name = intent.getStringExtra("name") ?: ""
                    logToFile("onStartCommand S lat=$lat lng=$lng name=$name running=$running lm=${lm}")
                    if (lat == 0.0 && lng == 0.0) { logToFile("S returning: lat/lng zero"); return START_STICKY }
                    if (running) {
                        // 服务已在运行，只更新坐标，不重新初始化 provider
                        curLat = lat; curLng = lng; curName = name
                        mockScope?.cancel()
                        startPushLoop()
                        try { startForeground(1001, notif(lat, lng, name)) } catch (_: Exception) {}
                    } else {
                        // 首次启动或服务已停止
                        if (lm == null) {
                            android.os.Handler(mainLooper).postDelayed({
                                onStartCommand(Intent(this, MockLocationService::class.java).apply {
                                    action = "S"; putExtra("lat", lat); putExtra("lng", lng); putExtra("name", name)
                                }, 0, startId)
                            }, 500)
                            return START_STICKY
                        }
                        startMockInternal(lat, lng, name)
                        try { startForeground(1001, notif(lat, lng, name)) } catch (_: Exception) {}
                        running = true
                    }
                }
                "IDLE" -> {
                    if (!running) {
                        try { startForeground(1001, notifIdle()) } catch (_: Exception) {}
                    }
                }
                "ADB" -> handleAdbCommand(intent)
                "T" -> {
                    stopMockInternal()
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                    stopSelf()
                    running = false
                }
            }
        } catch (e: Exception) {
            toast("启动异常: ${e.message}")
        }
        return START_STICKY
    }

    override fun onBind(i: Intent?) = null

    /** 内部：首次启动模拟（增强版：多次重试直到注册成功） */
    private fun startMockInternal(lat: Double, lng: Double, name: String = "") {
        synchronized(mockLock) {
            logToFile("startMockInternal lat=$lat lng=$lng name=$name running=$running")
            try {
                stopMockInternal()
                curLat = lat; curLng = lng; curName = name
                val m = lm
                if (m == null) { logToFile("lm is null, abort"); return }

                // 通过 Shizuku 清理旧应用 + 自动授予本APP模拟位置权限
                val ctx = this
                try {
                    if (ShizukuShell.isAvailable() && ShizukuShell.isGranted()) {
                        // 1. 撤销旧应用的模拟定位权限，避免冲突
                        ShizukuShell.exec("appops set top.xuante.moloc android:mock_location deny")
                        // 2. 清空系统记录的模拟定位应用
                        ShizukuShell.exec("settings put secure mock_location_app \"\"")
                        // 3. 为本APP授予模拟定位权限
                        val r = ShizukuShell.exec("appops set $packageName android:mock_location allow")
                        logToFile("appops set mock_location allow exitCode=${r.exitCode} output=${r.output}")
                        if (r.exitCode == 0) {
                            ShizukuShell.exec("settings put secure mock_location_app $packageName")
                            logToFile("settings put secure mock_location_app done")
                        }
                    }
                } catch (_: Exception) {}

                // 检测是否选中了本APP（仅弹Toast提示，不阻断流程）
                if (!isMockLocationApp(ctx)) {
                    logToFile("WARN: isMockLocationApp returned false")
                    android.os.Handler(mainLooper).post {
                        android.widget.Toast.makeText(ctx, "⚠️ 未在开发者选项中选中本APP\n请先设置：开发者选项 → 选择模拟位置信息应用 → 虚拟定位", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                        logToFile("calling registerProviders... lm=$m running=$running")
                        // 注册 provider，先尝试注册
                        val regOk = registerProviders(m)
                        // 不管注册是否全部成功，都尝试启动推送循环
                        logToFile("registerProviders result=$regOk, starting push loop anyway")
                        startPushLoop()
                        // 延迟5秒后通过Shizuku自动补发"第二次广播"，模拟手动发两次的效果
                        // 某些APP（如百度地图/高德SDK）需要第二次推送才会采纳坐标
                        // 用 Shizuku 执行 am start 命令，和用户手动执行的效果完全一致
                        // 延迟5秒后通过Shizuku自动补发"第二次广播"
                        logToFile("scheduling re-broadcast via Shizuku in 5s")
                        android.os.Handler(mainLooper).postDelayed({
                            logToFile("auto re-broadcast via Shizuku: sending second am start command")
                            // 使用当前坐标（curLat/curLng 在 startMockInternal 中已设置）
                            val lat = curLat; val lng = curLng; val name = curName
                            // 用 favorite action，确保通知显示收藏名而不是纯坐标
                            val cmd = if (name.isNotBlank()) {
                                "am start -n $packageName/.AdbCommandActivity --es action favorite --es name \"$name\""
                            } else {
                                "am start -n $packageName/.AdbCommandActivity --es action start --ef lat $lat --ef lng $lng"
                            }
                            try {
                                val r = ShizukuShell.exec(cmd)
                                logToFile("Shizuku re-broadcast exitCode=${r.exitCode} output=${r.output}")
                            } catch (e: Exception) {
                                logToFile("Shizuku re-broadcast failed: ${e.message}")
                            }
                        }, 5000)
                        if (!regOk) {
                            // 后台重试注册，但不阻塞推送
                            scheduleRetryRegister(1, m)
                        }
            } catch (e: Exception) {
                logToFile("CRASH in startMockInternal: ${e.javaClass.name}: ${e.message}")
                try {
                    val sw = java.io.StringWriter()
                    val pw = java.io.PrintWriter(sw)
                    e.printStackTrace(pw)
                    FileWriter("/sdcard/Download/virtuallocation_debug.txt", true).use { w ->
                        w.write("STACKTRACE:\n$sw\n")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** 注册 TestProvider，成功返回 true，失败返回 false */
    private fun registerProviders(m: LocationManager): Boolean {
        var allOk = true
        for (p in PROVS) {
            try { m.removeTestProvider(p) } catch (_: Exception) {}
            try {
                val sp = m.getProvider(p)
                if (sp != null)
                    m.addTestProvider(p, sp.requiresNetwork(), sp.requiresSatellite(), sp.requiresCell(), sp.hasMonetaryCost(), sp.supportsAltitude(), sp.supportsSpeed(), sp.supportsBearing(), sp.powerRequirement, sp.accuracy)
                else
                    m.addTestProvider(p, false, false, false, false, true, true, true, android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
                m.setTestProviderEnabled(p, true)
                m.setTestProviderStatus(p, LocationProvider.AVAILABLE, null, System.currentTimeMillis())
            } catch (e: Exception) {
                allOk = false
            }
        }
        return allOk
    }

    /** 内部：仅更新坐标（不重新注册 provider） */
    private fun updateMockLocationInternal(lat: Double, lng: Double) {
        curLat = lat; curLng = lng
        mockScope?.cancel()
        startPushLoop()
    }

    /** 延迟重试注册 provider，同步锁保护，注册成功后停止重试 */
    private fun scheduleRetryRegister(retryCount: Int, m: LocationManager) {
        if (retryCount > 3) {
            logToFile("registerProviders FAILED after 3 retries")
            return
        }
        android.os.Handler(mainLooper).postDelayed({
            synchronized(mockLock) {
                // 如果 running 为 true，说明之前某次重试已成功，不再重试
                if (running) return@synchronized
                logToFile("retry #$retryCount registerProviders...")
                if (registerProviders(m)) {
                    logToFile("retry #$retryCount success, startPushLoop")
                    startPushLoop()
                } else {
                    logToFile("retry #$retryCount failed, retry again")
                    scheduleRetryRegister(retryCount + 1, m)
                }
            }
        }, 1000)
    }

    /** 快速推送循环（自愈版：推送后验证系统是否采纳，不采纳则重试） */
    private val jitterRand = java.util.Random()
    private var pushHandler: android.os.Handler? = null
    private var pushRunnable: Runnable? = null
    /** 执行一次坐标推送 */
    private fun pushOnce(m: LocationManager) {
        try {
            val t = System.currentTimeMillis()
            val e = SystemClock.elapsedRealtimeNanos()
            val jl = (jitterRand.nextDouble() - 0.5) * 0.0002
            val jn = (jitterRand.nextDouble() - 0.5) * 0.0002
            for (p in PROVS) {
                try { m.setTestProviderStatus(p, LocationProvider.AVAILABLE, null, t) } catch (_: Exception) {}
                try {
                    m.setTestProviderLocation(p, Location(p).apply {
                        latitude = curLat + jl; longitude = curLng + jn
                        accuracy = 8f + jitterRand.nextFloat() * 12f
                        time = t; elapsedRealtimeNanos = e
                        altitude = 0.0; speed = 0.5f + jitterRand.nextFloat() * 1.5f; bearing = 0f
                    })
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** 检查系统是否已采纳模拟定位 */
    private fun isMockActive(m: LocationManager): Boolean {
        return try {
            for (p in PROVS) {
                val last = m.getLastKnownLocation(p) ?: continue
                // 检查是否为模拟位置（isFromMockProvider）且坐标与当前推送坐标接近
                if (last.isFromMockProvider) return true
            }
            // 如果三个provider都没取到，也可能是系统还未分配，不算失败
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 自愈版推送循环：先自检验证，不采纳则重试，通过后进入稳定轮询 */
    private fun startPushLoop() {
        val m = lm ?: return
        pushRunnable?.let { pushHandler?.removeCallbacks(it) }

        running = true
        pushHandler = android.os.Handler(android.os.Looper.getMainLooper())
        pushRunnable = object : Runnable {
            private var burstCount = 0
            private var healAttempt = 0
            override fun run() {
                pushOnce(m)
                burstCount++

                // 前3次快速推送（50ms/100ms/150ms间隔递增），模拟"发两次广播"的效果
                if (burstCount <= 3) {
                    val delay = when (burstCount) { 1 -> 50; 2 -> 100; else -> 150 }
                    pushHandler?.postDelayed(this, delay.toLong())
                    return
                }

                // 第4次推送后：检查系统是否采纳
                if (burstCount == 4) {
                    if (isMockActive(m)) {
                        // ✅ 系统已采纳，进入稳定轮询（每200ms）
                        logToFile("mock active confirmed after $burstCount pushes, entering steady loop")
                        pushHandler?.postDelayed(this, 200)
                        return
                    } else {
                        // ❌ 系统未采纳，启动自愈
                        healAttempt++
                        if (healAttempt <= 5) {
                            logToFile("heal attempt #$healAttempt: mock not active yet, re-registering provider status")
                            // 重新设置provider状态，尝试激活
                            for (p in PROVS) {
                                try {
                                    m.setTestProviderEnabled(p, true)
                                    m.setTestProviderStatus(p, LocationProvider.AVAILABLE, null, System.currentTimeMillis())
                                } catch (_: Exception) {}
                            }
                            // 再连推3次（快速模式）
                            burstCount = 0
                            pushHandler?.postDelayed(this, 100)
                        } else {
                            // 5次自愈都失败：强制重新注册provider + 重新授权
                            logToFile("heal failed after 5 attempts, force re-register provider")
                            healAttempt = 0
                            try {
                                // 重新授权
                                ShizukuShell.exec("appops set $packageName android:mock_location allow")
                                // 重新注册provider
                                for (p in PROVS) {
                                    try { m.removeTestProvider(p) } catch (_: Exception) {}
                                    try {
                                        val sp = m.getProvider(p)
                                        if (sp != null)
                                            m.addTestProvider(p, sp.requiresNetwork(), sp.requiresSatellite(), sp.requiresCell(), sp.hasMonetaryCost(), sp.supportsAltitude(), sp.supportsSpeed(), sp.supportsBearing(), sp.powerRequirement, sp.accuracy)
                                        else
                                            m.addTestProvider(p, false, false, false, false, true, true, true, android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
                                        m.setTestProviderEnabled(p, true)
                                        m.setTestProviderStatus(p, LocationProvider.AVAILABLE, null, System.currentTimeMillis())
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                            // 重新连推
                            burstCount = 0
                            pushHandler?.postDelayed(this, 200)
                        }
                        return
                    }
                }

                // 进入稳态后，每200ms持续推送
                pushHandler?.postDelayed(this, 200)
            }
        }
        // 第一次推送延迟200ms，确保 provider 注册完成
        pushHandler?.postDelayed(pushRunnable!!, 200)
    }

    private fun stopMockInternal() {
        mockScope?.cancel()
        mockScope = null
        lm?.let { m ->
            for (p in PROVS) try {
                m.setTestProviderEnabled(p, false)
                m.removeTestProvider(p)
            } catch (_: Exception) {}
        }
    }

    private fun notif(lat: Double, lng: Double, name: String = ""): Notification {
        val si = PendingIntent.getService(this, 0,
            Intent(this, MockLocationService::class.java).apply { action = "T" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (name.isNotBlank()) "🟢 $name" else "🟢 虚拟定位"
        return NotificationCompat.Builder(this, "mock_loc")
            .setContentTitle(title)
            .setContentText(String.format("%.6f, %.6f", lat, lng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", si)
            .build()
    }

    /** 空闲保活通知 */
    private fun notifIdle(): Notification {
        val si = PendingIntent.getService(this, 0,
            Intent(this, MockLocationService::class.java).apply { action = "T" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "mock_loc")
            .setContentTitle("💤 虚拟定位待机")
            .setContentText("点击启动模拟后开始定位")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", si)
            .build()
    }

    override fun onDestroy() {
        serviceInstance = null
        unregisterReceiver(updateReceiver)
        stopMockInternal()
        super.onDestroy()
    }
}
