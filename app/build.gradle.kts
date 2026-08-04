import com.android.build.gradle.internal.api.BaseVariantOutputImpl

// ==================== App 模块构建脚本 ====================
// 本文件配置应用模块的 SDK 版本、构建类型、签名、混淆和依赖

// AGP
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        versionCode = 20
        // 版本名称
        versionName = "2.6.1"
        // 单元测试
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 本地测试使用调试签名，正式发布请替换为自己的 keystore 签名
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    // 自定义APK输出名称
    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "AutoSlide-v${defaultConfig.versionName}.apk"
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
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    // ---- Shizuku：授权写入安全设置 / 低版本系统截图 ----
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // ---- Kotlin 协程 ----
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // ---- OCR：ML Kit 中文文字识别（关键词检测功能使用）----
    implementation(libs.mlkit.text.recognition.chinese)
    // ---- 测试 ----
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
