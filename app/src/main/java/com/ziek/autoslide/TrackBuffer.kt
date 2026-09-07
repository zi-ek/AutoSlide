package com.ziek.autoslide

/**
 * 行动轨迹本地缓冲队列：先落地、有网再冲刷。
 *
 * 采集与上报解耦——[LocationTracker] 每采到一个点只调 [append] 写本地（离线也不丢），
 * 上报由 [flush] 独立完成：有网就把攒下的点整批 POST 到 /api/track/batch，
 * **上报成功才从队首删掉**（先发后删，宁可极小概率重发也不丢点；服务端按时间戳去重）。
 *
 * 本地只保留最近 [RETENTION_MS]（1 天）的点：离线超过一天的更旧的点直接丢弃，
 * 防止长期离线把文件撑大。服务端另有 7 天 retention 与批量口。
 *
 * 存储：filesDir/track_queue.jsonl，一行一个点，字段与批量口一致：
 *   {lat, lon, accuracy?, provider?, time, moving?}
 * deviceId 是设备级常量，不逐点存，冲刷时统一放进批量载荷顶层。
 *
 * 调用方是单条采集协程（[LocationTracker.loop]），本类方法再加 @Synchronized 兜底，
 * 保证同一时刻只有一个读改写文件。
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ziek.autoslide.chat.ChatStorage
import com.ziek.autoslide.ec.EcRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object TrackBuffer {

    private const val TAG = "TrackBuffer"
    private const val FILE_NAME = "track_queue.jsonl"

    /* 本地只留最近 1 天：离线更久的旧点丢弃，够用又不撑爆存储 */
    private const val RETENTION_MS = 24 * 60 * 60 * 1000L
    /* 单次冲刷最多带多少点：控制请求体大小与单批失败的重发成本，剩余留到下轮 */
    private const val MAX_BATCH = 500

    private const val BATCH_PATH = "/api/track/batch"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 20000

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /** 追加一个采集点到本地队列；永不抛异常（离线也要稳稳落地） */
    @Synchronized
    fun append(context: Context, pt: JSONObject) {
        runCatching {
            file(context).appendText(pt.toString() + "\n")
        }.onFailure { LogX.w(TAG, "本地队列追加失败：$it") }
    }

    /**
     * 冲刷本地积压：读队列 → 丢弃超 1 天的旧点 → 无网则仅回写(落实剪枝)返回 →
     * 有网则分批 POST，成功的从队首移除，某批失败即停、剩余留待下轮。
     *
     * 必须在后台线程调用（内部有阻塞 IO）。
     */
    @Synchronized
    fun flush(context: Context) {
        val f = file(context)
        val all = runCatching { if (f.exists()) f.readLines() else emptyList() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
        if (all.isEmpty()) return

        // 剪枝：只保留最近 1 天。解析不出 time 的坏行一并丢弃。
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val fresh = all.filter { line ->
            runCatching { JSONObject(line).optLong("time", 0L) >= cutoff }.getOrDefault(false)
        }

        if (fresh.isEmpty()) { runCatching { f.delete() }; return }

        if (!hasNetwork(context)) {
            // 无网也要把剪枝结果落盘，免得旧点越堆越多
            if (fresh.size != all.size) rewrite(f, fresh)
            return
        }

        val deviceId = ChatStorage.deviceId(context)
        var sent = 0
        while (sent < fresh.size) {
            val end = minOf(sent + MAX_BATCH, fresh.size)
            if (!postBatch(deviceId, fresh.subList(sent, end))) break
            sent = end
        }

        // 只删已确认发出去的前缀；剩余（含失败那批）留到下轮
        val remaining = if (sent >= fresh.size) emptyList() else fresh.subList(sent, fresh.size).toList()
        rewrite(f, remaining)
        if (sent > 0) LogX.i(TAG, "轨迹冲刷 $sent 点，剩余 ${remaining.size}")
    }

    /** 用给定行整份重写队列文件；空则删文件 */
    private fun rewrite(f: File, lines: List<String>) {
        runCatching {
            if (lines.isEmpty()) f.delete()
            else f.writeText(lines.joinToString("\n") + "\n")
        }.onFailure { LogX.w(TAG, "队列回写失败：$it") }
    }

    /** 把一批行拼成批量载荷 POST 出去；2xx 视为成功 */
    private fun postBatch(deviceId: String, lines: List<String>): Boolean {
        val points = JSONArray()
        for (l in lines) runCatching { points.put(JSONObject(l)) }
        if (points.length() == 0) return true // 全是坏行，当作已处理，别卡住队列

        val body = JSONObject().put("deviceId", deviceId).put("points", points).toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(SERVER_BASE_URL + BATCH_PATH).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Report-Token", EcRegistry.TOKEN)
                // 低频请求不复用连接，避免池里的死连接抛 "unexpected end of stream"
                setRequestProperty("Connection", "close")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            LogX.i(TAG, "批量上报 ${points.length()} 点，服务器返回 $code")
            code in 200..299
        } catch (e: Exception) {
            LogX.w(TAG, "批量上报失败：$e")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** 是否有可用网络：无网时直接跳过冲刷，别白等一次连接超时 */
    private fun hasNetwork(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
