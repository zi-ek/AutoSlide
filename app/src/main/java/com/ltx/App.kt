package com.ltx

/**
 * 应用入口（移植自 GKD 的 App.kt）
 *
 * 这是保活链路里最关键的一环：进程无论因为什么原因被拉起
 * （磁贴绑定 / START_STICKY 服务重启 / 打开主界面 / 系统恢复前台服务），
 * Application.onCreate 都必定会执行，在这里立刻做一次自愈，
 * 就不再依赖「用户恰好下拉了通知栏」才能恢复无障碍。
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
