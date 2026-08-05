package com.ltx.service

/**
 * 自动滑动无障碍服务（核心服务）
 *
 * 通过无障碍服务执行屏幕滑动手势：
 * - 定时滑动模式（不停顿/固定时间/随机时间）：按设定节奏循环滑动；
 * - 关键词检测模式：定时截屏 + OCR 识别，命中关键词才滑动一次。
 * 同时支持音量键强制停止、息屏自动停止、自定义轨迹回放等能力。
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.ltx.DEFAULT_DOUYIN_AUTOPLAY
import com.ltx.DEFAULT_KEYWORD_COOLDOWN
import com.ltx.DEFAULT_KEYWORD_DIRECTION
import com.ltx.DEFAULT_KEYWORD_IGNORE_CASE
import com.ltx.DEFAULT_KEYWORD_INTERVAL
import com.ltx.DEFAULT_KEYWORD_MAX_TRIGGERS
import com.ltx.DEFAULT_KEYWORDS
import com.ltx.DEFAULT_MAX_PAUSE_TIME
import com.ltx.DEFAULT_MIN_PAUSE_TIME
import com.ltx.DEFAULT_PAUSE_TIME
import com.ltx.DEFAULT_SPEED
import com.ltx.DIRECTION_DOWN
import com.ltx.DIRECTION_LEFT
import com.ltx.DIRECTION_RIGHT
import com.ltx.DIRECTION_UP
import com.ltx.KEY_KEYWORD_COOLDOWN
import com.ltx.KEY_KEYWORD_DIRECTION
import com.ltx.KEY_KEYWORD_IGNORE_CASE
import com.ltx.KEY_KEYWORD_INTERVAL
import com.ltx.KEY_KEYWORD_MAX_TRIGGERS
import com.ltx.KEY_KEYWORDS
import com.ltx.KEY_DOUYIN_AUTOPLAY
import com.ltx.KEY_MAX_PAUSE_TIME
import com.ltx.KEY_MIN_PAUSE_TIME
import com.ltx.KEY_PAUSE_MODE
import com.ltx.KEY_PAUSE_TIME
import com.ltx.KEY_SPEED
import com.ltx.PAUSE_MODE_FIXED
import com.ltx.PAUSE_MODE_KEYWORD
import com.ltx.PAUSE_MODE_NONE
import com.ltx.PAUSE_MODE_RANDOM
import com.ltx.PREFS_NAME
import com.ltx.R
import com.ltx.SlideEvent
import com.ltx.SlideEventHub
import com.ltx.getTrajectoryKey
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * 自动滑动无障碍服务
 *
 * @author tianxing
 */
@SuppressLint("AccessibilityPolicy")
class AutoSlideService : AccessibilityService() {

