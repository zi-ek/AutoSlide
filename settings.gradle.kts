@file:Suppress("UnstableApiUsage")

// 插件管理仓库：从哪里下载 Android Gradle Plugin 和 Kotlin 插件
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// 依赖解析策略：所有模块的依赖统一从以下仓库下载，禁止模块内单独配置仓库
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// 项目名称与模块列表
rootProject.name = "AutoSlide"
include(":app", ":hidden-api")
