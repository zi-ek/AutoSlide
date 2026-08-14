package com.ziek.autoslide

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * AppOps 状态检查（移植自 GKD 的 PermissionState.kt，去掉隐藏 API 依赖）。
 *
 * Manifest 里声明了权限不等于 AppOps 就放行：国产 ROM 经常把
 * 无障碍、无障碍悬浮窗、前台服务等 AppOps 单独限制掉，
 * 这里用公开的 checkOpNoThrow 逐项探测，把真实状态暴露出来。
 */
object AppOpsState {

    private const val TAG = "AppOpsState"

    /* 隐藏 op 字符串（来自 AOSP AppOpsManager，运行时可直接用） */
    private const val OP_ACCESS_ACCESSIBILITY = "android:access_accessibility"
    private const val OP_CREATE_ACCESSIBILITY_OVERLAY = "android:create_accessibility_overlay"
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"
    private const val OP_FOREGROUND_SERVICE_SPECIAL_USE = "android:foreground_service_special_use"

    /* GET_APP_OPS_STATS 是隐藏权限，SDK 里没有常量，只能写字面量 */
    private const val PERMISSION_GET_APP_OPS_STATS = "android.permission.GET_APP_OPS_STATS"

    /* 部分机型读受限设置 AppOps 会抛 SecurityException，探测失败后不再重复尝试（GKD 同款） */
    private var canReadRestricted = true

    /** 无障碍服务 AppOps（Android 10+）是否放行 */
    fun isAccessibilityAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return checkAllowedOp(context, OP_ACCESS_ACCESSIBILITY)
    }

    /** 创建无障碍悬浮窗 AppOps（Android 10+）是否放行 */
    fun isCreateA11yOverlayAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return checkAllowedOp(context, OP_CREATE_ACCESSIBILITY_OVERLAY)
    }

    /** 访问受限设置 AppOps（Android 13+）是否放行 */
    fun isAccessRestrictedSettingsAllowed(context: Context): Boolean {
        if (!canReadRestricted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        // 没有 GET_APP_OPS_STATS 就读不了这个 op，跳过（GKD 同款）
        if (context.checkSelfPermission(PERMISSION_GET_APP_OPS_STATS) != PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return try {
            checkAllowedOp(context, OP_ACCESS_RESTRICTED_SETTINGS)
        } catch (_: SecurityException) {
            canReadRestricted = false
            true
        }
    }

    /** 特殊用途前台服务 AppOps（Android 14+）是否放行 */
    fun isForegroundServiceSpecialUseAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return checkAllowedOp(context, OP_FOREGROUND_SERVICE_SPECIAL_USE)
    }

    /** 启动相关操作权限综合结果（无障碍 + 无障碍悬浮窗 + 受限设置） */
    fun appOpsAllowed(context: Context): Boolean =
        isAccessibilityAllowed(context) &&
            isCreateA11yOverlayAllowed(context) &&
            isAccessRestrictedSettingsAllowed(context)

    /** 是否存在 AppOps 受限（含前台服务特殊用途） */
    fun isAppOpsRestricted(context: Context): Boolean =
        !appOpsAllowed(context) || !isForegroundServiceSpecialUseAllowed(context)

    /** 把四项状态打到 logcat，方便排查 ROM 限制 */
    fun logStatus(context: Context) {
        LogX.i(
            TAG,
            "appops: a11y=${isAccessibilityAllowed(context)}, " +
                "a11yOverlay=${isCreateA11yOverlayAllowed(context)}, " +
                "restrictedSettings=${isAccessRestrictedSettingsAllowed(context)}, " +
                "fgsSpecialUse=${isForegroundServiceSpecialUseAllowed(context)}, " +
                "restricted=${isAppOpsRestricted(context)}"
        )
    }

    private fun checkAllowedOp(context: Context, op: String): Boolean {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        return runCatching {
            manager.checkOpNoThrow(op, Process.myUid(), context.packageName)
        }.getOrDefault(AppOpsManager.MODE_ALLOWED).let { mode ->
            mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
        }
    }
}
