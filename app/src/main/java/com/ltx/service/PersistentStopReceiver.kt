package com.ltx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 常驻通知里的「停止」按钮：关闭常驻跳过检测并停止常驻服务
 */
class PersistentStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutoSlideService.getInstance()?.setPersistentSkipEnabled(false)
        context.stopService(Intent(context, StatusService::class.java))
    }
}
