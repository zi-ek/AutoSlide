package com.ziek.autoslide

/**
 * 应用入口（移植自 GKD 的 App.kt）
 *
 * 这是保活链路里最关键的一环：只要进程被系统重新拉起
 * （磁贴绑定 / START_STICKY 服务重启 / 打开主界面 / 系统恢复前台服务），
 * Application.onCreate 都必定会执行，在这里立刻做一次自愈。
 * 注意：进程被 force-stop 后系统不会自动拉起，必须由用户或恢复入口触发。
 */

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 注册无障碍启用列表监听并同步当前状态
        A11yState.init(this)
        // 进程刚起来：修无障碍 + 拉起常驻前台服务
        A11yState.syncFixState()
    }
}
