package com.ziek.autoslide

/**
 * 应用入口（对照 GKD 的 `App.kt` 移植）
 *
 * 这是保活链路里最关键的一环：进程无论因何原因被拉起
 * （磁贴绑定 / START_STICKY 服务重启 / 打开主界面 / 系统恢复前台服务），
 * Application.onCreate 都必定执行，在这里立刻做一次自愈（GKD: onCreate 末尾的 syncFixState）。
 *
 * 注意：进程被 force-stop 后系统不会自动拉起，必须由用户或磁贴等入口触发——GKD 同样如此。
 */

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {

    companion object {
        /* 进程启动时刻（GKD: App.startTime），主界面据此判断首次 resume 要不要跳过自愈 */
        @JvmStatic
        val startTime = SystemClock.elapsedRealtime()
    }

    /**
     * 解除 Android P+ 的非 SDK 接口限制（GKD: App.attachBaseContext）。
     *
     * 必须在 onCreate 之前完成，否则后面反射 AppOpsManager 的隐藏字段
     * （OP_ACCESS_ACCESSIBILITY 等）会一律失败，AppOps 放行就做不了。
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 读一次本地缓存的使用时长，进程刚起来时服务层就能拿到判定结果
        License.init(this)
        // 注册无障碍启用列表监听并同步当前状态
        A11yState.init(this)
        // 进程刚起来：修无障碍
        A11yState.syncFixState()
    }
}
