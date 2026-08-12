// ==================== App 模块构建脚本 ====================
// 本文件配置应用模块的 SDK 版本、构建类型、签名、混淆和依赖

import java.util.Properties
import java.io.InputStreamReader

// 版本名称（APK 输出文件名也使用它）
val appVersionName = "3.1.0"

// 正式签名配置：从 keystore.properties 读取（该文件包含密码，不要提交到仓库）
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) InputStreamReader(f.inputStream(), Charsets.UTF_8).use { load(it) }
}

// AGP
plugins {
    alias(libs.plugins.android.application)
}

android {
    // 命名空间
    namespace = "com.ltx"
    // 编译时使用的Android SDK版本
    compileSdk = 37
    defaultConfig {
        // 应用ID: 包名
        applicationId = "com.ltx"
        // 最低支持SDK版本
        minSdk = 26
        // 目标设备的SDK版本
        targetSdk = 37
        // 版本号
        versionCode = 32
        // 版本名称
        versionName = appVersionName
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
    }
    buildToolsVersion = "36.0.0"
}

// 自定义 APK 输出名称（AGP 9 新版变体 API）
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("AutoSlide-v$appVersionName.apk")
        }
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
    // ---- Kotlin 协程 ----
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // ---- OCR：ML Kit 中文文字识别（关键词检测功能使用）----
    implementation(libs.mlkit.text.recognition.chinese)
}
