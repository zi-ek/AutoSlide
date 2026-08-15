package com.ziek.autoslide.service

/**
 * 自动滑动无障碍服务（核心服务）
 *
 * 通过无障碍服务执行屏幕滑动手势：
 * - 定时滑动模式（不停顿/固定时间/随机时间）：按设定节奏循环滑动；
 * - 关键词检测模式：定时截屏 + OCR 识别，命中关键词才滑动一次。
 * 同时支持音量键强制停止、息屏自动停止、自定义轨迹回放等能力。
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.ziek.autoslide.LogX
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import com.ziek.autoslide.A11yState
import com.ziek.autoslide.DEFAULT_AUTO_TAP_ENABLED
import com.ziek.autoslide.DEFAULT_DOUYIN_AUTOPLAY
import com.ziek.autoslide.DEFAULT_KEYWORDS
import com.ziek.autoslide.DEFAULT_KEYWORD_COOLDOWN
import com.ziek.autoslide.DEFAULT_KEYWORD_DIRECTION
import com.ziek.autoslide.DEFAULT_KEYWORD_IGNORE_CASE
import com.ziek.autoslide.DEFAULT_KEYWORD_INTERVAL
import com.ziek.autoslide.DEFAULT_KEYWORD_MAX_TRIGGERS
import com.ziek.autoslide.DEFAULT_MAX_PAUSE_TIME
import com.ziek.autoslide.DEFAULT_MIN_PAUSE_TIME
import com.ziek.autoslide.DEFAULT_PAUSE_TIME
import com.ziek.autoslide.DEFAULT_SKIP_KEYWORDS
import com.ziek.autoslide.DEFAULT_SPEED
import com.ziek.autoslide.DIRECTION_DOWN
import com.ziek.autoslide.DIRECTION_LEFT
import com.ziek.autoslide.DIRECTION_RIGHT
import com.ziek.autoslide.DIRECTION_UP
import com.ziek.autoslide.KEY_DOUYIN_AUTOPLAY
import com.ziek.autoslide.KEY_AUTO_TAP_ENABLED
import com.ziek.autoslide.KEY_FLOATING_DESIRED
import com.ziek.autoslide.KEY_MACRO_PREFIX
import com.ziek.autoslide.KEY_KEYWORDS
import com.ziek.autoslide.KEY_KEYWORD_COOLDOWN
import com.ziek.autoslide.KEY_KEYWORD_DIRECTION
import com.ziek.autoslide.KEY_KEYWORD_IGNORE_CASE
import com.ziek.autoslide.KEY_KEYWORD_INTERVAL
import com.ziek.autoslide.KEY_KEYWORD_MAX_TRIGGERS
import com.ziek.autoslide.KEY_MAX_PAUSE_TIME
import com.ziek.autoslide.KEY_MIN_PAUSE_TIME
import com.ziek.autoslide.KEY_PAUSE_MODE
import com.ziek.autoslide.KEY_PAUSE_TIME
import com.ziek.autoslide.KEY_SKIP_KEYWORDS
import com.ziek.autoslide.KEY_SPEED
import com.ziek.autoslide.KEY_STATS_KEYWORD_MATCHES
import com.ziek.autoslide.KEY_STATS_SAVED_DISTANCE
import com.ziek.autoslide.KEY_STATS_TOTAL_SWIPES
import com.ziek.autoslide.PAUSE_MODE_FIXED
import com.ziek.autoslide.PAUSE_MODE_KEYWORD
import com.ziek.autoslide.PAUSE_MODE_NONE
import com.ziek.autoslide.PAUSE_MODE_RANDOM
import com.ziek.autoslide.PREFS_NAME
import com.ziek.autoslide.R
import com.ziek.autoslide.SlideEvent
import com.ziek.autoslide.SlideEventHub
import com.ziek.autoslide.input.AutoSlideInput
import com.ziek.autoslide.input.AutoSlideInputAction
import com.ziek.autoslide.input.AutoSlideInputCodec
import com.ziek.autoslide.parseKeywords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.random.asKotlinRandom

/**
 * 自动滑动无障碍服务
 *
 * @author tianxing
 */
@SuppressLint("AccessibilityPolicy")
open class AutoSlideService : AccessibilityService() {

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
    private var keywordCheckActive = false // 是否运行关键词检测循环（关键词模式或固定时间模式）
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
    // 截图专用单线程池：整个服务生命周期复用一个线程，避免关键词模式下每次检测都新建/销毁线程池
    private val screenshotExecutor: java.util.concurrent.ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    /* 抖音自动连播状态 */
    private var douyinAutoPlayInProgress = false // 是否正在执行抖音连播开启流程（防止重复触发）
    private var lastDouyinAutoPlayAt = 0L // 上次执行抖音连播流程的时间（用于冷却）
    private var douyinSessionDone = false // 本次进入抖音是否已执行过连播流程（离开抖音后重置）
    private var douyinAutoPlayCompleted = false // 已成功打开连播后不再工作，直到下次启动 App 才重置
    private var lastPushDialogDismissAt = 0L // 上次自动点「忽略」的时间（冷却，避免重复点击）
    /* 关键词检测循环 */
    private val keywordCheckRunnable = Runnable { runKeywordCheck() }
    /* 定时滑动循环 */
    private val slideRunnable = Runnable { runSlide() }
    /* 常驻「跳过」检测状态 */
    @Volatile
    private var persistentSkipEnabled = true // 常驻检测总开关（通知「停止」后关闭）
    private var lastSkipTapAt = 0L // 上次点击跳过按钮的时间（冷却用）
    private var lastOcrAt = 0L // 上次 OCR 兜底识别的时间（节流用）
    /* 自动点击事件驱动的去抖：连续界面事件合并成一次检查，界面静止时完全不唤醒 */
    private var autoTapCheckPending = false
    /* 互斥：同一时间只允许一个自动点击检查执行，防止并发重复点击（CAS 原子抢占） */
    private val autoTapChecking = AtomicBoolean(false)
    private val autoTapCheckRunnable = Runnable {
        autoTapCheckPending = false
        // 在后台线程执行节点树遍历与 OCR，避免占用主线程
        serviceScope.launch(Dispatchers.Default) { checkAndTapSkipOnce() }
    }
    /* 自动点击「跳过」的匹配关键词列表（用户可自行添加，OCR 命中后自动点击该文字位置） */
    @Volatile
    private var skipKeywordList: List<String> = parseKeywords(DEFAULT_SKIP_KEYWORDS)
    /* 自动点击总开关：主页开关控制，开启时始终检测并点击关键词，关闭时完全停止 */
    @Volatile
    private var autoTapEnabled = DEFAULT_AUTO_TAP_ENABLED
    /* 无障碍保活窗口：1x1 TYPE_ACCESSIBILITY_OVERLAY，让无障碍服务持有持续窗口（参考 GKD；不是免杀，仅部分 ROM 下有助于保活） */
    private var aliveOverlayView: View? = null

