package com.google.android.accessibility.selecttospeak

import com.ziek.autoslide.service.AutoSlideService

/**
 * 无障碍服务入口（类名沿用 GKD 风格的 Google SelectToSpeak 命名）。
 *
 * 注意：这只是类名层面的架构兼容（GKD 同款），系统仍按包名/UID/签名
 * 识别本应用，并不会因此获得 Google 系统组件的待遇，也不提供任何免杀能力。
 * 真正的保活依赖无障碍绑定 + 前台服务 + 被杀后的自愈链路。
 */
class SelectToSpeakService : AutoSlideService()
