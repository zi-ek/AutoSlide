package com.ltx.service

/**
 * 悬浮窗服务
 *
 * 在所有应用上层显示一个可拖动的悬浮控制面板：
 * 四个方向按钮启动对应方向的滑动，长按可录制/管理自定义轨迹；
 * 设置按钮返回主界面，关闭按钮停止服务。
 */

// import com.ltx.KEY_CUSTOM_TRAJECTORY_DOWN
// import com.ltx.KEY_CUSTOM_TRAJECTORY_LEFT
// import com.ltx.KEY_CUSTOM_TRAJECTORY_RIGHT
// import com.ltx.KEY_CUSTOM_TRAJECTORY_UP
// import com.ltx.getTrajectoryKey
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.edit
import com.ltx.DEFAULT_KEYWORDS
import com.ltx.DEFAULT_MAX_PAUSE_TIME
import com.ltx.DEFAULT_MIN_PAUSE_TIME
import com.ltx.DEFAULT_PAUSE_TIME
import com.ltx.DEFAULT_SPEED
import com.ltx.DIRECTION_DOWN
import com.ltx.DIRECTION_LEFT
import com.ltx.DIRECTION_RIGHT
import com.ltx.DIRECTION_UP
import com.ltx.KEY_KEYWORDS
import com.ltx.KEY_MAX_PAUSE_TIME
import com.ltx.KEY_MIN_PAUSE_TIME
import com.ltx.KEY_PAUSE_MODE
import com.ltx.KEY_PAUSE_TIME
import com.ltx.KEY_SPEED
import com.ltx.MainActivity
import com.ltx.PAUSE_MODE_KEYWORD
import com.ltx.PREFS_NAME
import com.ltx.R
import com.ltx.SlideEvent
import com.ltx.SlideEventHub
import com.ltx.isAccessibilityServicePermissionEnabled
import com.ltx.parseKeywords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗服务
 *
 * @author tianxing
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager // 窗口管理器（添加/更新悬浮窗）
    private lateinit var layoutParams: WindowManager.LayoutParams // 悬浮窗位置参数
    private lateinit var rootView: View      // 悬浮窗根布局（可拖拽）
    private lateinit var controlPanel: View  // 展开后的控制面板
    private lateinit var expandButton: View  // 收起后的小圆球（点击展开）
    private var initialX = 0f                // 拖拽开始时悬浮窗的 X 位置
    private var initialY = 0f                // 拖拽开始时悬浮窗的 Y 位置
    private var initialTouchX = 0f           // 按下时手指的 X 坐标
    private var initialTouchY = 0f           // 按下时手指的 Y 坐标
    private var recordOverlayView: View? = null // 轨迹录制遮罩视图
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main) // 主线程协程作用域
    private var lastScreenWidth = 0 // 上次记录的屏幕宽度（旋转后用于判断保持左/右同一侧）
    private var isExpandButtonEnlarged = false // 悬浮球是否处于放大状态
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shrinkRunnable = Runnable { 
        if (isExpandButtonEnlarged) {
            isExpandButtonEnlarged = false
            updateExpandButtonSize(30)
        }
    }

    /* 绑定服务 */
    override fun onBind(intent: Intent?): IBinder? = null
    
    /* 创建服务根视图并添加到窗口管理器 */
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        AutoSlideTileService.requestUpdate(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 创建悬浮窗根视图
        rootView = createRootView()
        controlPanel = rootView.findViewById(R.id.control_panel)
        expandButton = rootView.findViewById(R.id.floating_expand_button)
        layoutParams = createLayoutParams()
        lastScreenWidth = resources.displayMetrics.widthPixels
        // 注册拖拽事件处理
        setupDragging()
        setupControlButtons()
        // 添加悬浮窗到窗口管理器
        try {
            windowManager.addView(rootView, layoutParams)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(
                "FloatingWindowService",
                "Failed to add floating window: overlay permission missing",
                e
            )
            isServiceRunning = false
            AutoSlideTileService.requestUpdate(this)
            stopSelf()
            return
        }
        // 悬浮窗默认停靠在屏幕右下角（等布局完成拿到实际宽高后设置位置）
        rootView.post {
            if (!::rootView.isInitialized || !::layoutParams.isInitialized) {
                return@post
            }
            val displayMetrics = resources.displayMetrics
            // 计算右下角坐标，并保证不小于 0
            layoutParams.x = (displayMetrics.widthPixels - rootView.width + currentRightInset()).coerceAtLeast(0)
            layoutParams.y = (displayMetrics.heightPixels - rootView.height).coerceAtLeast(0)
            runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
        }
        // 监听自动滑动服务事件
        serviceScope.launch {
            SlideEventHub.eventFlow.collect { event ->
                if (!::rootView.isInitialized) return@collect
                when (event) {
                    is SlideEvent.ForceStop -> expand(stopSlide = false)
                    // is SlideEvent.CustomTrajectoryCleared -> updateDirectionButtonIndicators()
                }
            }
        }
    }

    /* 服务销毁时移除悬浮窗 */
    override fun onDestroy() {
        isServiceRunning = false
        AutoSlideTileService.requestUpdate(this)
        serviceScope.cancel()
        // removeRecordView()
        super.onDestroy()
        runCatching { windowManager.removeView(rootView) }
    }

    /**
     * 配置改变时更新悬浮窗位置
     * 
     * @param newConfig 配置
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::rootView.isInitialized && ::layoutParams.isInitialized) {
            // 根据旋转前的位置判断悬浮球在左半边还是右半边，旋转后保持同一侧
            val oldScreenWidth = lastScreenWidth
            val oldCenterX = layoutParams.x + rootView.width / 2f
            val keepLeft = oldScreenWidth == 0 || oldCenterX < oldScreenWidth / 2f
            rootView.post {
                if (::rootView.isInitialized && ::layoutParams.isInitialized) {
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels
                    val viewHeight = rootView.height
                    // 垂直位置限制在屏幕内
                    layoutParams.y = layoutParams.y.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
                    // 旋转后自动吸附到同一侧的屏幕边缘，避免停在屏幕中间
                    layoutParams.x = if (keepLeft) {
                        -currentLeftInset()
                    } else {
                        screenWidth - rootView.width + currentRightInset()
                    }
                    windowManager.updateViewLayout(rootView, layoutParams)
                }
            }
            // 记录最新的屏幕宽度，供下次旋转判断使用
            lastScreenWidth = resources.displayMetrics.widthPixels
        }
    }

    /**
     * 创建悬浮窗根视图
     *
     * @return 悬浮窗根视图实例
     */
    @SuppressLint("InflateParams")
    private fun createRootView(): View {
        val themedContext: Context = ContextThemeWrapper(this, R.style.Theme_AutoSlide)
        return LayoutInflater
            .from(themedContext)
            .inflate(R.layout.floating_window, null)
    }

    /**
     * 构造悬浮窗布局参数
     *
     * @return 视图窗口参数
     */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /* 设置拖拽事件处理 */
    private fun setupDragging() {
        val draggableRoot = rootView as? DraggableLinearLayout ?: return
        draggableRoot.setOnDragListener(object : DraggableLinearLayout.OnDragListener {
            override fun onDragDown(rawX: Float, rawY: Float) {
                initialX = layoutParams.x.toFloat()
                initialY = layoutParams.y.toFloat()
                initialTouchX = rawX
                initialTouchY = rawY
            }

            override fun onDragMove(rawX: Float, rawY: Float) {
                val deltaX = rawX - initialTouchX
                val deltaY = rawY - initialTouchY
                // 获取屏幕宽高和悬浮窗宽高
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                val viewWidth = rootView.width
                val viewHeight = rootView.height
                // 计算目标位置
                val targetX = (initialX + deltaX).toInt()
                val targetY = (initialY + deltaY).toInt()
                layoutParams.x = targetX.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
                layoutParams.y = targetY.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
                // 更新悬浮窗位置
                windowManager.updateViewLayout(rootView, layoutParams)
            }

            override fun onDragEnd(rawX: Float, rawY: Float) {
                // 松手后自动吸附到最近的屏幕边缘
                snapToNearestEdge()
            }
        })
    }

    /* 将悬浮窗吸附到最近的左/右屏幕边缘（垂直位置保持不变） */
    private fun snapToNearestEdge() {
        rootView.post {
            if (!::rootView.isInitialized || !::layoutParams.isInitialized) {
                return@post
            }
            val displayMetrics = resources.displayMetrics
            // 用悬浮窗中心点判断离哪条边更近
            val centerX = layoutParams.x + rootView.width / 2f
            layoutParams.x = if (centerX < displayMetrics.widthPixels / 2f) {
                // 离左边近：贴左边缘
                -currentLeftInset()
            } else {
                // 离右边近：贴右边缘
                displayMetrics.widthPixels - rootView.width + currentRightInset()
            }
            runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
        }
    }

    /**
     * 当前可见内容距窗口右边缘的内部间距（根布局内边距 + 小球的右边距）
     * 吸附时把这段间距“让出去”，让可见图标真正贴住屏幕边缘
     */
    private fun currentRightInset(): Int {
        val marginRight = if (expandButton.visibility == View.VISIBLE) {
            (expandButton.layoutParams as? ViewGroup.MarginLayoutParams)?.rightMargin ?: 0
        } else {
            0
        }
        return rootView.paddingRight + marginRight
    }

    /**
     * 当前可见内容距窗口左边缘的内部间距（根布局内边距 + 小球的左边距）
     */
    private fun currentLeftInset(): Int {
        val marginLeft = if (expandButton.visibility == View.VISIBLE) {
            (expandButton.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0
        } else {
            0
        }
        return rootView.paddingLeft + marginLeft
    }

    /* 绑定所有控制按钮事件 */
    private fun setupControlButtons() {
        expandButton.setOnClickListener {
            if (!isExpandButtonEnlarged) {
                // 第一次点击：放大图标并开启 3 秒计时
                isExpandButtonEnlarged = true
                updateExpandButtonSize(48) // 放大到 48dp
                mainHandler.removeCallbacks(shrinkRunnable)
                mainHandler.postDelayed(shrinkRunnable, 3000L)
            } else {
                // 放大状态下的第二次点击：恢复大小并展开面板
                mainHandler.removeCallbacks(shrinkRunnable)
                isExpandButtonEnlarged = false
                updateExpandButtonSize(30) // 恢复到 30dp
                expand()
            }
        }
        // 方向按钮⌈点击/长按⌋事件绑定
        bindDirectionButton(R.id.floating_up_button, DIRECTION_UP)
        bindDirectionButton(R.id.floating_down_button, DIRECTION_DOWN)
        bindDirectionButton(R.id.floating_left_button, DIRECTION_LEFT)
        bindDirectionButton(R.id.floating_right_button, DIRECTION_RIGHT)
        // 设置按钮点击事件
        rootView.findViewById<View>(R.id.floating_setting_button).setOnClickListener {
            returnToMainActivity()
            AutoSlideService.getInstance()?.stopSlide()
            stopSelf()
        }
        // 关闭按钮⌈点击⌋事件绑定
        rootView.findViewById<View>(R.id.floating_close_button).setOnClickListener {
            AutoSlideService.getInstance()?.stopSlide()
            stopSelf()
        }
        // 根据是否已有自定义轨迹更新方向按钮高亮
        updateDirectionButtonIndicators()
    }

    /**
     * 显示轨迹管理对话框
     *
     * @param direction 方向字符串
     */
    /*
    private fun showTrajectoryManageDialog(direction: String) {
        val items = arrayOf(getString(R.string.record), getString(R.string.reset))
        val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_AutoSlide))
            .setTitle(getTrajectoryManageTitle(direction)).setItems(items) { _, which ->
                when (items[which]) {
                    getString(R.string.record) -> startRecordingTrajectory(direction)
                    getString(R.string.reset) -> clearTrajectory(direction)
                }
            }.setNegativeButton(R.string.cancel, null)
        showSystemAlertDialog(builder)
    }
    */

    /**
     * 开始录制轨迹
     *
     * @param direction 方向字符串
     */
    /*
    private fun startRecordingTrajectory(direction: String) {
        AutoSlideService.getInstance()?.stopSlide()
        minimize()
        // 在全屏覆盖层显示录制方向提示
        val instructionText = getRecordDirectionInstruction(direction)
        // 创建录制视图
        val recordView =
            TrajectoryRecordView(this, instructionText = instructionText, onTrajectoryRecorded = { points ->
                removeRecordView()
                expand()
                val detected = detectTrajectoryDirection(points)
                if (detected != direction && detected.isNotEmpty()) {
                    showDirectionMismatchDialog(points, direction, detected)
                } else {
                    saveTrajectory(points, direction)
                    updateDirectionButtonIndicators()
                    Toast.makeText(this, R.string.trajectory_saved, Toast.LENGTH_SHORT).show()
                }
            }, onCancel = {
                removeRecordView()
                expand()
            })
        // 创建录制视图布局参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        // 添加录制视图到窗口管理器
        try {
            windowManager.addView(recordView, params)
            recordOverlayView = recordView
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "Failed to add record view", e)
        }
    }
    */

    /* 移除录制视图 */
    /*
    private fun removeRecordView() {
        val recordView = recordOverlayView ?: return
        runCatching { windowManager.removeView(recordView) }
        recordOverlayView = null
    }
    */

    /**
     * 保存轨迹
     *
     * @param points 轨迹点列表
     * @param direction 方向字符串
     */
    /*
    private fun saveTrajectory(points: List<PointF>, direction: String) {
        if (points.isEmpty()) return
        val sb = StringBuilder()
        points.forEach { point ->
            sb.append("${point.x},${point.y};")
        }
        val key = getTrajectoryKey(direction) ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(key, sb.toString())
        }
    }
    */

    /**
     * 清除轨迹
     *
     * @param direction 方向字符串
     */
    /*
    private fun clearTrajectory(direction: String) {
        val key = getTrajectoryKey(direction) ?: return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            remove(key)
        }
        updateDirectionButtonIndicators()
    }
    */

    /**
     * 获取方向显示名称
     *
     * @param direction 方向字符串
     * @return 显示名称
     */
    private fun getDirectionDisplayName(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.desc_slide_up)
        DIRECTION_DOWN -> getString(R.string.desc_slide_down)
        DIRECTION_LEFT -> getString(R.string.desc_slide_left)
        DIRECTION_RIGHT -> getString(R.string.desc_slide_right)
        else -> direction
    }

    /**
     * 获取轨迹管理标题
     *
     * @param direction 方向字符串
     * @return 标题
     */
    /*
    private fun getTrajectoryManageTitle(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.trajectory_title_up)
        DIRECTION_DOWN -> getString(R.string.trajectory_title_down)
        DIRECTION_LEFT -> getString(R.string.trajectory_title_left)
        DIRECTION_RIGHT -> getString(R.string.trajectory_title_right)
        else -> direction
    }
    */

    /**
     * 获取录制时的方向提示文本
     *
     * @param direction 方向字符串
     * @return 方向提示文本
     */
    /*
    private fun getRecordDirectionInstruction(direction: String): String = when (direction) {
        DIRECTION_UP -> getString(R.string.record_direction_up_explicit)
        DIRECTION_DOWN -> getString(R.string.record_direction_down_explicit)
        DIRECTION_LEFT -> getString(R.string.record_direction_left_explicit)
        DIRECTION_RIGHT -> getString(R.string.record_direction_right_explicit)
        else -> direction
    }
    */

    /**
     * 根据轨迹首尾点位移检测主导方向
     *
     * @param points 轨迹点列表
     * @return 主导方向
     */
    /*
    private fun detectTrajectoryDirection(points: List<PointF>): String {
        if (points.size < 2) return ""
        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        return when {
            abs(dx) > abs(dy) -> if (dx > 0) DIRECTION_LEFT else DIRECTION_RIGHT
            else -> if (dy > 0) DIRECTION_UP else DIRECTION_DOWN
        }
    }
    */

    /**
     * 当录制轨迹方向与所选方向不一致时弹出确认对话框
     *
     * @param points 已录制的轨迹点
     * @param selectedDirection 用户选择的方向
     * @param detectedDirection 检测到的实际方向
     */
    /*
    private fun showDirectionMismatchDialog(
        points: List<PointF>, selectedDirection: String, detectedDirection: String
    ) {
        val selectedName = getDirectionDisplayName(selectedDirection)
        val detectedName = getDirectionDisplayName(detectedDirection)
        val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_AutoSlide))
            .setTitle(R.string.trajectory_mismatch_title)
            .setMessage(getString(R.string.trajectory_mismatch_message, detectedName, selectedName))
            .setPositiveButton(R.string.save_anyway) { _, _ ->
                saveTrajectory(points, selectedDirection)
                updateDirectionButtonIndicators()
                Toast.makeText(this, R.string.trajectory_saved, Toast.LENGTH_SHORT).show()
            }.setNeutralButton(R.string.record_again) { _, _ ->
                startRecordingTrajectory(selectedDirection)
            }.setNegativeButton(R.string.cancel, null)
        showSystemAlertDialog(builder)
    }
    */

    /* 更新方向按钮视觉标记 */
    private fun updateDirectionButtonIndicators() {
        /*
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val defaultColor = ContextCompat.getColor(this, R.color.floating_btn_bg)
        val activeColor = ContextCompat.getColor(this, R.color.floating_btn_active)
        val defaultIconColor = ContextCompat.getColor(this, R.color.floating_btn_icon)
        val activeIconColor = ContextCompat.getColor(this, R.color.floating_btn_active_icon)
        // 方向按钮ID与轨迹存储键的映射
        val buttons = mapOf(
            R.id.floating_up_button to KEY_CUSTOM_TRAJECTORY_UP,
            R.id.floating_down_button to KEY_CUSTOM_TRAJECTORY_DOWN,
            R.id.floating_left_button to KEY_CUSTOM_TRAJECTORY_LEFT,
            R.id.floating_right_button to KEY_CUSTOM_TRAJECTORY_RIGHT
        )
        // 遍历方向按钮并更新视觉标记
        buttons.forEach { (viewId, key) ->
            val button = rootView.findViewById<FloatingActionButton>(viewId)
            val hasTrajectory = prefs.getString(key, null)?.isNotBlank() == true
            button?.let {
                it.backgroundTintList = ColorStateList.valueOf(
                    if (hasTrajectory) activeColor else defaultColor
                )
                it.imageTintList = ColorStateList.valueOf(
                    if (hasTrajectory) activeIconColor else defaultIconColor
                )
            }
        }
        */
    }

    /**
     * 为方向按钮绑定启动与录制逻辑
     *
     * @param viewId 按钮视图ID
     * @param direction 方向字符串(up/down/left/right)
     */
    private fun bindDirectionButton(viewId: Int, direction: String) {
        val button = rootView.findViewById<View>(viewId)
        // 方向按钮⌈点击⌋事件绑定
        button.setOnClickListener {
            val service = AutoSlideService.getInstance()
            if (service == null) {
                if (isAccessibilityServicePermissionEnabled()) {
                    // 设置里已开启但服务实例还没连上（启动中/崩溃后重连），给出准确提示并自动重试一次
                    Log.w("FloatingWindowService", "无障碍服务设置已开启但实例未连接，自动重试一次")
                    Toast.makeText(this, R.string.accessibility_service_starting, Toast.LENGTH_SHORT).show()
                    rootView.postDelayed({
                        val retryService = AutoSlideService.getInstance()
                        if (retryService != null) {
                            retryService.setDirection(direction)
                            startSlide()
                        }
                    }, 1500L)
                } else {
                    // 设置里确实没开启
                    Log.w("FloatingWindowService", "方向键点击时无障碍服务未开启")
                    Toast.makeText(this, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            service.setDirection(direction)
            startSlide()
        }
        // 方向按钮⌈长按⌋事件绑定
        /*
        button.setOnLongClickListener {
            val hasCustom = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(getTrajectoryKey(direction), null)
                ?.isNotBlank() == true
            if (hasCustom) {
                showTrajectoryManageDialog(direction)
            } else {
                startRecordingTrajectory(direction)
            }
            true
        }
        */
    }

    /* 最小化悬浮窗 */
    private fun minimize() {
        controlPanel.visibility = View.GONE
        expandButton.visibility = View.VISIBLE
        // 确保状态复位
        isExpandButtonEnlarged = false
        mainHandler.removeCallbacks(shrinkRunnable)
        val density = resources.displayMetrics.density
        expandButton.layoutParams.width = (30 * density).toInt()
        expandButton.layoutParams.height = (30 * density).toInt()
        // 收起时去掉根布局内边距和小球外边距，让窗口尺寸正好等于小球，实现真正贴边
        rootView.setPadding(0, 0, 0, 0)
        (expandButton.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
        rootView.requestLayout()
        windowManager.updateViewLayout(rootView, layoutParams)
        snapToRightAfterLayout()
    }

    /* 等收起后的布局完成，再把悬浮窗右边缘贴到屏幕右边缘 */
    private fun snapToRightAfterLayout() {
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (!::rootView.isInitialized || !::layoutParams.isInitialized) {
                    return
                }
                val displayMetrics = resources.displayMetrics
                layoutParams.x = displayMetrics.widthPixels - rootView.width
                runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
            }
        })
    }

    /**
     * 展开悬浮窗并停止当前自动滑动
     *
     * @param stopSlide 是否停止当前自动滑动
     */
    private fun expand(stopSlide: Boolean = true) {
        controlPanel.visibility = View.VISIBLE
        expandButton.visibility = View.GONE
        // 确保状态复位
        isExpandButtonEnlarged = false
        mainHandler.removeCallbacks(shrinkRunnable)
        // 恢复根布局内边距和小球外边距
        val density = resources.displayMetrics.density
        expandButton.layoutParams.width = (30 * density).toInt()
        expandButton.layoutParams.height = (30 * density).toInt()
        val padding = (6 * density).toInt()
        rootView.setPadding(padding, padding, padding, padding)
        val margin = (3 * density).toInt()
        (expandButton.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(margin, margin, margin, margin)
        rootView.requestLayout()
        windowManager.updateViewLayout(rootView, layoutParams)
        if (stopSlide) {
            AutoSlideService.getInstance()?.stopSlide()
        }
    }

    /* 启动自动滑动服务 */
    private fun startSlide() {
        minimize()
        // 从本地配置文件读取当前设置
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pauseMode = prefs.getInt(KEY_PAUSE_MODE, PAUSE_MODE_KEYWORD)
        if (pauseMode == PAUSE_MODE_KEYWORD) {
            var keywordText = prefs.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
            if (keywordText.isBlank()) {
                // 关键词为空时自动恢复默认关键词，保证方向键始终可用
                keywordText = DEFAULT_KEYWORDS
                prefs.edit { putString(KEY_KEYWORDS, DEFAULT_KEYWORDS) }
            }
            val hasKeyword = parseKeywords(keywordText).isNotEmpty()
            if (!hasKeyword) {
                expand()
                return
            }
        }
        AutoSlideService.getInstance()?.startSlideWithConfig(
            speedVal = prefs.getInt(KEY_SPEED, DEFAULT_SPEED),
            pauseModeVal = pauseMode,
            pauseTimeVal = prefs.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME),
            minPauseVal = prefs.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME),
            maxPauseVal = prefs.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME)
        )
    }

    /* 返回主界面 */
    private fun returnToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    /**
     * 显示系统级对话框
     *
     * @param builder 对话框构建器
     */
    private fun showSystemAlertDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    /**
     * 更新展开按钮的大小
     * 
     * @param sizeDp 目标大小(dp)
     */
    private fun updateExpandButtonSize(sizeDp: Int) {
        if (!::rootView.isInitialized || !::layoutParams.isInitialized) return
        val density = resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        
        expandButton.layoutParams.width = sizePx
        expandButton.layoutParams.height = sizePx
        rootView.requestLayout()
        
        // 更新窗口布局以适应新大小
        try {
            windowManager.updateViewLayout(rootView, layoutParams)
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "Update view layout failed", e)
        }
        
        // 布局完成后重新执行贴边吸附，确保放大后不悬在半空
        rootView.post {
            snapToNearestEdge()
        }
    }

    companion object {
        // 悬浮窗服务是否正在运行（供磁贴等模块查询）
        private var isServiceRunning = false

        /**
         * 获取悬浮窗服务运行状态
         * 
         * @return 悬浮窗服务是否正在运行
         */
        @JvmStatic
        fun isRunning(): Boolean = isServiceRunning
    }
}