    /* 更新统计数据 */
    private fun updateStats(swipes: Int = 0, matches: Int = 0, distanceMm: Int = 0) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {
            if (swipes > 0) putInt(KEY_STATS_TOTAL_SWIPES, prefs.getInt(KEY_STATS_TOTAL_SWIPES, 0) + swipes)
            if (matches > 0) putInt(KEY_STATS_KEYWORD_MATCHES, prefs.getInt(KEY_STATS_KEYWORD_MATCHES, 0) + matches)
            if (distanceMm > 0) putInt(KEY_STATS_SAVED_DISTANCE, prefs.getInt(KEY_STATS_SAVED_DISTANCE, 0) + distanceMm)
        }
    }

    private fun runSlide() {
        if (!isRunning) {
            return
        }
        if (isGestureActive) {
            // 已有手势在执行（例如关键词触发的滑动），跳过本次并顺延到下一轮
            scheduleNextSlide()
            return
        }
        performSlideByDirection(calculateGestureDurationMillis())
    }

    /* 息屏时强制停止滑动（抖音连播检测已改为事件驱动，无需在这里额外处理） */
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
private const val MAX_GESTURE_DURATION_MS = 3000L
private const val NO_PAUSE_GAP_MS = 80L
private const val GESTURE_CALLBACK_TIMEOUT_MS = 3000L
/* 宏回放等待条件：节点树轮询间隔（毫秒） */
private const val MACRO_WAIT_POLL_INTERVAL_MS = 300L
/* 跳过按钮点击冷却，防止关键词检测与常驻检测重复点击 */
private const val SKIP_TAP_COOLDOWN_MS = 1500L
/* 自动点击事件去抖：界面变化事件频繁时合并成一次检查 */
private const val AUTO_TAP_DEBOUNCE_MS = 150L
/* OCR 兜底节流：节点树找不到关键词时，最多每 2 秒截图识别一次 */
private const val SKIP_OCR_FALLBACK_INTERVAL_MS = 2000L
/* 节点树重试延迟：广告按钮刚出现时节点树往往还没填充文字，等 0.5 秒再查一次 */
private const val NODE_TREE_RETRY_DELAY_MS = 500L
/* 节点树扫描上限：防止超大节点树导致卡顿（扫到上限还没命中就交给 OCR 兜底） */
private const val SKIP_NODE_SCAN_LIMIT = 2000
/* 跳过文字最大长度：防止把正文长句当成按钮（GKD 同款） */
private const val SKIP_TEXT_MAX_LENGTH = 10
/* 排除词清单：这些文字即使含关键词也不算跳过按钮（GKD 同款防误触） */
private val SKIP_EXCLUDE_WORDS = listOf(
    "搜索", "历史记录", "在搜", "阅读并同意", "书签", "选好了", "设置", "完成", "下一步", "跳过片"
)
/* 回放去重：同一位置重复点击的最大间隔（毫秒）与判定距离（dp） */
private const val DUPLICATE_TAP_MAX_GAP_MS = 200L
private const val DUPLICATE_TAP_DISTANCE_DP = 8f
private const val SPEED_CURVE_FACTOR = 0.7
        private const val MIN_KEYWORD_INTERVAL_MS = 500
        private const val MAX_KEYWORD_INTERVAL_MS = 60_000
        private const val MIN_KEYWORD_COOLDOWN_MS = 1000
        private const val MAX_KEYWORD_COOLDOWN_MS = 120_000
        /* 回放时单次等待的上限（毫秒），防止误录的超长等待拖死循环 */
        private const val MAX_REPLAY_GAP_MS = 120_000L
        /* 精准匹配（屏幕文字 == 任务名称）的得分门槛 */
        private const val EXACT_MATCH_SCORE = 10000
        /* 抖音/快手自动连播相关常量 */
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        private const val DOUYIN_AUTOPLAY_TEXT = "自动连播"
        private const val KUAISHOU_PACKAGE = "com.smile.gifmaker"
        private const val KUAISHOU_LITE_PACKAGE = "com.kuaishou.nebula"
        private const val KUAISHOU_AUTOPLAY_TEXT = "自动上滑"
        private const val DOUYIN_AUTOPLAY_COOLDOWN_MS = 5_000L
        /* 推送通知弹窗：识别到标题后自动点「忽略」 */
        private const val PUSH_NOTIFICATION_DIALOG_TEXT = "打开推送通知"
        private const val PUSH_IGNORE_TEXT = "忽略"
        private const val PUSH_DIALOG_DISMISS_COOLDOWN_MS = 5_000L
        /* 快手自定义开关的状态：1=开 0=关 -1=未知（无法判断时禁止点击） */
        private const val KUAISHOU_STATE_ON = 1
        private const val KUAISHOU_STATE_OFF = 0
        private const val KUAISHOU_STATE_UNKNOWN = -1
        // 强引用持有实例（与 GKD 一致）：服务对象本就由系统持有，
        // 用弱引用反而可能在极端时机拿到 null，导致悬浮窗误报「服务未连接」
        @Volatile
        private var instance: AutoSlideService? = null

        /**
         * 获取服务单例实例
         *
         * @return 当前服务实例
         */
        @JvmStatic
        fun getInstance(): AutoSlideService? = instance
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
     * 读取指定名称的录制记录（PlainApp 输入框架：JSON 数组）
     *
     * @param name 录制名称
     * @return 输入序列；不存在或数据无效返回 null
     */
    fun getMacro(name: String): List<AutoSlideInput>? {
        if (name.isBlank()) return null
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_MACRO_PREFIX + name, null) ?: return null
        if (raw.isBlank()) return null
        // 优先解析新版 JSON 格式
        AutoSlideInputCodec.decode(raw)?.let { return it }
        // 兼容旧版“x,y;x,y;...”单路径格式
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        return AutoSlideInputCodec.decodeLegacyPath(raw, width, height)
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
        val keywordCheckWasActive = keywordCheckActive
        updateKeywordCheckFlags()
        // 刚切换到需要关键词检测的模式时，立即启动一次检测
        if (isRunning && keywordCheckActive && !keywordCheckWasActive) {
            scheduleKeywordCheck(0L)
        }
        if (!isRunning || isGestureActive || keywordModeActive) {
            return
        }
        // 移除当前滑动任务并重新调度新的停顿时间
        handler.removeCallbacks(slideRunnable)
        handler.postDelayed(slideRunnable, calculatePauseDelayMillis())
    }

    /* 根据当前停顿模式刷新关键词检测循环标记 */
    private fun updateKeywordCheckFlags() {
        keywordModeActive = pauseMode == PAUSE_MODE_KEYWORD && keywordList.isNotEmpty()
        keywordCheckActive = keywordModeActive
        if (!keywordCheckActive) {
            handler.removeCallbacks(keywordCheckRunnable)
        }
    }

    /**
     * 实时更新关键词检测配置
     *
     * @param interval 检测间隔
     * @param cooldown 冷却时间
     * @param keywords 关键词列表文本
     * @param ignoreCase 是否忽略大小写
     */
    fun updateKeywordConfig(
        interval: Int? = null,
        cooldown: Int? = null,
        keywords: String? = null,
        ignoreCase: Boolean? = null
    ) {
        interval?.let {
            keywordIntervalMs = it.coerceIn(MIN_KEYWORD_INTERVAL_MS, MAX_KEYWORD_INTERVAL_MS)
        }
        cooldown?.let {
            keywordCooldownMs = it.coerceIn(MIN_KEYWORD_COOLDOWN_MS, MAX_KEYWORD_COOLDOWN_MS)
        }
        keywords?.let {
            keywordList = parseKeywords(it)
        }
        ignoreCase?.let {
            keywordIgnoreCase = it
        }
        updateKeywordCheckFlags()
        LogX.i(
            TAG,
            "Keyword config updated: interval=${keywordIntervalMs}ms, cooldown=${keywordCooldownMs}ms, keywords=${keywordList.size}, ignoreCase=$keywordIgnoreCase"
        )
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

    /**
     * 服务进程创建即置存活标记（早于 onServiceConnected）。
     * 自愈逻辑用这个标记判断「无障碍是不是真的死了」，比等 onServiceConnected 更准。
     */
    override fun onCreate() {
        super.onCreate()
        A11yState.a11yRunningFlow.value = true
        // 无障碍一起来就拉起常驻前台服务（GKD: onCreated { StatusService.autoStart() }）
        runCatching { StatusService.autoStart(this) }
    }

    /* 服务连接完成后初始化屏幕参数并注册单例 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerScreenOffReceiver()
        loadKeywordConfig()
        loadSkipConfig()
        loadKeywordDirection()
        douyinAutoPlayCompleted = false
        douyinSessionDone = false
        // 无障碍服务连接后开启常驻「跳过」检测（点开始/启动滑动时 StatusService 也会再次开启）
        setPersistentSkipEnabled(true)
        // 添加 1x1 无障碍保活窗口（参考 GKD；对普通清理有辅助作用，对 force-stop 无效）
        addAliveOverlayView()
        // 自动启动常驻服务（参考 GKD：无障碍一连接就拉起前台服务，MIUI 清理时保留）
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, StatusService::class.java))
        }
        // 进程被清理后是靠系统重新绑定无障碍复活的，这里顺带把悬浮球恢复出来，
        // 否则用户看到的依然是「App 被清理了」
        restoreFloatingWindowIfNeeded()
        // 不再启动固定间隔轮询，改为在 onAccessibilityEvent 里事件驱动触发，省电
    }

    /* 服务销毁时停止滑动并释放单例 */
    override fun onDestroy() {
        removeAliveOverlayView()
        unregisterScreenOffReceiver()
        stopSlide()
        serviceScope.cancel()
        runCatching { textRecognizer?.close() }
        textRecognizer = null
        runCatching { screenshotExecutor.shutdown() }
        instance = null
        // 标记无障碍已死，供自愈逻辑判断；若用户仍希望服务开启，下一个入口会把它拉回来
        A11yState.a11yRunningFlow.value = false
        super.onDestroy()
    }

    /**
     * 若用户此前开启过悬浮窗且权限仍在，则重新拉起悬浮窗服务。
     * 用于进程被 MIUI 清理后、由系统重新绑定无障碍而复活的场景。
     */
    private fun restoreFloatingWindowIfNeeded() {
        val wanted = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_FLOATING_DESIRED, false)
        if (!wanted || FloatingWindowService.isRunning()) {
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            LogX.w(TAG, "floating window wanted but overlay permission missing")
            return
        }
        runCatching { startService(Intent(this, FloatingWindowService::class.java)) }
            .onSuccess { LogX.i(TAG, "floating window restored after process revival") }
            .onFailure { LogX.w(TAG, "restore floating window failed", it) }
    }

    /* 添加 1x1 无障碍保活窗口（系统信任无障碍覆盖层，不触发“上层显示”提示） */
    private fun addAliveOverlayView() {
        removeAliveOverlayView()
        val tempView = View(this)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags =
                flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            packageName = this@AutoSlideService.packageName
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(tempView, lp)
            aliveOverlayView = tempView
        } catch (e: Throwable) {
            aliveOverlayView = null
            LogX.w(TAG, "Failed to add alive overlay view", e)
        }
    }

    /* 移除无障碍保活窗口 */
    private fun removeAliveOverlayView() {
        val view = aliveOverlayView ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        aliveOverlayView = null
    }

    /**
     * 停止滑动并重置所有状态标记
     */
    fun stopSlide() {
        isRunning = false
        isGestureActive = false
        keywordModeActive = false
        keywordCheckActive = false
        runGeneration++
        handler.removeCallbacks(slideRunnable)
        handler.removeCallbacks(keywordCheckRunnable)
    }

    /**
     * 无障碍事件回调：不再用定时器轮询前台包名，而是直接复用系统已经在分发的无障碍事件来判断
     * 是否进入了抖音，省去了后台每 10 秒主动唤醒 CPU 查询前台应用的开销。
     * 离开抖音时重置会话标记；进入抖音时尝试触发连播检测（内部有冷却和去重，不会频繁执行）。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != DOUYIN_PACKAGE && packageName != KUAISHOU_PACKAGE &&
            packageName != KUAISHOU_LITE_PACKAGE
        ) {
            douyinSessionDone = false
        } else {
            tryStartDouyinAutoPlay(packageName)
        }
        // 自动处理「打开推送通知」弹窗：点一次「忽略」
        maybeDismissPushNotificationDialog()
        // 自动点击事件驱动：页面/内容变化时触发检查（复用系统已分发的无障碍事件，平时零轮询）
        if (packageName != this.packageName &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            scheduleAutoTapCheck()
        }
    }

    // 无障碍服务被系统中断时回调：无需额外处理
    override fun onInterrupt() = Unit

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
        updateKeywordCheckFlags()
        lastKeywordTriggerAt = 0L
        keywordConsecutiveTriggers = 0
        lastTriggeredTextHash = null
        runGeneration++
        val currentGen = runGeneration
        handler.removeCallbacks(slideRunnable)
        handler.removeCallbacks(keywordCheckRunnable)
        handler.postDelayed({
            if (currentGen == runGeneration && isRunning) {
                if (keywordCheckActive) {
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
    private fun performSlideByDirection(durationMillis: Long, fromKeyword: Boolean = false) {
        // 录制库与滑动循环解耦：方向键只执行默认线性滑动，录制内容由回放按钮选择执行
        val (startX, startY, endX, endY) = getSlideCoordinates(currentDirection)
        dispatchLineGesture(startX, startY, endX, endY, durationMillis, fromKeyword)
    }

    /**
     * 派发单个输入动作（对应 PlainApp 的 dispatchControl：动作 + 归一化坐标 + 时长）
     *
     * @param input 输入动作
     * @param width 当前屏幕宽度（像素）
     * @param height 当前屏幕高度（像素）
     * @return 手势是否成功派发
     */
    private suspend fun dispatchOneInput(input: AutoSlideInput, width: Int, height: Int): Boolean {
        val path = Path()
        return when (input.action) {
            AutoSlideInputAction.TAP -> {
                path.moveTo(input.x * width, input.y * height)
                dispatchGestureBlocking(buildGesture(path, input.duration.coerceIn(60L, 500L)))
            }

            AutoSlideInputAction.LONG_PRESS -> {
                path.moveTo(input.x * width, input.y * height)
                dispatchGestureBlocking(buildGesture(path, input.duration.coerceIn(500L, 3000L)))
            }

            AutoSlideInputAction.SWIPE -> {
                val points = input.points
                if (points.size >= 4) {
                    var first = true
                    var i = 0
                    while (i + 1 < points.size) {
                        val px = (points[i] * width).coerceIn(0f, width.toFloat())
                        val py = (points[i + 1] * height).coerceIn(0f, height.toFloat())
                        if (first) {
                            path.moveTo(px, py)
                            first = false
                        } else {
                            path.lineTo(px, py)
                        }
                        i += 2
                    }
                } else {
                    path.moveTo(input.x * width, input.y * height)
                    path.lineTo(input.endX * width, input.endY * height)
                }
                dispatchGestureBlocking(buildGesture(path, input.duration.coerceIn(80L, 2000L)))
            }

            AutoSlideInputAction.BACK -> {
                // 执行系统返回，并留一点时间等页面切换动画完成
                val ok = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (ok) {
                    delay(400L)
                }
                ok
            }

            AutoSlideInputAction.WAIT_FOR -> {
                // 等待条件由 playMacro 专门处理，不会走到这里（防御分支）
                true
            }
        }
    }

    /* 构建单个手势描述 */
    private fun buildGesture(path: Path, durationMillis: Long): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
            .build()

    /**
     * 等待手势执行完成（不修改 isGestureActive）
     *
     * @param gesture 待派发的手势
     * @return 是否成功完成
     */
    private suspend fun dispatchGestureBlocking(gesture: GestureDescription): Boolean =
        withTimeoutOrNull(GESTURE_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val success = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(true))
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(false))
                        }
                    }
                }, handler)
                if (!success && continuation.isActive) {
                    continuation.resumeWith(Result.success(false))
                }
            }
        } ?: false

    /**
     * 计算单个输入动作的路径长度（像素），用于统计
     *
     * @param input 输入动作
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @return 路径长度（像素）
     */
    private fun inputPathLength(input: AutoSlideInput, width: Int, height: Int): Float {
        val points = input.points
        if (input.action == AutoSlideInputAction.SWIPE && points.size >= 4) {
            var total = 0f
            var prevX = points[0] * width
            var prevY = points[1] * height
            var i = 2
            while (i + 1 < points.size) {
                val x = points[i] * width
                val y = points[i + 1] * height
                total += hypot(x - prevX, y - prevY)
                prevX = x
                prevY = y
                i += 2
            }
            return total
        }
        return hypot((input.endX - input.x) * width, (input.endY - input.y) * height)
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
        startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long,
        fromKeyword: Boolean = false
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

        // 统计数据
        val height = resources.displayMetrics.heightPixels
        val distanceMm = (height * 0.6f / resources.displayMetrics.density / 6.0f).toInt().coerceAtLeast(1)
        updateStats(swipes = 1, distanceMm = distanceMm)

        dispatchGestureAndContinue(gesture, fromKeyword)
    }

    /**
     * 分发手势并在结束后安排下一次滑动
     *
     * @param gesture 待分发的手势
     */
    private fun dispatchGestureAndContinue(gesture: GestureDescription, fromKeyword: Boolean = false) {
        isGestureActive = true
        val currentGen = runGeneration
        val success = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isGestureActive = false
                continueAfterGesture(currentGen, fromKeyword)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onCompleted(gestureDescription)
            }
        }, handler)
        // 分发失败时手动复位并继续下一轮滑动
        if (!success) {
            isGestureActive = false
            continueAfterGesture(currentGen, fromKeyword)
        }
    }

    /* ===== 抖音自动连播 ===== */

    /**
     * 处理抖音自动连播：先看界面里有没有「自动连播」
     * 没有就长按呼出菜单并上滑一次；如果还是没有，立即停止，绝不乱点其它按钮
     */
    private suspend fun handleDouyinAutoPlay(packageName: String) {
        val targetText = autoplayTextFor(packageName)
        // 先处理可能存在的推送通知弹窗，避免干扰后续手势
        maybeDismissPushNotificationDialog()
        // 1. 当前界面已能直接找到「自动连播」时，直接处理
        if (tryHandleAutoplaySwitch(targetText)) {
            finishDouyinAutoPlaySuccess(targetText)
            return
        }
        // 2. 长按呼出菜单 + 向上滑动露出「自动连播」
        // 长按前先记一下屏幕亮度，用于长按后判断菜单是否真的弹出来了（有些菜单文字不在无障碍树里）
        val brightnessBefore = screenBrightness()
        dispatchLongPress()
        delay(550)
        dispatchSwipeUpLowerHalf()
        delay(550)
        // 3. 菜单弹出后再找一次；找不到就结束，不做任何多余点击
        if (tryHandleAutoplaySwitch(targetText)) {
            finishDouyinAutoPlaySuccess(targetText)
        } else {
            LogX.w(TAG, "未找到「$targetText」开关，放弃操作，不乱点其它按钮")
            // 等菜单稳定后，只有菜单还开着才按返回关闭，避免误退当前页面
            delay(400)
            if (isMenuStillOpen(targetText) || isLongPressMenuVisible(brightnessBefore)) {
                LogX.i(TAG, "长按菜单仍处于打开状态，按返回键关闭菜单")
                runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
            }
        }
        lastDouyinAutoPlayAt = SystemClock.elapsedRealtime()
    }

    /* 根据前台应用返回需要查找的开关文字（抖音：自动连播；快手：自动上滑） */
    private fun autoplayTextFor(packageName: String): String {
        return if (packageName == KUAISHOU_PACKAGE || packageName == KUAISHOU_LITE_PACKAGE) {
            KUAISHOU_AUTOPLAY_TEXT
        } else {
            DOUYIN_AUTOPLAY_TEXT
        }
    }

    /* 检测「打开推送通知」弹窗，存在时自动点击「忽略」按钮（带冷却，避免重复点击） */
    private fun maybeDismissPushNotificationDialog() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPushDialogDismissAt < PUSH_DIALOG_DISMISS_COOLDOWN_MS) {
            return
        }
        val root = rootInActiveWindow ?: return
        val titleNodes = root.findAccessibilityNodeInfosByText(PUSH_NOTIFICATION_DIALOG_TEXT)
        if (titleNodes.isEmpty()) {
            return
        }
        titleNodes.forEach { runCatching { it.recycle() } }
        // 弹窗存在：优先找「忽略」按钮点击
        val ignoreNodes = root.findAccessibilityNodeInfosByText(PUSH_IGNORE_TEXT)
        var clicked = false
        try {
            for (node in ignoreNodes) {
                if (node.text?.toString()?.trim() != PUSH_IGNORE_TEXT) continue
                val target = if (node.isClickable) node else node.parent
                clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                if (clicked) {
                    LogX.i(TAG, "已自动点击「忽略」关闭推送通知弹窗")
                    break
                }
            }
        } finally {
            ignoreNodes.forEach { runCatching { it.recycle() } }
        }
        // 没有「忽略」按钮时（另一种弹窗格式），找关闭按钮（关闭/取消）
        if (!clicked && tryClickCloseButton(root)) {
            clicked = true
            LogX.i(TAG, "已自动点击关闭按钮关闭推送通知弹窗")
        }
        // 最后兜底：按返回键关闭弹窗（绝不点「立即开启」）
        if (!clicked) {
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
            clicked = true
            LogX.i(TAG, "未找到忽略/关闭按钮，使用返回键关闭推送通知弹窗")
        }
        if (clicked) {
            lastPushDialogDismissAt = now
        }
    }

    /* 在节点树中查找 content-desc 为「关闭/取消」的可点击按钮并点击 */
    private fun tryClickCloseButton(root: AccessibilityNodeInfo): Boolean {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            val desc = node.contentDescription?.toString() ?: ""
            if ((desc.contains("关闭") || desc.contains("取消")) && node.isClickable) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /* 成功处理完「自动连播」后的收尾：标记完成（之后 tryStartDouyinAutoPlay 会因 douyinAutoPlayCompleted 直接短路）、关闭菜单 */
    private suspend fun finishDouyinAutoPlaySuccess(targetText: String) {
        LogX.i(TAG, "Douyin autoplay switch handled")
        douyinAutoPlayCompleted = true
        // 等开关动画结束后，只有菜单还开着才按返回关闭（快手点完开关可能自动收起菜单，此时不能再按返回）
        delay(400)
        if (isMenuStillOpen(targetText)) {
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        }
    }

    /* 判断目标开关文字当前是否还在界面上（用于决定是否关闭菜单） */
    private fun isMenuStillOpen(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        val open = nodes.isNotEmpty()
        nodes.forEach { runCatching { it.recycle() } }
        return open
    }

    /**
     * 长按菜单是否还开着（用于找不到开关文字时决定是否按返回）：
     * 1. 先看无障碍树里有没有菜单专属文字（不感兴趣/举报/定时关闭/复制链接）；
     * 2. 树里看不到时，再用截屏亮度对比：菜单弹出后背景会明显变暗。
     * 两者都判断不出来时返回 false，宁可不动也不误退当前页面。
     */
    private suspend fun isLongPressMenuVisible(brightnessBefore: Float?): Boolean {
        if (hasLongPressMenuMarker()) return true
        val brightnessAfter = screenBrightness() ?: return false
        val before = brightnessBefore ?: return false
        // 背景至少变暗 25% 且画面已经很暗，才认为菜单弹出，避免视频画面本身变暗造成误判
        return before > 20f && brightnessAfter <= 150f && (before - brightnessAfter) >= before * 0.25f
    }

    /* 在无障碍树中查找长按菜单里才会出现的文字（正常视频界面不会出现这些文字） */
    private fun hasLongPressMenuMarker(): Boolean {
        val root = rootInActiveWindow ?: return false
        val markers = listOf("不感兴趣", "举报", "定时关闭", "复制链接")
        for (marker in markers) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(marker) }.getOrNull()
                ?: continue
            val found = nodes.isNotEmpty()
            nodes.forEach { runCatching { it.recycle() } }
            if (found) return true
        }
        return false
    }

    /* 截取当前屏幕并计算平均亮度（0~255），失败返回 null */
    private suspend fun screenBrightness(): Float? {
        val bitmap = runCatching { captureScreenBitmap() }.getOrNull() ?: return null
        return try {
            val soft = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (soft == null) {
                null
            } else {
                try {
                    var sum = 0L
                    var count = 0L
                    val step = 16
                    var y = 0
                    while (y < soft.height) {
                        var x = 0
                        while (x < soft.width) {
                            val p = soft.getPixel(x, y)
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            sum += (r * 299 + g * 587 + b * 114) / 1000
                            count++
                            x += step
                        }
                        y += step
                    }
                    if (count == 0L) null else sum.toFloat() / count
                } finally {
                    if (soft !== bitmap) {
                        runCatching { soft.recycle() }
                    }
                }
            }
        } finally {
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                runCatching { bitmap.recycle() }
            }
        }
    }

    /**
     * 在无障碍节点树中查找「自动连播」，读取开关真实状态后决定是否点击
     * 只点击开关节点本身，绝不点击整行，避免把已开启的开关误关
     *
     * @return true 表示已找到并处理（含本来已开启）；false 表示未找到，需要继续尝试
     */
    private suspend fun tryHandleAutoplaySwitch(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        if (nodes.isEmpty()) return false
        val textNode = nodes.firstOrNull {
            it.text?.toString()?.trim() == targetText
        } ?: nodes.first()
        val result = try {
            val switchNode = findSwitchNodeInRow(textNode)
            when {
                switchNode != null -> {
                    if (switchNode.isChecked) {
                        true // 开关已经是开启状态，无需操作
                    } else {
                        switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                else -> {
                    // 无标准开关节点：仅快手使用截屏看颜色；抖音保持安全不点击
                    if (targetText == KUAISHOU_AUTOPLAY_TEXT) {
                        toggleKuaishouVisualSwitch(textNode)
                    } else {
                        false
                    }
                }
            }
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
        return result
    }

    /**
     * 快手的「自动上滑」是无障碍树里没有状态的自定义开关：
     * 截屏看开关区域颜色（开=蓝色，关=灰色），确认是关才点击，避免误关
     */
    private suspend fun toggleKuaishouVisualSwitch(textNode: AccessibilityNodeInfo): Boolean {
        val row = textNode.parent ?: return false
        val rowRect = Rect()
        row.getBoundsInScreen(rowRect)
        if (rowRect.width() <= 0 || rowRect.height() <= 0) return false
        when (kuaishouToggleState(rowRect)) {
            KUAISHOU_STATE_ON -> {
                LogX.i(TAG, "Kuaishou toggle already ON")
                return true
            }
            KUAISHOU_STATE_OFF -> {
                val toggleX = rowRect.right - rowRect.width() * 0.14f
                val toggleY = rowRect.exactCenterY()
                LogX.i(TAG, "Kuaishou toggle OFF, tap at ($toggleX, $toggleY)")
                dispatchTap(toggleX, toggleY)
                delay(800)
                val on = kuaishouToggleState(rowRect) == KUAISHOU_STATE_ON
                LogX.i(TAG, "Kuaishou toggle after tap: $on")
                return on
            }
            else -> {
                LogX.w(TAG, "Kuaishou toggle state unknown, skip to avoid misclick")
                return false
            }
        }
    }

    /**
     * 截屏判断快手开关状态（开=蓝色，关=灰色）
     * 截图失败或无法读取像素时返回 UNKNOWN，调用方不得点击
     */
    private suspend fun kuaishouToggleState(rowRect: Rect): Int {
        val bitmap = runCatching { captureScreenBitmap() }.getOrNull() ?: return KUAISHOU_STATE_UNKNOWN
        return try {
            // 无障碍截图可能是 HARDWARE 位图，不能直接 getPixel，先转成软件位图
            val soft = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (soft == null) {
                KUAISHOU_STATE_UNKNOWN
            } else {
                try {
                    val left = (rowRect.right - rowRect.width() * 0.32f).toInt().coerceIn(0, soft.width - 1)
                    val right = (rowRect.right - rowRect.width() * 0.02f).toInt().coerceIn(0, soft.width - 1)
                    val top = (rowRect.top + rowRect.height() * 0.1f).toInt().coerceIn(0, soft.height - 1)
                    val bottom = (rowRect.bottom - rowRect.height() * 0.1f).toInt().coerceIn(0, soft.height - 1)
                    var blue = 0
                    var y = top
                    while (y <= bottom) {
                        var x = left
                        while (x <= right) {
                            val p = soft.getPixel(x, y)
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            if (r in 47..127 && g in 94..174 && b >= 215) blue++
                            x += 2
                        }
                        y += 2
                    }
                    if (blue > 300) KUAISHOU_STATE_ON else KUAISHOU_STATE_OFF
                } finally {
                    if (soft !== bitmap) {
                        runCatching { soft.recycle() }
                    }
                }
            }
        } catch (e: Exception) {
            LogX.w(TAG, "Kuaishou toggle state check failed", e)
            KUAISHOU_STATE_UNKNOWN
        } finally {
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                runCatching { bitmap.recycle() }
            }
        }
    }

    /* 在指定坐标模拟一次点击 */
    private suspend fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120L))
            .build()
        dispatchGestureAwait(gesture)
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

    /* 分发手势并等待手势执行完成（带超时保护，MIUI 上回调可能丢失） */
    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        val ok = withTimeoutOrNull(GESTURE_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                isGestureActive = true
                val success = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        isGestureActive = false
                        if (continuation.isActive) continuation.resumeWith(Result.success(true))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        isGestureActive = false
                        if (continuation.isActive) continuation.resumeWith(Result.success(false))
                    }
                }, handler)
                if (!success && continuation.isActive) {
                    isGestureActive = false
                    continuation.resumeWith(Result.success(false))
                }
            }
        } ?: false
        isGestureActive = false
        return ok
    }

    /**
     * 事件驱动版本的抖音连播检测：由 onAccessibilityEvent 在检测到当前包名为抖音时调用，
     * 不再需要每 10 秒主动轮询前台应用，省去后台常驻定时唤醒的耗电开销。
     * 内部仍保留会话去重（douyinSessionDone）和冷却（DOUYIN_AUTOPLAY_COOLDOWN_MS），
     * 所以即使同一次抖音会话内触发多次无障碍事件，也只会真正执行一次连播流程。
     */
    private fun tryStartDouyinAutoPlay(packageName: String) {
        val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_DOUYIN_AUTOPLAY, DEFAULT_DOUYIN_AUTOPLAY)
        // 只有用户点击方向键启动滑动后（isRunning）才允许执行自动连播
        if (!isRunning || !enabled || douyinAutoPlayCompleted || douyinSessionDone || douyinAutoPlayInProgress) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastDouyinAutoPlayAt < DOUYIN_AUTOPLAY_COOLDOWN_MS) {
            return
        }
        douyinSessionDone = true
        douyinAutoPlayInProgress = true
        serviceScope.launch {
            try {
                // 等 App 完全启动到界面后再执行，避免页面还没加载完成导致操作失败
                delay(5000)
                handleDouyinAutoPlay(packageName)
            } finally {
                douyinAutoPlayInProgress = false
            }
        }
    }

    /**
     * 由主界面开关调用：重置会话状态。事件驱动模式下无需显式启动/停止轮询，
     * 下次无障碍事件检测到进入抖音时会自动按最新开关状态判断。
     *
     * @param enabled 抖音自动连播开关状态（保留参数以兼容调用方，逻辑已改为读取实时配置）
     */
    fun setDouyinAutoPlayEnabled(enabled: Boolean) {
        douyinAutoPlayCompleted = false
        douyinSessionDone = false
        douyinAutoPlayInProgress = false
        if (enabled) {
            // 开关打开时立刻检查一次当前前台是否已是抖音/快手（一次性检查，不是轮询，不耗电）
            val currentPackage = rootInActiveWindow?.packageName?.toString()
            if (currentPackage == DOUYIN_PACKAGE || currentPackage == KUAISHOU_PACKAGE ||
                currentPackage == KUAISHOU_LITE_PACKAGE
            ) {
                tryStartDouyinAutoPlay(currentPackage)
            }
        }
    }

    /**
     * 录制过程中把一个刚录好的动作实时同步到当前应用（宏录制“边录边执行”）。
     * 挂起直到该手势派发完成，调用方据此恢复录制层的触摸。
     *
     * @param input 刚录制的输入动作
     */
    suspend fun dispatchRecordedStrokeAwait(input: AutoSlideInput) {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        dispatchOneInput(input, width, height)
    }

    /**
     * 回放按钮：立即执行一次指定名称的录制记录（不进入自动滑动循环）
     *
     * @param name 录制名称
     * @param onFinished 完整回放结束后的回调（被中断时不回调）
     * @param onActionStart 每个动作开始前的回调（用于显示点击圈圈/滑动痕迹）
     * @param onEnd 回放结束（无论完成还是被中断）后的清理回调
     * @return 是否存在可回放的录制记录
     */
    fun playMacro(
        name: String,
        onFinished: (() -> Unit)? = null,
        onActionStart: ((AutoSlideInput) -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ): Boolean {
        val macro = getMacro(name) ?: return false
        stopSlide()
        val currentGen = runGeneration
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        serviceScope.launch {
            var totalDistancePx = 0f
            var completed = true
            var prevInput: AutoSlideInput? = null
            for (input in macro) {
                // 用户按方向键/再次操作时中断本次回放
                if (currentGen != runGeneration) {
                    completed = false
                    break
                }
                // 等待条件：等屏幕出现/消失指定文字，满足后继续（或点击），超时中止回放
                if (input.action == AutoSlideInputAction.WAIT_FOR) {
                    onActionStart?.invoke(input)
                    val waitOk = waitForMacroCondition(input, currentGen)
                    if (!waitOk) {
                        completed = false
                        break
                    }
                    prevInput = input
                    continue
                }
                // 回放去重：同一位置超短间隔的重复点击只执行一次
                if (isDuplicateTap(prevInput, input, width, height, density)) {
                    prevInput = input
                    continue
                }
                val waitMs = input.delayMs.coerceIn(0L, MAX_REPLAY_GAP_MS)
                if (waitMs > 0) {
                    delay(waitMs)
                    if (currentGen != runGeneration) {
                        completed = false
                        break
                    }
                }
                onActionStart?.invoke(input)
                val ok = dispatchOneInput(input, width, height)
                if (!ok) {
                    completed = false
                    break
                }
                totalDistancePx += inputPathLength(input, width, height)
                prevInput = input
            }
            val distanceMm = (totalDistancePx / density / 6f).toInt().coerceAtLeast(1)
            updateStats(swipes = 1, distanceMm = distanceMm)
            if (completed) {
                onFinished?.invoke()
            }
            onEnd?.invoke()
        }
        return true
    }

    /* 回放去重：连续两个点击位置几乎相同且间隔很短时视为录制产生的重复点击 */
    private fun isDuplicateTap(
        prev: AutoSlideInput?,
        current: AutoSlideInput,
        width: Int,
        height: Int,
        density: Float
    ): Boolean {
        if (prev == null ||
            current.action != AutoSlideInputAction.TAP ||
            prev.action != AutoSlideInputAction.TAP
        ) {
            return false
        }
        if (current.delayMs >= DUPLICATE_TAP_MAX_GAP_MS) {
            return false
        }
        val dx = (current.x - prev.x) * width
        val dy = (current.y - prev.y) * height
        return hypot(dx, dy) < DUPLICATE_TAP_DISTANCE_DP * density
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
        var keywordText = prefs.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        if (keywordText.isBlank()) {
            // 关键词为空时自动恢复默认关键词
            keywordText = DEFAULT_KEYWORDS
            prefs.edit { putString(KEY_KEYWORDS, DEFAULT_KEYWORDS) }
        }
        keywordList = parseKeywords(keywordText)
    }

    /**
     * 从本地配置读取自动点击「跳过」按钮的匹配关键词列表
     */
    private fun loadSkipConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var skipText = prefs.getString(KEY_SKIP_KEYWORDS, DEFAULT_SKIP_KEYWORDS) ?: DEFAULT_SKIP_KEYWORDS
        if (skipText.isBlank()) {
            // 跳过关键词为空时自动恢复默认关键词
            skipText = DEFAULT_SKIP_KEYWORDS
            prefs.edit { putString(KEY_SKIP_KEYWORDS, DEFAULT_SKIP_KEYWORDS) }
        }
        skipKeywordList = parseKeywords(skipText)
        autoTapEnabled = prefs.getBoolean(KEY_AUTO_TAP_ENABLED, DEFAULT_AUTO_TAP_ENABLED)
        LogX.i(TAG, "Skip config loaded: keywords=${skipKeywordList.size}, autoTap=$autoTapEnabled")
    }

    /**
     * 更新自动点击总开关（主页开关调用）。
     * 自动点击是独立功能：开启后由无障碍事件驱动检测并点击关键词（GKD 同款思路），
     * 回放/录制期间也不停止；关闭后完全停止。
     *
     * @param enabled true=开启自动点击，false=完全停止
     */
    fun setAutoTapEnabled(enabled: Boolean) {
        autoTapEnabled = enabled
        LogX.i(TAG, "Auto tap enabled: $enabled")
        if (enabled) {
            // 打开开关时立即检查一次当前界面
            scheduleAutoTapCheck(0L)
        }
    }

    /**
     * 宏回放等待条件：轮询无障碍节点树，等待指定文字出现/消失。
     * 等待文字出现且勾选「点击」时，找到后自动点击该文字。
     *
     * @param input WAIT_FOR 动作
     * @param currentGen 当前运行代数（用于检测回放中断）
     * @return true=条件满足；false=超时或回放被中断
     */
    private suspend fun waitForMacroCondition(input: AutoSlideInput, currentGen: Int): Boolean {
        val text = input.waitText.trim()
        if (text.isEmpty()) return true
        val timeoutAt = SystemClock.elapsedRealtime() + input.waitTimeoutMs.coerceIn(1_000L, 120_000L)
        while (SystemClock.elapsedRealtime() < timeoutAt) {
            if (currentGen != runGeneration) return false
            val root = rootInActiveWindow
            val exists = root != null && findTextNodeInTree(root, text) != null
            if (input.waitDisappear) {
                // 等消失：当前已经不存在即满足
                if (!exists) return true
            } else {
                // 等出现：找到后按需点击，然后继续
                if (exists && root != null) {
                    if (input.waitClick) {
                        tryClickNodeByText(root, text)
                    }
                    return true
                }
            }
            delay(MACRO_WAIT_POLL_INTERVAL_MS)
        }
        LogX.w(TAG, "Macro wait timeout: text=$text, disappear=${input.waitDisappear}")
        Toast.makeText(this, getString(R.string.wait_for_timeout, text), Toast.LENGTH_LONG).show()
        return false
    }

    /* BFS 查找第一个文本/描述包含目标文字的节点（不区分大小写） */
    private fun findTextNodeInTree(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val target = text.lowercase()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < SKIP_NODE_SCAN_LIMIT) {
            val node = queue.removeFirst()
            scanned++
            val nodeText = runCatching { node.text?.toString() }.getOrNull()
            val nodeDesc = runCatching { node.contentDescription?.toString() }.getOrNull()
            if (nodeText?.lowercase()?.contains(target) == true ||
                nodeDesc?.lowercase()?.contains(target) == true
            ) {
                return node
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.add(child)
            }
        }
        return null
    }

    /* 点击文字节点：优先点击节点本身或可点击祖先，失败则按节点中心坐标点击 */
    private suspend fun tryClickNodeByText(root: AccessibilityNodeInfo, text: String) {
        val target = findTextNodeInTree(root, text) ?: return
        val clickable = findClickableSelfOrAncestor(target)
        val clicked = clickable != null &&
            runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        if (clicked) {
            LogX.i(TAG, "Macro wait click: $text")
            return
        }
        // 节点点击失败：用文字中心坐标兜底
        val bounds = Rect()
        runCatching { target.getBoundsInScreen(bounds) }
        if (!bounds.isEmpty) {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            performSkipTap(bounds.centerX().toFloat() / width, bounds.centerY().toFloat() / height)
        }
    }

    /**
     * 实时更新自动点击「跳过」按钮的匹配关键词（主界面输入时调用）
     *
     * @param keywords 跳过关键词文本（用中文逗号/英文逗号/换行分隔）
     */
    fun updateSkipConfig(keywords: String) {
        val parsed = parseKeywords(keywords)
        skipKeywordList = parsed.ifEmpty { parseKeywords(DEFAULT_SKIP_KEYWORDS) }
        LogX.i(TAG, "Skip config updated: keywords=${skipKeywordList.size}")
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
        if (!isRunning || !keywordCheckActive) {
            return
        }
        val finalDelay = delayMs.coerceAtLeast(50)
        handler.removeCallbacks(keywordCheckRunnable) // 确保只有一个定时器在运行
        handler.postDelayed(keywordCheckRunnable, finalDelay)
        LogX.d(TAG, "Keyword check scheduled: delay=${finalDelay}ms")
    }

    /**
     * 手势结束后的继续逻辑：关键词模式等待冷却，其他模式进入下一轮定时滑动
     *
     * @param currentGen 当前运行代数
     */
    private fun continueAfterGesture(currentGen: Int, fromKeyword: Boolean) {
        if (fromKeyword) {
            // 关键词触发的滑动结束后，等待冷却再进行下一次检测
            scheduleKeywordCheck(keywordCooldownMs.toLong())
        } else {
            scheduleNextSlide(currentGen)
        }
    }

    /**
     * 执行一次关键词检测：截图 -> OCR -> 匹配
     */
    private fun runKeywordCheck() {
        if (!isRunning || !keywordCheckActive) {
            return
        }
        val currentGen = runGeneration
        serviceScope.launch {
            val bitmap = captureScreenBitmap()
            if (currentGen != runGeneration || !isRunning) {
                bitmap?.recycle()
                return@launch
            }
            val ocrResult = if (bitmap != null) recognizeSkipHits(bitmap) else ("" to emptyList())
            bitmap?.recycle()
            if (currentGen != runGeneration || !isRunning) {
                return@launch
            }
            handleKeywordCheckResult(ocrResult.first, currentGen)
        }
    }

    /**
     * 处理一次 OCR 识别结果：仅按用户关键词滑动（自动点击已独立成单独功能，不在这里处理）。
     *
     * @param text 识别出的屏幕文字
     */
    private suspend fun handleKeywordCheckResult(
        text: String,
        currentGen: Int
    ) {
        if (currentGen != runGeneration || !isRunning || !keywordCheckActive) return
        // 不扫描 AutoSlide 自己的界面（主界面/聊天室等），自己页面永远不可能是广告
        if (isAutoSlideWindow()) {
            LogX.d(TAG, "Current window is AutoSlide itself, skip keyword check")
            scheduleKeywordCheck(keywordIntervalMs.toLong())
            return
        }
        LogX.d(TAG, "OCR text: $text")
        // 已有手势在执行时（例如定时滑动正在进行），本次命中先跳过，等待下一轮检测
        if (isGestureActive) {
            LogX.d(TAG, "Gesture in progress, skip keyword trigger")
            scheduleKeywordCheck(keywordIntervalMs.toLong())
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsedSinceTrigger = now - lastKeywordTriggerAt
        // 冷却期内不再触发
        if (elapsedSinceTrigger < keywordCooldownMs) {
            val remaining = keywordCooldownMs - elapsedSinceTrigger
            LogX.d(TAG, "In cooldown, skipping. Remaining: ${remaining}ms")
            scheduleKeywordCheck(remaining)
            return
        }
        // 仅按用户关键词匹配；未命中则重置连续触发计数
        val keywordMatched = matchesKeyword(text)
        if (!keywordMatched) {
            keywordConsecutiveTriggers = 0
            lastTriggeredTextHash = null
            LogX.d(TAG, "Keyword not matched. Next check in ${keywordIntervalMs}ms")
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
            LogX.d(TAG, "Max triggers reached for this screen text. Waiting interval.")
            scheduleKeywordCheck(keywordIntervalMs.toLong())
            return
        }
        keywordConsecutiveTriggers++
        lastKeywordTriggerAt = now
        updateStats(matches = 1)
        LogX.i(TAG, "Keyword matched! Swiping. (Trigger $keywordConsecutiveTriggers/$keywordMaxTriggers)")
        performSlideByDirection(calculateGestureDurationMillis(), fromKeyword = true)
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
     * 判断 OCR 识别出的单行文字是否命中任一跳过关键词（不区分大小写，适配 Skip Ad 等英文按钮）
     *
     * @param lineText 单行识别文字
     * @return 是否命中
     */
    private fun matchesSkipKeyword(lineText: String): Boolean {
        if (skipKeywordList.isEmpty() || lineText.isBlank()) {
            return false
        }
        val haystack = lineText.lowercase()
        return skipKeywordList.any { keyword ->
            keyword.isNotEmpty() && haystack.contains(keyword.lowercase())
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
        return try {
            suspendCancellableCoroutine { continuation ->
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = try {
                                val buffer = screenshot.hardwareBuffer
                                val result = Bitmap.wrapHardwareBuffer(buffer, null)
                                buffer.close()
                                result
                            } catch (e: Exception) {
                                LogX.w(TAG, "HardwareBuffer to Bitmap failed", e)
                                null
                            }
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(bitmap))
                            } else {
                                bitmap?.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            LogX.w(TAG, "Accessibility screenshot failed, code=$errorCode")
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(null))
                            }
                        }
                    })
                } catch (e: Exception) {
                    LogX.w(TAG, "takeScreenshot error", e)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(null))
                    }
                }
            }
        } catch (e: Exception) {
            LogX.w(TAG, "captureScreenViaAccessibility error", e)
            null
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
     * 使用 ML Kit 中文模型识别位图文字，并返回所有命中「跳过关键词」的文字行中心坐标（归一化 0~1）。
     * 用于自动点击 App 启动广告的「跳过」按钮（关键词列表由用户在主页配置，默认「跳过」）。
     *
     * @param bitmap 屏幕位图
     * @return (完整识别文字, 命中文字行的中心坐标列表)
     */
    private suspend fun recognizeSkipHits(bitmap: Bitmap): Pair<String, List<Pair<Float, Float>>> =
        withContext(Dispatchers.IO) {
            try {
                val recognizer = textRecognizer ?: TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                ).also { textRecognizer = it }
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = Tasks.await(recognizer.process(image))
                val width = bitmap.width.coerceAtLeast(1).toFloat()
                val height = bitmap.height.coerceAtLeast(1).toFloat()
                val hits = mutableListOf<Pair<Float, Float>>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        if (matchesSkipKeyword(line.text)) {
                            val box = line.boundingBox
                            if (box != null) {
                                hits += (box.centerX().toFloat() / width).coerceIn(0f, 1f) to
                                    (box.centerY().toFloat() / height).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
                result.text to hits
            } catch (e: Exception) {
                if (!ocrFailureNotified) {
                    ocrFailureNotified = true
                    LogX.e(TAG, "OCR failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AutoSlideService, R.string.keyword_ocr_failed, Toast.LENGTH_LONG).show()
                    }
                }
                "" to emptyList()
            }
        }

    /**
     * 回放前自动回桌面查找并打开与录制名称匹配的 App。
     *
     * 按已安装应用名称直接打开（精准匹配 > 最长模糊匹配），不翻桌面。
     *
     * @param name 录制名称（作为关键词）
     * @return 是否成功找到并点击打开
     */
    suspend fun autoFindAndOpenAppByName(name: String): Boolean {
        val target = name.trim()
        if (target.length < 2) return false
        // 1) 按已安装应用名称直接打开（例如「汽水音乐刷金币」会精准选中「汽水音乐」）
        if (launchAppByLabel(target)) {
            LogX.i(TAG, "AutoLaunch: opened app by label for '$target'")
            // MIUI 可能弹出「后台弹出界面」确认框，自动点掉「允许/始终允许」
            dismissBackgroundStartDialogIfNeeded()
            // 留一点时间等 App 启动，再开始执行录制动作
            delay(1200)
            return true
        }
        LogX.w(TAG, "AutoLaunch: no app label match for '$target', skip auto open")
        return false
    }

    /**
     * 自动点击独立功能：优先在无障碍节点树中查找关键词并点击（零 OCR 开销、即时响应），
     * 节点树中找不到时才截图 + OCR 兜底（适配自绘界面等没有节点文字的场景）。
     *
     * @return 是否点击了跳过按钮
     */
    suspend fun checkAndTapSkipOnce(): Boolean {
        // 互斥：同一时间只允许一个自动点击检查执行，防止事件并发导致重复点击
        if (!autoTapChecking.compareAndSet(false, true)) return false
        try {
            // 开关关闭或保活停止时完全停止
            if (!autoTapEnabled || !persistentSkipEnabled) return false
            // 不扫描 AutoSlide 自己的界面（主界面/聊天室/录制回放），自己页面永远不可能是广告
            if (isAutoSlideWindow()) return false
            // 优先节点树：直接读文字属性，几乎不耗电
            if (tryClickSkipNodeByTree()) return true
            // 广告刚出现时节点树往往还没填充文字，延迟 0.5 秒重试一次再决定是否 OCR
            delay(NODE_TREE_RETRY_DELAY_MS)
            if (!autoTapEnabled || !persistentSkipEnabled) return false
            if (isAutoSlideWindow()) return false
            if (tryClickSkipNodeByTree()) return true
            // 节点树没命中 → 截图 + OCR 兜底（节流：事件频繁时最多每 2 秒一次）
            val now = SystemClock.elapsedRealtime()
            if (now - lastOcrAt < SKIP_OCR_FALLBACK_INTERVAL_MS) return false
            lastOcrAt = now
            val bitmap = captureScreenBitmap()
            val ocr = if (bitmap != null) recognizeSkipHits(bitmap) else ("" to emptyList())
            bitmap?.recycle()
            return performAutoTap(ocr.second)
        } finally {
            autoTapChecking.set(false)
        }
    }

    /**
     * 在无障碍节点树中查找命中关键词的节点并点击。
     * 优先点击节点本身或最近的可点击祖先；节点点击失败时返回 false 交给 OCR 兜底。
     *
     * @return 是否已通过节点树完成点击
     */
    private fun tryClickSkipNodeByTree(): Boolean {
        // 冷却期内不重复点击
        if (SystemClock.elapsedRealtime() - lastSkipTapAt < SKIP_TAP_COOLDOWN_MS) return false
        return try {
            val root = rootInActiveWindow ?: return false
            val keywords = skipKeywordList.filter { it.isNotBlank() }
            if (keywords.isEmpty()) return false
            val target = findSkipNodeByBfs(root) ?: return false
            val clickable = findClickableSelfOrAncestor(target)
            val clicked = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) return false
            lastSkipTapAt = SystemClock.elapsedRealtime()
            LogX.i(TAG, "Skip node clicked: text=${target.text}, desc=${target.contentDescription}")
            true
        } catch (e: Throwable) {
            LogX.w(TAG, "Node tree tap failed, fallback to OCR", e)
            false
        }
    }

    /* BFS 遍历节点树，返回第一个命中跳过关键词的节点（限制扫描数量避免卡顿） */
    private fun findSkipNodeByBfs(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < SKIP_NODE_SCAN_LIMIT) {
            val node = queue.removeFirst()
            scanned++
            if (matchesSkipNode(node)) return node
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.add(child)
            }
        }
        return null
    }

    /**
     * 节点是否命中跳过关键词（GKD 同款匹配规则）：
     * 1) 文本/描述含关键词，且长度 < 10、对用户可见、尺寸像按钮（防正文长句误触）
     * 2) 关键词含「跳过/skip」语义时，控件 id 是 SDK 跳过按钮（如穿山甲 tt_splash_skip_btn）也算命中
     * 3) 命中排除词清单的节点直接跳过（搜索框/设置项/下一步等）
     */
    private fun matchesSkipNode(node: AccessibilityNodeInfo): Boolean {
        val text = runCatching { node.text?.toString() }.getOrNull()
        val desc = runCatching { node.contentDescription?.toString() }.getOrNull()
        // 排除词：搜索框、设置项、正文引导等，即使含关键词也不算跳过按钮
        if (text != null && SKIP_EXCLUDE_WORDS.any { text.contains(it) }) return false
        if (desc != null && SKIP_EXCLUDE_WORDS.any { desc.contains(it) }) return false
        val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
        if (!visible) return false

        // 文本匹配：含关键词 + 长度 < 10 + 尺寸像按钮
        if (text != null && text.length < SKIP_TEXT_MAX_LENGTH &&
            skipKeywordList.any { text.lowercase().contains(it.lowercase()) } &&
            isSkipButtonSize(node)
        ) {
            return true
        }
        // 描述匹配：含关键词 + 长度 < 10
        if (desc != null && desc.length < SKIP_TEXT_MAX_LENGTH &&
            skipKeywordList.any { desc.lowercase().contains(it.lowercase()) }
        ) {
            return true
        }
        // id 匹配：关键词含跳过/skip 时，SDK 跳过按钮 id 也算命中
        if (isSkipIntentKeyword()) {
            val id = runCatching { node.viewIdResourceName }.getOrNull() ?: return false
            val lowerId = id.lowercase()
            if (lowerId.endsWith("tt_splash_skip_btn") ||
                (lowerId.contains("skip") &&
                    !lowerId.contains("video") && !lowerId.contains("head") && !lowerId.contains("tail"))
            ) {
                return true
            }
        }
        return false
    }

    /* 用户关键词是否包含「跳过/skip」语义（决定是否启用控件 id 匹配） */
    private fun isSkipIntentKeyword(): Boolean =
        skipKeywordList.any { kw ->
            val k = kw.lowercase()
            k.contains("skip") || k.contains("跳过") || k.contains("跳過")
        }

    /* 尺寸像按钮：宽 <= 500px 且高 <= 300px（GKD 全局开屏规则同款，防把大块文字当按钮） */
    private fun isSkipButtonSize(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        if (bounds.isEmpty) return false
        return bounds.width() <= 500 && bounds.height() <= 300
    }

    /* 返回节点本身或最近的可点击祖先 */
    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = runCatching { current.parent }.getOrNull()
        }
        return null
    }

    /**
     * 「自动点击」执行入口：命中关键词且满足条件时执行点击。
     * 不检查手势互斥，回放/滑动期间只要开关开启都会执行（广告出现时点击优先）。
     *
     * @param hits 命中关键词的文字行中心坐标列表（归一化 0~1）
     * @return 是否执行了点击
     */
    private suspend fun performAutoTap(hits: List<Pair<Float, Float>>): Boolean {
        // 主页开关：关闭时完全停止自动点击
        if (!autoTapEnabled) return false
        // 不扫描 AutoSlide 自己的界面（主界面/聊天室/录制回放），自己页面永远不可能是广告
        if (isAutoSlideWindow()) return false
        if (SystemClock.elapsedRealtime() - lastSkipTapAt < SKIP_TAP_COOLDOWN_MS) return false
        val hit = hits.firstOrNull() ?: return false
        performSkipTap(hit.first, hit.second)
        return true
    }

    /* 当前前台窗口是否属于 AutoSlide 自己（主界面/聊天室/录制回放悬浮窗等），是则跳过广告检测 */
    private fun isAutoSlideWindow(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString()
        // 无法确定当前窗口时也跳过，避免误点
        return pkg == null || pkg == packageName
    }

    /**
     * 保活恢复入口：通知「停止」后关闭；StatusService/无障碍服务连接时重新开启。
     * 事件驱动模式下只负责标记状态：真正是否执行还取决于主页的自动点击总开关（autoTapEnabled）。
     */
    fun setPersistentSkipEnabled(enabled: Boolean) {
        persistentSkipEnabled = enabled
        if (enabled) {
            // 服务连接/保活恢复时立即检查一次当前界面
            scheduleAutoTapCheck(0L)
        }
    }

    /**
     * 自动点击事件驱动的触发入口：界面变化事件到来时调用。
     * 去抖合并连续事件；开关/保活未开启时不安排检查。
     *
     * @param delayMs 延迟毫秒数（默认 150ms 去抖，0 表示立即）
     */
    private fun scheduleAutoTapCheck(delayMs: Long = AUTO_TAP_DEBOUNCE_MS) {
        if (!autoTapEnabled || !persistentSkipEnabled) return
        if (autoTapCheckPending) return
        autoTapCheckPending = true
        handler.postDelayed(autoTapCheckRunnable, delayMs)
    }

    /* 在指定坐标点击「跳过」按钮，并记录点击冷却 */
    private suspend fun performSkipTap(x: Float, y: Float) {
        LogX.i(TAG, "Skip button tapped at ($x, $y)")
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val input = AutoSlideInput(
            action = AutoSlideInputAction.TAP,
            x = x,
            y = y,
            duration = 120L
        )
        dispatchOneInput(input, width, height)
        lastSkipTapAt = SystemClock.elapsedRealtime()
    }

    /**
     * 自动处理 MIUI「后台弹出界面」确认框。
     *
     * 直接通过系统启动其他 App 时，MIUI 若未授予「后台弹出界面」权限，会弹出
     * securitycenter 的确认框（按钮通常是「始终允许 / 拒绝 / 取消」，部分版本是
     * 「允许 / 拒绝」）。这里在启动后轮询几秒，检测到弹窗就自动点击允许按钮。
     */
    private suspend fun dismissBackgroundStartDialogIfNeeded() {
        val allowTexts = setOf("允许", "始终允许")
        repeat(10) { attempt ->
            delay(500)
            val root = rootInActiveWindow ?: return@repeat
            val packageName = root.packageName?.toString() ?: ""
            val rootText = root.text?.toString() ?: ""
            val isStartDialog = packageName.contains("miui.securitycenter") ||
                rootText.contains("后台弹出界面") ||
                containsTextInTree(root, "后台弹出界面")
            if (!isStartDialog) {
                return@repeat
            }
            val button = findButtonNode(root, allowTexts)
            if (button != null) {
                LogX.i(TAG, "AutoLaunch: background start dialog detected, clicking allow button (attempt ${attempt + 1})")
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
    }

    /* 在无障碍节点树中查找文字匹配的节点（含子节点） */
    private fun containsTextInTree(node: AccessibilityNodeInfo, text: String): Boolean {
        val nodeText = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (nodeText.contains(text) || desc.contains(text)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsTextInTree(child, text)) {
                return true
            }
        }
        return false
    }

    /* 在无障碍节点树中查找「允许/始终允许」按钮：先找文字节点，再向上找可点击的父节点 */
    private fun findButtonNode(root: AccessibilityNodeInfo?, texts: Set<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodeText = root.text?.toString()?.trim() ?: ""
        val desc = root.contentDescription?.toString()?.trim() ?: ""
        if (nodeText in texts || desc in texts) {
            var clickable = root
            while (clickable != null && !clickable.isClickable) {
                clickable = clickable.parent
            }
            return clickable ?: root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findButtonNode(child, texts)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * 在已安装应用列表里按名称匹配并直接打开 App。
     *
     * 得分规则与 OCR 搜索一致：名称完全相等 > 标签完整包含任务名称 > 任务名称包含
     * 标签（取最长）。例如任务名「汽水音乐刷金币」会优先选标签「汽水音乐」而不是「音乐」。
     *
     * @param target 任务名称（作为关键词）
     * @return 是否成功启动
     */
    private fun launchAppByLabel(target: String): Boolean {
        val targetLower = target.lowercase()
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = try {
            packageManager.queryIntentActivities(launchIntent, 0)
        } catch (e: Exception) {
            LogX.w(TAG, "AutoLaunch: query launcher apps failed", e)
            return false
        }
        var best: ResolveInfo? = null
        var bestScore = -1
        for (info in resolveInfos) {
            val label = (info.loadLabel(packageManager)?.toString() ?: "").trim()
            if (label.length < 2) continue
            val lower = label.lowercase()
            val score = when {
                lower == targetLower -> EXACT_MATCH_SCORE + label.length
                lower.contains(targetLower) -> 5000 + label.length
                targetLower.contains(lower) -> label.length
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                best = info
            }
        }
        val info = best ?: return false
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(info.activityInfo.packageName, info.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            LogX.i(
                TAG,
                "AutoLaunch: launching '${info.loadLabel(packageManager)}' (${info.activityInfo.packageName}) score=$bestScore"
            )
            true
        } catch (e: Exception) {
            LogX.w(TAG, "AutoLaunch: start activity failed", e)
            false
        }
    }

}
