# 虚拟定位APP 开发全记录 & 迭代指南

> ⚠️ **核心教训：不要闭门造车！先查资料再动手！**
> 
> 遇到不确定的技术问题，先去 GitHub / Stack Overflow / 博客搜一下成熟的方案，
> 而不是自己瞎猜。5分钟搜索能省2小时踩坑。

---

## 一、项目概述

| 项目 | 内容 |
|------|------|
| 应用名 | 虚拟定位 |
| 包名 | `com.virtuallocation.app` |
| 技术栈 | Kotlin + Jetpack Compose + Material3 |
| 目标 | 不 root、不需要地图，输入坐标即可修改系统定位 |

---

## 二、核心原理

### 2.1 Android 模拟定位机制

Android 系统在 `LocationManager` 中提供了 `addTestProvider()` 和 `setTestProviderLocation()` 两个 API，允许**被授权的应用**向系统注入模拟位置。

```
用户APP ──请求定位──→ LocationManager ──返回模拟坐标──→ APP显示虚拟位置
                           ↑
                     setTestProviderLocation()
                           ↑
                    我们的APP（协程每200ms推送一次）
```

### 2.2 关键条件

- **应用必须出现在「开发者选项 → 选择模拟位置信息应用」列表中**
- 在列表中选中后，系统授予该应用模拟定位权限
- 应用需要**持续推送**位置数据，不能只发一次

---

## 三、踩坑全记录

### ❌ 坑1：testOnly 导致安装失败

**瞎猜**：加了 `android:testOnly="true"` 应该就能出现在列表里了

**结果**：安装报错 `软件无效异常-15`，普通用户无法安装 testOnly 应用

**正确做法**：
- ❌ 不加 `testOnly="true"`
- ✅ 声明 `ACCESS_MOCK_LOCATION` 权限（加 `tools:ignore` 绕过 Lint 检查）

```xml
<uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION"
    tools:ignore="ProtectedPermissions" />
```

### ❌ 坑2：位置只发3次就停

**瞎猜**：发3次应该够了吧？

**结果**：系统 API 能读到，但其他 APP 根本来不及获取就没了

**正确做法**：用协程**持续推送**，每 100~200ms 发一次

```kotlin
mockJob = CoroutineScope(Dispatchers.IO).launch {
    while (isActive) {
        val loc = Location(provider)
        loc.latitude = currentLat
        loc.longitude = currentLng
        loc.accuracy = 3.0f
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        locationManager.setTestProviderLocation(provider, loc)
        delay(100L) // 持续推送
    }
}
```

### ❌ 坑3：只覆盖 GPS 一个定位源

**瞎猜**：GPS 定位源够了吧？

**结果**：部分 APP（如地图）可能使用 network 或 passive 源，读不到模拟位置

**正确做法**：同时覆盖 `gps`、`network`、`passive` 三个定位源

```kotlin
val PROVIDERS = arrayOf("gps", "network", "passive")
for (provider in PROVIDERS) {
    locationManager.addTestProvider(provider, ...)
    locationManager.setTestProviderEnabled(provider, true)
}
```

### ❌ 坑4：依赖问题走了弯路

**过程**：
- 用协程需要 kotlinx-coroutines 依赖
- 先去改 `libs.versions.toml` 太麻烦
- 直接顶部加了 `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`

**经验**：小改动直接加 implementation 更高效，不要过度设计

---

## 四、完整配置参考

### AndroidManifest.xml 正确配置

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- 必要定位权限 -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    
    <!-- 模拟定位权限（让应用出现在开发者选项列表中） -->
    <uses-permission android:name="android.permission.ACCESS_MOCK_LOCATION"
        tools:ignore="ProtectedPermissions" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.MyApplication"
        tools:targetApi="31">
        <!-- 注意：不要加 testOnly！ -->
    </application>
</manifest>
```

### app/build.gradle.kts 关键配置

```kotlin
android {
    namespace = "com.virtuallocation.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.virtuallocation.app"
        minSdk = 24 // 最低 Android 7 就够了
        targetSdk = 35
    }
}

