package com.ziek.autoslide

/**
 * 设备信息采集（移植自 PlainApp 的 DeviceInfoHelper / PhoneHelper / BatteryReceiver）
 *
 * 全部走公开 API，不需要任何额外权限：
 * - 设备与系统：`android.os.Build` 家族
 * - 设备名称：`Settings.Global.DEVICE_NAME` → `Settings.Secure.bluetooth_name` → 厂商+型号
 *   实测红米返回「Redmi K30S Ultra」、一加返回「OnePlus 15R」，即厂商设定的营销名，
 *   比 `Build.MODEL` 的内部代号（M2007J3SC / CPH2767）可读得多
 * - 内存/存储：`ActivityManager.MemoryInfo` 与 `StatFs`
 * - 电池：`ACTION_BATTERY_CHANGED` 粘性广播；容量走 PowerProfile 反射，取不到记 0
 *
 * 字段命名与分组对齐 PlainApp 的「设备 / 系统 / 硬件 / 平台信息 / 电池」五张卡片，
 * 后台按同样的结构展示。
 */

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.util.Locale

object DeviceInfo {

    private const val TAG = "DeviceInfo"

    /**
     * 采集全部设备信息。
     *
     * @param context 上下文
     * @return 与后台展示结构一致的 JSON
     */
    fun collect(context: Context): JSONObject = JSONObject().apply {
        put("device", deviceSection(context))
        put("system", systemSection())
        put("hardware", hardwareSection(context))
        put("platform", platformSection(context))
        put("battery", batterySection(context))
    }

    /* 设备：名称、平台、厂商、型号、语言、应用版本 */
    private fun deviceSection(context: Context) = JSONObject()
        .put("name", deviceName(context))
        .put("platform", "ANDROID")
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("language", Locale.getDefault().language)
        .put("appVersion", appVersionName(context))
        .put("appBuildNumber", appVersionCode(context))

    /* 系统：系统名、版本、内核、运行时间 */
    private fun systemSection() = JSONObject()
        .put("osName", "Android")
        .put("osVersion", Build.VERSION.RELEASE)
        .put("kernelVersion", System.getProperty("os.version").orEmpty())
        .put("uptime", SystemClock.elapsedRealtime())

    /* 硬件：CPU 架构、总内存、总存储、分辨率、屏幕密度 */
    private fun hardwareSection(context: Context): JSONObject {
        val memInfo = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
        }
        val totalStorage = runCatching { StatFs(Environment.getDataDirectory().path).totalBytes }.getOrDefault(0L)
        val dm = context.resources.displayMetrics
        return JSONObject()
            .put("cpuArch", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("totalMemory", memInfo.totalMem)
            .put("totalStorage", totalStorage)
            .put("displayWidth", dm.widthPixels)
            .put("displayHeight", dm.heightPixels)
            .put("displayDensity", dm.density)
    }

    /* 平台信息：SDK、安全补丁、构建标识等 */
    private fun platformSection(context: Context): JSONObject {
        val glEs = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .deviceConfigurationInfo.glEsVersion
        }.getOrDefault("")
        return JSONObject()
            .put("sdkVersion", Build.VERSION.SDK_INT)
            .put("versionCodeName", Build.VERSION.CODENAME)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH.orEmpty())
            .put("bootloader", Build.BOOTLOADER)
            .put("buildNumber", Build.DISPLAY)
            .put("radioVersion", runCatching { Build.getRadioVersion().orEmpty() }.getOrDefault(""))
            .put("hardware", Build.HARDWARE)
            .put("board", Build.BOARD)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("buildBrand", Build.BRAND)
            .put("javaVmVersion", System.getProperty("java.vm.version").orEmpty())
            .put("glEsVersion", glEs)
            .put("fingerprint", Build.FINGERPRINT)
            .put("buildTime", Build.TIME)
    }

    /* 电池：健康、电量、状态、电源、技术、温度、电压、容量 */
    private fun batterySection(context: Context): JSONObject {
        val json = JSONObject()
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        if (intent != null) {
            json.put("level", intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                .put("voltage", intent.getIntExtra("voltage", 0))
                .put("temperature", intent.getIntExtra("temperature", 0) / 10)
                .put("status", intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                .put("plugged", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
                .put("health", intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
                .put("technology", intent.extras?.getString(BatteryManager.EXTRA_TECHNOLOGY).orEmpty())
        }
        json.put("capacity", batteryCapacity(context))
        return json
    }

    /**
     * 供统计上报用的展示型号，与 [deviceName] 同源。
     *
     * @param context 上下文
     * @return 例如「Redmi K30S Ultra」
     */
    fun displayModel(context: Context): String = deviceName(context)

    /**
     * 设备名称：优先系统设置里的设备名，其次蓝牙名，最后退回「厂商 型号」。
     * 与 PlainApp PhoneHelper.getDeviceName 一致。
     */
    private fun deviceName(context: Context): String {
        runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            manufacturer.replaceFirstChar { it.uppercase() } + " " + model
        }
    }

    /* 电池设计容量：PowerProfile 是内部类，只能反射；取不到返回 0 */
    private fun batteryCapacity(context: Context): Int = runCatching {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(context)
        val value = cls.getMethod("getAveragePower", String::class.java)
            .invoke(profile, "battery.capacity") as Double
        value.toInt()
    }.getOrElse {
        LogX.w(TAG, "read battery capacity failed", it)
        0
    }

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun appVersionCode(context: Context): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)
}
