package com.ziek.autoslide.service

/**
 * 悬浮窗服务
 *
 * 在所有应用上层显示一个可拖动的悬浮控制面板：
 * 四个方向按钮启动对应方向的滑动；
 * 录制按钮录制独立的“操作宏”（点击/长按/滑动/等待），与方向键解耦；
 * 设置按钮返回主界面，关闭按钮停止服务。
 */

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import com.ziek.autoslide.LogX
import android.util.TypedValue
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ziek.autoslide.DEFAULT_KEYWORDS
import com.ziek.autoslide.DEFAULT_MAX_PAUSE_TIME
import com.ziek.autoslide.DEFAULT_MIN_PAUSE_TIME
import com.ziek.autoslide.DEFAULT_PAUSE_TIME
import com.ziek.autoslide.DEFAULT_SPEED
import com.ziek.autoslide.DIRECTION_DOWN
import com.ziek.autoslide.DIRECTION_LEFT
import com.ziek.autoslide.DIRECTION_RIGHT
import com.ziek.autoslide.DIRECTION_UP
import com.ziek.autoslide.ImportSettingsActivity
import com.ziek.autoslide.KEY_KEYWORDS
import com.ziek.autoslide.KEY_MAX_PAUSE_TIME
import com.ziek.autoslide.KEY_MIN_PAUSE_TIME
import com.ziek.autoslide.KEY_PAUSE_MODE
import com.ziek.autoslide.KEY_PAUSE_TIME
import com.ziek.autoslide.KEY_SPEED
import com.ziek.autoslide.License
import com.ziek.autoslide.MainActivity
import com.ziek.autoslide.PAUSE_MODE_KEYWORD
import com.ziek.autoslide.PREFS_NAME
import com.ziek.autoslide.R
import com.ziek.autoslide.SlideEvent
import com.ziek.autoslide.SlideEventHub
import com.ziek.autoslide.KEY_FLOATING_DESIRED
import com.ziek.autoslide.KEY_MACRO_PREFIX
import com.ziek.autoslide.KEY_MACRO_LOOP_COUNT
import com.ziek.autoslide.KEY_MACRO_LAUNCH_ONCE
import com.ziek.autoslide.MacroSync
import com.ziek.autoslide.input.AutoSlideInput
import com.ziek.autoslide.input.AutoSlideInputCodec
import com.ziek.autoslide.isAccessibilityServicePermissionEnabled
import com.ziek.autoslide.parseKeywords
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private lateinit var pauseButton: FloatingActionButton // 悬浮球放大时显示的总暂停按钮
    private var initialX = 0f                // 拖拽开始时悬浮窗的 X 位置
    private var initialY = 0f                // 拖拽开始时悬浮窗的 Y 位置
    private var initialTouchX = 0f           // 按下时手指的 X 坐标
    private var initialTouchY = 0f           // 按下时手指的 Y 坐标
    private var recordOverlayView: View? = null // 轨迹录制遮罩视图
    private var playListDialog: AlertDialog? = null // 回放记录列表对话框（删除后刷新用）
    private var floatingWindowHidden = false // 悬浮窗是否被完全隐藏（录制/回放期间）
    private var playbackFeedbackView: PlaybackFeedbackView? = null // 回放可视化反馈层
    private var playbackCountdownView: View? = null // 回放前倒计时遮罩
    private var playbackCountdownLabel: View? = null // 回放倒计时下方的「共 N 次」提示
    private var playbackCountdownJob: Job? = null   // 倒计时协程
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main) // 主线程协程作用域
    private var lastScreenWidth = 0 // 上次记录的屏幕宽度（旋转后用于判断保持左/右同一侧）
    private var isExpandButtonEnlarged = false // 悬浮球是否处于放大状态
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shrinkRunnable = Runnable { 
        if (isExpandButtonEnlarged) {
            isExpandButtonEnlarged = false
            updateExpandButtonSize(30)
            pauseButton.visibility = View.GONE
        }
    }

    /* 绑定服务 */
    override fun onBind(intent: Intent?): IBinder? = null
    
    /* 创建服务根视图并添加到窗口管理器 */
    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
        // 记录「悬浮窗应处于显示状态」，供进程被清理后复活时自动恢复；
        // 只有用户点关闭/设置按钮或磁贴关闭时才会清除
        setFloatingDesired(true)
        AutoSlideTileService.requestUpdate(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 创建悬浮窗根视图
        rootView = createRootView()
        controlPanel = rootView.findViewById(R.id.control_panel)
        expandButton = rootView.findViewById(R.id.floating_expand_button)
        pauseButton = rootView.findViewById(R.id.floating_pause_button)
        layoutParams = createLayoutParams()
        lastScreenWidth = resources.displayMetrics.widthPixels
        // 注册拖拽事件处理
        setupDragging()
        setupControlButtons()
        // 先以全透明添加：gravity 是 TOP|START，addView 那一刻 x/y 还是 0，
        // 直接可见就会在左上角闪一帧再跳到目标位置。等下面 post 里定好位再一起显形。
        layoutParams.alpha = 0f
        // 添加悬浮窗到窗口管理器
        try {
            windowManager.addView(rootView, layoutParams)
        } catch (e: WindowManager.BadTokenException) {
            LogX.e(
                "FloatingWindowService",
                "Failed to add floating window: overlay permission missing",
                e
            )
            isServiceRunning = false
            AutoSlideTileService.requestUpdate(this)
            stopSelf()
            return
        }
        // 悬浮窗默认贴右边缘、上下居中（等布局完成拿到实际宽高后设置位置）
        rootView.post {
            if (!::rootView.isInitialized || !::layoutParams.isInitialized) {
                return@post
            }
            val displayMetrics = resources.displayMetrics
            // 贴右边缘，垂直方向取屏幕中线，均保证不小于 0
            layoutParams.x = (displayMetrics.widthPixels - rootView.width + currentRightInset()).coerceAtLeast(0)
            layoutParams.y = ((displayMetrics.heightPixels - rootView.height) / 2).coerceAtLeast(0)
            // 位置和透明度一次提交：用户看到的第一帧就已经在目标位置
            layoutParams.alpha = 1f
            runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
        }
        // 监听自动滑动服务事件
        serviceScope.launch {
            SlideEventHub.eventFlow.collect { event ->
                if (!::rootView.isInitialized) return@collect
                when (event) {
                    is SlideEvent.ForceStop -> expand(stopSlide = false)
                }
            }
        }
    }

    /* 服务销毁时移除悬浮窗 */
    override fun onDestroy() {
        instance = null
        isServiceRunning = false
        AutoSlideTileService.requestUpdate(this)
        serviceScope.cancel()
        playbackCountdownJob?.cancel()
        playbackCountdownJob = null
        playbackCountdownView?.let { runCatching { windowManager.removeView(it) } }
        playbackCountdownView = null
        playbackCountdownLabel?.let { runCatching { windowManager.removeView(it) } }
        playbackCountdownLabel = null
        removeRecordView()
        playbackFeedbackView?.let { feedback ->
            runCatching { (rootView as? ViewGroup)?.removeView(feedback) }
        }
        playbackFeedbackView = null
        playListDialog?.dismiss()
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
        pauseButton.setOnClickListener {
            val service = AutoSlideService.getInstance()
            if (service == null) {
                Toast.makeText(this, R.string.accessibility_service_starting, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val paused = !service.isAutomationPaused()
            service.setAutomationPaused(paused)
            updatePauseButton()
            Toast.makeText(
                this,
                if (paused) R.string.automation_paused else R.string.automation_resumed,
                Toast.LENGTH_SHORT,
            ).show()
        }
        expandButton.setOnClickListener {
            if (!isExpandButtonEnlarged) {
                // 第一次点击：放大图标并开启 3 秒计时
                isExpandButtonEnlarged = true
                updateExpandButtonSize(48) // 放大到 48dp
                pauseButton.visibility = View.VISIBLE
                updatePauseButton()
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
            // 用户主动收起悬浮窗：复活时不再自动恢复
            setFloatingDesired(false)
            returnToMainActivity()
            AutoSlideService.getInstance()?.stopSlide()
            stopSelf()
        }
        // 关闭按钮⌈点击⌋事件绑定
        // 收起按钮：把面板收成小圆球，不退出悬浮窗；
        // 彻底关闭悬浮窗只由主界面的「关闭悬浮窗」按钮负责
        rootView.findViewById<View>(R.id.floating_close_button).setOnClickListener {
            AutoSlideService.getInstance()?.stopSlide()
            minimize()
        }
        // 录制按钮：先弹命名窗口，输入名称后开始录制
        rootView.findViewById<View>(R.id.floating_record_button).setOnClickListener {
            val service = AutoSlideService.getInstance()
            if (service == null) {
                if (isAccessibilityServicePermissionEnabled()) {
                    Toast.makeText(this, R.string.accessibility_service_starting, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            showNewMacroDialog()
        }
        // 回放按钮：弹出录制记录列表，选择后回放
        rootView.findViewById<View>(R.id.floating_play_button).setOnClickListener {
            val service = AutoSlideService.getInstance()
            if (service == null) {
                if (isAccessibilityServicePermissionEnabled()) {
                    Toast.makeText(this, R.string.accessibility_service_starting, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            showPlayListDialog()
        }
    }

    /* 刷新悬浮球上方按钮的暂停/恢复图标与无障碍说明。 */
    private fun updatePauseButton() {
        val paused = AutoSlideService.getInstance()?.isAutomationPaused() ?: false
        pauseButton.setImageResource(
            if (paused) R.drawable.ic_floating_play else R.drawable.ic_floating_pause
        )
        pauseButton.contentDescription = getString(
            if (paused) R.string.desc_resume_all else R.string.desc_pause_all
        )
    }

    /**
     * 新建录制：弹出名称输入窗口，确定后开始录制
     */
    private fun showNewMacroDialog() {
        val dialogContext = createDialogContext()
        val inputLayout = TextInputLayout(dialogContext).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.record_name_hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            isSingleLine = true
            // 服务上下文创建输入框时禁用文本选择工具条，
            // 避免部分机型（如 ColorOS）在弹出选择工具栏时 getDisplay 崩溃
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
        }
        inputLayout.addView(input)
        val launchOnceCheck = CheckBox(dialogContext).apply {
            text = getString(R.string.record_launch_once_label)
            textSize = 14f
            isChecked = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_MACRO_LAUNCH_ONCE, false)
        }
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(inputLayout)
            addView(
                launchOnceCheck,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            )
        }
        val builder = MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.record_name_title)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.record_name_empty, Toast.LENGTH_SHORT).show()
                } else {
                    val launchOnce = launchOnceCheck.isChecked
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                        putBoolean(KEY_MACRO_LAUNCH_ONCE, launchOnce)
                    }
                    startRecordingTrajectory(name, launchOnce)
                }
            }
            .setNegativeButton(R.string.cancel, null)
        showSystemAlertDialog(builder)
    }

    /* 创建带显示上下文且套用应用主题的对话框上下文（服务上下文无法直接获取 Display） */
    private fun createDialogContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return@runCatching
                val windowContext = createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
                // 窗口上下文默认不带应用主题，必须包一层 Theme_AutoSlide，
                // 否则 TextInputLayout 等 Material 组件会因主题不是 AppCompat 而崩溃
                return ContextThemeWrapper(windowContext, R.style.Theme_AutoSlide)
            }
        }
        return ContextThemeWrapper(this, R.style.Theme_AutoSlide)
    }

    /**
     * 回放列表：弹出所有录制记录，点击一条开始回放
     */
    private fun showPlayListDialog() {
        val dialogContext = createDialogContext()
        val names = listMacroNames().toMutableList()
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.macro_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val listView = ListView(dialogContext).apply {
            divider = ColorDrawable(ContextCompat.getColor(this@FloatingWindowService, R.color.dialog_divider))
            dividerHeight = 1
            // 行内有导出/清除按钮：必须允许子项获得焦点（itemsCanFocus=true），
            // 否则默认 itemsCanFocus=false 会让 ListView 把整行当作单个可聚焦项，
            // 部分 ROM 上第一次按下被行焦点消费，按钮要点两次才响应。
            // 注意：行内按钮必须保持默认 focusable=true，二者配合才能生效。
            itemsCanFocus = true
        }
        // 触摸反馈用的可点击态背景（涟漪效果，来自当前主题的 selectableItemBackground）
        val rippleOutValue = TypedValue()
        dialogContext.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleOutValue, true)
        // 行尾「清除」的右侧留白，取弹窗标题同一个内边距值：
        // MaterialAlertDialog 的标题/正文左右都用 dialogPreferredPadding，直接复用才能真正对齐，
        // 写死 dp 在不同 ROM 改过对话框样式时会对不上。取不到再退回 24dp。
        val dialogEdgePadding = TypedValue().let { out ->
            if (dialogContext.theme.resolveAttribute(
                    androidx.appcompat.R.attr.dialogPreferredPadding, out, true
                )
            ) {
                TypedValue.complexToDimensionPixelSize(out.data, resources.displayMetrics)
            } else {
                dp(24)
            }
        }
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = names.size
            override fun getItem(position: Int): Any = names[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val name = names[position]
                val row = LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(0), dialogEdgePadding, dp(0))
                    if (rippleOutValue.resourceId != 0) {
                        setBackgroundResource(rippleOutValue.resourceId)
                    }
                    isClickable = true
                }
                val nameView = TextView(dialogContext).apply {
                    text = name
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(this@FloatingWindowService, R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val clearButton = Button(dialogContext).apply {
                    text = getString(R.string.macro_clear)
                    textSize = 14f
                    isAllCaps = false
                    // 无背景，仅红色文字
                    setTextColor(Color.parseColor("#E53935"))
                    setBackgroundColor(Color.TRANSPARENT)
                    // minWidth/minHeight 只管 TextView 自己那一层；View 层的
                    // minimumWidth 来自主题 buttonStyle 的 88dp，不一并清掉的话
                    // 文字会被居中在一个 88dp 宽的空盒子里，看着就是间距特别大
                    minWidth = 0
                    minHeight = 0
                    minimumWidth = 0
                    minimumHeight = 0
                    // 水平内边距归零；与弹窗右边缘的留白由行的右内边距统一提供
                    setPadding(0, paddingTop, 0, paddingBottom)
                    setOnClickListener {
                        confirmDeleteMacro(name)
                    }
                }
                val exportButton = Button(dialogContext).apply {
                    text = getString(R.string.macro_export)
                    textSize = 14f
                    isAllCaps = false
                    setTextColor(ContextCompat.getColor(this@FloatingWindowService, R.color.primary))
                    setBackgroundColor(Color.TRANSPARENT)
                    // minWidth/minHeight 只管 TextView 自己那一层；View 层的
                    // minimumWidth 来自主题 buttonStyle 的 88dp，不一并清掉的话
                    // 文字会被居中在一个 88dp 宽的空盒子里，看着就是间距特别大
                    minWidth = 0
                    minHeight = 0
                    minimumWidth = 0
                    minimumHeight = 0
                    // 水平内边距归零，按钮宽度就等于文字宽度，间距全部交给外边距算
                    setPadding(0, paddingTop, 0, paddingBottom)
                    setOnClickListener {
                        playListDialog?.dismiss()
                        // 临时隐藏悬浮窗，避免遮挡系统分享面板；12 秒后自动恢复
                        hideForExternalPicker()
                        mainHandler.postDelayed({ restoreAfterExternalPicker() }, EXTERNAL_PICKER_RESTORE_DELAY_MS)
                        exportSlideSettings()
                    }
                }
                val shareButton = Button(dialogContext).apply {
                    text = getString(R.string.macro_share)
                    textSize = 14f
                    isAllCaps = false
                    setTextColor(ContextCompat.getColor(this@FloatingWindowService, R.color.primary))
                    setBackgroundColor(Color.TRANSPARENT)
                    // minWidth/minHeight 只管 TextView 自己那一层；View 层的
                    // minimumWidth 来自主题 buttonStyle 的 88dp，不一并清掉的话
                    // 文字会被居中在一个 88dp 宽的空盒子里，看着就是间距特别大
                    minWidth = 0
                    minHeight = 0
                    minimumWidth = 0
                    minimumHeight = 0
                    setPadding(0, paddingTop, 0, paddingBottom)
                    setOnClickListener {
                        confirmShareMacro(name)
                    }
                }
                // 「一个汉字」取实际字宽而不是写死 dp，系统字体放大时间距跟着一起放大
                val charWidth = shareButton.paint.measureText("字").toInt()
                // 导出 —(1 字)— 分享 —(2 字)— 清除
                shareButton.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = charWidth }
                clearButton.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = charWidth * 2 }
                row.addView(nameView)
                row.addView(exportButton)
                row.addView(shareButton)
                row.addView(clearButton)
                // 点击整行开始回放
                row.setOnClickListener {
                    showLoopCountDialog(name)
                }
                return row
            }
        }
        listView.adapter = adapter
        val importButton = Button(dialogContext).apply {
            text = getString(R.string.macro_import)
            textSize = 14f
            isAllCaps = false
            // 文字靠右贴近取消按钮；必须显式带上 CENTER_VERTICAL，
            // 否则 setGravity 会把垂直分量重置成 TOP，导致文字比取消高一截
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // 对齐动作录制弹窗的确定/取消：m3_btn_padding_top/bottom = 6dp
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, R.color.primary))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minHeight = 0
            // 对齐动作录制弹窗的确定/取消：m3_btn_padding_top/bottom = 6dp
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            setOnClickListener {
                playListDialog?.dismiss()
                // 临时隐藏悬浮窗，避免遮挡系统文件选择器；完成后由导入页恢复
                hideForExternalPicker()
                val intent = Intent(this@FloatingWindowService, ImportSettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        val cancelButton = Button(dialogContext).apply {
            text = getString(R.string.cancel)
            textSize = 14f
            isAllCaps = false
            // 对齐动作录制弹窗的确定/取消：m3_btn_padding_top/bottom = 6dp
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, R.color.text_secondary))
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minHeight = 0
            // 对齐动作录制弹窗的确定/取消：m3_btn_padding_top/bottom = 6dp
            setPadding(paddingLeft, dp(6), paddingRight, dp(6))
            setOnClickListener { playListDialog?.dismiss() }
        }
        // 底部按钮行：导入配置 + 取消 并排
        val bottomRow = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                importButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
            addView(
                cancelButton,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
        }
        val listContainer = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(
                bottomRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    // 这个弹窗没有系统的正/负按钮，按钮栏整体缺失，
                    // 手动补回 MDC 按钮栏的外部间距：ButtonBarLayout 2dp + MaterialButton inset 4dp
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            )
        }
        val builder = MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.play_list_title)
            .setView(listContainer)
        playListDialog = builder.create().also {
            it.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            it.show()
        }
    }

    /* 导出 slide_settings.xml（宏 + 全部设置）到系统分享面板，方便拷贝到其它设备 */
    private fun exportSlideSettings() {
        try {
            val src = File(getFilesDir().parentFile, "shared_prefs/slide_settings.xml")
            if (!src.exists()) {
                Toast.makeText(this, R.string.macro_export_not_found, Toast.LENGTH_SHORT).show()
                return
            }
            val exportDir = File(cacheDir, "export").apply { mkdirs() }
            val dst = File(exportDir, "slide_settings.xml")
            src.copyTo(dst, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dst)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, getString(R.string.macro_export_title))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                Intent.createChooser(shareIntent, getString(R.string.macro_export_title)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            LogX.e("FloatingWindowService", "Export slide_settings failed", e)
            Toast.makeText(this, R.string.macro_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /* 回放设置弹窗：选择循环次数（记住上次使用值） */
    private fun showLoopCountDialog(name: String) {
        val dialogContext = createDialogContext()
        val inputLayout = TextInputLayout(dialogContext).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.macro_loop_hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            // 记住上次使用的循环次数
            val last = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_MACRO_LOOP_COUNT, 1)
            setText(last.toString())
            setSelection(text?.length ?: 0)
            // 服务上下文创建输入框时禁用文本选择工具条，避免部分机型 getDisplay 崩溃
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
        }
        inputLayout.addView(input)
        val container = FrameLayout(dialogContext).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(inputLayout)
        }
        MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.macro_loop_title)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val count = input.text.toString().toIntOrNull()
                if (count == null || count !in 1..99) {
                    Toast.makeText(this, R.string.macro_loop_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                        putInt(KEY_MACRO_LOOP_COUNT, count)
                    }
                    startPlayback(name, count)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .let { showSystemAlertDialog(it) }
    }

    /* 进入回放：关闭列表、进入全屏反馈层、显示倒计时 */
    /* 试用到期时给出提示并拦下动作 */
    private fun blockedByLicense(): Boolean {
        if (!License.blocked) return false
        Toast.makeText(this, R.string.license_blocked_toast, Toast.LENGTH_LONG).show()
        return true
    }

    private fun startPlayback(name: String, loopCount: Int) {
        // 试用到期：在这里就挡住，不让用户白等完倒计时才发现回放起不来
        if (blockedByLicense()) return
        playListDialog?.dismiss()
        enterPlaybackMode()
        showPlaybackCountdown(name, loopCount)
    }

    /* 回放前倒计时：屏幕正中间圆形半透明 10..0，期间自动点启动广告「跳过」，结束后开始回放 */
    private fun showPlaybackCountdown(name: String, loopCount: Int) {
        val service = AutoSlideService.getInstance() ?: return
        val circle = TextView(this).apply {
            text = "5"
            textSize = 56f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
        }
        // 用独立悬浮窗显示倒计时，避免叠加到全屏反馈层（LinearLayout 里第二个 MATCH_PARENT 子 View 会被压成 0 高度）
        val countdownParams = WindowManager.LayoutParams(
            dp(140),
            dp(140),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        runCatching { windowManager.addView(circle, countdownParams) }
        playbackCountdownView = circle
        // 圆圈下方显示「共 N 次」
        val label = TextView(this).apply {
            text = getString(R.string.macro_loop_total, loopCount)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }
        val labelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            y = dp(100)
        }
        runCatching { windowManager.addView(label, labelParams) }
        playbackCountdownLabel = label
        playbackCountdownJob = serviceScope.launch {
            // 倒计时期间自动回桌面查找并打开与录制名称匹配的 App（找不到则照常回放）
            var autoLaunched = false
            val autoLaunchJob = launch {
                autoLaunched = runCatching { service.autoFindAndOpenAppByName(name) }.getOrDefault(false)
            }
            for (i in 10 downTo 0) {
                circle.text = i.toString()
                delay(1000)
            }
            runCatching { windowManager.removeView(circle) }
            playbackCountdownView = null
            playbackCountdownLabel?.let { runCatching { windowManager.removeView(it) } }
            playbackCountdownLabel = null
            // 倒计时结束但 App 还在翻页查找时，最多再等 30 秒（多页桌面翻页+OCR 较慢）
            withTimeoutOrNull(30_000) { autoLaunchJob.join(); autoLaunched }
            val started = service.playMacro(
                name,
                loopCount,
                skipLaunchOnce = autoLaunched,
                onFinished = { showPlaybackFinishedDialog() },
                onActionStart = { input -> playbackFeedbackView?.showAction(input) },
                onEnd = {
                    playbackFeedbackView?.clearAction()
                    exitPlaybackMode()
                }
            )
            if (!started) {
                Toast.makeText(this@FloatingWindowService, R.string.macro_not_found, Toast.LENGTH_SHORT).show()
                exitPlaybackMode()
            }
        }
    }

    /* 进入回放模式：复用悬浮窗窗口变成全屏透明反馈层（不新增悬浮窗，避免系统弹警告） */
    private fun enterPlaybackMode() {
        controlPanel.visibility = View.GONE
        expandButton.visibility = View.GONE
        val feedback = PlaybackFeedbackView(this)
        (rootView as? ViewGroup)?.addView(
            feedback,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        playbackFeedbackView = feedback
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.x = 0
        layoutParams.y = 0
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // 与录制层同理：全屏不透明窗口会触发 Android 12+ 防触摸劫持，
        // 导致无障碍注入的回放手势被系统丢弃，alpha 降到 0.7 规避。
        layoutParams.alpha = 0.7f
        runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
    }

    /* 退出回放模式：移除反馈层，恢复悬浮球/面板 */
    private fun exitPlaybackMode() {
        playbackFeedbackView?.let { feedback ->
            runCatching { (rootView as? ViewGroup)?.removeView(feedback) }
        }
        playbackFeedbackView = null
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        layoutParams.alpha = 1.0f
        runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
        expand()
    }

    /* 回放完毕提示弹窗：圆形 OK 按钮 */
    private fun showPlaybackFinishedDialog() {
        val dialogContext = createDialogContext()
        val content = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
        }
        val message = TextView(dialogContext).apply {
            text = getString(R.string.playback_finished)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@FloatingWindowService, R.color.text_primary))
        }
        val okButton = Button(dialogContext).apply {
            text = getString(R.string.ok_short)
            textSize = 16f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@FloatingWindowService, R.color.primary))
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                topMargin = dp(24)
            }
        }
        content.addView(message)
        content.addView(okButton)

        val dialog = MaterialAlertDialogBuilder(dialogContext)
            .setView(content)
            .create()
        okButton.setOnClickListener { dialog.dismiss() }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    /**
     * 清除确认弹窗
     *
     * 先收起动作列表再弹确认框：动作列表与确认框都是 TYPE_APPLICATION_OVERLAY，
     * 部分 ROM（如 MIUI）对同类型 overlay 弹窗的层级排序不稳定，叠放时确认框
     * 可能被动作列表盖住。取消或删除后按当前记录重新打开列表。
     *
     * @param name 录制名称
     */
    private fun confirmDeleteMacro(name: String) {
        playListDialog?.dismiss()
        playListDialog = null
        MaterialAlertDialogBuilder(createDialogContext())
            .setTitle(R.string.macro_delete_title)
            .setMessage(getString(R.string.macro_delete_message, name))
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteMacro(name)
                // 删除后重新打开动作列表；没有剩余录制则不再弹出
                if (listMacroNames().isNotEmpty()) {
                    showPlayListDialog()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // 取消删除，恢复动作列表
                showPlayListDialog()
            }
            .let { showSystemAlertDialog(it) }
    }

    /**
     * 分享确认。
     *
     * 上传是把用户自己录的脚本发到服务器，属于对外动作，必须由用户明确点头；
     * 弹窗里写清上传范围，取消则原样退回动作列表。
     */
    private fun confirmShareMacro(name: String) {
        playListDialog?.dismiss()
        playListDialog = null
        MaterialAlertDialogBuilder(createDialogContext())
            .setTitle(R.string.macro_share_title)
            .setMessage(getString(R.string.macro_share_message, name))
            .setPositiveButton(R.string.confirm) { _, _ ->
                shareMacro(name)
                // 分享后回到动作列表，方便接着分享下一条
                showPlayListDialog()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // 取消分享，恢复动作列表
                showPlayListDialog()
            }
            .let { showSystemAlertDialog(it) }
    }

    /* 上传单条脚本，结果用吐司反馈（用 applicationContext，服务提前销毁也不会崩） */
    private fun shareMacro(name: String) {
        val toastContext = applicationContext
        Toast.makeText(toastContext, R.string.macro_sharing, Toast.LENGTH_SHORT).show()
        MacroSync.share(toastContext, name) { ok ->
            Toast.makeText(
                toastContext,
                if (ok) R.string.macro_share_success else R.string.macro_share_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /* 删除指定名称的录制记录 */
    private fun deleteMacro(name: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            remove(KEY_MACRO_PREFIX + name)
        }
        Toast.makeText(this, R.string.macro_deleted, Toast.LENGTH_SHORT).show()
    }

    /* dp 转 px */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 记录悬浮窗是否应处于显示状态
     *
     * @param desired true=进程复活后自动恢复悬浮球；false=用户主动关闭，不再恢复
     */
    private fun setFloatingDesired(desired: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_FLOATING_DESIRED, desired)
        }
    }

    /**
     * 开始录制操作宏（与方向键解耦）
     *
     * @param name 录制名称
     * @param markFirstLaunchOnly 是否把第一个触摸动作标记为“仅首轮执行”
     */
    private fun startRecordingTrajectory(name: String, markFirstLaunchOnly: Boolean = true) {
        AutoSlideService.getInstance()?.stopSlide()
        // 录制期间完全隐藏悬浮窗（不显示悬浮球）
        hideFloatingWindow()
        lateinit var recordView: InputRecorderView
        recordView = InputRecorderView(
            this,
            instructionText = getRecordInstruction(name),
            onRecorded = { inputs ->
                removeRecordView()
                expand()
                if (inputs.isEmpty()) {
                    Toast.makeText(this, R.string.record_empty, Toast.LENGTH_SHORT).show()
                } else {
                    saveMacro(name, inputs)
                    Toast.makeText(this, R.string.macro_saved, Toast.LENGTH_SHORT).show()
                }
            },
            onCancelled = {
                removeRecordView()
                expand()
                Toast.makeText(this, R.string.record_cancelled, Toast.LENGTH_SHORT).show()
            },
            onStrokeRecorded = { input ->
                AutoSlideService.getInstance()?.dispatchRecordedStrokeAwait(input)
            },
            onAddWaitFor = {
                showWaitForDialog(recordView)
            },
            onAddTapText = {
                showTapTextDialog(recordView)
            },
            markFirstLaunchOnly = markFirstLaunchOnly
        )
        // 创建录制视图布局参数
        val density = resources.displayMetrics.density
        val edgeInset = (3 * density).toInt() // 左右各留 3dp，不挡住系统边缘返回手势
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - edgeInset * 2,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0, // 允许获取焦点：录制层要能接收音量键/返回键
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeInset
            // Android 12+ 的防触摸劫持机制：全屏不透明覆盖层（alpha=1.0）会拦截
            // 无障碍注入给下层应用的手势（Untrusted touch due to occlusion）。
            // 把窗口 alpha 降到 0.8 以下（这里用 0.7），下层应用才能正常收到同步手势。
            alpha = 0.7f
        }
        // 添加录制视图到窗口管理器
        try {
            windowManager.addView(recordView, params)
            recordOverlayView = recordView
        } catch (e: Exception) {
            LogX.e("FloatingWindowService", "Failed to add record view", e)
            showFloatingWindow()
            expand()
        }
    }

    /* 弹出「插入等待条件」窗口：输入文字、选择出现/消失、是否点击 */
    private fun showWaitForDialog(recordView: InputRecorderView) {
        val dialogContext = createDialogContext()
        val inputLayout = TextInputLayout(dialogContext).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.wait_for_text_hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            isSingleLine = true
            // 服务上下文创建输入框时禁用文本选择工具条，
            // 避免部分机型（如 ColorOS）在弹出选择工具栏时 getDisplay 崩溃
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
        }
        inputLayout.addView(input)

        val appearRadio = RadioButton(dialogContext).apply {
            text = getString(R.string.wait_for_appear)
            isChecked = true
        }
        val disappearRadio = RadioButton(dialogContext).apply {
            text = getString(R.string.wait_for_disappear)
        }
        val radioGroup = RadioGroup(dialogContext).apply {
            orientation = RadioGroup.VERTICAL
            addView(appearRadio)
            addView(disappearRadio)
        }
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(inputLayout)
            addView(radioGroup)
        }
        MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.wait_for_title)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.wait_for_empty, Toast.LENGTH_SHORT).show()
                } else {
                    recordView.addWaitForAction(text, disappearRadio.isChecked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .let { showSystemAlertDialog(it) }
    }

    /* 弹出「插入点击文字」窗口：输入文字，回放时先找到该文字再点击 */
    private fun showTapTextDialog(recordView: InputRecorderView) {
        val dialogContext = createDialogContext()
        val inputLayout = TextInputLayout(dialogContext).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.tap_text_hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            isSingleLine = true
            // 服务上下文创建输入框时禁用文本选择工具条，
            // 避免部分机型（如 ColorOS）在弹出选择工具栏时 getDisplay 崩溃
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
        }
        inputLayout.addView(input)
        val container = FrameLayout(dialogContext).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(inputLayout)
        }
        MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.tap_text_title)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.tap_text_empty, Toast.LENGTH_SHORT).show()
                } else {
                    recordView.addTapTextAction(text)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .let { showSystemAlertDialog(it) }
    }

    /* 移除录制视图 */
    private fun removeRecordView() {
        val recordView = recordOverlayView ?: return
        runCatching { windowManager.removeView(recordView) }
        recordOverlayView = null
        showFloatingWindow()
    }

    /* 完全隐藏悬浮窗（录制/回放期间不显示悬浮球） */
    private fun hideFloatingWindow() {
        if (floatingWindowHidden || !::rootView.isInitialized || !::layoutParams.isInitialized) return
        floatingWindowHidden = true
        runCatching { windowManager.removeView(rootView) }
    }

    /* 导出/导入时临时隐藏悬浮窗，避免遮挡系统分享面板/文件选择器 */
    private var externalPickerHidden = false

    fun hideForExternalPicker() {
        externalPickerHidden = true
        hideFloatingWindow()
    }

    fun restoreAfterExternalPicker() {
        if (!externalPickerHidden) return
        externalPickerHidden = false
        showFloatingWindow()
    }

    /* 恢复显示悬浮窗（重新添加并贴到屏幕边缘） */
    private fun showFloatingWindow() {
        if (!floatingWindowHidden || !::rootView.isInitialized || !::layoutParams.isInitialized) return
        if (!isServiceRunning) return
        floatingWindowHidden = false
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching {
            if (rootView.parent == null) {
                windowManager.addView(rootView, layoutParams)
                rootView.post { snapToNearestEdge() }
            }
        }
    }

    /**
     * 保存录制记录
     *
     * @param name 录制名称
     * @param inputs 输入序列
     */
    private fun saveMacro(name: String, inputs: List<AutoSlideInput>) {
        if (name.isBlank() || inputs.isEmpty()) return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_MACRO_PREFIX + name, AutoSlideInputCodec.encode(inputs))
        }
    }

    /**
     * 列出所有录制记录名称（按名称排序）
     */
    private fun listMacroNames(): List<String> =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).all
            .filterKeys { it.startsWith(KEY_MACRO_PREFIX) }
            .filterValues { (it as? String)?.isNotBlank() == true }
            .keys
            .map { it.removePrefix(KEY_MACRO_PREFIX) }
            .sorted()

    /**
     * 获取录制时的操作提示文本
     *
     * @param name 录制名称
     */
    private fun getRecordInstruction(name: String): String =
        getString(R.string.record_instruction_format, name)

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
                    LogX.w("FloatingWindowService", "无障碍服务设置已开启但实例未连接，自动重试一次")
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
                    LogX.w("FloatingWindowService", "方向键点击时无障碍服务未开启")
                    Toast.makeText(this, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            service.setDirection(direction)
            startSlide()
        }
    }

    /* 最小化悬浮窗 */
    private fun minimize() {
        controlPanel.visibility = View.GONE
        pauseButton.visibility = View.GONE
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
        pauseButton.visibility = View.GONE
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
        // 展开后把面板重新拉回屏幕内（贴到最近的左/右边缘），
        // 避免从收起态（贴右边缘的小球）展开后面板跑到屏幕外
        rootView.post {
            if (!::rootView.isInitialized || !::layoutParams.isInitialized) {
                return@post
            }
            val displayMetrics = resources.displayMetrics
            val panelWidth = rootView.width
            val panelHeight = rootView.height
            val centerX = layoutParams.x + panelWidth / 2f
            layoutParams.x = if (centerX < displayMetrics.widthPixels / 2f) {
                0
            } else {
                (displayMetrics.widthPixels - panelWidth).coerceAtLeast(0)
            }
            layoutParams.y = layoutParams.y.coerceIn(
                0,
                (displayMetrics.heightPixels - panelHeight).coerceAtLeast(0)
            )
            runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
        }
        if (stopSlide) {
            AutoSlideService.getInstance()?.stopSlide()
        }
    }

    /* 启动自动滑动服务 */
    private fun startSlide() {
        if (blockedByLicense()) return
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
    private fun showSystemAlertDialog(builder: MaterialAlertDialogBuilder) {
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
            LogX.e("FloatingWindowService", "Update view layout failed", e)
        }
        
        // 布局完成后重新执行贴边吸附，确保放大后不悬在半空
        rootView.post {
            snapToNearestEdge()
        }
    }

    companion object {
        /* 导出分享后悬浮窗自动恢复的延迟（毫秒） */
        private const val EXTERNAL_PICKER_RESTORE_DELAY_MS = 12_000L
        // 悬浮窗服务是否正在运行（供磁贴等模块查询）
        private var isServiceRunning = false
        // 当前服务实例（供导入中转页完成后恢复悬浮窗）
        @Volatile
        var instance: FloatingWindowService? = null
            private set

        /**
         * 获取悬浮窗服务运行状态
         * 
         * @return 悬浮窗服务是否正在运行
         */
        @JvmStatic
        fun isRunning(): Boolean = isServiceRunning
    }
}
