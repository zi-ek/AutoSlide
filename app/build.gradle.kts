// ==================== App 模块构建脚本 ====================
// 本文件配置应用模块的 SDK 版本、构建类型、签名、混淆和依赖

import java.util.Properties
import java.io.InputStreamReader

// 版本名称（APK 输出文件名也使用它）
val appVersionName = "3.3.0"

// 后端服务地址：来自 gradle.properties 的 autoslide.serverBaseUrl，
// 经 buildConfigField 注入 BuildConfig，代码里只认 Constants.SERVER_BASE_URL 这一个来源
val serverBaseUrl = providers.gradleProperty("autoslide.serverBaseUrl").getOrElse("")

// 正式签名配置：从 keystore.properties 读取（该文件包含密码，不要提交到仓库）
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) InputStreamReader(f.inputStream(), Charsets.UTF_8).use { load(it) }
}

// AGP
plugins {
    alias(libs.plugins.android.application)
    // 本项目不用 @Parcelize；应用该插件仅为把 KGP 2.4.10 带上类路径（GKD 同款思路）
    alias(libs.plugins.kotlin.parcelize)
    // kotlin-loc：给关键日志自动补上 文件:行号
    alias(libs.plugins.loc)
    // li.songe.remap：把 hidden-api 里 @RemapType 标注的桩类重定向到系统真实类
    alias(libs.plugins.remap)
}

android {
    // 命名空间
    namespace = "com.ziek.autoslide"
    // 编译时使用的Android SDK版本
    compileSdk = 37
    defaultConfig {
        // 应用ID: 包名
        applicationId = "com.ziek.autoslide"
        // 最低支持SDK版本
        minSdk = 26
        // 目标设备的SDK版本
        targetSdk = 37
        // 版本号
        versionCode = 34
        // 版本名称
        versionName = appVersionName
        // 后端服务地址（统计 / 脚本备份 / 聊天室共用）
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile", "autoslide-release.jks"))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "autoslide")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }
    buildTypes {
        debug {
            // Android Studio 调试构建也用正式签名，方便直接覆盖安装正式签名版
            if (!keystoreProperties.getProperty("storePassword").isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 有正式 keystore 时用正式签名，没有则回退调试签名（方便其他机器直接构建）
            signingConfig = if (keystoreProperties.getProperty("storePassword").isNullOrEmpty()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        // 启用视图绑定
        viewBinding = true
        // 启用 AIDL（priv-kit 通过 Shizuku UserService 桥接需要）
        aidl = true
        // 启用 BuildConfig（AGP 8 起默认关闭），用于注入后端服务地址
        buildConfig = true
    }
    buildToolsVersion = "36.0.0"
}

// 自定义 APK 输出名称（AGP 9 新版变体 API）
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("AutoSlide-v$appVersionName.apk")
        }
        // priv-kit 原生启动器：minSdk < 29 需要解压 native 库，才能通过 app_process 执行
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
        variant.packaging.jniLibs.useLegacyPackagingFromBundle.set(true)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // ---- UI 基础 ----
    implementation(libs.appcompat)
    implementation(libs.material)
    // ---- Shizuku：授权写入安全设置 / 低版本系统截图 ----
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // ---- Priv Kit：自管理特权运行时（通过 Shizuku 外部桥接启动，自动授予 WRITE_SECURE_SETTINGS）----
    implementation(libs.priv.core)
    // ---- kotlin-loc：编译期注入日志位置，无需打包进 APK ----
    compileOnly(libs.loc.annotation)
    // ---- 系统隐藏 API 桩代码：只在编译期存在，运行时由系统真实实现提供 ----
    compileOnly(project(":hidden-api"))
    // ---- 解除 Android P+ 非 SDK 接口限制，否则反射不到 AppOpsManager 的隐藏字段 ----
    implementation(libs.lsposed.hiddenapibypass)
    // ---- Kotlin 协程 ----
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // ---- OCR：ML Kit 中文文字识别（关键词检测功能使用）----
    implementation(libs.mlkit.text.recognition.chinese)
}