    private val secureRandom = SecureRandom().asKotlinRandom() // 手势随机偏移用的随机数
    private val handler = Handler(Looper.getMainLooper())      // 主线程 Handler，调度滑动/检测任务
    private var runGeneration = 0        // 运行代数：每次启停自增，用于让旧任务失效
    private var isScreenOffReceiverRegistered = false // 息屏广播是否已注册
    private var speed = DEFAULT_SPEED    // 滑动速度（1~100，决定手势持续时间）
    private var pauseMode = PAUSE_MODE_NONE // 当前滑动模式（不停顿/固定/随机/关键词）
    private var pauseTime = DEFAULT_PAUSE_TIME   // 固定停顿时间（秒）
    private var minPauseTime = DEFAULT_MIN_PAUSE_TIME // 随机停顿下限（秒）
    private var maxPauseTime = DEFAULT_MAX_PAUSE_TIME // 随机停顿上限（秒）
    private var currentDirection = DIRECTION_LEFT // 当前滑动方向（默认左滑）
    private var isRunning = false        // 是否正在运行滑动/检测
    private var isGestureActive = false  // 是否正在执行手势（防止手势期间重复调度）
    /* 关键词检测（OCR）状态 */
    private var keywordModeActive = false // 当前是否运行关键词检测模式
    private var keywordList: List<String> = emptyList() // 用户填写的关键词列表
    private var keywordIgnoreCase = DEFAULT_KEYWORD_IGNORE_CASE // 是否忽略大小写
    private var keywordIntervalMs = DEFAULT_KEYWORD_INTERVAL * 1000 // 检测间隔（毫秒，由秒换算）
    private var keywordCooldownMs = DEFAULT_KEYWORD_COOLDOWN * 1000 // 触发后冷却（毫秒，由秒换算）
    private var keywordMaxTriggers = DEFAULT_KEYWORD_MAX_TRIGGERS // 同一画面最多触发次数
    private var lastKeywordTriggerAt = 0L // 上次触发滑动的时间（用于冷却判断）
    private var lastTriggeredTextHash: Int? = null // 上次触发时的识别文字哈希
    private var keywordConsecutiveTriggers = 0 // 同一段文字连续触发计数
    private var ocrFailureNotified = false // 是否已提示过 OCR 失败（避免反复弹提示）
    private var textRecognizer: TextRecognizer? = null // ML Kit 中文文字识别器
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main) // 截图/OCR 协程作用域
    /* 抖音自动连播状态 */
    private var douyinAutoPlayInProgress = false // 是否正在执行抖音连播开启流程（防止重复触发）
    private var lastDouyinAutoPlayAt = 0L // 上次执行抖音连播流程的时间（用于冷却）
    private var douyinSessionDone = false // 本次进入抖音是否已执行过连播流程（离开抖音后重置）
    private var douyinAutoPlayCompleted = false // 已成功打开连播后不再工作，直到下次启动 App 才重置

    /* 关键词检测循环 */
    private val keywordCheckRunnable = Runnable { runKeywordCheck() }
    /* 定时滑动循环 */
    private val slideRunnable = Runnable { runSlide() }
    /* 抖音前台检测轮询 */
    private val douyinWatchRunnable = Runnable { checkDouyinForeground() }

    /* 执行一次定时滑动 */
    private fun runSlide() {
        if (!isRunning) {
            return
        }
        performSlideByDirection(calculateGestureDurationMillis())
    }

    /* 息屏时强制停止滑动 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF || !isRunning) {
                return
            }
            forceStop()
        }
    }

    companion object {
        private const val TAG = "AutoSlideService"
        private const val MIN_GESTURE_DURATION_MS = 100L
        private const val MAX_GESTURE_DURATION_MS = 900L
        private const val NO_PAUSE_GAP_MS = 80L
        private const val SPEED_CURVE_FACTOR = 0.7
        private const val MIN_KEYWORD_INTERVAL_MS = 200
        private const val MAX_KEYWORD_INTERVAL_MS = 60_000
        private const val MIN_KEYWORD_COOLDOWN_MS = 500
        private const val MAX_KEYWORD_COOLDOWN_MS = 120_000
        /* 抖音自动连播相关常量 */
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        private const val DOUYIN_AUTOPLAY_TEXT = "自动连播"
        private const val DOUYIN_AUTOPLAY_COOLDOWN_MS = 5_000L
        private const val DOUYIN_WATCH_INTERVAL_MS = 10_000L
        private var instanceRef: WeakReference<AutoSlideService>? = null

        /**
         * 获取服务单例实例
         *
         * @return 当前服务实例
         */
        @JvmStatic
        fun getInstance(): AutoSlideService? = instanceRef?.get()
    }

    /**
     * 设置滑动方向
     *
     * @param direction 目标方向字符串(up/down/left/right)
     */
    fun setDirection(direction: String) {
        currentDirection = when (direction) {
            DIRECTION_UP, DIRECTION_DOWN, DIRECTION_LEFT, DIRECTION_RIGHT -> direction
            else -> DIRECTION_LEFT
        }
    }

    /**
     * 读取自定义轨迹字符串
     *
     * @param direction 方向字符串
     * @return 自定义轨迹字符串
     */
    private fun getCustomTrajectory(direction: String): String? {
        val key = getTrajectoryKey(direction) ?: return null
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val value = prefs.getString(key, null)
        return if (value.isNullOrBlank()) null else value
    }

    /**
     * 清除自定义轨迹
     *
     * @param direction 方向字符串
     */
    private fun clearCustomTrajectory(direction: String) {
        val key = getTrajectoryKey(direction) ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            remove(key)
        }
        SlideEventHub.sendEvent(SlideEvent.CustomTrajectoryCleared)
    }

    /**
     * 更新滑动速度而不触发启动逻辑
     *
     * @param newSpeed 最新速度值
     */
    fun updateSpeed(newSpeed: Int) {
        speed = newSpeed.coerceIn(1, 100)
    }

    /**
     * 安排下一次定时滑动
     *
     * @param currentGen 当前运行代数
     */
    private fun scheduleNextSlide(currentGen: Int = runGeneration) {
        if (isRunning && currentGen == runGeneration) {
            handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
        }
    }

    /**
     * 更新停顿配置参数
     *
     * @param mode 停顿模式
     * @param time 固定停顿时间
     * @param min 随机停顿下限
     * @param max 随机停顿上限
     */
    fun updatePauseConfig(mode: Int, time: Int, min: Int, max: Int) {
        pauseMode = mode
        pauseTime = time.coerceAtLeast(1)
        minPauseTime = min.coerceAtLeast(1)
        maxPauseTime = max.coerceAtLeast(1)
        if (!isRunning || isGestureActive || keywordModeActive) {
            return
        }
        // 移除当前滑动任务并重新调度新的停顿时间
        handler.removeCallbacks(slideRunnable)
        handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
    }

    /**
     * 实时更新关键词检测配置
     *
     * @param interval 检测间隔
     * @param cooldown 冷却时间
     */
    fun updateKeywordConfig(interval: Int, cooldown: Int) {
        keywordIntervalMs = interval.coerceIn(MIN_KEYWORD_INTERVAL_MS, MAX_KEYWORD_INTERVAL_MS)
        keywordCooldownMs = cooldown.coerceIn(MIN_KEYWORD_COOLDOWN_MS, MAX_KEYWORD_COOLDOWN_MS)
        Log.i(TAG, "Keyword config updated: interval=${keywordIntervalMs}ms, cooldown=${keywordCooldownMs}ms")
    }

    /**
     * 接收外部启动参数并开始自动滑动
     *
     * @param intent 启动参数(包含速度与停顿配置)
     * @param flags 系统启动标记
     * @param startId 启动请求ID
     * @return 固定返回START_STICKY
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.run {
            updateConfigFromIntent(this)
            startAutoSlide()
        }
        return START_STICKY
    }

   /**
    * 根据配置启动自动滑动
    *
    * @param speedVal 速度值
    * @param pauseModeVal 停顿模式
    * @param pauseTimeVal 固定停顿时间
    * @param minPauseVal 随机停顿下限
    * @param maxPauseVal 随机停顿上限
    */
    fun startSlideWithConfig(
        speedVal: Int, pauseModeVal: Int, pauseTimeVal: Int, minPauseVal: Int, maxPauseVal: Int
    ) {
        speed = speedVal.coerceIn(1, 100)
        pauseMode = pauseModeVal
        pauseTime = pauseTimeVal.coerceAtLeast(1)
        minPauseTime = minPauseVal.coerceAtLeast(1)
        maxPauseTime = maxPauseVal.coerceAtLeast(1)
        loadKeywordConfig()
        startAutoSlide()
    }

    /* 服务连接完成后初始化屏幕参数并注册单例 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        // 请求按键过滤能力(用于音量键强制停止滑动)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        registerScreenOffReceiver()
        loadKeywordConfig()
        loadKeywordDirection()
        douyinAutoPlayCompleted = false
        douyinSessionDone = false
        startDouyinWatchIfEnabled()
    }

    /* 服务销毁时停止滑动并释放单例 */
    override fun onDestroy() {
        unregisterScreenOffReceiver()
        stopSlide()
        handler.removeCallbacks(douyinWatchRunnable)
        serviceScope.cancel()
        runCatching { textRecognizer?.close() }
        textRecognizer = null
        instanceRef = null
        super.onDestroy()
    }

    /* 停止自动滑动循环 */
    fun stopSlide() {
        if (!isRunning) {
            return
        }
        isRunning = false
        isGestureActive = false
        keywordModeActive = false
        runGeneration++
        handler.removeCallbacks(slideRunnable)
        handler.removeCallbacks(keywordCheckRunnable)
    }

    /**
     * 无障碍事件回调：抖音连播由定时轮询驱动
     * 这里只在离开抖音时重置失败重试标记（成功完成后不再工作，直到下次启动 App）
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != DOUYIN_PACKAGE) {
            douyinSessionDone = false
        }
    }

    // 无障碍服务被系统中断时回调：无需额外处理
    override fun onInterrupt() = Unit

    /**
     * 监听音量键(在滑动运行中按音量键强制停止)
     *
     * @param event 物理按键事件
     * @return 是否已处理按键事件
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 只在按键按下且滑动正在运行时处理
        if (event.action != KeyEvent.ACTION_DOWN || !isRunning) {
            return super.onKeyEvent(event)
        }
        // 判断是否为音量键
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey) {
            return super.onKeyEvent(event)
        }
        // 强制停止滑动并恢复悬浮窗面板
        forceStop()
        return true
    }

    /* 强制停止滑动并恢复悬浮窗面板 */
    private fun forceStop() {
        stopSlide()
        SlideEventHub.sendEvent(SlideEvent.ForceStop)
    }

    /* 注册息屏广播 */
    private fun registerScreenOffReceiver() {
        if (isScreenOffReceiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isScreenOffReceiverRegistered = true
    }

    /* 注销息屏广播 */
    private fun unregisterScreenOffReceiver() {
        if (!isScreenOffReceiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(screenOffReceiver) }
        isScreenOffReceiverRegistered = false
    }

    /* 滑动起止坐标数据类 */
    private data class SlideCoordinates(
        val startX: Float, val startY: Float, val endX: Float, val endY: Float
    )

    /**
     * 根据滑动方向计算滑动起止坐标
     *
     * @param direction 滑动方向
     * @return 起止坐标
     */
    private fun getSlideCoordinates(direction: String): SlideCoordinates {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val centerX = width / 2f
        val centerY = height / 2f
        return when (direction) {
            DIRECTION_UP -> SlideCoordinates(centerX, height * 0.2f, centerX, height * 0.8f)
            DIRECTION_DOWN -> SlideCoordinates(centerX, height * 0.8f, centerX, height * 0.2f)
            DIRECTION_LEFT -> SlideCoordinates(width * 0.1f, centerY, width * 0.9f, centerY)
            DIRECTION_RIGHT -> SlideCoordinates(width * 0.9f, centerY, width * 0.1f, centerY)
            else -> SlideCoordinates(width * 0.1f, centerY, width * 0.9f, centerY)
        }
    }

    /**
     * 从Intent中读取运行参数
     *
     * @param intent 启动参数
     */
    private fun updateConfigFromIntent(intent: Intent) {
        speed = intent.getIntExtra(KEY_SPEED, DEFAULT_SPEED).coerceIn(1, 100)
        pauseMode = intent.getIntExtra(KEY_PAUSE_MODE, PAUSE_MODE_KEYWORD)
        pauseTime = intent.getIntExtra(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME).coerceAtLeast(1)
        minPauseTime = intent.getIntExtra(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME).coerceAtLeast(1)
        maxPauseTime = intent.getIntExtra(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME).coerceAtLeast(1)
    }

    /* 启动自动滑动循环 */
    private fun startAutoSlide() {
        isRunning = true
        isGestureActive = false
        keywordModeActive = pauseMode == PAUSE_MODE_KEYWORD && keywordList.isNotEmpty()
        lastKeywordTriggerAt = 0L
        keywordConsecutiveTriggers = 0
        lastTriggeredTextHash = null
        runGeneration++
        val currentGen = runGeneration
        handler.removeCallbacks(slideRunnable)
        handler.removeCallbacks(keywordCheckRunnable)
        // 延迟300ms执行第一次滑动/检测(等待悬浮窗完成最小化动画)
        handler.postDelayed({
            if (currentGen == runGeneration && isRunning) {
                if (keywordModeActive) {
                    scheduleKeywordCheck(0L)
                } else {
                    runSlide()
                }
            }
        }, 300L)
    }

    /**
     * 按当前方向执行一次滑动
     *
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun performSlideByDirection(durationMillis: Long) {
        // 读取自定义轨迹字符串
        val trajectoryStr = getCustomTrajectory(currentDirection)
        if (trajectoryStr != null) {
            // 分发自定义轨迹
            dispatchCustomGesture(trajectoryStr, durationMillis)
        } else {
            // 分发默认轨迹
            val (startX, startY, endX, endY) = getSlideCoordinates(currentDirection)
            dispatchLineGesture(startX, startY, endX, endY, durationMillis)
        }
    }

    /**
     * 分发自定义轨迹
     *
     * @param trajectoryStr 轨迹字符串
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun dispatchCustomGesture(trajectoryStr: String, durationMillis: Long) {
        // 按分号拆分轨迹字符串并去掉空项
        val pointsStr = trajectoryStr.split(";").filter { it.isNotBlank() }
        // 不足两个点视为无效数据清除后跳过本次滑动
        if (pointsStr.size < 2) {
            clearCustomTrajectory(currentDirection)
            continueAfterGesture(runGeneration)
            return
        }
        // 解析轨迹点
        val parsedPoints = pointsStr.mapNotNull { pointStr ->
            val xyValues = pointStr.split(",")
            if (xyValues.size == 2) {
                val x = xyValues[0].toFloatOrNull() ?: return@mapNotNull null
                val y = xyValues[1].toFloatOrNull() ?: return@mapNotNull null
                PointF(x, y)
            } else null
        }
        // 解析后有效点不足两个视为无效数据清除后跳过本次滑动
        if (parsedPoints.size < 2) {
            clearCustomTrajectory(currentDirection)
            continueAfterGesture(runGeneration)
            return
        }
        // 根据轨迹点构建手势路径
        val path = Path()
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val maxOffset = 5f * density
        // 为每个点添加轻微随机偏移并限制在屏幕范围内
        parsedPoints.forEachIndexed { index, point ->
            val xOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
            val yOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
            val finalX = (point.x + xOffset).coerceIn(0f, width.toFloat())
            val finalY = (point.y + yOffset).coerceIn(0f, height.toFloat())
            if (index == 0) {
                path.moveTo(finalX, finalY)
            } else {
                path.lineTo(finalX, finalY)
            }
        }
        // 构建自定义轨迹手势
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMillis)
        ).build()
        dispatchGestureAndContinue(gesture)
    }

    /**
     * 计算滑动手势持续时间
     *
     * @return 手势持续时间(毫秒)
     */
    private fun calculateGestureDurationMillis(): Long {
        val normalizedSpeed = speed.coerceIn(1, 100) / 100.0
        val curvedProgress = ln(1.0 + SPEED_CURVE_FACTOR * normalizedSpeed) / ln(1.0 + SPEED_CURVE_FACTOR)
        val durationRange = MAX_GESTURE_DURATION_MS - MIN_GESTURE_DURATION_MS
        return (MAX_GESTURE_DURATION_MS - durationRange * curvedProgress).roundToLong()
    }

    /**
     * 计算两次定时滑动之间的停顿时间
     *
     * @return 停顿时间(毫秒)
     */
    private fun calculatePauseDelayMillis(): Long = when (pauseMode) {
        PAUSE_MODE_FIXED -> pauseTime.coerceAtLeast(0) * 1000L
        PAUSE_MODE_RANDOM -> {
            val minMs = minPauseTime.coerceAtLeast(0) * 1000L
            val maxMs = maxPauseTime.coerceAtLeast(0) * 1000L
            val (lo, hi) = minOf(minMs, maxMs) to maxOf(minMs, maxMs)
            if (lo == hi) lo else (lo..hi).random(secureRandom)
        }

        else -> NO_PAUSE_GAP_MS
    }

    /**
     * 分发一条线性手势
     *
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param durationMillis 手势持续时间(毫秒)
     */
    private fun dispatchLineGesture(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long
    ) {
        val density = resources.displayMetrics.density
        val maxOffset = 10f * density
        // 计算起止坐标偏移量
        val startXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val startYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val endXOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        val endYOffset = ((secureRandom.nextDouble() * 2 - 1) * maxOffset).toFloat()
        // 计算实际起止坐标
        val actualStartX = startX + startXOffset
        val actualStartY = startY + startYOffset
        val actualEndX = endX + endXOffset
        val actualEndY = endY + endYOffset
        // 计算中点坐标
        val midX = (actualStartX + actualEndX) / 2
        val midY = (actualStartY + actualEndY) / 2
        // 计算控制点坐标偏移量
        val controlOffset = 15f * density
        // 计算控制点坐标
        val controlX = midX + ((secureRandom.nextDouble() * 2 - 1) * controlOffset).toFloat()
        val controlY = midY + ((secureRandom.nextDouble() * 2 - 1) * controlOffset).toFloat()
        // 构造贝塞尔曲线路径模拟真人滑动的自然微弯轨迹
        val path = Path().apply {
            moveTo(actualStartX, actualStartY)
            quadTo(controlX, controlY, actualEndX, actualEndY)
        }
        // 构建并分发手势
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMillis)
        ).build()
        dispatchGestureAndContinue(gesture)
    }

    /**
     * 分发手势并在结束后安排下一次滑动
     *
     * @param gesture 待分发的手势
     */
    private fun dispatchGestureAndContinue(gesture: GestureDescription) {
        isGestureActive = true
        val currentGen = runGeneration
        val success = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isGestureActive = false
                continueAfterGesture(currentGen)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onCompleted(gestureDescription)
            }
        }, handler)
        // 分发失败时手动复位并继续下一轮滑动
        if (!success) {
            isGestureActive = false
            continueAfterGesture(currentGen)
        }
    }

    /* ===== 抖音自动连播 ===== */

    /**
     * 处理抖音自动连播：先看界面里有没有「自动连播」
     * 没有就长按呼出菜单并上滑一次；如果还是没有，立即停止，绝不乱点其它按钮
     */
    private suspend fun handleDouyinAutoPlay() {
        // 1. 当前界面已能直接找到「自动连播」时，直接处理
        if (tryToggleDouyinAutoplaySwitch()) {
            finishDouyinAutoPlaySuccess()
            return
        }
        // 2. 长按呼出菜单 + 向上滑动露出「自动连播」
        dispatchLongPress()
        delay(550)
        dispatchSwipeUpLowerHalf()
        delay(550)
        // 3. 菜单弹出后再找一次；找不到就结束，不做任何多余点击
        if (tryToggleDouyinAutoplaySwitch()) {
            finishDouyinAutoPlaySuccess()
        } else {
            Log.w(TAG, "未找到「自动连播」开关，放弃操作，不乱点其它按钮")
            // 等菜单稳定后按返回键关闭菜单，避免菜单留在屏幕上
            delay(400)
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        }
        lastDouyinAutoPlayAt = SystemClock.elapsedRealtime()
    }

    /* 成功处理完「自动连播」后的收尾：标记完成、停止轮询、关闭菜单 */
    private suspend fun finishDouyinAutoPlaySuccess() {
        Log.i(TAG, "Douyin autoplay switch handled")
        douyinAutoPlayCompleted = true
        stopDouyinWatch()
        // 等开关动画结束后，关闭抖音弹出的菜单窗口
        delay(400)
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    /**
     * 在无障碍节点树中查找「自动连播」，读取开关真实状态后决定是否点击
     * 只点击开关节点本身，绝不点击整行，避免把已开启的开关误关
     *
     * @return true 表示已找到并处理（含本来已开启）；false 表示未找到，需要继续尝试
     */
    private fun tryToggleDouyinAutoplaySwitch(): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(DOUYIN_AUTOPLAY_TEXT)
        if (nodes.isEmpty()) return false
        val textNode = nodes.firstOrNull {
            it.text?.toString()?.trim() == DOUYIN_AUTOPLAY_TEXT
        } ?: nodes.first()
        val result = try {
            val switchNode = findSwitchNodeInRow(textNode)
            when {
                switchNode == null -> false // 找不到开关节点，无法判断状态，不盲目点击
                switchNode.isChecked -> true // 开关已经是开启状态，无需操作
                else -> switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
        return result
    }

    /**
     * 在「自动连播」文本节点所在行内查找真正的开关节点
     * 只在文本节点所在的那一行里找，绝不向上扩展到整个菜单容器，
     * 并且要求开关和文字在同一水平行内，避免误点其它行的开关（如「定时关闭」）
     *
     * @param node 文本节点
     * @return 找到的开关节点，未找到返回 null
     */
    private fun findSwitchNodeInRow(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val row = node.parent ?: return null
        if (isSwitchNode(node)) return node
        val candidate = findSwitchInChildren(row, depth = 2) ?: return null
        // 安全校验：开关必须和「自动连播」文字位于同一水平行
        val textRect = Rect()
        node.getBoundsInScreen(textRect)
        val switchRect = Rect()
        candidate.getBoundsInScreen(switchRect)
        val maxVerticalGap = maxOf(textRect.height(), switchRect.height()) * 1.2f
        return if (abs(switchRect.exactCenterY() - textRect.exactCenterY()) <= maxVerticalGap) {
            candidate
        } else {
            null
        }
    }

    /**
     * 在限定深度内查找子节点中的开关（只查这一行的两三层，不会扩散到其它行）
     *
     * @param node 起始节点
     * @param depth 剩余查找深度
     * @return 找到的开关节点，未找到返回 null
     */
    private fun findSwitchInChildren(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isSwitchNode(child)) return child
            val found = findSwitchInChildren(child, depth - 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * 判断节点是否为 Switch/CheckBox 开关
     *
     * @param node 节点
     * @return 是否为开关节点
     */
    private fun isSwitchNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase() ?: ""
        return node.isCheckable || className.contains("switch") || className.contains("checkbox") ||
            className.contains("toggle")
    }

    /* 长按屏幕中央偏下位置（触发抖音的更多菜单） */
    private suspend fun dispatchLongPress() {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.55f
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500L))
            .build()
        dispatchGestureAwait(gesture)
    }

    /* 向上滑动屏幕下半部分（把菜单里的「自动连播」划出来） */
    private suspend fun dispatchSwipeUpLowerHalf() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.75f)
            lineTo(width * 0.5f, height * 0.45f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300L))
            .build()
        dispatchGestureAwait(gesture)
    }

    /* 分发手势并等待手势执行完成 */
    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val success = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(true))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(false))
                }
            }, handler)
            if (!success && continuation.isActive) {
                continuation.resumeWith(Result.success(false))
            }
        }

    /* 开关打开且本次会话未完成时，开始定时轮询抖音前台检测 */
    private fun startDouyinWatchIfEnabled() {
        val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_DOUYIN_AUTOPLAY, DEFAULT_DOUYIN_AUTOPLAY)
        if (!enabled || douyinAutoPlayCompleted) {
            stopDouyinWatch()
            return
        }
        handler.removeCallbacks(douyinWatchRunnable)
        handler.postDelayed(douyinWatchRunnable, DOUYIN_WATCH_INTERVAL_MS)
    }

    /* 停止抖音检测轮询 */
    private fun stopDouyinWatch() {
        handler.removeCallbacks(douyinWatchRunnable)
    }

    /* 安排下一次轮询 */
    private fun scheduleNextDouyinWatch() {
        handler.postDelayed(douyinWatchRunnable, DOUYIN_WATCH_INTERVAL_MS)
    }

    /**
     * 检查当前前台应用是否为抖音；是且本次会话未执行过时启动连播流程
     */
    private fun checkDouyinForeground() {
        val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_DOUYIN_AUTOPLAY, DEFAULT_DOUYIN_AUTOPLAY)
        if (!enabled || douyinAutoPlayCompleted) {
            stopDouyinWatch()
            return
        }
        val packageName = rootInActiveWindow?.packageName?.toString()
        if (packageName != DOUYIN_PACKAGE) {
            douyinSessionDone = false
            scheduleNextDouyinWatch()
            return
        }
        if (!douyinSessionDone && !douyinAutoPlayInProgress) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDouyinAutoPlayAt >= DOUYIN_AUTOPLAY_COOLDOWN_MS) {
                douyinSessionDone = true
                douyinAutoPlayInProgress = true
                serviceScope.launch {
                    try {
                        handleDouyinAutoPlay()
                    } finally {
                        douyinAutoPlayInProgress = false
                    }
                }
            }
        }
        scheduleNextDouyinWatch()
    }

    /**
     * 由主界面开关调用：打开时开始轮询并允许重新执行，关闭时停止轮询
     *
     * @param enabled 抖音自动连播开关状态
     */
    fun setDouyinAutoPlayEnabled(enabled: Boolean) {
        douyinAutoPlayCompleted = false
        douyinSessionDone = false
        douyinAutoPlayInProgress = false
        if (enabled) {
            startDouyinWatchIfEnabled()
        } else {
            stopDouyinWatch()
        }
    }

    /* ===== 关键词检测（OCR） ===== */

    /**
     * 从本地配置读取关键词检测参数
     */
    private fun loadKeywordConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        keywordIgnoreCase = prefs.getBoolean(KEY_KEYWORD_IGNORE_CASE, DEFAULT_KEYWORD_IGNORE_CASE)
        keywordIntervalMs = (prefs.getInt(KEY_KEYWORD_INTERVAL, DEFAULT_KEYWORD_INTERVAL) * 1000)
            .coerceIn(MIN_KEYWORD_INTERVAL_MS, MAX_KEYWORD_INTERVAL_MS)
        keywordCooldownMs = (prefs.getInt(KEY_KEYWORD_COOLDOWN, DEFAULT_KEYWORD_COOLDOWN) * 1000)
            .coerceIn(MIN_KEYWORD_COOLDOWN_MS, MAX_KEYWORD_COOLDOWN_MS)
        keywordMaxTriggers = prefs.getInt(KEY_KEYWORD_MAX_TRIGGERS, DEFAULT_KEYWORD_MAX_TRIGGERS)
            .coerceIn(1, 50)
        keywordList = (prefs.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * 读取关键词触发方向并应用到当前滑动方向
     */
    private fun loadKeywordDirection() {
        val stored = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_KEYWORD_DIRECTION, DEFAULT_KEYWORD_DIRECTION)
        val direction = when (stored) {
            DIRECTION_UP, DIRECTION_DOWN, DIRECTION_LEFT, DIRECTION_RIGHT -> stored
            else -> DEFAULT_KEYWORD_DIRECTION
        }
        currentDirection = direction
    }

    /**
     * 安排下一次关键词检测
     *
     * @param delayMs 延迟毫秒数
     */
    private fun scheduleKeywordCheck(delayMs: Long) {
        if (!isRunning || !keywordModeActive) {
            return
        }
        val finalDelay = delayMs.coerceAtLeast(50)
        handler.removeCallbacks(keywordCheckRunnable) // 确保只有一个定时器在运行
        handler.postDelayed(keywordCheckRunnable, finalDelay)
        Log.d(TAG, "Keyword check scheduled: delay=${finalDelay}ms")
    }

    /**
     * 手势结束后的继续逻辑：关键词模式等待冷却，其他模式进入下一轮定时滑动
     *
     * @param currentGen 当前运行代数
     */
    private fun continueAfterGesture(currentGen: Int) {
        if (keywordModeActive) {
            scheduleKeywordCheck(keywordCooldownMs.toLong())
        } else {
            scheduleNextSlide(currentGen)
        }
    }

    /**
     * 执行一次关键词检测：截图 -> OCR -> 匹配
     */
    private fun runKeywordCheck() {
        if (!isRunning) {
            return
        }
        val currentGen = runGeneration
        serviceScope.launch {
            val bitmap = captureScreenBitmap()
            if (currentGen != runGeneration || !isRunning) {
                bitmap?.recycle()
                return@launch
            }
            val text = if (bitmap != null) recognizeText(bitmap) else ""
            bitmap?.recycle()
            if (currentGen != runGeneration || !isRunning) {
                return@launch
            }
            handleKeywordCheckResult(text, currentGen)
        }
    }

    /**
     * 处理一次 OCR 识别结果并决定是否触发滑动
     *
     * @param text 识别出的屏幕文字
     */
    private fun handleKeywordCheckResult(text: String, currentGen: Int) {
        if (currentGen != runGeneration || !isRunning) return
        Log.d(TAG, "OCR text: $text")
        val now = SystemClock.elapsedRealtime()
        val elapsedSinceTrigger = now - lastKeywordTriggerAt
        // 冷却期内不再触发
        if (elapsedSinceTrigger < keywordCooldownMs) {
            val remaining = keywordCooldownMs - elapsedSinceTrigger
            Log.d(TAG, "In cooldown, skipping. Remaining: ${remaining}ms")
            scheduleKeywordCheck(remaining)
            return
        }
        // 未命中关键词则重置连续触发计数
        if (!matchesKeyword(text)) {
            keywordConsecutiveTriggers = 0
            lastTriggeredTextHash = null
            Log.d(TAG, "Keyword not matched. Next check in ${keywordIntervalMs}ms")
            scheduleKeywordCheck(keywordIntervalMs.toLong())
            return
        }
        // 同一画面文字最多连续触发指定次数，防止疯狂滑动
        val textHash = text.hashCode()
        if (textHash != lastTriggeredTextHash) {
            keywordConsecutiveTriggers = 0
            lastTriggeredTextHash = textHash
        }
        if (keywordConsecutiveTriggers >= keywordMaxTriggers) {
            Log.d(TAG, "Max triggers reached for this screen text. Waiting interval.")
            scheduleKeywordCheck(keywordIntervalMs.toLong())
            return
        }
        keywordConsecutiveTriggers++
        lastKeywordTriggerAt = now
        Log.i(TAG, "Keyword matched! Swiping. (Trigger $keywordConsecutiveTriggers/$keywordMaxTriggers)")
        performSlideByDirection(calculateGestureDurationMillis())
    }

    /**
     * 判断识别文字是否包含任一关键词
     *
     * @param text 识别出的屏幕文字
     * @return 是否命中
     */
    private fun matchesKeyword(text: String): Boolean {
        if (keywordList.isEmpty() || text.isBlank()) {
            return false
        }
        val haystack = if (keywordIgnoreCase) text.lowercase() else text
        return keywordList.any { keyword ->
            val target = if (keywordIgnoreCase) keyword.lowercase() else keyword
            target.isNotEmpty() && haystack.contains(target)
        }
    }

    /**
     * 截取当前屏幕
     *
     * @return 屏幕位图，失败时返回 null
     */
    private suspend fun captureScreenBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenViaAccessibility()
        } else {
            captureScreenViaShizuku()
        }
    }

    /**
     * 通过无障碍服务截图（Android 11+）
     *
     * @return 屏幕位图，失败时返回 null
     */
    @SuppressLint("NewApi")
    private suspend fun captureScreenViaAccessibility(): Bitmap? {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            suspendCancellableCoroutine { continuation ->
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = try {
                                val buffer = screenshot.hardwareBuffer
                                val result = Bitmap.wrapHardwareBuffer(buffer, null)
                                buffer.close()
                                result
                            } catch (e: Exception) {
                                Log.w(TAG, "HardwareBuffer to Bitmap failed", e)
                                null
                            }
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(bitmap))
                            } else {
                                bitmap?.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Accessibility screenshot failed, code=$errorCode")
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(null))
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "takeScreenshot error", e)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(null))
                    }
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * 通过 Shizuku 执行 screencap 截图（Android 8-10 备用方案）
     *
     * @return 屏幕位图，失败时返回 null
     */
    private suspend fun captureScreenViaShizuku(): Bitmap? = withContext(Dispatchers.IO) {
        val granted = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            if (!ocrFailureNotified) {
                ocrFailureNotified = true
                Toast.makeText(this@AutoSlideService, R.string.keyword_need_android_11, Toast.LENGTH_LONG).show()
            }
            return@withContext null
        }
        runCatching {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply {
                isAccessible = true
            }
            val process = newProcessMethod.invoke(
                null, arrayOf("/system/bin/screencap", "-p"), null, null
            ) as ShizukuRemoteProcess
            val bytes = process.inputStream.use { it.readBytes() }
            val exitCode = process.waitFor()
            process.destroy()
            if (exitCode == 0) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
        }.getOrNull()
    }

    /**
     * 使用 ML Kit 中文模型识别位图中的文字
     *
     * @param bitmap 屏幕位图
     * @return 识别出的文字
     */
    private suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            val recognizer = textRecognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            ).also { textRecognizer = it }
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            result.text
        } catch (e: Exception) {
            if (!ocrFailureNotified) {
                ocrFailureNotified = true
                Log.e(TAG, "OCR failed", e)
                Toast.makeText(this@AutoSlideService, R.string.keyword_ocr_failed, Toast.LENGTH_LONG).show()
            }
            ""
        }
    }
}
