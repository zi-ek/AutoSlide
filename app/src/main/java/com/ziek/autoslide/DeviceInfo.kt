package com.ziek.autoslide

/**
 * 设备信息采集（移植自 PlainApp 的 DeviceInfoHelper / PhoneHelper）
 *
 * 全部走公开 API，不需要任何额外权限：
 * - 设备与系统：`android.os.Build` 家族
 * - 设备名称：`Settings.Global.DEVICE_NAME` → `Settings.Secure.bluetooth_name` → 厂商+型号
 *   实测红米返回「Redmi K30S Ultra」、一加返回「OnePlus 15R」，即厂商设定的营销名，
 *   比 `Build.MODEL` 的内部代号（M2007J3SC / CPH2767）可读得多
 * - 内存/存储：`ActivityManager.MemoryInfo` 与 `StatFs`
 *
 * 只采后台真正会用到的字段：后台不展示、也不检索的一律不采。电池整组
 * （电量/电压/温度/状态/健康/技术/容量）与平台里的 SDK、安全补丁、bootloader、
 * 基带、产品代号、Java VM、OpenGL ES、构建时间等，都已因此停止采集。
 */

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object DeviceInfo {

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
        put("platform", platformSection())
        put("network", networkSection(context))
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

    /* 硬件：CPU 架构、总内存、总存储、分辨率 */
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
    }

    /*
     * 平台信息：只保留后台真正会用到的几项。
     *
     * hardware 是概览上显示的「处理器」；buildBrand 是那行徽标里的品牌；
     * buildNumber / board / device / fingerprint 供设备列表搜索用。
     * 其余（SDK、安全补丁、bootloader、基带、产品代号、Java VM、OpenGL ES、
     * 构建时间）后台一个都没消费过，属于采了就扔，已停止采集。
     */
    private fun platformSection() = JSONObject()
        .put("buildNumber", Build.DISPLAY)
        .put("hardware", Build.HARDWARE)
        .put("board", Build.BOARD)
        .put("device", Build.DEVICE)
        .put("buildBrand", Build.BRAND)
        .put("fingerprint", Build.FINGERPRINT)

    /**
     * 单独采集一次网络信息，供后台按需刷新使用。
     *
     * 与 [collect] 里的 network 段同源，但不采集其余分组——设备重报一次 IP
     * 不该顺带把内存、电池、构建指纹整包再传一遍。
     *
     * @param context 上下文
     * @return 形如 `{"ipv4":[...],"ipv6":[...]}`
     */
    fun collectNetwork(context: Context): JSONObject = networkSection(context)

    /**
     * 网络段：本机当前的可连地址。
     *
     * 采集本身在 [LocalAddresses]——全应用只有那一份。这里以前有过自己的一套
     * （只看默认网络、留 ULA、留蜂窝私有 v4），和 Easycontrol 那套报出来的地址对不上，
     * 同一台手机在看板上显示两组数字，谁也说不清哪组是对的。现在两边同源。
     *
     * 报的是一个**有序列表**而不再拆成 ipv4/ipv6：顺序有意义（默认网络排最前，
     * 控制端按这个顺序并发尝试），拆开就丢了。
     */
    private fun networkSection(context: Context): JSONObject =
        JSONObject().put("addresses", JSONArray(LocalAddresses.collect(context)))

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

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun appVersionCode(context: Context): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)
}
