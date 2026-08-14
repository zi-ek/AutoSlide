package com.ziek.autoslide.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ziek.autoslide.SERVER_BASE_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap

data class ChatMember(val deviceId: String, val name: String, val joinedAt: String)

data class ChatChannel(
    val id: String,
    val code: String,
    val name: String,
    val creatorId: String,
    val members: List<ChatMember>,
    val lastMessageText: String,
    val lastMessageTime: String,
    val joined: Boolean = false,
)

data class ChatMessage(
    val seq: Long,
    val deviceId: String,
    val name: String,
    val text: String,
    val time: String,
    val type: String = "text",
    val image: String = "",
)

data class ChatAnnouncement(
    val title: String,
    val content: String,
    val updatedAt: String,
)

/** 聊天后端：走 Cloudflare Tunnel 反代，全部接口基于 JSON。地址来自 [SERVER_BASE_URL] */
object ChatApi {
    // 依赖非 const 的 SERVER_BASE_URL，因此只能是 val
    private val BASE = "$SERVER_BASE_URL/api/chat"
    private const val TIMEOUT_MS = 10000

    suspend fun createChannel(name: String, deviceId: String, deviceName: String): ChatChannel =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("name", name)
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
            parseChannel(post("/create", body).getJSONObject("channel"))
        }

    suspend fun joinChannel(channelId: String, deviceId: String, deviceName: String): ChatChannel =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("channelId", channelId)
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
            parseChannel(post("/join", body).getJSONObject("channel"))
        }

    suspend fun getChannels(deviceId: String): List<ChatChannel> =
        withContext(Dispatchers.IO) {
            val arr = get("/channels?deviceId=${enc(deviceId)}").getJSONArray("channels")
            (0 until arr.length()).map { parseChannel(arr.getJSONObject(it)) }
        }

    suspend fun getChannel(id: String, deviceId: String): ChatChannel =
        withContext(Dispatchers.IO) {
            parseChannel(get("/channel?id=${enc(id)}&deviceId=${enc(deviceId)}").getJSONObject("channel"))
        }

    suspend fun getMessages(channelId: String, after: Long): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val arr = get("/messages?channelId=${enc(channelId)}&after=$after").getJSONArray("messages")
            (0 until arr.length()).map { parseMessage(arr.getJSONObject(it)) }
        }

    suspend fun sendMessage(
        channelId: String,
        deviceId: String,
        deviceName: String,
        text: String,
        imageBase64: String? = null,
    ): ChatMessage =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("channelId", channelId)
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
                .put("text", text)
            if (!imageBase64.isNullOrBlank()) {
                body.put("image", imageBase64)
            }
            parseMessage(post("/send", body).getJSONObject("message"))
        }

    suspend fun leaveChannel(channelId: String, deviceId: String) =
        withContext(Dispatchers.IO) {
            post("/leave", JSONObject().put("channelId", channelId).put("deviceId", deviceId))
        }

    suspend fun deleteChannel(channelId: String, deviceId: String) =
        withContext(Dispatchers.IO) {
            post("/delete", JSONObject().put("channelId", channelId).put("deviceId", deviceId))
        }

    suspend fun getAnnouncement(): ChatAnnouncement =
        withContext(Dispatchers.IO) {
            val o = get("/announcement").getJSONObject("announcement")
            ChatAnnouncement(
                title = o.optString("title"),
                content = o.optString("content"),
                updatedAt = o.optString("updatedAt"),
            )
        }

    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            return parseResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): JSONObject {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            return parseResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (code !in 200..299 || !json.optBoolean("ok", false)) {
            throw Exception(json.optString("error", "HTTP $code"))
        }
        return json
    }

    private fun parseChannel(o: JSONObject): ChatChannel {
        val membersArr = o.optJSONArray("members")
        val members = if (membersArr == null) emptyList() else (0 until membersArr.length()).map { i ->
            val m = membersArr.getJSONObject(i)
            ChatMember(m.optString("deviceId"), m.optString("name"), m.optString("joinedAt"))
        }
        return ChatChannel(
            id = o.optString("id"),
            code = o.optString("code"),
            name = o.optString("name"),
            creatorId = o.optString("creatorId"),
            members = members,
            lastMessageText = o.optString("lastMessageText"),
            lastMessageTime = o.optString("lastMessageTime"),
            joined = o.optBoolean("joined"),
        )
    }

    private fun parseMessage(o: JSONObject) = ChatMessage(
        seq = o.optLong("seq"),
        deviceId = o.optString("deviceId"),
        name = o.optString("name"),
        text = o.optString("text"),
        time = o.optString("time"),
        type = o.optString("type", "text"),
        image = o.optString("image"),
    )

    fun imageUrl(path: String): String = SERVER_BASE_URL + path

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** 图片消息加载器：简单 LRU 内存缓存，避免重复下载 */
object ChatImageLoader {
    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 24
    }

    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val bmp = BitmapFactory.decodeStream(conn.inputStream)
            conn.disconnect()
            if (bmp != null) cache[url] = bmp
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
