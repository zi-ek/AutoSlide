package com.ltx

/**
 * Context 扩展函数文件
 *
 * 提供与权限判断相关的便捷扩展方法，供主界面、悬浮窗、磁贴等模块复用。
 */

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.ltx.service.AutoSlideService

/**
 * 判断当前应用⌈无障碍服务权限⌋是否已启用
 *
 * @return ⌈无障碍服务权限⌋是否已启用
 */
fun Context.isAccessibilityServicePermissionEnabled(): Boolean {
    // 1. 先读取系统级开关：无障碍总开关是否开启
    val enabled = runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    }.getOrDefault(0)
    if (enabled != 1) {
        return false
    }
    // 2. 再读取已启用的无障碍服务列表，判断本应用服务是否在其中
    val services = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    // 本应用无障碍服务的组件名（包名/类名）
    val targetComponent = ComponentName(this, AutoSlideService::class.java)
    // 3. 用冒号分隔的列表里逐个比对组件名
    return services.split(":").any {
        ComponentName.unflattenFromString(it.trim()) == targetComponent
    }
}
