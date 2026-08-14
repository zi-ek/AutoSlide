package com.ziek.autoslide.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ziek.autoslide.A11yState
import com.ziek.autoslide.LogX
import com.ziek.autoslide.MainActivity
import com.ziek.autoslide.R

/**
 * 常驻后台前台服务：
 * - 状态栏常驻一条「自动滑屏器 · 无障碍服务运行中」通知
 * - 保持进程存活，让 AutoSlideService 的常驻「跳过」检测持续运行
 * - 通知栏提供「停止」按钮，点击后真正退出常驻
 */
class StatusService : Service() {

    companion object {
        const val CHANNEL_ID = "persistent_service"
        const val NOTIFICATION_ID = 100
        private const val REFRESH_INTERVAL_MS = 30_000L

        /* 常驻服务是否正在运行（自愈重启判断用） */
        @Volatile
        private var isRunning = false

        /**
         * 自愈重启：常驻服务不在运行时尝试拉起（移植 GKD StatusService.autoStart）。
         * 无通知权限或后台启动受限时静默跳过，由其它触点（磁贴/主界面）下次重试。
         */
        @JvmStatic
        fun autoStart(context: Context) {
            if (isRunning) {
                return
            }
            // Android 13+ 无通知权限无法启动前台服务
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, StatusService::class.java))
            }
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshForeground()
            // 定时自检：进程还活着但无障碍绑定已经死掉时（MIUI 常见），
            // 不必等用户下拉通知栏，这里直接修。无障碍正常时该调用会立刻返回。
            A11yState.fixRestartA11yService()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 恢复常驻「跳过」检测：StatusService 是常驻载体，被系统重启后把检测重新打开
        // （职责划分：StatusService 管常驻与健康检查，AutoSlideService 管无障碍自动化）
        AutoSlideService.getInstance()?.setPersistentSkipEnabled(true)
        // 被系统 START_STICKY 重启时也走一次自愈：此时无障碍多半也一起被杀了
        A11yState.fixRestartA11yService()
        return try {
            startForegroundInternal(buildNotification())
            // 定期刷新前台通知，让 MIUI 一直认为服务活跃（参考 GKD 的状态驱动刷新）
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
            START_STICKY
        } catch (e: Exception) {
            // Android 14+ 若系统限制「特殊用途前台服务」，不要直接放弃：
            // 先降级用无类型的前台服务重试一次，仍失败才退出常驻。
            LogX.e("StatusService", "startForeground specialUse failed, retry plain", e)
            return try {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, buildNotification())
                refreshHandler.removeCallbacks(refreshRunnable)
                refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
                START_STICKY
            } catch (e2: Exception) {
                LogX.e("StatusService", "startForeground fallback failed too", e2)
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    private fun startForegroundInternal(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshForeground() {
        try {
            startForegroundInternal(buildNotification())
        } catch (e: Exception) {
            // 刷新失败不影响，下一轮再试
            LogX.w("StatusService", "refresh foreground failed", e)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.status_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.status_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, PersistentStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_slide_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.status_stop), stopIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
