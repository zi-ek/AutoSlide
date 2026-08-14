package com.google.android.accessibility.selecttospeak

import com.ltx.service.AutoSlideService

/**
 * 伪装成 Google 无障碍组件的空壳服务（参考 GKD）：
 * 继承 AutoSlideService，让国产 ROM 的“无障碍白名单/自动清理机制”
 * 误认为这是 Google 系统组件，避免一键清理时被强制停止。
 */
class SelectToSpeakService : AutoSlideService()
