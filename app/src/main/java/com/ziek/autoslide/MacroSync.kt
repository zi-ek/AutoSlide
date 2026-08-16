package com.ziek.autoslide

/**
 * 录制脚本同步
 *
 * 只上传用户创建的录制脚本（即「导出/导入」涉及的那部分内容），
 * 不再上传整个 slide_settings.xml——那里面还夹着滑动速度、关键词、
 * 统计数据等与脚本无关的本机设置，没有备份价值，也扩大了数据面。
 *
 * 服务器按设备 ID 分目录保存，后台的设备列表里可以直接查看和下载。
 */

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object MacroSync {

    private const val TAG = "MacroSync"
    private const val FILENAME = "scripts.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null

    /**
     * 延迟上传（防抖）：录制记录连续变化时只上传一次
     *
     * @param context 上下文
     * @param delayMs 延迟毫秒数
     */
    fun schedule(context: Context, delayMs: Long = 2000) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            doUpload(context)
        }
    }

    /**
     * 立即上传本机录制脚本
     *
     * @param context 上下文
     */
    fun uploadNow(context: Context) {
        scope.launch { doUpload(context) }
    }

    /**
     * 收集本机全部录制脚本。
     *
     * 每条记录在 SharedPreferences 里的键是 `macro_名称`，
     * 值是 [com.ziek.autoslide.input.AutoSlideInputCodec] 编码出的 JSON 数组字符串。
     *
     * @param context 上下文
     * @return 形如 {"count":N,"scripts":[{"name":..,"actions":[..]}]} 的载荷；没有脚本时返回 null
     */
    private fun collectScripts(context: Context): JSONObject? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scripts = JSONArray()
        prefs.all
            .filterKeys { it.startsWith(KEY_MACRO_PREFIX) }
            .toSortedMap()
            .forEach { (key, value) ->
                val encoded = value as? String
                if (encoded.isNullOrBlank()) return@forEach
                val item = JSONObject().put("name", key.removePrefix(KEY_MACRO_PREFIX))
                // 动作数组能解析就按结构化存，解析不了则原样保留字符串，不丢数据
                runCatching { JSONArray(encoded) }
                    .onSuccess { item.put("actions", it).put("actionCount", it.length()) }
                    .onFailure { item.put("actions", encoded) }
                scripts.put(item)
            }
        if (scripts.length() == 0) return null
        return JSONObject().put("count", scripts.length()).put("scripts", scripts)
    }

    private suspend fun doUpload(context: Context) {
        try {
            val payload = collectScripts(context)
            if (payload == null) {
                Log.d(TAG, "no recorded scripts, skip upload")
                return
            }
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            val count = payload.optInt("count", 0)

            val url = java.net.URL(SERVER_BASE_URL + "/api/upload")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty(
                "X-Device-Id",
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            )
            connection.setRequestProperty("X-Device-Name", "${Build.MANUFACTURER} ${Build.MODEL}")
            connection.setRequestProperty("X-Filename", FILENAME)
            connection.setRequestProperty("X-Script-Count", count.toString())
            connection.outputStream.use { it.write(bytes) }

            val responseCode = connection.responseCode
            Log.d(TAG, "upload scripts response: $responseCode, count=$count, size=${bytes.size}")
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "upload scripts failed", e)
        }
    }
}
