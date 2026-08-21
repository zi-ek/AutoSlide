package com.ziek.autoslide

/**
 * 录制脚本分享与脚本库
 *
 * 上传只在用户主动点击动作列表里的「分享」时发生，且一次只上传被点中的那一条脚本。
 * 保存/删除录制、App 启动都不再触发任何上传——脚本是用户自己录的内容，
 * 什么时候交出去应当由用户决定。
 *
 * 上传的内容仅限这条脚本的名称与动作序列，不含滑动速度、关键词、统计数据
 * 等本机设置。服务器按设备 ID 分目录保存，后台的设备列表里可以查看和下载。
 */

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

object MacroSync {

    private const val TAG = "MacroSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    /** 脚本库里的一条：列表展示用，正文按需再取 */
    data class LibraryScript(
        val deviceId: String,
        val filename: String,
        val name: String,
        val actionCount: Int,
        val deviceName: String,
        val updatedAt: String,
    )

    /**
     * 拉取脚本库清单（所有人分享上来的脚本）。
     *
     * 清单不含动作正文，只有名称和几个展示字段；点「使用」时才去取正文，
     * 避免库里脚本一多，打开弹窗就拖一大坨数据。
     */
    suspend fun listLibrary(): List<LibraryScript> = withContext(Dispatchers.IO) {
        val json = httpGet(SERVER_BASE_URL + "/api/scripts")
        val arr = JSONObject(json).optJSONArray("scripts") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            LibraryScript(
                deviceId = o.optString("deviceId"),
                filename = o.optString("filename"),
                name = name,
                actionCount = o.optInt("actionCount", 0),
                deviceName = o.optString("deviceName"),
                updatedAt = o.optString("updatedAt"),
            )
        }
    }

    /**
     * 取回某条脚本的动作正文。
     *
     * @return 可以直接写进 SharedPreferences 的 JSON 数组字符串（与
     *         [com.ziek.autoslide.input.AutoSlideInputCodec.encode] 产出的格式一致）
     */
    suspend fun fetchActions(item: LibraryScript): String = withContext(Dispatchers.IO) {
        val url = SERVER_BASE_URL + "/api/download" +
            "?deviceId=" + URLEncoder.encode(item.deviceId, "UTF-8") +
            "&filename=" + URLEncoder.encode(item.filename, "UTF-8")
        val payload = JSONObject(httpGet(url))
        val first = payload.optJSONArray("scripts")?.optJSONObject(0)
            ?: throw IllegalStateException("脚本内容为空")
        // actions 正常是数组；老数据里可能是解析失败时原样存下的字符串，两种都接住
        first.optJSONArray("actions")?.toString()
            ?: first.optString("actions").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("脚本内容为空")
    }

    /* 简单 GET，非 2xx 直接抛异常交给调用方兜底 */
    private fun httpGet(url: String): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 分享单条录制脚本到服务器。
     *
     * 只应由用户点击「分享」触发，不要在保存/删除等自动路径上调用。
     *
     * @param context 上下文（内部只持有 applicationContext，不会泄漏 Activity/Service）
     * @param name 脚本名称
     * @param onResult 结果回调，回到主线程执行；true 表示服务器已收下
     */
    fun share(context: Context, name: String, onResult: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val ok = runCatching { upload(appContext, name) }
                .onFailure { Log.e(TAG, "share script failed: $name", it) }
                .getOrDefault(false)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /**
     * 取出单条录制脚本，打包成上传载荷。
     *
     * 每条记录在 SharedPreferences 里的键是 `macro_名称`，
     * 值是 [com.ziek.autoslide.input.AutoSlideInputCodec] 编码出的 JSON 数组字符串。
     *
     * @param context 上下文
     * @param name 脚本名称
     * @return 形如 {"count":1,"scripts":[{"name":..,"actions":[..]}]} 的载荷；脚本不存在时返回 null
     */
    private fun collectScript(context: Context, name: String): JSONObject? {
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MACRO_PREFIX + name, null)
        if (encoded.isNullOrBlank()) return null
        val item = JSONObject().put("name", name)
        // 动作数组能解析就按结构化存，解析不了则原样保留字符串，不丢数据
        runCatching { JSONArray(encoded) }
            .onSuccess { item.put("actions", it).put("actionCount", it.length()) }
            .onFailure { item.put("actions", encoded) }
        return JSONObject().put("count", 1).put("scripts", JSONArray().put(item))
    }

    /**
     * 脚本名 -> 服务器文件名。
     *
     * 服务端的 sanitize() 会把非 ASCII 字符统统替换成下划线，中文名直接拿去当文件名
     * 会全部撞成同一个文件、互相覆盖。这里改用名称哈希，既是纯 ASCII 又一名一档，
     * 同一条脚本重复分享覆盖自己，不同脚本各存各的。
     */
    private fun fileNameFor(name: String): String {
        val hex = MessageDigest.getInstance("SHA-1")
            .digest(name.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
        return "script_$hex.json"
    }

    /* 真正发请求，返回服务器是否收下 */
    private fun upload(context: Context, name: String): Boolean {
        val payload = collectScript(context, name)
        if (payload == null) {
            Log.w(TAG, "script not found, skip share: $name")
            return false
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val fileName = fileNameFor(name)

        val url = java.net.URL(SERVER_BASE_URL + "/api/upload")
        val connection = url.openConnection() as java.net.HttpURLConnection
        return try {
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
            connection.setRequestProperty("X-Filename", fileName)
            connection.setRequestProperty("X-Script-Count", "1")
            connection.outputStream.use { it.write(bytes) }

            val responseCode = connection.responseCode
            Log.d(TAG, "share script response: $responseCode, file=$fileName, size=${bytes.size}")
            responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }
}
