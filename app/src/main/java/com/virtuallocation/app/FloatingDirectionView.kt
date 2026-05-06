package com.virtuallocation.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 方向控制悬浮窗 v4.0
 * - 暗色半透明圆角风格，跟手拖动
 * - ↑↓←→ 方向移动，支持可选步长
 * - 步长选项：50米 / 500米 / 5公里 / 30公里
 * - 保存按钮自动收藏
 * - 顶部实时坐标显示 + 关闭
 */
class FloatingDirectionView(context: Context) : FrameLayout(context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val params: WindowManager.LayoutParams
    private var offsetX = 0f
    private var offsetY = 0f
    private var isDragging = false

    // 步长选项（米）
    private val stepOptions = listOf(50.0, 200.0, 2000.0, 10000.0)
    private val stepLabels = listOf("50m", "200m", "2km", "10km")
    private var currentStepIndex = 0
    private val currentStep get() = stepOptions[currentStepIndex]

    private val LAT_PER_METER = 1.0 / 111320.0

    private lateinit var tvCoord: TextView
    private lateinit var stepButtons: List<TextView>

    // 接收坐标更新广播 -> 刷新显示
    private val coordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_COORD_UPDATE) {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lng = intent.getDoubleExtra("lng", 0.0)
                updateCoordDisplay(lat, lng)
            }
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.floating_direction_view, this, true)
        tvCoord = findViewById(R.id.tv_coord)

        // 初始化步长按钮列表
        stepButtons = listOf(
            findViewById(R.id.btn_step_0),
            findViewById(R.id.btn_step_1),
            findViewById(R.id.btn_step_2),
            findViewById(R.id.btn_step_3)
        )

        val wmType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 300
        }

        // 注册坐标更新广播
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(coordReceiver, IntentFilter(ACTION_COORD_UPDATE))

        // 方向按钮（使用当前步长）
        fun moveUp() = moveDirection(0.0, currentStep)
        fun moveDown() = moveDirection(0.0, -currentStep)
        fun moveLeft() = moveDirection(-currentStep, 0.0)
        fun moveRight() = moveDirection(currentStep, 0.0)

        findViewById<View>(R.id.btn_up).setOnClickListener { moveUp() }
        findViewById<View>(R.id.btn_down).setOnClickListener { moveDown() }
        findViewById<View>(R.id.btn_left).setOnClickListener { moveLeft() }
        findViewById<View>(R.id.btn_right).setOnClickListener { moveRight() }
        findViewById<View>(R.id.btn_save).setOnClickListener { saveCurrentLocation() }
        findViewById<View>(R.id.btn_close).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_open_baidu).setOnClickListener { openBaiduMap() }

        // 步长选择按钮
        stepButtons.forEachIndexed { index, btn ->
            btn.text = stepLabels[index]
            btn.setOnClickListener { selectStep(index) }
        }

        // 默认选中第一个（50m）
        updateStepHighlight()

        // === 跟手拖拽 ===
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    offsetX = event.x
                    offsetY = event.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX - offsetX).roundToInt()
                    val newY = (event.rawY - offsetY).roundToInt()
                    params.x = newX.coerceAtLeast(0)
                    params.y = newY.coerceAtLeast(0)
                    windowManager.updateViewLayout(this, params)
                    isDragging = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val consumed = isDragging
                    isDragging = false
                    consumed
                }
                else -> false
            }
        }

        // 显示当前坐标
        updateCoordDisplay(
            MockLocationService.curLat,
            MockLocationService.curLng
        )
    }

    /** 选中步长 */
    private fun selectStep(index: Int) {
        currentStepIndex = index
        updateStepHighlight()
        val label = stepLabels[index]
        val meters = stepOptions[index].toLong()
        val display = if (meters >= 1000) "${meters / 1000}公里" else "${meters}米"
        Toast.makeText(context, "步长: $display", Toast.LENGTH_SHORT).show()
    }

    /** 更新步长按钮高亮 */
    private fun updateStepHighlight() {
        stepButtons.forEachIndexed { index, btn ->
            if (index == currentStepIndex) {
                btn.setBackgroundResource(R.drawable.bg_circle_button_save) // 橙色高亮
                btn.setTextColor(0xFFFFFFFF.toInt())
                btn.setTypeface(null, Typeface.BOLD)
            } else {
                btn.setBackgroundResource(R.drawable.bg_circle_button) // 普通灰色
                btn.setTextColor(0xFFAAAAAA.toInt())
                btn.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    /** 移动指定方向 */
    private fun moveDirection(dxMeters: Double, dyMeters: Double) {
        val lat = MockLocationService.curLat
        val lng = MockLocationService.curLng

        if (lat == 0.0 && lng == 0.0) {
            Toast.makeText(context, "⚠️ 请先在APP中启动模拟定位", Toast.LENGTH_SHORT).show()
            return
        }

        val lngPerMeter = 1.0 / (111320.0 * cos(Math.toRadians(lat)))
        val newLat = lat + dyMeters * LAT_PER_METER
        val newLng = lng + dxMeters * lngPerMeter

        // 发送广播通知 MockLocationService 更新
        context.sendBroadcast(
            Intent(MockLocationService.ACTION_UPDATE_COORD).apply {
                putExtra(MockLocationService.EXTRA_LAT, newLat)
                putExtra(MockLocationService.EXTRA_LNG, newLng)
            }
        )

        updateCoordDisplay(newLat, newLng)
    }

    /** 保存当前坐标到收藏 */
    private fun saveCurrentLocation() {
        val lat = MockLocationService.curLat
        val lng = MockLocationService.curLng

        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(ACTION_SAVE_FAVORITE).apply {
                putExtra("lat", lat)
                putExtra("lng", lng)
            }
        )

        Toast.makeText(context, "✅ 坐标已收藏", Toast.LENGTH_SHORT).show()
    }

    /** 通过 Shizuku 强开百度地图（静默，不弹提示） */
    private fun openBaiduMap() {
        ShizukuShell.exec("am start -a android.intent.action.VIEW -d baidumap://map")
    }

    private fun updateCoordDisplay(lat: Double, lng: Double) {
        tvCoord.text = if (lat == 0.0 && lng == 0.0) "未启动"
        else "%.6f, %.6f".format(lat, lng)
    }

    fun show() {
        try {
            windowManager.addView(this, params)
        } catch (e: Exception) {
            Toast.makeText(context, "悬浮窗权限未开启，请在设置中允许", Toast.LENGTH_LONG).show()
        }
    }

    fun dismiss() {
        try {
            if (parent != null) windowManager.removeView(this)
            LocalBroadcastManager.getInstance(context).unregisterReceiver(coordReceiver)
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent(ACTION_FLOATING_CLOSED)
            )
            // 回到APP主界面
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_COORD_UPDATE = "com.virtuallocation.app.COORD_UPDATE"
        const val ACTION_SAVE_FAVORITE = "com.virtuallocation.app.SAVE_FAVORITE"
        const val ACTION_FLOATING_CLOSED = "com.virtuallocation.app.FLOATING_CLOSED"
    }
}
