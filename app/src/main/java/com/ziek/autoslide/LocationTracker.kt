package com.ziek.autoslide

/**
 * 行动轨迹采集引擎（对齐「两只脚」LocationRecordService 的工作逻辑）。
 *
 * 与旧版「轮询拉取」的根本区别：
 * - 旧版：每 1~10 分钟调一次 [LocationReporter.collectFresh]，主动求一个新定位；
 * - 现在（两只脚同款）：**GPS 常驻监听推送**（requestLocationUpdates 500ms）+ 每
 *   [TICK_MS]（2s）定时从缓冲里挑精度最好的一个点，过过滤规则后落盘/上报。
 *
 * 事件驱动的好处：信号一到系统就推过来，不再有「20s 等一枪 fix」的空窗；
 * 采样密度完全交给过滤规则（时间/距离/精度/静止漂移），信号好轨迹密，信号差自然稀疏。
 *
 * 省电（运动门控）：GPS 射频只要在监听就持续通电，是耗电大头（实测约占整机 2%+/小时）。
 * 因此**不动就给 GPS 断电**——由加速度计判定静止、停稳 [STILL_GRACE_MS] 后 [manageGps] 撤掉
 * 定位监听；重新移动（加速度计确认，或 GPS 测速刷新）再上电；静止期间每 [GPS_RECHECK_INTERVAL_MS]
 * 开一个 [GPS_PROBE_MS] 探测窗口，兜住加速度计漏判的平稳移动（高铁/电梯）。加速度计始终在跑当门铃。
 *
 * 采集与上报解耦不变：落点先写本地队列 [TrackBuffer]（离线不丢），由 [TrackBuffer.flush]
 * 每 [FLUSH_INTERVAL_MS] 整批冲刷到服务端 /api/track/batch。
 *
 * 依赖宿主：由 [com.ziek.autoslide.service.TrackRecordService]（进程 :trackrecord）在
 * onCreate/onDestroy 调 [start]/[stop]。没有「始终允许」定位或总开关未开时 [start] 直接跳过。
 */

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sqrt

object LocationTracker {

    private const val TAG = "LocationTracker"

    /* ==================== 采样（对齐两只脚 LocationRecordService） ==================== */

    /* GPS 常驻监听的最小更新间隔（两只脚: requestLocationUpdates("gps", 5000ms, 0m, listener)） */
    private const val GPS_MIN_TIME_MS = 5000L

    /* 网络兜底监听间隔（两只脚 LocationHelper 同款 5000ms）：城市峡谷/隧道里 GPS 失锁时
       兜住「至少还有一条」，过不过得了过滤规则另说（精度闸一视同仁） */
    private const val NET_MIN_TIME_MS = 5000L

    /* 定时选优落点周期（两只脚: Timer period = mMinTimeAllow 默认 10000ms）：
       每周期从缓冲挑精度最好的一个点 → 过过滤 → 落点 → 清空缓冲 */
    private const val TICK_MS = 15000L

    /* 缓冲上限：GPS 500ms 下 2s 最多 4~5 条，给足余量防止异常时无限膨胀 */
    private const val MAX_PENDING = 32

    /* ==================== 过滤规则（两只脚 locationFilter 默认值） ==================== */

    /* 低于该速度的点拒收（两只脚 mMinSpeedAllow=0，恒不触发，保留对称性） */
    private const val MIN_SPEED_ALLOW = 0f
    /* 高于该速度（100 m/s = 360 km/h）视为荒唐点拒收 */
    private const val MAX_SPEED_ALLOW = 100f
    /* 精度差于此的点拒收（两只脚 mMaxAccuracyAllow=50） */
    private const val MAX_ACCURACY_ALLOW = 50f
    /* 距上次落点至少这么久（两只脚 mMinTimeAllow=2000，实际判定用 -300 余量） */
    private const val MIN_TIME_ALLOW_MS = 2000L
    private const val TIME_SLACK_MS = 300L
    /* 距上次落点至少这么远（两只脚 mMinDistanceAllow=3m），滤掉静止抖动 */
    private const val MIN_DISTANCE_ALLOW = 3f
    /* 静止状态下位移超过该值且无速度 → 判为漂移拒收（两只脚 mStationaryDistanceThreshold=5） */
    private const val STATIONARY_DRIFT_ALLOW = 5f

