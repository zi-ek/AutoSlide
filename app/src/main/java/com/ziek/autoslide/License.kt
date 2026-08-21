package com.ziek.autoslide

/**
 * 使用时长授权（试用期 + 分享奖励）
 *
 * 判定权全部在服务端：`GET /api/license` 返回一个绝对到期时间戳，本地只负责缓存和比对。
 * 这样重装、清数据、改系统时间都绕不过去，客户端也不需要自己算任何规则。
 *
 * 三条硬约束：
 * 1. **没联系上服务器不影响使用**：请求失败时沿用本地缓存的到期时间；本地压根没有缓存
 *    （新装 + 一直没网）时一律放行，绝不因为网络问题把用户挡在外面。
 * 2. **缓存永久有效**：只要缓存里没到期就能一直用，不设离线宽限期。
 * 3. **时钟倒退无效**：把系统时间改早时，以最后一次见到的服务端时间为准。
 *
 * 服务端实现见 `server/src/license.js`。
 */

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ziek.autoslide.service.AutoSlideService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object License {

    private const val TAG = "License"

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /* 同步成功后，回到前台再次同步的最短间隔 */
    private const val SYNC_INTERVAL_MS = 30 * 60 * 1000L

    /* 同步失败（多半是没网）后的最短重试间隔 */
    private const val RETRY_INTERVAL_MS = 60 * 1000L

    /**
     * 授权状态快照。
     *
     * @param known 是否拿到过服务端结果。没有缓存时为 false，此时一律放行。
     */
    data class Status(
        val code: String = "",
        val inviteUrl: String = "",
        val invitedCount: Int = 0,
        val bonusDays: Int = 0,
        val expireAt: Long = 0L,
        val canBind: Boolean = false,
        val known: Boolean = false
    ) {
        /** 剩余天数（向上取整），未知或已到期时为 0 */
        fun remainDays(now: Long): Int =
            if (!known || expireAt <= now) 0 else ((expireAt - now + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** 当前是否已到期（到期后停掉全部自动功能） */
    val blockedFlow = MutableStateFlow(false)

    /** 最近一次已知的授权状态，供界面展示剩余天数与邀请码 */
    val statusFlow = MutableStateFlow(Status())

    /** 服务不在前台时也要能读，直接暴露一个易失字段给服务层快速判断 */
    @Volatile
    var blocked = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var appContext: Context? = null
    private var syncing = false
    private var lastSyncAt = 0L   // elapsedRealtime，同步成功时刻
    private var lastFailAt = 0L   // elapsedRealtime，同步失败时刻

    /**
     * 进程启动时读一次缓存，让服务层在界面还没起来时就知道自己该不该干活。
     *
     * @param context 上下文
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        statusFlow.value = readCache(context)
        applyBlocked(context)
    }

    /* ==================== 状态判定 ==================== */

    /**
     * 判定当前是否到期。
     * 没有任何缓存（从没连上过服务器）时返回 false —— 网络问题不该把用户挡在外面。
     *
     * @param context 上下文
     * @return 是否已到期
     */
    fun isExpired(context: Context): Boolean {
        val status = statusFlow.value.takeIf { it.known } ?: readCache(context)
        if (!status.known) return false
        return effectiveNow(context) >= status.expireAt
    }

    /**
     * 防时钟倒退的“当前时间”。
     *
     * 用户把系统时间改早时，`System.currentTimeMillis()` 会小于最后一次见到的服务端时间；
     * 这时以服务端时间为准，并叠加本次开机以来真实流逝的时长（elapsedRealtime 改不了）。
     *
     * @param context 上下文
     * @return 用于比对到期时间的当前时刻
     */
    private fun effectiveNow(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastServerTime = prefs.getLong(KEY_LIC_SERVER_TIME, 0L)
        if (lastServerTime <= 0L) return now
        // 同步发生在本次开机内时，用单调时钟补上流逝的时间；跨重启则退化为直接比较
        val monotonic = if (lastSyncAt > 0L) {
            lastServerTime + (SystemClock.elapsedRealtime() - lastSyncAt)
        } else {
            lastServerTime
        }
        return maxOf(now, monotonic)
    }

    /* 重新计算是否到期，并把结论推给无障碍服务 */
    private fun applyBlocked(context: Context) {
        val expired = isExpired(context)
        blocked = expired
        blockedFlow.value = expired
        AutoSlideService.getInstance()?.applyLicenseBlocked(expired)
        Log.d(TAG, "license blocked=$expired, status=${statusFlow.value}")
    }

    /**
     * 不联网，只按本地缓存重新判定一次。
     * App 长时间开着不动时缓存可能已经跨过到期点，每次回到界面都重算一遍。
     *
     * @param context 上下文
     */
    fun revalidate(context: Context) = applyBlocked(context)

    /* ==================== 本地缓存 ==================== */

    private fun readCache(context: Context): Status {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val expireAt = prefs.getLong(KEY_LIC_EXPIRE_AT, 0L)
        return Status(
            code = prefs.getString(KEY_LIC_CODE, "").orEmpty(),
            inviteUrl = prefs.getString(KEY_LIC_INVITE_URL, "").orEmpty(),
            invitedCount = prefs.getInt(KEY_LIC_INVITED_COUNT, 0),
            bonusDays = prefs.getInt(KEY_LIC_BONUS_DAYS, 0),
            expireAt = expireAt,
            canBind = prefs.getBoolean(KEY_LIC_CAN_BIND, false),
            known = expireAt > 0L
        )
    }

    private fun writeCache(context: Context, status: Status, serverTime: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastServerTime = prefs.getLong(KEY_LIC_SERVER_TIME, 0L)
        prefs.edit {
            putLong(KEY_LIC_EXPIRE_AT, status.expireAt)
            putString(KEY_LIC_CODE, status.code)
            putString(KEY_LIC_INVITE_URL, status.inviteUrl)
            putInt(KEY_LIC_INVITED_COUNT, status.invitedCount)
            putInt(KEY_LIC_BONUS_DAYS, status.bonusDays)
            putBoolean(KEY_LIC_CAN_BIND, status.canBind)
            // 服务端时间只许往前走，防止拿一个旧响应把基准冲回去
            if (serverTime > lastServerTime) putLong(KEY_LIC_SERVER_TIME, serverTime)
        }
    }

    /* ==================== 与服务端同步 ==================== */

    /**
     * 打开 App 时同步一次授权状态：本次进程启动必查，之后按间隔查。
     * 失败只记时间，不打扰用户，也不影响使用。
     *
     * @param activity 活动
     * @param force 是否忽略间隔限制
     * @param onDone 同步结束（成功或失败）后的回调，在主线程执行
     */
    fun syncIfNeeded(activity: Activity, force: Boolean = false, onDone: (() -> Unit)? = null) {
        if (!force) {
            if (syncing) return
            val now = SystemClock.elapsedRealtime()
            if (lastSyncAt != 0L && now - lastSyncAt < SYNC_INTERVAL_MS) return
            if (lastFailAt != 0L && now - lastFailAt < RETRY_INTERVAL_MS) return
        }
        val appCtx = activity.applicationContext
        val lifecycleScope = (activity as? LifecycleOwner)?.lifecycleScope ?: scope
        syncing = true
        lifecycleScope.launch {
            try {
                fetchStatus(appCtx).onSuccess { (status, serverTime) ->
                    lastSyncAt = SystemClock.elapsedRealtime()
                    lastFailAt = 0L
                    writeCache(appCtx, status, serverTime)
                    statusFlow.value = status
                    applyBlocked(appCtx)
                }.onFailure {
                    lastFailAt = SystemClock.elapsedRealtime()
                    // 连不上服务器：保持本地缓存的结论，不做任何限制变更
                    Log.w(TAG, "license sync failed", it)
                }
                onDone?.invoke()
            } finally {
                // 页面销毁把协程取消掉时也要复位，否则这个标记会永久挡住后续同步
                syncing = false
            }
        }
    }

    /**
     * 填写好友的邀请码。
     *
     * @param activity 活动
     * @param code 邀请码
     * @param onResult 结果回调（主线程）：成功返回 null，失败返回可直接展示的原因
     */
    fun bind(activity: Activity, code: String, onResult: (String?) -> Unit) {
        val appCtx = activity.applicationContext
        val lifecycleScope = (activity as? LifecycleOwner)?.lifecycleScope ?: scope
        lifecycleScope.launch {
            postBind(appCtx, code.trim().uppercase())
                .onSuccess { (status, serverTime) ->
                    lastSyncAt = SystemClock.elapsedRealtime()
                    writeCache(appCtx, status, serverTime)
                    statusFlow.value = status
                    applyBlocked(appCtx)
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: appCtx.getString(R.string.license_network_error)) }
        }
    }

    /* 拉取授权状态 */
    private suspend fun fetchStatus(context: Context): Result<Pair<Status, Long>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(
                    SERVER_BASE_URL + "/api/license?deviceId=" +
                        URLEncoder.encode(deviceId(context), "UTF-8")
                )
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    parseResponse(connection)
                } finally {
                    connection.disconnect()
                }
            }
        }

    /* 提交邀请码 */
    private suspend fun postBind(context: Context, code: String): Result<Pair<Status, Long>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(SERVER_BASE_URL + "/api/license/bind")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    val payload = JSONObject()
                        .put("deviceId", deviceId(context))
                        .put("code", code)
                    connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                    parseResponse(connection)
                } finally {
                    connection.disconnect()
                }
            }
        }

    /**
     * 解析响应：200 转成状态，其它状态码把服务端给的 reason 原样抛出去展示给用户。
     * 失败响应要读 errorStream，读 inputStream 会直接抛 IOException 丢掉 reason。
     */
    private fun parseResponse(connection: HttpURLConnection): Pair<Status, Long> {
        val code = connection.responseCode
        val body = if (code == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (code != HttpURLConnection.HTTP_OK || json?.optBoolean("ok") != true) {
            throw IllegalStateException(json?.optString("reason").takeUnless { it.isNullOrEmpty() } ?: "HTTP $code")
        }
        val status = Status(
            code = json.optString("code"),
            inviteUrl = json.optString("inviteUrl").ifEmpty { URL_INVITE_PREFIX + json.optString("code") },
            invitedCount = json.optInt("invitedCount"),
            bonusDays = json.optInt("bonusDays"),
            expireAt = json.optLong("expireAt"),
            canBind = json.optBoolean("canBind"),
            known = json.optLong("expireAt") > 0L
        )
        return status to json.optLong("serverTime")
    }

    /** 设备标识：与统计上报保持一致，用 ANDROID_ID（同签名同设备重装、清数据都不变） */
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /** 展示用的剩余天数，界面直接取这个值 */
    fun remainDays(context: Context): Int = statusFlow.value.remainDays(effectiveNow(context))
}
