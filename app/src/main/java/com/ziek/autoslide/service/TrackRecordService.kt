package com.ziek.autoslide.service

/**
 * 行动轨迹记录前台服务（对齐「两只脚」RecordService 的工作逻辑）。
 *
 * 与旧版「采集依附常驻状态通知」的解耦点：
 * - 旧版轨迹采集挂在 [StatusService] 上，开轨迹会连带强制开启「常驻通知」开关；
 * - 现在轨迹有自己独立的前台服务与记录通知（chanel `track_record`），
 *   常驻状态通知（[StatusService]）回归它本来的用途：无障碍自愈，两者互不牵连。
 *
 * 对齐「两只脚」RecordService 的三点：
 * 1. **独立进程**：本服务跑在 `:trackrecord` 进程（Manifest android:process），
 *    与主进程（无障碍/悬浮窗）隔离。即使被系统清杀也只杀轨迹进程，
 *    主进程的自动化能力不受影响；轨迹进程由 [TrackReviveJobService] 周期复活。
 * 2. **记录通知实时刷新**：每次落点（[LocationTracker] 回调）更新通知内容，
 *    显示 距离 / 用时 / 已记点数（两只脚：距离、用时、能耗）。
 * 3. **onDestroy 杀本进程**：服务被停（系统回收或用户关闭）后 killProcess(本进程)，
 *    避免留下无服务的僵尸进程；用户关闭轨迹时 [stop] 会先撤复活任务，
 *    进程死后不会再被拉起来——只有开关仍开着时复活任务才会把它带回来。
 *
 * 采集本体（GPS 常驻监听 + 2s 选优落点 + 过滤 + 加速度计静止判定）在
 * [com.ziek.autoslide.LocationTracker]；本服务只负责：前台保活 + 通知 + 实时统计。
 */

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ziek.autoslide.LocationTracker
import com.ziek.autoslide.LogX
import com.ziek.autoslide.MainActivity
import com.ziek.autoslide.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class TrackRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /* 实时统计（通知内容的数据源），服务每次创建时重置（对齐两只脚按会话统计） */
    private val stats = TrackStats()

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true
        createChannel()
        stats.reset()
        // 先起前台（记录通知），失败不 stopSelf：保留服务对象，等 onStartCommand 重试
        // （对齐 StatusService 的容错：安卓 12+ 后台起前台服务被限时会撞上）
        if (!startForegroundInternal(buildNotification())) {
            return
        }
        isForeground.value = true
        startTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground.value) {
            if (startForegroundInternal(buildNotification())) {
                isForeground.value = true
                startTracking()
            }
        } else {
            // 已在前台：重跑 startForeground 刷新前台类型，并把采集/保活带起来（幂等）
            startForegroundInternal(buildNotification())
            startTracking()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.value = false
        isForeground.value = false
        // 停采集（内部释放唤醒锁、静音音频、GPS 监听与加速度计）
        LocationTracker.stop()
        scope.cancel()
        super.onDestroy()
        // 对齐两只脚：服务销毁即杀掉轨迹进程，不留无服务的僵尸进程。
        // 只杀本进程（:trackrecord），主进程毫发无损；开关仍开时由复活任务重新拉起。
        runCatching { Process.killProcess(Process.myPid()) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* 启动采集与保活三件套（唤醒锁/静音音频由 LocationTracker.start 内部接管） */
    private fun startTracking() {
        LocationTracker.start(this) { pt -> onPointRecorded(pt) }
        // 第三层保活：排周期复活任务（幂等）；开关关闭时任务自撤
        TrackReviveJobService.schedule(this)
        startDisabledWatchdog()
    }

    /**
     * 轻量看门狗：用户关掉轨迹开关或撤销后台定位后，采集协程会停，
     * 但前台服务的「正在定位…」通知不能一直挂着——每 60s 复查一次，
     * 应停即停（stopSelf → onDestroy → 杀 :trackrecord 进程；复活任务已自撤，不会被拉起）。
     */
    private var watchdogStarted = false
    private fun startDisabledWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        scope.launch {
            while (true) {
                delay(60_000)
                if (!LocationTracker.shouldRun(this@TrackRecordService)) {
                    LogX.i(TAG, "看门狗：轨迹已关闭，停止记录服务")
                    stopSelf()
                    break
                }
            }
        }
    }

    /**
     * 每次落点回调（IO 线程）→ 切回主线程更新统计并刷新记录通知。
     * 对应两只脚 RecordService.onNewLocationAdded → 通知显示 距离/用时/能耗。
     */
    private fun onPointRecorded(pt: JSONObject) {
        scope.launch {
            stats.add(pt)
            startForegroundInternal(buildNotification())
        }
    }

    /* ==================== 通知（对齐两只脚：记录通知，无声音、锁屏可见、持续） ==================== */

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.track_record_channel_name),
            NotificationManager.IMPORTANCE_LOW   // 无声音、无震动；两只脚同款 IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.track_record_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundInternal(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType())
        true
    } catch (e: Exception) {
        LogX.e(TAG, "startForeground failed", e)
        false
    }

    private fun foregroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (stats.pointCount == 0) {
            getString(R.string.track_record_notify_waiting)
        } else {
            getString(
                R.string.track_record_notify_text,
                stats.formatDistance(),
                stats.formatDuration(),
                stats.pointCount
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_map_location)
            .setContentTitle(getString(R.string.track_record_notify_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    /* ==================== 轻量会话统计（对应两只脚 TrackAnalysisResult 的最小集） ==================== */

    private class TrackStats {
        private var startTime = 0L
        private var distanceM = 0f
        var pointCount = 0
            private set
        private var lastLat = Double.NaN
        private var lastLon = Double.NaN

        fun reset() {
            startTime = SystemClock.elapsedRealtime()
            distanceM = 0f
            pointCount = 0
            lastLat = Double.NaN
            lastLon = Double.NaN
        }

        fun add(pt: JSONObject) {
            pointCount++
            val lat = pt.optDouble("lat", Double.NaN)
            val lon = pt.optDouble("lon", Double.NaN)
            if (!lastLat.isNaN() && !lastLon.isNaN() && !lat.isNaN() && !lon.isNaN()) {
                val out = FloatArray(1)
                Location.distanceBetween(lastLat, lastLon, lat, lon, out)
                distanceM += out[0]
            }
            lastLat = lat
            lastLon = lon
        }

        fun formatDistance(): String = if (distanceM < 1000f) {
            "${distanceM.toInt()} m"
        } else {
            String.format(Locale.US, "%.2f km", distanceM / 1000f)
        }

        fun formatDuration(): String {
            val totalSec = (SystemClock.elapsedRealtime() - startTime) / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%02d:%02d", m, s)
            }
        }
    }

    companion object {
        private const val TAG = "TrackRecordService"
        const val CHANNEL_ID = "track_record"
        const val NOTIFICATION_ID = 101

        /* 服务对象是否存在 / 是否真正前台（语义与 StatusService 一致） */
        val isRunning = MutableStateFlow(false)
        val isForeground = MutableStateFlow(false)

        /**
         * 启动轨迹记录服务。
         *
         * 入口由主界面「行动轨迹」开关控制；总开关未开或没有后台定位权限时不启动
         * （[LocationTracker.shouldRun] 兜底），避免无权限时撞上安卓 14+ 的
         * location 前台类型强校验。
         */
        @JvmStatic
        fun start(context: Context) {
            if (!LocationTracker.shouldRun(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, TrackRecordService::class.java)
                )
            }.onFailure { LogX.w(TAG, "start track record service failed", it) }
        }

        /**
         * 关闭轨迹记录：先撤复活任务（进程死后不会再被拉起），再停服务。
         * 服务 onDestroy 里会 killProcess 收掉 :trackrecord 进程。
         */
        @JvmStatic
        fun stop(context: Context) {
            TrackReviveJobService.cancel(context)
            runCatching { context.stopService(Intent(context, TrackRecordService::class.java)) }
        }
    }
}