    /* 进入缓冲前的垃圾闸：荒唐精度（个别 ROM 的 fused 会冒 acc=天文数字）直接无视。
       与上面的 50m 落点闸不冲突：这是挡垃圾，那是挑好点 */
    private const val MAX_ACCEPTABLE_ACCURACY_M = 2000f

    /* ==================== 静止判定（两只脚 MotionDetector 同款算法） ==================== */

    /* 加速度幅值平滑系数：smooth = 0.9*old + 0.1*new */
    private const val SMOOTH_ALPHA = 0.9f
    /* 平滑后幅值低于该值视为「可能静止」 */
    private const val STATIONARY_AMPLITUDE = 0.15f
    /* 持续低于阈值这么久（2.5s）才确认静止 */
    private const val STATIONARY_CONFIRM_MS = 2500L

    /* ==================== 运动门控 GPS（省电核心：不动就给 GPS 断电） ==================== */

    /* 停止移动后仍保持 GPS 开启的缓冲期：红灯、短暂驻足不至于立刻断电又重连（避免抖动） */
    private const val STILL_GRACE_MS = 60 * 1000L
    /* GPS 断电后，每隔这么久开一个探测窗口，接住加速度计漏判的平稳移动（高铁/电梯） */
    private const val GPS_RECHECK_INTERVAL_MS = 5 * 60 * 1000L
    /* 每个探测窗口的时长：给足暖启动拿到一两枪 fix，仍静止就再断电 */
    private const val GPS_PROBE_MS = 45 * 1000L
    /* GPS 测速高于此（1 m/s≈3.6km/h）视为「确实在动」：即便加速度计误判静止也据此保持 GPS */
    private const val MOVING_SPEED_MPS = 1.0f

    /* ==================== 静止自动关闭 ==================== */

    /* 连续静止（加速度计确认无有效移动）超过该时长，主动关掉轨迹开关：
       静止期间 GPS 已被运动门控断电，这一步进一步把整个前台服务/唤醒锁/加速度计也收掉；
       关掉后由服务看门狗收尾停服务 */
    private const val AUTO_OFF_STILL_MS = 30 * 60 * 1000L

    /* ==================== 上报节流 ==================== */

    /* 本地积压冲刷周期：每 30s 尝试一次批量上报（旧版 1~10min；改密后轨迹更实时，
       又不至于 2s 一 POST 把服务器打爆） */
    private const val FLUSH_INTERVAL_MS = 30 * 1000L

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null

    /* GPS/网络常驻监听（进程内仅一份，随采集协程注册/注销） */
    private var lm: LocationManager? = null
    private var locationListener: LocationListener? = null

    /* 加速度计静止判定（进程内仅一份） */
    private var sensorManager: SensorManager? = null
    private var accelListener: SensorEventListener? = null

    /* 原始定位缓冲：GPS/网络回调只往这里塞，定时器每 TICK 挑最优、清空 */
    private val pending = CopyOnWriteArrayList<Location>()

    /* 上次通过过滤的落点（过滤规则的参照系） */
    @Volatile private var lastPoint: Location? = null
    @Volatile private var lastAddTime = 0L

    /* 由加速度计实时维护：当前是否已确认静止 */
    @Volatile private var isStationary = false

    /* 最后移动时刻（elapsedRealtime）；连续静止超过 [AUTO_OFF_STILL_MS] 时自动关开关 */
    @Volatile private var lastMoveTime = 0L

    /* 运动门控：GPS 断电的时刻 / 当前探测窗口的开始时刻（0 = 不在对应状态） */
    @Volatile private var gpsOffSince = 0L
    @Volatile private var gpsProbeStart = 0L

    /* 静止自动关的计时基准：最后一次「真实位移落点」的墙钟时刻（持久化）。
       只在真的走了 ≥3m、点通过过滤后才前进，免疫 GPS 测速噪声与加速度计抖动 */
    @Volatile private var lastRecordedMoveAt = 0L

    /* 落点回调（TrackRecordService 用来刷新记录通知）；从采集协程线程调用 */
    @Volatile private var onPoint: ((JSONObject) -> Unit)? = null

