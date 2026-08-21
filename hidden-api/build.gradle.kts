// ==================== hidden-api 模块构建脚本 ====================
// 从 GKD 原样移植的系统隐藏 API 桩代码（android.* / com.android.internal.*）。
//
// 这些类只在编译期存在：app 模块用 compileOnly 依赖，运行时由系统的真实实现提供。
// li.songe.remap 注解处理器负责把 @RemapType 标注的桩类（如 AppOpsManagerHidden）
// 在字节码层面重定向到真实类（android.app.AppOpsManager），从而绕开
// 「编译期看不到隐藏字段、运行期又确实存在」的矛盾。

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "hidden.api"
    // 与 app 模块保持一致
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    // 必须与 app 模块保持一致（均统一使用 Java 17）
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.remap.annotation)
    annotationProcessor(libs.remap.processor)
}
