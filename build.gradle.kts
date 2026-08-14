// 根项目构建脚本：只声明项目共用的插件版本，不在根项目里执行构建
plugins {
    // Android 应用插件（在 app 模块中使用，这里仅声明版本）
    alias(libs.plugins.android.application) apply false
    // 与 GKD 一致：KGP 2.4.10 必须出现在根 classpath 上，
    // AGP 9.3.1 内置 Kotlin 才会自动升级到 2.4.10（否则固定 2.2.10）
    alias(libs.plugins.kotlin.parcelize) apply false
    // kotlin-loc 编译器插件
    alias(libs.plugins.loc) apply false
}
