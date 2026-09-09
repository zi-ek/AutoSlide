package com.ziek.autoslide

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.ziek.autoslide.service.TrackRecordService

/**
 * 接收 Easycontrol 的 ADB 指令，远程切换「行动轨迹」开关。
 *
 *   adb shell am start -n com.ziek.autoslide/.TrackCommandActivity --es track true
 *   adb shell am start -n com.ziek.autoslide/.TrackCommandActivity --es track false
 *
 * 与 [com.ziek.autoslide.ec.EcSerialActivity] 同一模式：导出、NoDisplay、noHistory，
 * 外部（Easycontrol）只能触发开关切换，拿不到任何别的能力。
 *
 * 执行结果用 Toast + LogX 反馈在被控端；am start 同步返回的只有「组件是否拉起成功」，
 * 权限不足这类业务失败无法回传给 Easycontrol，由被控端界面可见
 * （开关不点亮 + Toast 提示，用户下次打开主界面即见）。
 *
 * 开启走与本地开关一致的语义：权限齐（前台定位 + 后台「始终允许」+ 通知）才置位并启动；
 * 权限缺一就明确失败、不置位——避免「远程点了开，实际没在记录」的假象。
 */
class TrackCommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmd = intent?.getStringExtra(EXTRA_TRACK)?.trim()?.lowercase()
        val message = when (cmd) {
            "true", "1", "on" -> enableTrack()
            "false", "0", "off" -> disableTrack()
            else -> "行动轨迹指令参数错误：am start -n $packageName/.TrackCommandActivity --es track true|false"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        LogX.i(TAG, message)
        finish()
    }

    /** 远程开启：权限齐才置位启动，否则明确失败 */
    private fun enableTrack(): String {
        if (!LocationTracker.hasBackgroundLocation(this)) {
            return "无法远程开启行动轨迹：定位权限不足（需「始终允许」），请先在手机上手动开启一次"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return "无法远程开启行动轨迹：通知权限未授予"
        }
        prefs().edit().putBoolean(KEY_TRACK_ENABLED, true).apply()
        TrackRecordService.start(this)
        return "行动轨迹已远程开启"
    }

    /** 远程关闭：置位关闭 + 停服务（onDestroy 杀 :trackrecord 进程，复活任务随 stop 自撤） */
    private fun disableTrack(): String {
        prefs().edit().putBoolean(KEY_TRACK_ENABLED, false).apply()
        TrackRecordService.stop(this)
        return "行动轨迹已远程关闭"
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        private const val TAG = "TrackCommand"
        const val EXTRA_TRACK = "track"
    }
}
