package com.ziek.autoslide.priv

/**
 * AppOps 系统服务代理（对照 GKD 的 `priv/CompatAppOpsService.kt` 移植）
 *
 * 应用自己调 `AppOpsManager.setMode` 会被系统拒绝——那是 system_server 才有的权限。
 * 这里通过 priv-kit 的 [PrivilegeBinderWrapper] 把调用转发到特权服务端（shell/root 身份），
 * 用 `com.android.internal.app.IAppOpsService`（hidden-api 模块提供的桩）直接下发 setMode。
 *
 * 桩类只在编译期存在，运行时绑定到系统真实的 Binder 接口。
 */

import android.app.AppOpsManager
import android.content.Context
import com.android.internal.app.IAppOpsService
import com.ziek.autoslide.LogX
import priv.kit.core.binder.PrivilegeBinderWrapper
import kotlin.reflect.KClass

internal class CompatAppOpsService {

    val value: IAppOpsService = IAppOpsService.Stub.asInterface(
        requireNotNull(PrivilegeBinderWrapper.fromSystemService(Context.APP_OPS_SERVICE)) {
            "APP_OPS_SERVICE binder unavailable"
        }
    )

    companion object {
        private const val TAG = "CompatAppOpsService"

        /**
         * 当前系统是否支持「创建无障碍悬浮窗」这个 op。
         * 它只存在于 Android 14 的部分修订版及更高版本（GKD 注释：14.0.0_r29 - r37、r50 - 17），
         * 探测不到就不要去 setMode，否则 op code 是错的。
         */
        val supportA11yOverlay: Boolean by lazy {
            AppOpsManager::class.detectHiddenField("OP_CREATE_ACCESSIBILITY_OVERLAY")
        }

        /**
         * 当前系统是否支持「后台任意运行」这个 op。
         * AOSP 从 Android 8 起就有，但个别 ROM 会改名或去掉，探测不到就跳过。
         */
        val supportRunAnyInBackground: Boolean by lazy {
            AppOpsManager::class.detectHiddenField("OP_RUN_ANY_IN_BACKGROUND")
        }

        /* 特权服务端是否提供 AppOps 服务，拿不到就别构造代理 */
        fun isAvailable(): Boolean = runCatching {
            PrivilegeBinderWrapper.hasSystemService(Context.APP_OPS_SERVICE)
        }.onFailure { LogX.w(TAG, "hasSystemService failed", it) }.getOrDefault(false)
    }
}

/**
 * 探测隐藏字段是否存在（GKD: HiddenApiDetect.kt 的 detectHiddenField）。
 *
 * 依赖 HiddenApiBypass 已解除非 SDK 接口限制，否则 Android P+ 上一律反射不到。
 *
 * @param fieldName 字段名
 * @return 字段是否存在
 */
internal fun KClass<*>.detectHiddenField(fieldName: String): Boolean = try {
    java.getField(fieldName)
    true
} catch (_: NoSuchFieldException) {
    false
}
