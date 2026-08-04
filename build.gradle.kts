// 根项目构建脚本：只声明项目共用的插件版本，不在根项目里执行构建
plugins {
    // Android 应用插件（在 app 模块中使用，这里仅声明版本）
    alias(libs.plugins.android.application) apply false
    // Kotlin Android 插件（在 app 模块中使用，这里仅声明版本）
    alias(libs.plugins.kotlin.android) apply false
}
