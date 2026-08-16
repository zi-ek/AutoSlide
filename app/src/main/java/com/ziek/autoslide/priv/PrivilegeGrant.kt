package com.ziek.autoslide.priv

/**
 * 特权自授权（对照 GKD 的 `priv/PrivilegeContext.kt` 里的 grantSelf / allowAllSelfPermission 移植）
 *
 * 这是 GKD 保活链路的**起点**，也是之前整条链路跑不起来的原因：
 * `WRITE_SECURE_SETTINGS` 是 signature|privileged 权限，覆盖安装、恢复出厂、被 ROM 撤权之后就没了，
 * 而没有它 [com.ziek.autoslide.A11yState.fixA11yService] 第一道检查就会直接返回，无障碍永远修不回来。
 *
 * GKD 的做法是每次 syncFixState 都通过 priv-kit 特权服务给自己补授一次，所以用户只要授权过 Shizuku
 * 一次，之后无论重装还是被撤权都能自动拿回来。这里照搬这个行为。
 *
 * 未移植的部分：GKD 的 `allowAllSelfMode()` 还会通过 hidden-api 把一组 AppOps
 * （通知/悬浮窗/无障碍/受限设置/FGS specialUse）设为 ALLOWED。priv-kit 的公开 API 没有 AppOps
 * 写入能力，GKD 是靠自带的 hidden-api 模块 + IAppOpsService 代理做的，成本远高于本次改动；
 * 且这些 AppOps 在正常授权路径下本来就是放行的（[com.ziek.autoslide.AppOpsState] 会打日志暴露异常情况）。
 */

import android.Manifest
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.ziek.autoslide.LogX
import kotlinx.coroutines.delay
import priv.kit.core.Privilege

internal object PrivilegeGrant {

    private const val TAG = "PrivilegeGrant"

    /* 隐藏权限，android.Manifest.permission 里没有常量（GKD: PermissionStates.Manifest_permission_GET_APP_OPS_STATS） */
    private const val GET_APP_OPS_STATS = "android.permission.GET_APP_OPS_STATS"

    /* 等待特权服务端握手完成的轮询上限（约 10 秒，与 MainActivity 的手动授权路径一致） */
    private const val HANDSHAKE_POLL_TIMES = 50
    private const val HANDSHAKE_POLL_INTERVAL_MS = 200L

    /**
     * 确保特权服务已连接，然后给自己补授所需权限（GKD: syncFixState 里的 grantSelf）。
     *
     * 全程静默：Shizuku 没装、没授权、特权服务起不来都只打日志，不打扰用户——
     * 用户仍可以走主界面的「Shizuku 授权」手动路径。
     *
     * @param context 上下文
     */
    suspend fun ensureServerAndGrantSelf(context: Context) {
        val app = context.applicationContext
        if (!Privilege.pingServer()) {
            if (!startServerSilently(app)) return
        }
        grantSelf(app)
    }

    /**
     * 给自己补授权限并放行 AppOps（GKD: grantSelf = allowAllSelfMode + allowAllSelfPermission）。
     *
     * @param context 上下文
     */
    fun grantSelf(context: Context) {
        val app = context.applicationContext
        runCatching {
            if (!Privilege.pingServer()) return
            // 受限的特权服务端（例如非 root 且系统不允许授权）直接放弃，避免每次都白跑
            if (Privilege.isPermissionRestricted()) {
                LogX.w(TAG, "privilege server cannot grant runtime permissions")
                return
            }
            allowAllSelfMode(app)
            allowAllSelfPermission(app)
        }.onFailure { LogX.w(TAG, "grantSelf failed", it) }
    }

