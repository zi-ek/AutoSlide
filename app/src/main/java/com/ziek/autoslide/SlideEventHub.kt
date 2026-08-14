package com.ziek.autoslide

/**
 * 滑动事件中心
 *
 * 使用协程 Flow 在应用内各组件（无障碍服务、悬浮窗）之间传递事件，
 * 例如“强制停止”“自定义轨迹被清除”等，实现解耦通信。
 */


import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/* 滑动事件中心 */
object SlideEventHub {
    /* 私有事件流：容量 64，发送方不会因接收方处理慢而阻塞 */
    private val _eventFlow = MutableSharedFlow<SlideEvent>(extraBufferCapacity = 64)
    /* 对外只读的事件流，供订阅方 collect */
    val eventFlow = _eventFlow.asSharedFlow()

    /**
     * 发送事件
     * 
     * @param event 事件
     */
    fun sendEvent(event: SlideEvent) {
        _eventFlow.tryEmit(event)
    }
}

/* 滑动事件 */
sealed class SlideEvent {
    /* 强行停止滑动事件 */
    object ForceStop : SlideEvent()
}
