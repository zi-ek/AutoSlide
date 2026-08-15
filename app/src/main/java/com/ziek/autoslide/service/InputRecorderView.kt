package com.ziek.autoslide.service

/**
 * 输入录制视图（基于 PlainApp 输入框架改造）
 *
 * 悬浮窗长按方向键后显示的全屏半透明遮罩：
 * 宏录制：用户直接在屏幕上点击/滑动，
 * 每个动作会被记录（含两次操作之间的等待时间），
 * 同时立即同步派发给下方应用执行，做到“边录边操作真实 App”。
 */

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.ziek.autoslide.input.AutoSlideInput
import com.ziek.autoslide.input.AutoSlideInputAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 输入录制视图
 *
 * @param context 上下文
 * @param instructionText 录制提示文字
 * @param onRecorded 录制完成回调（非空输入序列）
 * @param onCancelled 取消回调
 * @param onStrokeRecorded 每录完一个动作后的实时同步回调（挂起函数，等派发完成再恢复触摸）
 * @param onAddWaitFor 点击「等待」按钮的回调（由外层弹出输入窗口，确认后调用 [addWaitForAction]）
 */
@SuppressLint("ViewConstructor")
class InputRecorderView(
    context: Context,
    private val instructionText: String,
    private val onRecorded: (List<AutoSlideInput>) -> Unit,
    private val onCancelled: () -> Unit,
    private val onStrokeRecorded: suspend (AutoSlideInput) -> Unit,
    private val onAddWaitFor: () -> Unit
) : FrameLayout(context) {

    private val screenWidth: Int
    private val screenHeight: Int
    private val density: Float
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /* 上次执行返回的时间（用于过滤执行返回后弹回录制层的回声按键） */
    private var lastPerformedBackAt = 0L
    /* 绘制画布在屏幕上的实际偏移（录制窗口从状态栏下方开始，与全屏坐标不一致） */
    private var viewOffsetX = 0
    private var viewOffsetY = 0

    /* 已录制完成的动作列表 */
    private val recordedInputs = mutableListOf<AutoSlideInput>()
    /* 当前手指滑动的路径点（归一化 x,y 交替） */
    private val currentPoints = mutableListOf<Float>()
    /* 已录制的完整笔画（每个动作一条，归一化 x,y 交替），用于持续显示录制结果 */
    private val recordedStrokes = mutableListOf<List<Float>>()
    /* 已插入的等待条件标记（绿色文字显示在屏幕上） */
    private val recordedWaitMarks = mutableListOf<String>()
    /* 当前手势的起点与按下时间 */
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var touching = false
    private var activePointerId = -1
    /* 上一个动作结束的时刻（用于计算操作间隔） */
    private var lastStrokeEndAt = 0L
    /* 当前动作执行前需要等待的间隔（毫秒） */
    private var pendingDelayMs = 0L

    /* 轨迹画笔：红色圆头粗线 */
    private val strokePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /* 小提示文字画笔（底部计数） */
    private val hintPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    /* 等待条件标记画笔：绿色圆角提示 */
    private val waitMarkPaint = Paint().apply {
        color = Color.GREEN
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    /* 绘制当前轨迹的视图 */
    private val drawView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // 绘制所有已录制的笔画（抬手后仍然保留，方便看清录到了什么）
            for (stroke in recordedStrokes) {
                drawStroke(canvas, stroke)
            }
            // 绘制当前正在画的笔画
            if (touching) {
                drawStroke(canvas, currentPoints)
            }
            // 底部显示已录制数量
            if (!touching && recordedInputs.isNotEmpty()) {
                val countText = "已录制 ${recordedInputs.size} 个动作，可继续操作，按音量上↑键保存"
                canvas.drawText(countText, w / 2f, h - 5f * density, hintPaint)
            }
            // 绘制已插入的等待条件标记（从屏幕上方往下排，不遮挡底部操作区）
            var markY = 100f * density
            for (mark in recordedWaitMarks) {
                canvas.drawText(mark, w / 2f, markY, waitMarkPaint)
                markY += 40f * density
            }
        }
    }

    init {
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        density = displayMetrics.density

        // 更淡的半透明背景，录制时要能看清并操作下方应用
        setBackgroundColor("#22000000".toColorInt())
        isClickable = true
        // 允许获取焦点，让音量键/返回键能控制录制
        isFocusable = true
        isFocusableInTouchMode = true

        // 顶部提示条：说明录制方式与退出方式（不拦截触摸）
        val instructionView = TextView(context).apply {
            text = instructionText
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = false
            isFocusable = false
        }
        addView(
            instructionView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                marginStart = dp(12)
                marginEnd = dp(12)
            }
        )
        addView(drawView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 右上角「等待」按钮：插入“等屏幕出现/消失指定文字”的条件步骤
        val waitButton = Button(context).apply {
            text = "＋等待"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC2E7D32"))
            isClickable = true
            setOnClickListener { onAddWaitFor() }
        }
        addView(
            waitButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                // 顶部水平居中，避免遮挡右上角等常见操作元素
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(64)
            }
        )

        setOnTouchListener { _, event -> handleTouch(event) }
    }

    /**
     * 插入一条等待条件动作（由外层弹窗确认后调用）。
     * 该动作不会实时派发给下方应用，只在回放时生效。
     *
     * @param text 要等待的文字
     * @param disappear true=等文字消失，false=等文字出现
     */
    fun addWaitForAction(text: String, disappear: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        recordedInputs.add(
            AutoSlideInput(
                action = AutoSlideInputAction.WAIT_FOR,
                delayMs = 0L,
                waitText = trimmed,
                waitDisappear = disappear
            )
        )
        val mark = if (disappear) "等待消失：$trimmed" else "等待出现：$trimmed"
        recordedWaitMarks.add(mark)
        drawView.invalidate()
    }

    /* 窗口挂载后请求焦点并记录画布位置 */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            requestFocus()
            // 记录画布在屏幕上的位置，绘制时把全屏坐标换算成画布局部坐标
            val location = IntArray(2)
            drawView.getLocationOnScreen(location)
            viewOffsetX = location[0]
            viewOffsetY = location[1]
        }
    }

    /* 音量键控制录制：音量上=完成，音量下=取消；返回键会被录制成 BACK 动作并立即执行 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val repeatCount = event?.repeatCount ?: 0
        if (repeatCount > 0) {
            return true // 长按音量键只触发一次
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 过滤自己执行返回产生的回声按键，避免无限循环
                val now = SystemClock.elapsedRealtime()
                if (now - lastPerformedBackAt < BACK_ECHO_GUARD_MS) {
                    return true
                }
                // 返回键录制成 BACK 动作并立即执行，让录制中的 App 正常返回
                recordBackAction()
                AutoSlideService.getInstance()
                    ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (recordedInputs.isEmpty()) {
                    onCancelled()
                } else {
                    onRecorded(recordedInputs.toList())
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                onCancelled()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * 把一个刚录好的动作实时同步到下方应用，并异步采集点击位置的控件名片
     * （id/文字，供回放时跨设备定位）。
     * 派发期间把录制层设为不可触摸，避免注入的手势被录制层自己截获造成循环。
     */
    private fun liveDispatchLastStroke() {
        val index = recordedInputs.lastIndex
        if (index < 0) return
        setWindowTouchable(false)
        recorderScope.launch {
            try {
                val original = recordedInputs[index]
                val enriched = AutoSlideService.getInstance()?.enrichInputTarget(original) ?: original
                // 用索引更新，避免录制者已继续录入新动作时改错条目
                if (index < recordedInputs.size) {
                    recordedInputs[index] = enriched
                }
                onStrokeRecorded(enriched)
            } finally {
                setWindowTouchable(true)
            }
        }
    }

    /* 切换录制层窗口是否可触摸（派发同步动作时临时放行触摸给下方应用） */
    private fun setWindowTouchable(touchable: Boolean) {
        val params = layoutParams as? WindowManager.LayoutParams ?: return
        val targetFlags = if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.flags != targetFlags) {
            params.flags = targetFlags
            runCatching {
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(this, params)
            }
        }
    }

    /* 绘制一条笔画：滑动画线，点击/长按画圆点 */
    private fun drawStroke(canvas: Canvas, points: List<Float>) {
        if (points.size < 2) return
        // 先把归一化的全屏坐标还原成绝对像素，再减去画布偏移得到画布局部坐标，
        // 保证标记出现在手指实际触摸的位置
        if (points.size == 2) {
            val absX = points[0] * screenWidth - viewOffsetX
            val absY = points[1] * screenHeight - viewOffsetY
            canvas.drawCircle(absX, absY, 12f * density, strokePaint)
            return
        }
        val path = Path()
        var first = true
        var i = 0
        while (i + 1 < points.size) {
            val px = points[i] * screenWidth - viewOffsetX
            val py = points[i + 1] * screenHeight - viewOffsetY
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
            i += 2
        }
        canvas.drawPath(path, strokePaint)
    }

    /* 处理触摸：把一次按下到抬起识别为一个输入动作 */
    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.elapsedRealtime()
                pendingDelayMs = if (lastStrokeEndAt == 0L) {
                    0L
                } else {
                    (now - lastStrokeEndAt).coerceIn(0L, MAX_RECORD_WAIT_MS)
                }
                activePointerId = event.getPointerId(event.actionIndex)
                startX = event.rawX
                startY = event.rawY
                downTime = event.eventTime
                touching = true
                currentPoints.clear()
                addPoint(event)
                drawView.invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touching || event.getPointerId(event.actionIndex) != activePointerId) {
                    return true
                }
                val lastX = currentPoints[currentPoints.size - 2]
                val lastY = currentPoints[currentPoints.size - 1]
                val newX = event.rawX / screenWidth
                val newY = event.rawY / screenHeight
                val dxPx = (newX - lastX) * screenWidth
                val dyPx = (newY - lastY) * screenHeight
                // 过滤手指微抖产生的密集点（最小采样距离 5dp）
                if (dxPx * dxPx + dyPx * dyPx > MIN_POINT_DISTANCE_PX * MIN_POINT_DISTANCE_PX * density * density) {
                    addPoint(event)
                }
                drawView.invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!touching || event.getPointerId(event.actionIndex) != activePointerId) {
                    return true
                }
                touching = false
                activePointerId = -1
                val duration = event.eventTime - downTime
                classifyAndAppend(duration, pendingDelayMs)
                lastStrokeEndAt = SystemClock.elapsedRealtime()
                currentPoints.clear()
                drawView.invalidate()
                liveDispatchLastStroke()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                touching = false
                activePointerId = -1
                currentPoints.clear()
                drawView.invalidate()
                return true
            }
        }
        return true
    }

    /**
     * 把物理返回键录制成一个 BACK 动作
     */
    private fun recordBackAction() {
        val now = SystemClock.elapsedRealtime()
        lastPerformedBackAt = now
        val delayMs = if (lastStrokeEndAt == 0L) {
            0L
        } else {
            (now - lastStrokeEndAt).coerceIn(0L, MAX_RECORD_WAIT_MS)
        }
        recordedInputs.add(AutoSlideInput(action = AutoSlideInputAction.BACK, delayMs = delayMs))
        lastStrokeEndAt = now
    }

    /* 追加一个归一化采样点 */
    private fun addPoint(event: MotionEvent) {
        currentPoints.add((event.rawX / screenWidth).coerceIn(0f, 1f))
        currentPoints.add((event.rawY / screenHeight).coerceIn(0f, 1f))
    }

    /**
     * 根据位移与时长把当前手势分类为 TAP / LONG_PRESS / SWIPE
     *
     * @param duration 手指按住到抬起的时间（毫秒）
     * @param delayMs 该动作执行前需要等待的间隔（毫秒）
     */
    private fun classifyAndAppend(duration: Long, delayMs: Long) {
        if (currentPoints.size < 2) {
            return
        }
        val startNormX = startX / screenWidth
        val startNormY = startY / screenHeight
        val endNormX = currentPoints[currentPoints.size - 2]
        val endNormY = currentPoints[currentPoints.size - 1]
        val dxPx = (endNormX - startNormX) * screenWidth
        val dyPx = (endNormY - startNormY) * screenHeight
        val movedPx = sqrt(dxPx * dxPx + dyPx * dyPx)
        val tapThresholdPx = 16 * density

        val input = when {
            // 位移很小且按住超过 500ms → 长按
            movedPx < tapThresholdPx && duration >= LONG_PRESS_MIN_MS -> AutoSlideInput(
                action = AutoSlideInputAction.LONG_PRESS,
                x = startNormX,
                y = startNormY,
                duration = duration.coerceIn(LONG_PRESS_MIN_MS, LONG_PRESS_MAX_MS),
                delayMs = delayMs
            )
            // 位移很小 → 点击
            movedPx < tapThresholdPx -> AutoSlideInput(
                action = AutoSlideInputAction.TAP,
                x = startNormX,
                y = startNormY,
                duration = duration.coerceIn(TAP_MIN_MS, TAP_MAX_MS),
                delayMs = delayMs
            )
            // 有明显位移 → 滑动，保存完整路径点
            else -> AutoSlideInput(
                action = AutoSlideInputAction.SWIPE,
                x = startNormX,
                y = startNormY,
                endX = endNormX,
                endY = endNormY,
                duration = duration.coerceIn(SWIPE_MIN_MS, SWIPE_MAX_MS),
                delayMs = delayMs,
                points = currentPoints.toList()
            )
        }
        // 去重：同一位置超短间隔的重复点击只记录一次（修复部分设备一次触摸被录成两次）
        val last = recordedInputs.lastOrNull()
        val lastTap = last?.takeIf { it.action == AutoSlideInputAction.TAP }
        val duplicateTap = input.action == AutoSlideInputAction.TAP &&
            lastTap != null &&
            delayMs < DUPLICATE_TAP_MAX_GAP_MS &&
            run {
                val dx = (input.x - lastTap.x) * screenWidth
                val dy = (input.y - lastTap.y) * screenHeight
                sqrt(dx * dx + dy * dy) < DUPLICATE_TAP_DISTANCE_DP * density
            }
        if (!duplicateTap) {
            recordedInputs.add(input)
            // 把这条笔画保留下来持续显示
            recordedStrokes.add(currentPoints.toList())
        }
    }

    /* dp 转 px */
    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val MIN_POINT_DISTANCE_PX = 5f
        /* 同一位置重复点击的最大间隔（毫秒），用于过滤一次触摸被录成两次的问题 */
        private const val DUPLICATE_TAP_MAX_GAP_MS = 200L
        /* 重复点击的判定距离（dp） */
        private const val DUPLICATE_TAP_DISTANCE_DP = 8f
        private const val TAP_MIN_MS = 60L
        private const val TAP_MAX_MS = 500L
        private const val LONG_PRESS_MIN_MS = 500L
        private const val LONG_PRESS_MAX_MS = 3000L
        private const val SWIPE_MIN_MS = 80L
        private const val SWIPE_MAX_MS = 2000L
        /* 执行返回后的回声过滤窗口（毫秒） */
        private const val BACK_ECHO_GUARD_MS = 800L
        /* 单次操作间隔的上限（毫秒），防止误录超长等待 */
        private const val MAX_RECORD_WAIT_MS = 120_000L
    }
}