dependencies {
    // 协程（必须，用于持续推送位置）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // Compose 全家桶
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
}
```

---

## 五、代码架构详解

### 5.1 MockLocationHelper.kt（核心引擎）

```
┌─────────────────────────────────────────────┐
│             MockLocationHelper               │
├─────────────────────────────────────────────┤
│ startMock(lat, lng) → 启动模拟定位           │
│   ├ 1. 清除旧的 test provider                │
│   ├ 2. 注册 gps/network/passive 三个源       │
│   ├ 3. 启动协程，每100ms推送一次坐标          │
│   └ 4. 返回 null（成功）或错误信息            │
│                                             │
│ stopMock() → 停止模拟定位                     │
│   ├ 1. 取消协程                              │
│   ├ 2. 禁用 test provider                    │
│   └ 3. 移除 test provider                    │
└─────────────────────────────────────────────┘
```

### 5.2 MainActivity.kt（UI层）

```
┌─────────────────────────────────────────────┐
│              界面结构                         │
├─────────────────────────────────────────────┤
│ ① 状态卡片（显示当前定位状态）                │
│ ② 坐标输入（纬度 + 经度）                    │
│ ③ 收藏功能                                  │
│   ├ 📍 收藏当前位置                          │
│   └ ⭐ 收藏输入坐标                          │
│ ④ 收藏列表（点击填入，✕ 删除）               │
│ ⑤ 启动/停止按钮                             │
│ ⑥ 使用说明                                  │
└─────────────────────────────────────────────┘
```

收藏数据用 SharedPreferences + JSONArray 持久化：
```kotlin
private fun saveLocationsToPrefs(context: Context, list: List<SavedLocation>) {
    val prefs = context.getSharedPreferences("virtual_location_prefs", Context.MODE_PRIVATE)
    val arr = JSONArray()
    for (loc in list) {
        val obj = JSONObject()
        obj.put("name", loc.name)
        obj.put("lat", loc.lat)
        obj.put("lng", loc.lng)
        arr.put(obj)
    }
    prefs.edit().putString("saved_locations", arr.toString()).apply()
}
```

---

## 六、疑难解答

### Q：应用已经出现在开发者选项列表里了，但定位没变
**A1：** 目标 APP 有定位缓存，**彻底关闭重开**（划掉后台）
**A2：** 目标 APP 里手动点击**定位刷新按钮**
**A3：** 确认 APP **没有关闭**（模拟定位协程在运行中）

### Q：安装报错 -15（软件无效）
**原因：** AndroidManifest 中设置了 `android:testOnly="true"`
**解决：** 去掉 `testOnly`，只保留正常的 `ACCESS_MOCK_LOCATION` 权限

### Q：为什么系统定位能读到，但地图APP读不到？
**原因：** 地图APP使用多个定位源混合定位（GPS + 网络 + WiFi）
**解决：** 覆盖 `gps`、`network`、`passive` 三个源，并且持续推送

### Q：高德地图模拟定位不生效怎么办？
**原因：** 高德地图SDK主动检测模拟定位（`isFromMockProvider()`），默认拒绝。
**这是应用层检测，APP层面无法绕过。**

**解决方案：**
1. ✅ **百度地图** — 我们的APP可以正常使用
2. ✅ **虚拟机方案**（用户发现的完美方案）：
   - 在手机上安装一个虚拟机APP（如VMOS等）
   - 虚拟机内安装高德地图
   - 宿主机上用我们的APP改定位
   - 虚拟机里的高德读取的是虚拟机自身的定位系统
   - 完全检测不到宿主机的模拟定位标记
   - **优点**：无需root，任何模拟定位软件都能生效
   - 如果未来要把这个方案做成产品功能，可考虑集成轻量虚拟机

### Q：更高安卓版本（Android 14/15）有变化吗？
**答：** Android 14+ 进一步限制了模拟定位，但原理不变。
- 仍然需要 ACCESS_MOCK_LOCATION + 开发者选项选择
- 某些APP可能会检测模拟环境并拒绝（FakeLocation 可绕过）
- 未来可能需要考虑 Xposed / LSPosed 模块方案（root需要）

---

## 七、后续迭代计划

### P0 - 基础功能完善
- [ ] ✅ ~~输入坐标修改定位~~（已完成）
- [ ] ✅ ~~出现在开发者选项列表~~（已完成）
- [ ] ✅ ~~持续推送定位~~（已完成）
- [ ] ✅ ~~收藏功能~~（已完成）
- [ ] 增加**坐标搜索**（输入地名自动解析经纬度）
- [ ] 增加**地图选点**（点击地图获取坐标，虽然用户说不需要地图，但可以做个轻量的）

### P1 - 用户体验提升
- [ ] **前台服务**：APP切后台后模拟定位继续运行（目前会被杀）
- [ ] **通知栏常驻**：显示当前模拟状态（用户能感知在运行）
- [ ] **快捷开关**：桌面小部件一键开启/关闭模拟
- [ ] **坐标导入/导出**：JSON格式分享收藏坐标

### P2 - 高级功能
- [ ] **路线模拟**：沿路径移动（输入起点终点，自动插值）
- [ ] **速度模拟**：步行/骑行/驾车模式
- [ ] **WiFi/BSSID 伪装**：有些 APP 用 WiFi 位置校验
- [ ] **反检测**：隐藏模拟环境标记（针对反作弊APP）

### P3 - 技术突破
- [ ] **Xposed 模块版**：全局拦截定位（需要 root）
- [ ] **HAL 层注入**：直接修改 GPS HAL 输出（需要 root）
- [ ] **Android 14+ 适配**：新版本权限变化跟进
- [ ] **Google Play 合规**：不上架，仅侧载

---

## 八、技术要点速查

| 知识点 | 说明 |
|--------|------|
| `LocationManager.addTestProvider()` | 注册模拟位置提供者 |
| `LocationManager.setTestProviderLocation()` | 设置模拟坐标（需持续调用） |
| `LocationManager.removeTestProvider()` | 移除模拟提供者 |
| `ACCESS_MOCK_LOCATION` | 使应用出现在开发者选项列表 |
| `tools:ignore="ProtectedPermissions"` | 绕过系统保护权限检查 |
| `CoroutineScope(Dispatchers.IO).launch` | 后台持续推送坐标 |
| `SystemClock.elapsedRealtimeNanos()` | 正确的时间戳（避免被检测） |
| SharedPreferences + JSONArray | 持久化收藏坐标 |

---

## 九、备忘录（下次开发前必看）

1. **面对技术不确定性 → 先搜！** Google / GitHub / Stack Overflow
2. **遇到报错 → 看日志** `adb logcat | grep MockLocationHelper`
3. **先搞清楚原理**（Android 模拟定位机制）再写代码
4. **不要一次猜到底**，反复试错浪费 token
5. **和用户沟通** — 用户说"没改"可能是操作问题而不是代码问题
6. **保持简单** — 不需要的依赖不加，不需要的配置不写

---

*最后更新：2026-05-02*
*下次迭代请开发者务必查阅此文档！*
