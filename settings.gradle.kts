@file:Suppress("UnstableApiUsage")

// 插件管理仓库：从哪里下载 Android Gradle Plugin 和 Kotlin 插件
pluginManagement {
    repositories {
        // Google 仓库（AGP、AndroidX、Google 依赖）
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Maven 中央仓库（第三方库）
        mavenCentral()
        // Gradle 官方插件门户
        gradlePluginPortal()
    }
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
include(":app")
