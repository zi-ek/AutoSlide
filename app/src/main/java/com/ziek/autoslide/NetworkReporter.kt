package com.ziek.autoslide

/**
 * 网络信息按需上报。
 *
 * 后台想看某台设备当前的局域网 IP 时，服务端并没有办法主动叫醒这台手机——
 * FCM 在国内这批设备上基本不可用（华为/荣耀无 GMS，其余机型也连不上 Google），
 * 厂商推送又要求应用上架各家商店。所以只能反过来：**设备定期问服务端有没有指令**。
 *
 * 轮询本身很便宜：服务端那一侧是纯内存查表，不读写任何文件；
 * 只有真的取到指令、需要回报数据的那一次才产生写入。
 *
 * 宿主选的是无障碍服务而不是 MainActivity：这个 App 的典型用法是
 * 「打开 → 启动滑屏 → 切到抖音」，主界面可见的时间可能只有十几秒，
 * 定时器挂在那里几乎轮不到。无障碍服务在用户开启后是准常驻的，
 * 才是「设备正在使用」的真实信号。
 *
 * 代价也要说清楚：**用户没开无障碍、或进程被系统杀掉时收不到指令**。
 * 此时指令会留在服务端，等设备下次上线再执行，后台界面会如实显示为等待中。
 */

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object NetworkReporter {

    private const val TAG = "NetworkReporter"

    /* 轮询间隔。后台按钮的生效延迟就等于它——平均 30 秒、最坏 60 秒。
       想再快只能上长连接，那是下一个阶段的事。 */
    private const val POLL_INTERVAL_MS = 60_000L

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    private var job: Job? = null

    /**
     * 启动轮询循环。
     *
     * 可重入：已经在跑就直接返回，避免无障碍服务重连时叠出多条循环。
     *
     * @param scope 宿主的协程作用域，随宿主销毁一起取消
     * @param context 任意上下文，内部只持有 applicationContext
     */
    fun start(scope: CoroutineScope, context: Context) {
        if (job?.isActive == true) {
            return
        }

        val appCtx = context.applicationContext

        job = scope.launch(Dispatchers.IO) {
            LogX.i(TAG, "网络信息轮询已启动，间隔 ${POLL_INTERVAL_MS / 1000}s")

            while (isActive) {
                // 单次失败（断网、服务端重启）不该让整条循环退出
                runCatching { pollOnce(appCtx) }
                    .onFailure { LogX.d(TAG, "轮询失败：${it.message}") }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 停止轮询，无障碍服务销毁时调用 */
    fun stop() {
        job?.cancel()
        job = null
        LogX.i(TAG, "网络信息轮询已停止")
    }

    /* 问一次服务端有没有待执行的指令，有才真正采集并上报 */
    private fun pollOnce(context: Context) {
        val id = deviceId(context)
        if (id.isEmpty()) {
            return
        }

        if (!hasPendingRefresh(id)) {
            return
        }

        LogX.i(TAG, "收到后台刷新指令，开始上报网络信息")
        reportNetwork(context, id)
    }

    /* GET /api/device/pending：极轻的一次查询，只回一个布尔 */
    private fun hasPendingRefresh(deviceId: String): Boolean {
        val url = URL(
            SERVER_BASE_URL + "/api/device/pending?deviceId=" +
                java.net.URLEncoder.encode(deviceId, "UTF-8")
        )
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return false
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optBoolean("refreshNetwork", false)
        } finally {
            connection.disconnect()
        }
    }

    /* POST /api/report/network：只带 network 段，不重传整包 deviceInfo */
    private fun reportNetwork(context: Context, deviceId: String) {
        val url = URL(SERVER_BASE_URL + "/api/report/network")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

            val payload = JSONObject()
                .put("deviceId", deviceId)
                .put("network", DeviceInfo.collectNetwork())

            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                LogX.i(TAG, "网络信息上报成功")
            } else {
                LogX.w(TAG, "网络信息上报失败，响应码 $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /* 与授权体系同一个设备标识：固定签名下 ANDROID_ID 稳定，且跨应用不可关联 */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
}