    /**
     * 把国产 ROM 常单独限制掉的几个 AppOps 全部设为 ALLOWED（GKD: allowAllSelfMode）。
     *
     * Manifest 声明了权限不等于 AppOps 放行——被 ignore 掉时通知发不出、悬浮窗弹不出、
     * 无障碍连不上，且完全没有报错，只能从这里主动改回来。
     *
     * @param context 上下文
     */
    private fun allowAllSelfMode(context: Context) {
        if (!CompatAppOpsService.isAvailable()) {
            LogX.w(TAG, "app ops service unavailable from privilege server")
            return
        }
        val service = runCatching { CompatAppOpsService() }
            .onFailure { LogX.w(TAG, "create app ops proxy failed", it) }
            .getOrNull() ?: return
        setAllowSelfMode(context, service, AppOpsManagerHidden.OP_POST_NOTIFICATION)
        setAllowSelfMode(context, service, AppOpsManagerHidden.OP_SYSTEM_ALERT_WINDOW)
        // GKD 没有这一项，是实测小米 HyperOS 后追加的：
        // 该 op 被设成 ignore 时，进程被杀后 MIUI 的 AutoStartManagerService 会驳回
        // START_STICKY 的服务重启（logcat: "MIUILOG- Reject RestartService"），
        // 保活链路直接断掉。GKD 在同一台机器上没被限制，所以它不需要处理这个。
        if (CompatAppOpsService.supportRunAnyInBackground) {
            setAllowSelfMode(context, service, AppOpsManagerHidden.OP_RUN_ANY_IN_BACKGROUND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setAllowSelfMode(context, service, AppOpsManagerHidden.OP_ACCESS_ACCESSIBILITY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setAllowSelfMode(context, service, AppOpsManagerHidden.OP_ACCESS_RESTRICTED_SETTINGS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setAllowSelfMode(context, service, AppOpsManagerHidden.OP_FOREGROUND_SERVICE_SPECIAL_USE)
        }
        // 这个 op 只存在于部分 Android 14 修订版及以上，探测不到就不能按 code 下发
        if (CompatAppOpsService.supportA11yOverlay) {
            setAllowSelfMode(context, service, AppOpsManagerHidden.OP_CREATE_ACCESSIBILITY_OVERLAY)
        }
    }

    /* 单个 op：已经是 ALLOWED 就不重复下发（GKD: setAllowSelfMode） */
    private fun setAllowSelfMode(context: Context, service: CompatAppOpsService, code: Int) {
        runCatching {
            val uid = Process.myUid()
            val mode = service.value.checkOperation(code, uid, context.packageName)
            if (mode != AppOpsManager.MODE_ALLOWED) {
                service.value.setMode(code, uid, context.packageName, AppOpsManager.MODE_ALLOWED)
                LogX.i(TAG, "app op $code set to allowed")
            }
        }.onFailure { LogX.w(TAG, "set app op $code failed", it) }
    }

    /* 补授运行时权限（GKD: allowAllSelfPermission） */
    private fun allowAllSelfPermission(context: Context) {
        grantSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS)
        grantSelfPermission(context, GET_APP_OPS_STATS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /* 单个权限：已授予则跳过（GKD: grantSelfPermission） */
    private fun grantSelfPermission(context: Context, name: String) {
        if (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            Privilege.grantRuntimePermission(
                packageName = context.packageName,
                permissionName = name,
            )
        }.onSuccess {
            LogX.i(TAG, "granted $name")
        }.onFailure {
            LogX.w(TAG, "grant $name failed", it)
        }
    }

    /**
     * 静默拉起 priv-kit 特权服务端（GKD: App.onCreate 里的 PrivilegeUi.startSilently）。
     *
     * @return 特权服务端是否已就绪
     */
    private suspend fun startServerSilently(context: Context): Boolean {
        val starter = PrivilegeShizukuExternalStarter(context)
        return try {
            if (!starter.isAvailable()) {
                // Shizuku 没运行或没授权：正常情况，用户还没走过授权流程
                return false
            }
            // 首次访问 nativeStarterCommand 需要解析 APK，必须在后台线程（调用方已在 IO 上）
            starter.start(Privilege.nativeStarterCommand)
            waitForServer()
        } catch (e: Exception) {
            LogX.w(TAG, "start privilege server silently failed", e)
            false
        } finally {
            runCatching { starter.close() }
        }
    }

    /* 轮询等待握手完成 */
    private suspend fun waitForServer(): Boolean {
        repeat(HANDSHAKE_POLL_TIMES) {
            if (Privilege.pingServer()) return true
            delay(HANDSHAKE_POLL_INTERVAL_MS)
        }
        return false
    }
}
