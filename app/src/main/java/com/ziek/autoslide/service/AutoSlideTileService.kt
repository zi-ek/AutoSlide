package com.ziek.autoslide.service

/**
 * 快捷设置磁贴（通知栏下拉里的“自动滑动”磁贴）
 *
 * 一键启动/停止悬浮窗服务，并在权限不足时引导用户打开主界面。
 */

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ziek.autoslide.A11yState
import com.ziek.autoslide.KEY_FLOATING_DESIRED
import com.ziek.autoslide.MainActivity
import com.ziek.autoslide.PREFS_NAME
import com.ziek.autoslide.R
import com.ziek.autoslide.isAccessibilityServicePermissionEnabled

/**
 * 自动滑动磁贴服务
 *
 * @author tianxing
 */
class AutoSlideTileService : TileService() {

    /* 开始监听 */
    override fun onStartListening() {
        super.onStartListening()
        updateTileState(FloatingWindowService.isRunning())
        reviveAfterKill()
    }

    /**
     * 磁贴复活（移植 GKD「无感保活」）：
     * 磁贴在通知面板可见时，下拉通知栏会绑定本服务——即使进程已被系统杀死，
     * 绑定前也会先把进程拉起。这里趁进程活着把常驻服务拉起来，
     * 并在无障碍「设置里开启但实际未连接」时踢活无障碍服务。
     */
    private fun reviveAfterKill() {
        val now = System.currentTimeMillis()
        if (now - lastA11yFixAt < A11Y_FIX_THROTTLE_MS) {
            return
        }
        lastA11yFixAt = now
        // 统一走自愈入口：拉起常驻前台服务 + 修复无障碍。
        // 注意这里不再要求「无障碍已在启用列表里」——ROM 把组件整个移出列表时，
        // 恰恰是最需要修复的时刻，旧实现在这里直接放弃了。
        A11yState.syncFixState()
    }

    /**
     * 更新磁贴状态
     *
     * @param isActive 是否激活
     */
    private fun updateTileState(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_slide_tile)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        // 检查⌈无障碍服务权限⌋和⌈悬浮窗权限⌋是否已启用
        val hasAccessibility = isAccessibilityServicePermissionEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)
        if (!hasAccessibility || !hasOverlay) {
            // 提示用户
            Toast.makeText(this, R.string.tile_permission_toast, Toast.LENGTH_SHORT).show()
            // 打开主界面
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
            return
        }
        // 切换服务状态
        val isRunning = FloatingWindowService.isRunning()
        if (isRunning) {
            // 用户主动关闭：进程复活时不再自动恢复悬浮球
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_FLOATING_DESIRED, false).apply()
            // 停止滑动
            AutoSlideService.getInstance()?.stopSlide()
            // 停止悬浮窗服务
            val stopIntent = Intent(this, FloatingWindowService::class.java)
            stopService(stopIntent)
        } else {
            // 用户主动开启 = 明确表达希望服务常驻，允许自愈逻辑工作
            A11yState.setServiceDesired(this, true)
            // 启动悬浮窗服务
            val startIntent = Intent(this, FloatingWindowService::class.java)
            startService(startIntent)
        }
        // 更新磁贴状态
        updateTileState(!isRunning)
        // 点击磁贴时顺带自愈常驻服务（移植 GKD onTileClicked → StatusService.autoStart）
        StatusService.autoStart(this)
    }

    companion object {
        /* 磁贴复活节流间隔：3 秒内最多触发一次修复（与 GKD 一致） */
        private const val A11Y_FIX_THROTTLE_MS = 3_000L
        /* 上次执行磁贴复活的时间（跨磁贴实例共享，频繁下拉通知栏时避免重复踢活） */
        private var lastA11yFixAt = 0L

        /**
         * 请求更新磁贴状态
         *
         * @param context 上下文
         */
        @JvmStatic
        fun requestUpdate(context: Context) {
            val componentName = ComponentName(context, AutoSlideTileService::class.java)
            requestListeningState(context, componentName)
        }
    }
}
