# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Shizuku: 防止反射方法及跨进程Provider被混淆
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }

# priv-kit Shizuku 外部桥接服务：需要被 Shizuku 程序远程实例化/混淆保护
-keep class com.ltx.priv.PrivilegeShizukuStartService { *; }
-keep class com.ltx.priv.IPrivilegeShizukuStartService { *; }

# priv-core 通过 compileOnly 隐藏 API 桩引用的系统类（运行时存在，编译期 android.jar 中没有）
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContentProviderHolder
-dontwarn android.app.ContextImpl
-dontwarn android.app.IActivityManager$Stub
-dontwarn android.app.IActivityManager
-dontwarn android.app.LoadedApk
-dontwarn android.content.IContentProvider
-dontwarn android.content.pm.IPackageManager$Stub
-dontwarn android.content.pm.IPackageManager
-dontwarn android.content.res.CompatibilityInfo
-dontwarn android.os.ServiceManager
-dontwarn android.permission.IPermissionManager$Stub
-dontwarn android.permission.IPermissionManager

# 无障碍服务: 保证系统能反射实例化服务
-keep class com.ltx.service.AutoSlideService { *; }

# 操作宏: 枚举名通过 name()/valueOf() 序列化到 JSON，必须跨版本保持稳定，
# 否则旧包保存的宏在新包解析失败会被自动清除
-keep class com.ltx.input.AutoSlideInputAction { *; }

# 崩溃定位: 保留源文件及行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ML Kit OCR
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