    /** 总开关 + 权限都满足时才算「应当采集」。开关走跨进程一致的 [TrackState]（见其说明）。 */
    fun shouldRun(context: Context): Boolean {
        return TrackState.isEnabled(context) && hasBackgroundLocation(context)
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

    /** 由轨迹前台服务在满足条件时调用；幂等，重复调用不会起第二个循环 */
    @Synchronized
    fun start(context: Context, onPoint: ((JSONObject) -> Unit)? = null) {
        if (loopJob?.isActive == true) {
            // 已在采集：只换回调（服务重连时通知刷新仍要生效）
            this.onPoint = onPoint
            return
        }
        if (!shouldRun(context)) {
            LogX.d(TAG, "未开启或无后台定位权限，不启动轨迹采集")
            return
        }
        val app = context.applicationContext
        this.onPoint = onPoint
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        loopJob = s.launch {
            // 熄屏防冻保活（两只脚 immortal 原版方案）：
            // ① 持 PARTIAL_WAKE_LOCK 让 CPU 不休眠，GPS 回调与定时器照跑；
            // ② MediaPlayer 循环播放 res/raw 里的风暴音效（音量 0），把进程伪装成「正在放音乐」，
            //    国产 ROM 熄屏极不愿杀/冻。
            acquireWakeLock(app)
            val silence = launch { pumpSilence(app) }
            try {
                loop(app)
            } finally {
                silence.cancel()
                releaseWakeLock()
            }
        }
        // 第三层保活：排周期复活任务，进程被清后把轨迹前台服务连采集一起拉回来（幂等）
        TrackReviveJobService.schedule(app)
        LogX.i(TAG, "行动轨迹采集已启动（事件驱动模式）")
    }

    @Synchronized
    fun stop() {
        loopJob = null
        scope?.cancel()
        scope = null
        lastPoint = null
        lastAddTime = 0L
        isStationary = false
        lastMoveTime = 0L
        gpsOffSince = 0L
        gpsProbeStart = 0L
        lastRecordedMoveAt = 0L
        onPoint = null
        pending.clear()
        unregister()
        LogX.i(TAG, "行动轨迹采集已停止")
    }

    /* ==================== 主循环（对齐两只脚 TimerTask：每 TICK 选优落点） ==================== */

    /** 写入轨迹总开关（跨进程一致存储；静止自动关在 :trackrecord 里调用它也能被主进程立即看到） */
    fun setTrackEnabled(context: Context, enabled: Boolean) {
        TrackState.setEnabled(context, enabled)
    }

    /** 持久化「最后真实位移时刻」，供进程被杀重启后自动关计时续算（跨进程一致存储） */
    private fun persistLastMove(context: Context, atMs: Long) {
        TrackState.setLastMoveAt(context, atMs)
    }

    /**
     * 用户「手动/远程打开」轨迹开关时调用：把自动关计时基准重置为现在。
     *
     * 与「进程复活」区分开——复活时 [loop] 会沿用持久化的旧值继续累积静止时长；
     * 而用户是刚主动打开，必须重新开始 30 分钟计时，否则会用上次遗留的陈旧时刻导致「一开就秒关」。
     */
    fun markUserEnabled(context: Context) {
        val now = System.currentTimeMillis()
        lastRecordedMoveAt = now
        persistLastMove(context, now)
        LogX.i(TAG, "用户开启轨迹，重置静止自动关计时")
    }

    private suspend fun loop(context: Context) {
        registerLocationListeners(context)
        registerAccelerometer(context)
        var lastFlush = 0L
        // 启动时刻视为「刚移动过」，防止开局把 lastMoveTime=0 误判成已静止 30 分钟
        lastMoveTime = SystemClock.elapsedRealtime()
        // 自动关计时基准：读持久化值——进程被国产 ROM 杀了重启后接着算、不归零，
        // 这样「静止满 30 分钟」能跨进程复活累积（否则 vivo 频繁杀进程，每次复活都从头算，永不触发）。
        // 只有从没记录过（首次）才以现在为准；「用户重新打开开关」的重置交给 markUserEnabled，不在这里做。
        run {
            val saved = TrackState.lastMoveAt(context)
            if (saved == 0L) {
                lastRecordedMoveAt = System.currentTimeMillis()
                persistLastMove(context, lastRecordedMoveAt)
            } else {
                lastRecordedMoveAt = saved
            }
        }
        try {
            while (scope?.let { it.isActive } == true) {
                // 采集期间用户可能关掉开关或撤销权限，每轮复查一次
                if (!shouldRun(context)) {
                    LogX.d(TAG, "运行中检测到开关关闭/权限撤销，停止采集")
                    break
                }
                val now = SystemClock.elapsedRealtime()
                // 运动门控用的移动时刻：加速度计一动就刷新，供 manageGps 的缓冲期判断（可以噪声敏感）
                if (!isStationary) lastMoveTime = now
                // 静止自动关闭：以「最后一次真实位移落点」的墙钟时刻计时——
                // 它只在真走了 ≥3m 落点时才前进，免疫 GPS 测速噪声/加速度计抖动，且跨进程重启不归零；
                // 再要求当前加速度计确认静止，避免室内无 GPS 的移动被误判成静止而误关。
                if (isStationary && System.currentTimeMillis() - lastRecordedMoveAt >= AUTO_OFF_STILL_MS) {
                    LogX.i(TAG, "已连续静止 ${AUTO_OFF_STILL_MS / 60_000} 分钟无位移，自动关闭行动轨迹开关")
                    setTrackEnabled(context, false)
                    break
                }
                // 运动门控：不动就给 GPS 断电，动了/探测到再上电（省电核心）
                manageGps(context, now)
                try {
                    // 1) 从缓冲挑精度最好的一个点（两只脚 getBestAccuracyLocation）
                    val best = pickBestFromPending()
                    if (best != null) {
                        // GPS 测速确认在动 → 刷新移动时刻，接住加速度计误判静止的平稳移动（高铁/电梯），
                        // 让门控继续给 GPS 供电
                        if (best.hasSpeed() && best.speed > MOVING_SPEED_MPS) lastMoveTime = now
                        // 2) 过过滤规则（两只脚 filteredLocationChanged → locationFilter）
                        if (locationFilter(best)) {
                            val moving = if (isStationary) 0 else 1
                            val pt = buildPoint(best, moving)
                            TrackBuffer.append(context, pt)
                            lastPoint = best
                            lastAddTime = best.time
                            // 只有加速度计也确认在动(!isStationary)时，才把这个落点当「真实移动」推进自动关计时。
                            // 静止时 GPS 漂移也会落点(3~5m 噪声过得了 3m 距离闸)，若一并推进就会反复清零计时器、
                            // 让自动关永不触发——这正是上一版没生效的根因之一。
                            if (!isStationary) {
                                lastRecordedMoveAt = best.time
                                persistLastMove(context, best.time)
                            }
                            onPoint?.invoke(pt)
                            LogX.i(TAG, "轨迹落点(${best.provider} ±${best.accuracy}m, " +
                                "moving=$moving): ${best.latitude},${best.longitude}")
                        } else {
                            LogX.d(TAG, "缓冲点未过过滤规则，本轮不落点")
                        }
                        // 3) 清空缓冲，等下个周期的推送（两只脚同款：取最优后清）
                        pending.clear()
                    } else {
                        LogX.d(TAG, "本周期缓冲为空（信号弱或无新定位）")
                    }

                    // 上报节流：每 FLUSH_INTERVAL_MS 冲刷一次本地积压（无网自动跳过）
                    if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                        TrackBuffer.flush(context)
                        lastFlush = now
                    }
                } catch (e: Exception) {
                    LogX.w(TAG, "采样一轮出错：$e")
                }
                delay(TICK_MS)
            }
        } finally {
            unregister()
        }
    }

