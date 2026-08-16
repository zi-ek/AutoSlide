package com.ziek.autoslide.service

/**
 * 常驻状态前台服务（对照 GKD 的 `service/StatusService.kt` 移植）
 *
 * 它的作用不是「免杀」，而是让进程成为 START_STICKY 的前台服务：
 * 被系统清理后系统会重新拉起服务 → 进程启动 → Application.onCreate → syncFixState → 无障碍自愈。
 *
 * 与 GKD 一致的几个细节：
 * - 通知内容由状态驱动刷新（无障碍是否运行 / 是否在启用列表 / 是否有写入安全设置权限），
 *   **没有定时刷新**，也不在这里做定时自检；
 * - 通知**没有「停止」按钮**（GKD 的 status 通知 stopService 为 null）；
 * - 不重写 onStartCommand，沿用 Service 默认的 START_STICKY。
 */

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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ziek.autoslide.A11yState
import com.ziek.autoslide.KEY_STATUS_SERVICE_ENABLED
import com.ziek.autoslide.LogX
import com.ziek.autoslide.MainActivity
import com.ziek.autoslide.PREFS_NAME
import com.ziek.autoslide.R
import com.ziek.autoslide.hasWriteSecureSettingsPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StatusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true
        createChannel()
        if (!startForegroundInternal(buildNotification(statusText()))) {
            // Android 12+ 在后台不允许调 startForeground（实测开机后由系统重启本服务、
            // 或磁贴从 force-stop 状态复活时都会撞上）。这里必须让 isForeground 保持 false，
            // 否则 needRestart 判定不成立，主界面回到前台时就再也不会重试，通知永远回不来。
            // 不 stopSelf：保留服务对象，等 onStartCommand 或主界面回到前台时重试
            return
        }
        isForeground.value = true
        startTextRefreshLoop()
    }


    /* 状态驱动刷新通知文案（GKD: combine(...).collect { startForeground() }） */
    private fun startTextRefreshLoop() {
        if (refreshStarted) return
        refreshStarted = true
        scope.launch {
            combine(
                A11yState.a11yRunningFlow,
                A11yState.a11yEnabledFlow
            ) { _, _ -> statusText() }.collect { text ->
                startForegroundInternal(buildNotification(text))
            }
        }
    }

    private var refreshStarted = false

    /**
     * 服务已存在时再次 startForegroundService 不会重跑 onCreate，
     * 所以补进前台的重试必须放在这里——否则一旦首次 startForeground 被后台限制拒绝，
     * 之后无论怎么调都救不回来。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground.value) {
            if (startForegroundInternal(buildNotification(statusText()))) {
                isForeground.value = true
                startTextRefreshLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.value = false
        isForeground.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* 通知副标题：对照 GKD statusTriple() 的分支 */
    private fun statusText(): String = when {
        A11yState.a11yRunningFlow.value -> getString(R.string.status_a11y_running)
        A11yState.a11yEnabledFlow.value -> getString(R.string.status_a11y_broken)
        hasWriteSecureSettingsPermission() -> getString(R.string.status_a11y_disabled)
        else -> getString(R.string.status_a11y_no_permission)
    }

    private fun startForegroundInternal(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            // 与 GKD 一致：交给 manifest 声明的类型，不在代码里写死
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            } else {
                0
            }
        )
        true
    } catch (e: Exception) {
        // 系统自动重启 START_STICKY 服务时不走应用侧的启动检查，权限也可能在检查后变化，
        // 这里必须兜底，否则会直接崩掉（GKD: NotificationDispatcher.startForeground 同款处理）
        LogX.e(TAG, "startForeground failed", e)
        false
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

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_slide_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    companion object {
        private const val TAG = "StatusService"
        const val CHANNEL_ID = "persistent_service"
        const val NOTIFICATION_ID = 100

        /* 对应 GKD 的 StatusService.isRunning：服务对象是否存在 */
        val isRunning = MutableStateFlow(false)

        /**
         * 是否真正处于前台（startForeground 成功、通知已显示）。
         *
         * 与 [isRunning] 分开的原因：服务被系统重启但 startForeground 被后台限制拒绝时，
         * 服务对象是存在的（isRunning=true）却没有通知。只看 isRunning 会误判成「已在运行」，
         * 导致回到前台后不再重试，常驻通知永久消失。
         */
        val isForeground = MutableStateFlow(false)

        /* 上次自动拉起的时间（GKD: lastAutoStart，1 秒节流） */
        private var lastAutoStart = 0L

        /**
         * 用户是否开启了常驻通知（对应 GKD 的 storeFlow.enableStatusService，同样默认关闭，
         * 由「必要权限」卡片里的常驻通知开关控制）。
         *
         * @param context 上下文
         */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_STATUS_SERVICE_ENABLED, false)

        /**
         * 记录用户是否希望常驻通知开启
         *
         * @param context 上下文
         * @param enabled 是否开启
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STATUS_SERVICE_ENABLED, enabled)
                .apply()
        }

        /* 对应 GKD 的 needRestart：开关已开、通知尚未真正显示、且两项前台服务权限都具备 */
        private fun needRestart(context: Context): Boolean =
            isEnabled(context) &&
                !isForeground.value &&
                hasNotificationPermission(context) &&
                hasSpecialUsePermission(context)

        /**
         * 自愈重启常驻服务（GKD: StatusService.autoStart）。
         * 1 秒节流；需要已有服务或前台可见才能自主启动，否则系统会拒绝并抛异常。
         *
         * @param context 上下文
         */
        @JvmStatic
        fun autoStart(context: Context) {
            if (System.currentTimeMillis() - lastAutoStart < 1000) return
            if (!needRestart(context)) return
            lastAutoStart = System.currentTimeMillis()
            start(context)
        }

        /**
         * 启动常驻服务
         *
         * @param context 上下文
         */
        @JvmStatic
        fun start(context: Context) {
            if (!hasNotificationPermission(context) || !hasSpecialUsePermission(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, StatusService::class.java)
                )
            }.onFailure { LogX.w(TAG, "start status service failed", it) }
        }

        /**
         * 关闭常驻服务（GKD: StatusService.stop）
         *
         * @param context 上下文
         */
        @JvmStatic
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, StatusService::class.java)) }
        }

        private fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun hasSpecialUsePermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                context.checkSelfPermission(
                    Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
                ) == PackageManager.PERMISSION_GRANTED
    }
}
