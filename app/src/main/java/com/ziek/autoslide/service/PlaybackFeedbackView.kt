package com.ziek.autoslide.service

/**
 * 回放反馈视图
 *
 * 回放时显示在全屏的透明层上：
 * 点击/长按画红圈，滑动画红色轨迹，和录制时的视觉一致。
 * 该层不拦截触摸，不影响回放操作真实应用。
 */

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.ziek.autoslide.input.AutoSlideInput
import com.ziek.autoslide.input.AutoSlideInputAction

/**
 * 回放反馈视图
 *
 * @param context 上下文
 */
@SuppressLint("ViewConstructor")
class PlaybackFeedbackView(context: Context) : View(context) {

    private val screenWidth = resources.displayMetrics.widthPixels
    private val screenHeight = resources.displayMetrics.heightPixels
    private val density = resources.displayMetrics.density
    /* 视图在屏幕上的偏移（全屏窗口从状态栏下方开始，与全屏坐标不一致） */
    private var offsetX = 0
    private var offsetY = 0
    private var currentInput: AutoSlideInput? = null

    private val strokePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /* 等待条件提示文字画笔 */
    private val waitPaint = Paint().apply {
        color = Color.GREEN
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    /* 窗口挂载后记录画布在屏幕上的位置 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val location = IntArray(2)
            getLocationOnScreen(location)
            offsetX = location[0]
            offsetY = location[1]
        }
    }

    /* 显示当前正在回放的动作标记 */
    fun showAction(input: AutoSlideInput) {
        currentInput = input
        invalidate()
    }

    /* 清空当前标记 */
    fun clearAction() {
        currentInput = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val input = currentInput ?: return
        when (input.action) {
            AutoSlideInputAction.TAP, AutoSlideInputAction.LONG_PRESS -> {
                val cx = input.x * screenWidth - offsetX
                val cy = input.y * screenHeight - offsetY
                canvas.drawCircle(cx, cy, 12f * density, strokePaint)
            }

            AutoSlideInputAction.SWIPE -> {
                val points = input.points
                val path = Path()
                if (points.size >= 4) {
                    var first = true
                    var i = 0
                    while (i + 1 < points.size) {
                        val px = points[i] * screenWidth - offsetX
                        val py = points[i + 1] * screenHeight - offsetY
                        if (first) {
                            path.moveTo(px, py)
                            first = false
                        } else {
                            path.lineTo(px, py)
                        }
                        i += 2
                    }
                } else {
                    path.moveTo(input.x * screenWidth - offsetX, input.y * screenHeight - offsetY)
                    path.lineTo(input.endX * screenWidth - offsetX, input.endY * screenHeight - offsetY)
                }
                canvas.drawPath(path, strokePaint)
            }

            AutoSlideInputAction.BACK -> Unit // 返回动作不画标记

            AutoSlideInputAction.WAIT_FOR -> {
                // 这里绝对不能画出 waitText：等待期间的 OCR 兜底是整屏截图（含本覆盖层），
                // 一旦把关键词画在屏幕上，OCR 会读到自己画的字并立刻判定条件成立。
                canvas.drawText("等待条件中…", width / 2f, height * 0.35f, waitPaint)
            }

            AutoSlideInputAction.FIND_AND_TAP -> {
                // 与等待条件同理：不能把目标文字画上屏，否则 OCR 会读到自己画的字
                canvas.drawText("按文字定位中…", width / 2f, height * 0.35f, waitPaint)
            }
        }
    }
}
