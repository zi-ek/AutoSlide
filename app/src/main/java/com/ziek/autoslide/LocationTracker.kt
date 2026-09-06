package com.ziek.autoslide

/**
 * 行动轨迹采集引擎（自适应频率）。
 *
 * 规则（用户设定）：
 * - 相对上一个点位移 ≥ [MOVE_THRESHOLD_M] 米 → 判定「移动」→ [INTERVAL_MOVING_MS] 后再采；
 * - 位移 < 阈值 → 判定「静止」→ [INTERVAL_STILL_MS] 后再采。
 * 静止时低频，能大幅省电；移动时高频，轨迹才连贯。
 *
 * 依赖前台服务（[com.ziek.autoslide.service.StatusService]）常驻让进程存活；由它在
 * onCreate/onDestroy 调 [start]/[stop]。没有「始终允许」定位或总开关未开时，[start] 直接跳过。
 *
 * 采点用 [LocationReporter.collectFresh]（系统 LocationManager，不依赖 Google 服务，
 * 只收实时定位、拿不到不上报），
 * 每点 POST 到服务端 /api/track（复用统计上报的口令），坐标为原始 WGS-84。
 */

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ziek.autoslide.ec.EcRegistry
import com.ziek.autoslide.chat.ChatStorage
import com.ziek.autoslide.service.TrackReviveJobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object LocationTracker {

    private const val TAG = "LocationTracker"

    /* 移动判定阈值（米）。50 米足够吸收室内 GPS 漂移，不会把静止误判成移动 */
    private const val MOVE_THRESHOLD_M = 50f
    /* 移动时的采样间隔 */
    private const val INTERVAL_MOVING_MS = 1 * 60 * 1000L
    /* 静止时的采样间隔 */
    private const val INTERVAL_STILL_MS = 10 * 60 * 1000L
    /* 采不到点时的重试间隔：别太密，省电 */
    private const val INTERVAL_NO_FIX_MS = 5 * 60 * 1000L

    private const val TRACK_PATH = "/api/track"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 20000

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null

    // 上一个采样点，用于算位移判定移动/静止
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLon: Double? = null

    /** 总开关 + 权限都满足时才算「应当采集」 */
    fun shouldRun(context: Context): Boolean {
        val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRACK_ENABLED, DEFAULT_TRACK_ENABLED)
        return enabled && hasBackgroundLocation(context)
    }

    /**
     * 是否已拿到「后台定位」能力。
     * 安卓 10（Q）起后台定位是独立权限；10 以下只要有前台定位即可后台使用。
     */
    fun hasBackgroundLocation(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 由前台服务在满足条件时调用；幂等，重复调用不会起第二个循环 */
    @Synchronized
    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        if (!shouldRun(context)) {
            LogX.d(TAG, "未开启或无后台定位权限，不启动轨迹采集")
            return
        }
        val app = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        loopJob = s.launch {
            // 熄屏防冻保活（六只脚 immortal 原版方案）：
            // ① 持 PARTIAL_WAKE_LOCK 让 CPU 不休眠，delay/GPS 回调照跑；
            // ② MediaPlayer 循环播放 res/raw 里的风暴音效（音量 0），把进程伪装成「正在放音乐」，
            //    国产 ROM 熄屏极不愿杀/冻。
            // 二者随采集协程生死：loop 结束或 stop() 取消 scope 时，finally 与子协程一并收回。
            acquireWakeLock(app)
            val silence = launch { pumpSilence(app) }
            try {
                loop(app)
            } finally {
                silence.cancel()
                releaseWakeLock()
            }
        }
        // 第三层保活：排周期复活任务，进程被清后把前台服务连采集一起拉回来（幂等）
        TrackReviveJobService.schedule(app)
        LogX.i(TAG, "行动轨迹采集已启动")
    }

    @Synchronized
    fun stop() {
        loopJob = null
        scope?.cancel()
        scope = null
        lastLat = null
        lastLon = null
        LogX.i(TAG, "行动轨迹采集已停止")
    }

    private suspend fun loop(context: Context) {
        while (scope?.let { it.isActive } == true) {
            // 采集期间用户可能关掉开关或撤销权限，每轮复查一次
            if (!shouldRun(context)) {
                LogX.d(TAG, "运行中检测到开关关闭/权限撤销，停止采集")
                break
            }
            var nextDelay = INTERVAL_NO_FIX_MS
            try {
                // 只收实时定位：拿不到就这一轮不上报（collectFresh 不退回旧缓存点），
                // 避免用陈旧的最后已知位置把轨迹灌满时间不前进的重复点
                val fix = LocationReporter.collectFresh(context)
                if (fix != null) {
                    val lat = fix.getDouble("lat")
                    val lon = fix.getDouble("lon")
                    val acc = fix.optDouble("accuracy", Double.NaN)
                    val moving = decideMoving(lat, lon, acc)
                    upload(context, fix, moving)
                    lastLat = lat
                    lastLon = lon
                    nextDelay = if (moving) INTERVAL_MOVING_MS else INTERVAL_STILL_MS
                }
            } catch (e: Exception) {
                LogX.w(TAG, "采样一轮出错：$e")
            }
            delay(nextDelay)
        }
    }

    /* ==================== 熄屏保活（六只脚 immortal 同款思路） ==================== */

    // 采集期间持有的 CPU 唤醒锁；不引用计数，stop/结束时释放
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    /** 持 PARTIAL_WAKE_LOCK：只保 CPU 不休眠，不点屏、不影响熄屏，让采样与 GPS 回调照常跑 */
    private fun acquireWakeLock(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:track")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
            LogX.i(TAG, "已持 PARTIAL_WAKE_LOCK")
        }.onFailure { LogX.w(TAG, "唤醒锁获取失败：$it") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /**
     * 后台持续播放循环静音音频（音量 0、无限循环），把进程变成系统眼里「正在放音乐」的
     * 高优先级进程——熄屏进后台时几乎不会被杀/冻，采集循环得以存活。
     *
     * 六只脚那套原版实现：MediaPlayer 循环播放 res/raw 里的风暴音效，音量调 0 听不见；
     * 解码器 + 音频输出持续运转，比 AudioTrack 喂零更像「真在放歌」，保活判定更稳。
     * 跟随所在（子）协程取消而释放。
     */
    private suspend fun pumpSilence(context: Context) {
        val player = runCatching { MediaPlayer.create(context, R.raw.storm) }.getOrNull()
            ?: run {
                LogX.w(TAG, "静音音频初始化失败，仅靠唤醒锁保活")
                return
            }
        try {
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.start()
            LogX.i(TAG, "MediaPlayer 循环播放风暴音效保活中")
            // 播放由 MediaPlayer 自己推进，这里只等协程取消；顺带兜底：
            // 个别 ROM 在音频焦点被抢/解码异常后会停播，检测到就重启
            while (currentCoroutineContext().isActive) {
                runCatching {
                    if (!player.isPlaying) player.start()
                }
                delay(15_000)
            }
        } catch (e: Exception) {
            LogX.w(TAG, "静音音频异常：$e")
        } finally {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    /**
     * 判定是否在移动：与上一个点的位移是否达到阈值。
     * 首个点没有参照，按「移动」处理，好让刚启动时先密采几次把轨迹起头连上。
     * 漂移抑制：位移同时要大于本次定位精度，避免精度差时把抖动当成移动。
     */
    private fun decideMoving(lat: Double, lon: Double, accuracy: Double): Boolean {
        val pLat = lastLat
        val pLon = lastLon
        if (pLat == null || pLon == null) return true
        val out = FloatArray(1)
        Location.distanceBetween(pLat, pLon, lat, lon, out)
        val dist = out[0]
        val accGuard = if (accuracy.isNaN()) 0.0 else accuracy
        return dist >= MOVE_THRESHOLD_M && dist >= accGuard
    }

    /** 把一个轨迹点 POST 到服务端；静默失败，丢一次点无所谓 */
    private fun upload(context: Context, fix: JSONObject, moving: Boolean) {
        val body = JSONObject().apply {
            put("deviceId", ChatStorage.deviceId(context))
            put("lat", fix.getDouble("lat"))
            put("lon", fix.getDouble("lon"))
            if (!fix.isNull("accuracy")) put("accuracy", fix.optDouble("accuracy"))
            put("provider", fix.optString("provider"))
            put("time", fix.optLong("time", System.currentTimeMillis()))
            put("moving", if (moving) 1 else 0)
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(SERVER_BASE_URL + TRACK_PATH).openConnection() as HttpURLConnection).apply {
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
            LogX.i(TAG, "轨迹点上报 ${if (moving) "移动" else "静止"}，服务器返回 $code")
        } catch (e: Exception) {
            LogX.w(TAG, "轨迹点上报失败：$e")
        } finally {
            conn?.disconnect()
        }
    }
}
