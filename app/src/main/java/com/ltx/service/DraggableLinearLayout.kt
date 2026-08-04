package com.ltx.service

/**
 * 可拖拽线性布局
 *
 * 悬浮窗根布局使用的自定义 View：
 * 识别手指按下/拖动/抬起，把拖动回调交给宿主（FloatingWindowService）来移动悬浮窗。
 */

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 可拖拽线性布局
 *
 * @author tianxing
 */
class DraggableLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var initialTouchX = 0f                 // 按下时的原始 X 坐标
    private var initialTouchY = 0f                 // 按下时的原始 Y 坐标
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop // 系统触摸滑动阈值（px）
    private var isDragging = false                 // 当前是否处于拖拽状态
    private var onDragListener: OnDragListener? = null // 拖拽事件回调

    /**
     * 拖拽监听接口
     */
    interface OnDragListener {
        /**
         * 拖拽按下事件
         * 
         * @param rawX 拖拽按下时X坐标
         * @param rawY 拖拽按下时Y坐标
         */
        fun onDragDown(rawX: Float, rawY: Float)

        /**
         * 拖拽移动事件
         * 
         * @param rawX 拖拽移动时X坐标
         * @param rawY 拖拽移动时Y坐标
         */
        fun onDragMove(rawX: Float, rawY: Float)

        /**
         * 拖拽结束事件（手指抬起或取消）
         *
         * @param rawX 结束时的X坐标
         * @param rawY 结束时的Y坐标
         */
        fun onDragEnd(rawX: Float, rawY: Float)
    }

    /**
     * 设置拖拽监听
     * 
     * @param listener 拖拽监听
     */
    fun setOnDragListener(listener: OnDragListener) {
        this.onDragListener = listener
    }

    /**
     * 拦截触摸事件
     * 
     * @param ev 触摸事件
     * @return 是否拦截触摸事件
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                isDragging = false
                onDragListener?.onDragDown(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = ev.rawX - initialTouchX
                val deltaY = ev.rawY - initialTouchY
                if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                    isDragging = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * 执行点击事件
     * 
     * @return 是否执行点击事件
     */
    @Suppress("RedundantOverride")
    override fun performClick(): Boolean {
        return super.performClick()
    }

    /**
     * 处理触摸事件
     * 
     * @param ev 触摸事件
     * @return 是否处理触摸事件
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    onDragListener?.onDragMove(ev.rawX, ev.rawY)
                    return true
                } else {
                    val deltaX = ev.rawX - initialTouchX
                    val deltaY = ev.rawY - initialTouchY
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                        isDragging = true
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    onDragListener?.onDragEnd(ev.rawX, ev.rawY)
                } else {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    onDragListener?.onDragEnd(ev.rawX, ev.rawY)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