    /* ==================== 常驻定位监听（两只脚 requestLocationUpdates 同款） ==================== */

    @android.annotation.SuppressLint("MissingPermission")
    private fun registerLocationListeners(context: Context) {
        if (locationListener != null) return
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                // 垃圾闸：荒唐精度直接无视（个别 ROM 的 fused 会冒 acc=天文数字，实测 86400000）
                if (loc.hasAccuracy() && loc.accuracy > MAX_ACCEPTABLE_ACCURACY_M) return
                // 用系统此刻统一打时间戳（两只脚 onLocationChanged 里 setTime(now) 同款），
                // 避免个别 provider 的旧时间戳污染时间闸与轨迹顺序
                loc.time = System.currentTimeMillis()
                pending.add(loc)
                if (pending.size > MAX_PENDING) pending.removeAt(0)
            }

            // 下面三个在 API 30 起是接口默认方法，但 minSdk 26 的旧机上必须有实体实现，
            // 否则运行时 AbstractMethodError，因此显式留空覆盖（见 LocationReporter 同款注释）。
            @Deprecated("API 29 起系统不再回调，保留仅为兼容 minSdk 26 的旧机")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        // 主通道：GPS 常驻推送（对齐两只脚 500ms）；Looper 用主线程（回调极轻）
        runCatching {
            mgr.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_MIN_TIME_MS, 0f, listener, Looper.getMainLooper()
            )
        }.onFailure { LogX.w(TAG, "GPS 常驻监听注册失败：$it") }
        // 兜底通道：网络定位（两只脚 LocationHelper 同款 2000ms）；过不过得了 50m 精度闸另说
        runCatching {
            mgr.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, NET_MIN_TIME_MS, 0f, listener, Looper.getMainLooper()
            )
        }.onFailure { LogX.w(TAG, "网络定位常驻监听注册失败：$it") }
        lm = mgr
        locationListener = listener
        LogX.i(TAG, "GPS(${GPS_MIN_TIME_MS}ms) + NETWORK(${NET_MIN_TIME_MS}ms) 常驻监听已注册")
    }

    /** 只给定位监听（GPS/NETWORK）下电，保留加速度计继续当门卫；门控与全量停止共用 */
    private fun unregisterLocationListeners() {
        val l = locationListener ?: return
        runCatching { lm?.removeUpdates(l) }
        lm = null
        locationListener = null
        LogX.i(TAG, "GPS/NETWORK 监听已下电")
    }

    private fun unregister() {
        unregisterLocationListeners()
        val a = accelListener
        if (a != null) {
            runCatching { sensorManager?.unregisterListener(a) }
            sensorManager = null
            accelListener = null
        }
    }

    /* ==================== 运动门控（省电核心：不动就给 GPS 断电） ==================== */

    /**
     * 按「是否在动」给 GPS/NETWORK 监听上下电，省掉静止时的 GPS 射频功耗
     * （射频瞬时功率是硬件定死的，只能靠减少通电时长来省）。
     *
     * - 在动（加速度计确认，或 GPS 测速刷新了 [lastMoveTime]）→ 保持监听开启；
     * - 刚停下 [STILL_GRACE_MS] 内 → 仍保持开启（红灯/驻足不来回抖动）；
     * - 静止够久 → 断电；此后每隔 [GPS_RECHECK_INTERVAL_MS] 开一个 [GPS_PROBE_MS] 的探测窗口，
     *   接住加速度计漏判的平稳移动，仍静止就再断电。
     *
     * 加速度计始终在跑（近乎不耗电），是唯一的「有人来了」门铃。
     */
    private fun manageGps(context: Context, now: Long) {
        val isOn = locationListener != null
        val movingRecently = !isStationary || (now - lastMoveTime) < STILL_GRACE_MS
        if (movingRecently) {
            if (!isOn) {
                registerLocationListeners(context)
                LogX.i(TAG, "检测到移动，GPS 上电")
            }
            gpsProbeStart = 0L
            gpsOffSince = 0L
            return
        }
        // 静止够久
        if (isOn) {
            val probing = gpsProbeStart != 0L
            if (!probing || now - gpsProbeStart >= GPS_PROBE_MS) {
                // 移动缓冲期刚过、或探测窗口结束仍静止 → 断电
                unregisterLocationListeners()
                pending.clear()
                gpsProbeStart = 0L
                gpsOffSince = now
            }
            // 否则：探测窗口内，保持开
        } else {
            // GPS 已断电：到点开一个探测窗口，核对是否在平稳移动
            if (gpsOffSince != 0L && now - gpsOffSince >= GPS_RECHECK_INTERVAL_MS) {
                registerLocationListeners(context)
                gpsProbeStart = now
                gpsOffSince = 0L
                LogX.d(TAG, "静止探测：临时开 GPS 核对是否在平稳移动")
            }
        }
    }

    /* ==================== 静止判定（两只脚 MotionDetector：加速度计平滑 + 阈值 + 确认时长） ==================== */

    private fun registerAccelerometer(context: Context) {
        if (accelListener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        var smooth = 0f
        var stillSince = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                // 幅值 = 模长与重力的差，静止时≈0，晃动时明显
                val delta = abs(magnitude - SensorManager.GRAVITY_EARTH)
                smooth = SMOOTH_ALPHA * smooth + (1 - SMOOTH_ALPHA) * delta
                val now = SystemClock.elapsedRealtime()
                if (smooth < STATIONARY_AMPLITUDE) {
                    if (stillSince == 0L) stillSince = now
                    if (now - stillSince >= STATIONARY_CONFIRM_MS) isStationary = true
                } else {
                    stillSince = 0L
                    isStationary = false
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        runCatching {
            // 回调走主线程 Looper：必须带 Handler，否则无 Looper 线程注册会抛异常导致静止判定整个失效（过去在 IO 协程中注册无法回调）；
            // 监听器只做轻量计算 + 写 volatile，主线程不卡
            sm.registerListener(
                listener, acc, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper())
            )
        }.onSuccess {
            sensorManager = sm
            accelListener = listener
            LogX.i(TAG, "加速度计静止判定已注册")
        }.onFailure { LogX.w(TAG, "加速度计注册失败，静止判定不可用：$it") }
    }

    /* ==================== 选优与过滤（两只脚 getBestAccuracyLocation + locationFilter） ==================== */

    /** 从缓冲挑精度最好的一个点：精度优先，其次时间新 */
    private fun pickBestFromPending(): Location? {
        if (pending.isEmpty()) return null
        var best: Location? = null
        for (loc in pending) {
            if (best == null || isMoreAccurate(loc, best)) best = loc
        }
        return best
    }

    private fun isMoreAccurate(cand: Location, cur: Location): Boolean {
        if (!cand.hasAccuracy()) return false
        if (!cur.hasAccuracy()) return true
        if (cand.accuracy != cur.accuracy) return cand.accuracy < cur.accuracy
        return cand.time > cur.time
    }

    /**
     * 过滤规则（对齐两只脚 locationFilter 默认值，判定顺序一致）：
     * 1. 速度低于下限（0）或高于上限（100 m/s）→ 拒；
     * 2. 精度差于 50m → 拒；
     * 3. 距上次落点不足 1700ms → 拒（时间太密）；
     * 4. 距上次落点不足 3m → 拒（距离太近，静止抖动）；
     * 5. 已确认静止、却位移 ≥5m 且无速度 → 拒（静止漂移）。
     */
    private fun locationFilter(loc: Location): Boolean {
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        if (speed < MIN_SPEED_ALLOW) return false
        if (speed > MAX_SPEED_ALLOW) return false
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_ALLOW) return false

        val last = lastPoint
        if (last != null) {
            if (loc.time - lastAddTime < MIN_TIME_ALLOW_MS - TIME_SLACK_MS) return false
            val out = FloatArray(1)
            Location.distanceBetween(last.latitude, last.longitude, loc.latitude, loc.longitude, out)
            val dist = out[0]
            if (dist < MIN_DISTANCE_ALLOW) return false
            if (isStationary && dist >= STATIONARY_DRIFT_ALLOW && speed <= 0f) return false
        }
        return true
    }

    /**
     * 采集结果 → 落盘/上报用的一个点。
     * 字段与批量口一致（deviceId 是设备级常量，冲刷时统一带，不逐点存）；
     * time 取定位自带的时刻（已在监听回调里统一为 now），攒着晚点补传也不会时间错位。
     */
    private fun buildPoint(loc: Location, moving: Int): JSONObject =
        JSONObject().apply {
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            if (loc.hasAccuracy()) put("accuracy", loc.accuracy.toDouble())
            put("provider", loc.provider.orEmpty())
            put("time", loc.time)
            put("moving", moving)
        }

    /* ==================== 熄屏保活（两只脚 immortal 同款思路） ==================== */

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
     * 两只脚原版实现：MediaPlayer 循环播放 assets/voice/storm.mp3，音量调 0 听不见；
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
}
