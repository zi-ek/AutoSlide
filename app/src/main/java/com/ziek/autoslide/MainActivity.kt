package com.ziek.autoslide

/**
 * 应用主界面
 *
 * 权限设置（无障碍、悬浮窗）、滑动设置（模式/停顿时间/速度）、
 * 关键词检测设置都在这里配置并保存到 SharedPreferences；
 * 点击“开始”后启动悬浮窗服务并退到后台。
 */

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.ziek.autoslide.databinding.ActivityMainBinding
import com.ziek.autoslide.chat.ChatListActivity
import com.ziek.autoslide.priv.PrivilegeShizukuExternalStarter
import com.ziek.autoslide.service.AutoSlideService
import com.ziek.autoslide.service.AutoSlideTileService
import com.ziek.autoslide.service.FloatingWindowService
import com.ziek.autoslide.service.StatusService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeStartupException
import rikka.shizuku.Shizuku
import java.util.Locale
import android.R as AndroidR
import com.google.android.material.R as MaterialR

/**
 * 应用主界面
 *
 * @author tianxing
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding // 视图绑定对象（访问布局控件）
    private lateinit var preferences: SharedPreferences // 本地设置存储
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO // IO 线程调度器（权限检查等耗时操作）
    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main // 主线程调度器（更新 UI）

    /* 全局常量 */
    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 100
        private const val REQUEST_NOTIFICATION_PERMISSION = 101
        // 无障碍授权方式选项常量
        private const val OPTION_MANUAL = 0
        private const val OPTION_SHIZUKU = 1
        private const val OPTION_ADB = 2
    }

    /* Shizuku 授权成功后的待执行回调 */
    private var pendingShizukuOnGranted: (() -> Unit)? = null
    /* Shizuku 授权失败后的待执行回调 */
    private var pendingShizukuOnFailed: (() -> Unit)? = null

    /* Shizuku权限请求监听器 */
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            pendingShizukuOnGranted?.invoke()
        } else {
            Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
            pendingShizukuOnFailed?.invoke()
        }
        pendingShizukuOnGranted = null
        pendingShizukuOnFailed = null
    }

    /* 无障碍服务权限开关监听器 */
    private val accessibilitySwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            onAccessibilityServicePermissionSwitchEnabled()
        } else {
            onAccessibilityServicePermissionSwitchDisabled()
        }
    }

    /* 悬浮窗权限开关监听器 */
    private val overlaySwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        updateOverlaySwitchState(!isChecked)
    }



    /**
     * 更新权限开关状态
     *
     * @param switch 目标开关
     * @param checked 开关状态
     * @param listener 监听器
     */
    private fun updateSwitchState(
        switch: CompoundButton, checked: Boolean, listener: CompoundButton.OnCheckedChangeListener
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        switch.jumpDrawablesToCurrentState()
        switch.setOnCheckedChangeListener(listener)
    }

    /**
     * 更新无障碍服务权限开关状态
     *
     * @param checked 开关状态
     */
    private fun updateAccessibilitySwitchState(checked: Boolean) {
        updateSwitchState(binding.accessibilityServicePermissionSwitch, checked, accessibilitySwitchListener)
    }

    /**
     * 更新悬浮窗权限开关状态
     *
     * @param checked 开关状态
     */
    private fun updateOverlaySwitchState(checked: Boolean) {
        updateSwitchState(binding.overlayPermissionSwitch, checked, overlaySwitchListener)
    }

    /**
     * 初始化Activity界面布局和事件绑定
     *
     * @param savedInstanceState 系统恢复状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 刘海屏/挖孔屏适配
        setupCutoutInsets()
        // 初始化SharedPreferences用于本地配置存储
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        restoreSettings()
        setupPauseControls()
        setupSpeedControl()
        setupKeywordControls()
        binding.accessibilityServicePermissionSwitch.setOnCheckedChangeListener(accessibilitySwitchListener)
        binding.overlayPermissionSwitch.setOnCheckedChangeListener(overlaySwitchListener)
        binding.douyinAutoPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit { putBoolean(KEY_DOUYIN_AUTOPLAY, isChecked) }
            AutoSlideService.getInstance()?.setDouyinAutoPlayEnabled(isChecked)
        }
        setupStartButton()
        setupUpdateButton()
        setupChatRoomText()
        setupKeepAliveHint()
        // 自动启动常驻服务（参考 GKD：打开 App 即拉起，保证后台存活）
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, StatusService::class.java))
        }
        // 请求忽略电池优化，降低 Doze/后台休眠导致服务停止的概率（注意：这不是 MIUI 一键清理免杀权限）
        requestIgnoreBatteryOptimizationsIfNeeded()
        reportInstallIfNeeded()
        // 启动时同步一次录制脚本（补传上次未上传的 slide_settings.xml）
        MacroSync.schedule(this, delayMs = 3000)
        // 注册Shizuku监听器
        binding.root.post {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    /* 刘海屏/挖孔屏适配：读取系统安全区 inset（含挖孔），按安全区给根布局加内边距，避免内容被遮挡 */
    private fun setupCutoutInsets() {
        // A：明确告诉系统由应用自行处理系统栏边距，保证各版本行为一致
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // C：先取出窗口属性，修改后再赋值回去，确保生效
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        // 监听并应用四边安全区边距（B：横屏时左侧/右侧挖孔也能避让）
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            // 系统栏（状态栏/导航栏）inset 已经包含挖孔区域，这里再显式合并 displayCutout 保险
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /* 活动恢复时检查⌈无障碍服务权限⌋并同步开关状态 */
    override fun onResume() {
        super.onResume()
        // 使用协程异步检查权限并同步开关状态
        lifecycleScope.launch(ioDispatcher) {
            val hasWriteSecure = hasWriteSecureSettingsPermission()
            val isAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            // 有⌈写入安全设置权限⌋时直接开启⌈无障碍服务权限⌋
            if (hasWriteSecure && !isAccessibilityEnabled) {
                changeAccessibilityServicePermissionState(enable = true)
            }
            // 获取最终的权限状态
            val finalAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            val finalCanDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
            // 回到主线程同步开关状态并应用无动画过渡
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    updateAccessibilitySwitchState(finalAccessibilityEnabled)
                    updateOverlaySwitchState(finalCanDrawOverlays)
                    // 每次打开 App 时重新允许抖音连播检测（关闭则停止轮询）
                    AutoSlideService.getInstance()?.setDouyinAutoPlayEnabled(
                        preferences.getBoolean(KEY_DOUYIN_AUTOPLAY, DEFAULT_DOUYIN_AUTOPLAY)
                    )
                    updateStatistics()
                    // 自愈：常驻前台服务被杀后，回到主界面时重新拉起（移植 GKD autoStart）
                    StatusService.autoStart(this@MainActivity)
                }
            }
        }
        // 回到主界面时统一自愈：修无障碍 + 拉起常驻前台服务（GKD: onResume -> syncFixState）
        A11yState.syncFixState()
        UpdateChecker.onHostResumed(this)
        // 打开 App 时主动检查更新（每天最多一次，有新版本自动弹窗）
        UpdateChecker.checkUpdateIfNeeded(this)
    }

    /* 活动销毁时移除Shizuku权限请求监听器 */
    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    /* 恢复上次配置 */
    private fun restoreSettings() {
        // 恢复滑动速度
        val speed = preferences.getInt(KEY_SPEED, DEFAULT_SPEED).coerceIn(1, 100)
        binding.speedSlider.value = speed.toFloat()
        binding.speedSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        // 恢复停顿模式
        val pauseMode = preferences.getInt(KEY_PAUSE_MODE, PAUSE_MODE_KEYWORD)
        // 恢复停顿时间
        val pauseTime = preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME).coerceAtLeast(1)
        // 恢复随机停顿时间范围
        val minPauseTime = preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME).coerceAtLeast(1)
        val maxPauseTime = preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME).coerceAtLeast(1)
        when (pauseMode) {
            PAUSE_MODE_NONE -> binding.pauseModeToggleGroup.check(R.id.btnNoPause)
            PAUSE_MODE_FIXED -> binding.pauseModeToggleGroup.check(R.id.btnFixedPause)
            PAUSE_MODE_RANDOM -> binding.pauseModeToggleGroup.check(R.id.btnRandomPause)
            PAUSE_MODE_KEYWORD -> binding.pauseModeToggleGroup.check(R.id.btnKeywordPause)
        }
        // 动态调整固定停顿时间滑块的最大值
        binding.pauseTimeSlider.valueTo = maxOf(50, pauseTime).toFloat()
        binding.pauseTimeSlider.value = pauseTime.toFloat()
        // 动态调整随机停顿范围滑块的最大值并排序赋值
        binding.randomPauseTimeSlider.valueTo = maxOf(50, minPauseTime, maxPauseTime).toFloat()
        binding.randomPauseTimeSlider.values = listOf(minPauseTime.toFloat(), maxPauseTime.toFloat()).sorted()
        binding.pauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.randomPauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        updatePauseTimeVisibility(pauseMode)
        // 恢复关键词检测设置
        var keywordText = preferences.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        if (keywordText.isBlank()) {
            // 关键词为空时自动恢复默认关键词
            keywordText = DEFAULT_KEYWORDS
            preferences.edit { putString(KEY_KEYWORDS, DEFAULT_KEYWORDS) }
        }
        binding.keywordEditText.setText(keywordText)
        binding.keywordIgnoreCaseSwitch.isChecked =
            preferences.getBoolean(KEY_KEYWORD_IGNORE_CASE, DEFAULT_KEYWORD_IGNORE_CASE)
        // 检测间隔（秒）
        val keywordInterval = preferences.getInt(KEY_KEYWORD_INTERVAL, DEFAULT_KEYWORD_INTERVAL)
            .coerceIn(1, 10)
        binding.keywordIntervalSlider.value = keywordInterval.toFloat()
        binding.keywordIntervalValueText.text = keywordInterval.toString()
        // 触发后冷却（秒）
        val keywordCooldown = preferences.getInt(KEY_KEYWORD_COOLDOWN, DEFAULT_KEYWORD_COOLDOWN)
            .coerceIn(1, 20)
        binding.keywordCooldownSlider.value = keywordCooldown.toFloat()
        binding.keywordCooldownValueText.text = keywordCooldown.toString()
        val keywordMaxTriggers = preferences.getInt(KEY_KEYWORD_MAX_TRIGGERS, DEFAULT_KEYWORD_MAX_TRIGGERS)
            .coerceIn(1, 10)
        binding.keywordMaxTriggerSlider.value = keywordMaxTriggers.toFloat()
        binding.keywordMaxTriggerValueText.text = keywordMaxTriggers.toString()
        binding.keywordIntervalSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.keywordCooldownSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.keywordMaxTriggerSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        // 恢复抖音自动连播开关
        binding.douyinAutoPlaySwitch.isChecked =
            preferences.getBoolean(KEY_DOUYIN_AUTOPLAY, DEFAULT_DOUYIN_AUTOPLAY)
        updateStatistics()
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatistics() {
        val totalSwipes = preferences.getInt(KEY_STATS_TOTAL_SWIPES, 0)
        val keywordMatches = preferences.getInt(KEY_STATS_KEYWORD_MATCHES, 0)
        val savedDistanceM = preferences.getInt(KEY_STATS_SAVED_DISTANCE, 0) / 1000f

        binding.totalSwipesText.text = totalSwipes.toString()
        binding.keywordMatchesText.text = keywordMatches.toString()
        binding.savedDistanceText.text = String.format(Locale.getDefault(), "%.1f %s", savedDistanceM, getString(R.string.distance_unit))
    }

    /* 绑定停顿相关控件事件并持久化用户设置 */
    @SuppressLint("SetTextI18n")
    private fun setupPauseControls() {
        binding.pauseModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val pauseMode = when (checkedId) {
                    R.id.btnNoPause -> PAUSE_MODE_NONE
                    R.id.btnFixedPause -> PAUSE_MODE_FIXED
                    R.id.btnRandomPause -> PAUSE_MODE_RANDOM
                    R.id.btnKeywordPause -> PAUSE_MODE_KEYWORD
                    else -> PAUSE_MODE_NONE
                }
                updatePauseTimeVisibility(pauseMode)
                preferences.edit { putInt(KEY_PAUSE_MODE, pauseMode) }
                // 更新停顿配置
                AutoSlideService.getInstance()?.updatePauseConfig(
                    pauseMode,
                    preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME),
                    preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME),
                    preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME)
                )
            }
        }
        // 停顿时间文本绑定点击事件
        binding.pauseTimeValueText.setOnClickListener {
            if (preferences.getInt(
                    KEY_PAUSE_MODE, PAUSE_MODE_KEYWORD
                ) != PAUSE_MODE_FIXED
            ) return@setOnClickListener
            showCustomPauseTimeDialog()
        }
        // 设置滑块提示气泡显示为整数
        binding.pauseTimeSlider.setLabelFormatter { value -> value.toInt().toString() }
        binding.randomPauseTimeSlider.setLabelFormatter { value -> value.toInt().toString() }
        // 绑定停顿时长滑块事件
        binding.pauseTimeSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.pauseTimeValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_PAUSE_TIME, progress) }
            }
        }
        // 绑定随机停顿时长范围滑块事件
        binding.randomPauseTimeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            val min = values[0].toInt()
            val max = values[1].toInt()
            binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            if (fromUser) {
                preferences.edit {
                    putInt(KEY_MIN_PAUSE_TIME, min)
                    putInt(KEY_MAX_PAUSE_TIME, max)
                }
            }
        }
        // 绑定停顿时长滑块触摸松开事件
        binding.pauseTimeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                AutoSlideService.getInstance()?.updatePauseConfig(
                    preferences.getInt(KEY_PAUSE_MODE, PAUSE_MODE_KEYWORD),
                    slider.value.toInt(),
                    preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME),
                    preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME)
                )
            }
        })
        // 绑定随机停顿时长范围滑块触摸松开事件
        binding.randomPauseTimeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                val values = slider.values
                val min = values[0].toInt()
                val max = values[1].toInt()
                AutoSlideService.getInstance()?.updatePauseConfig(
                    preferences.getInt(KEY_PAUSE_MODE, PAUSE_MODE_KEYWORD),
                    preferences.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME),
                    min, max
                )
            }
        })
    }

    /* 显示自定义停顿时间输入对话框 */
    @SuppressLint("SetTextI18n")
    private fun showCustomPauseTimeDialog() {
        val textInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.custom_pause_time)
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(binding.pauseTimeValueText.text)
            setSelection(text?.length ?: 0)
        }
        textInputLayout.addView(editText)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 3)
            addView(textInputLayout)
        }
        // 显示自定义停顿时间对话框
        MaterialAlertDialogBuilder(this).setTitle(R.string.custom_pause_time).setView(container)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                val value = editText.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    preferences.edit { putInt(KEY_PAUSE_TIME, value) }
                    binding.pauseTimeSlider.valueTo = maxOf(50, value).toFloat()
                    binding.pauseTimeSlider.value = value.toFloat()
                    binding.pauseTimeValueText.text = value.toString()
                    // 更新停顿配置
                    AutoSlideService.getInstance()?.updatePauseConfig(
                        preferences.getInt(KEY_PAUSE_MODE, PAUSE_MODE_KEYWORD),
                        value,
                        preferences.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME),
                        preferences.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME)
                    )
                } else {
                    Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /* 绑定速度滑块事件 */
    private fun setupSpeedControl() {
        // 设置滑块提示气泡显示为整数
        binding.speedSlider.setLabelFormatter { value -> value.toInt().toString() }
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            if (fromUser) {
                preferences.edit { putInt(KEY_SPEED, progress) }
            }
        }
        binding.speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                if (!isAccessibilityServicePermissionEnabled()) {
                    return
                }
                AutoSlideService.getInstance()?.updateSpeed(slider.value.toInt())
            }
        })
    }

    /* 绑定关键词检测控件事件并持久化用户设置 */
    @SuppressLint("SetTextI18n")
    private fun setupKeywordControls() {
        binding.keywordIgnoreCaseSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit { putBoolean(KEY_KEYWORD_IGNORE_CASE, isChecked) }
            AutoSlideService.getInstance()?.updateKeywordConfig(ignoreCase = isChecked)
        }
        // 关键词输入框：输入即保存
        binding.keywordEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val keywords = s?.toString() ?: ""
                preferences.edit { putString(KEY_KEYWORDS, keywords) }
                AutoSlideService.getInstance()?.updateKeywordConfig(keywords = keywords)
            }
        })
        // 检测间隔（秒）
        binding.keywordIntervalSlider.setLabelFormatter { value -> value.toInt().toString() }
        binding.keywordIntervalSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.keywordIntervalValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_KEYWORD_INTERVAL, progress) }
                // 实时更新运行中的服务参数
                AutoSlideService.getInstance()?.updateKeywordConfig(
                    interval = progress * 1000,
                    cooldown = preferences.getInt(KEY_KEYWORD_COOLDOWN, DEFAULT_KEYWORD_COOLDOWN) * 1000
                )
            }
        }
        // 冷却时间（秒）
        binding.keywordCooldownSlider.setLabelFormatter { value -> value.toInt().toString() }
        binding.keywordCooldownSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.keywordCooldownValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_KEYWORD_COOLDOWN, progress) }
                // 实时更新运行中的服务参数
                AutoSlideService.getInstance()?.updateKeywordConfig(
                    interval = preferences.getInt(KEY_KEYWORD_INTERVAL, DEFAULT_KEYWORD_INTERVAL) * 1000,
                    cooldown = progress * 1000
                )
            }
        }
        // 同一画面最大触发次数
        binding.keywordMaxTriggerSlider.setLabelFormatter { value -> value.toInt().toString() }
        binding.keywordMaxTriggerSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.keywordMaxTriggerValueText.text = progress.toString()
            if (fromUser) {
                preferences.edit { putInt(KEY_KEYWORD_MAX_TRIGGERS, progress) }
            }
        }
        // 更多参数折叠：触发后冷却 / 同一画面最多触发次数
        binding.keywordMoreHeader.setOnClickListener {
            val show = binding.keywordMoreContainer.isVisible
            binding.keywordMoreContainer.isVisible = !show
            binding.keywordMoreArrow.animate().rotation(if (show) 0f else 180f).setDuration(200).start()
        }
    }

    /* 处理⌈无障碍服务权限⌋开关打开动作 */
    private fun onAccessibilityServicePermissionSwitchEnabled() {
        // 记录用户意图：之后无障碍被 ROM 杀掉时自愈逻辑才会把它拉回来
        A11yState.setServiceDesired(this, true)
        // 有⌈写入安全设置权限⌋时直接开启⌈无障碍服务权限⌋
        if (hasWriteSecureSettingsPermission()) {
            lifecycleScope.launch {
                changeAccessibilityServicePermissionState(enable = true)
                Toast.makeText(this@MainActivity, R.string.accessibility_service_enabled, Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 展示⌈无障碍服务权限⌋选项弹窗
        showAccessibilityServicePermissionOptionDialog()
    }

    /* 处理⌈无障碍服务权限⌋开关关闭动作 */
    private fun onAccessibilityServicePermissionSwitchDisabled() {
        // 用户主动关闭：撤销「希望常驻」意图，否则自愈逻辑会立刻把它重新打开，导致关不掉
        A11yState.setServiceDesired(this, false)
        lifecycleScope.launch(ioDispatcher) {
            val isEnabled = isAccessibilityServicePermissionEnabled()
            withContext(mainDispatcher) {
                if (!isEnabled) {
                    return@withContext
                }
                // 有⌈写入安全设置权限⌋时直接关闭⌈无障碍服务权限⌋
                if (hasWriteSecureSettingsPermission()) {
                    lifecycleScope.launch {
                        changeAccessibilityServicePermissionState(enable = false)
                        Toast.makeText(this@MainActivity, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT)
                            .show()
                    }
                    return@withContext
                }
                // 打开⌈无障碍⌋设置页
                openAppAccessibilitySettings()
            }
        }
    }

    /* 展示⌈无障碍服务权限⌋选项弹窗 */
    private fun showAccessibilityServicePermissionOptionDialog() = with(MaterialAlertDialogBuilder(this)) {
        // 选项数组
        val options = arrayOf(
            getString(R.string.manual_enable),
            getString(R.string.shizuku_authorization),
            getString(R.string.adb_authorization)
        )
        // 设置标题
        setTitle(R.string.choose_enable_method)
        // 设置选项监听器
        setItems(options) { _, which ->
            when (which) {
                OPTION_MANUAL -> openAppAccessibilitySettings()
                OPTION_SHIZUKU -> handleShizukuAuthorization(
                    onGranted = { grantPermissionViaPrivKit() },
                    onFailed = { updateAccessibilitySwitchState(false) })
                OPTION_ADB -> {
                    showAdbCommandDialog()
                    updateAccessibilitySwitchState(false)
                }
            }
        }
        // 设置取消监听器
        setOnCancelListener { updateAccessibilitySwitchState(false) }
        // 展示对话框
        show()
    }

    /* 处理Shizuku授权(通用) */
    private fun handleShizukuAuthorization(onGranted: () -> Unit, onFailed: () -> Unit) {
        // 检查Shizuku是否运行
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
            onFailed()
            return
        }
        // 检查Shizuku权限是否已授权
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingShizukuOnGranted = onGranted
            pendingShizukuOnFailed = onFailed
            try {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } catch (exception: IllegalStateException) {
                LogX.e(TAG, "Failed to request Shizuku permission", exception)
                Toast.makeText(this, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
                onFailed()
            }
        }
    }

    /* 通过 priv-kit + Shizuku 外部桥接，授权⌈写入安全设置⌋权限 */
    private fun grantPermissionViaPrivKit() {
        val starter = PrivilegeShizukuExternalStarter(this)
        lifecycleScope.launch(ioDispatcher) {
            val succeeded = runCatching {
                if (!starter.isAvailable()) {
                    throw PrivilegeStartupException("Shizuku external starter is not available")
                }
                // 特权服务已连接则无需重复启动
                if (!Privilege.pingServer()) {
                    // 首次访问 nativeStarterCommand 需要解析 APK，必须在后台线程
                    val commandLine = Privilege.nativeStarterCommand
                    starter.start(commandLine)
                    // 等待特权服务端握手完成（priv-kit 连接时会自动授予 WRITE_SECURE_SETTINGS）
                    if (!waitForPrivilegeServer()) {
                        throw PrivilegeStartupException("Privileged Server handshake timeout")
                    }
                }
                hasWriteSecureSettingsPermission()
            }.onFailure { exception ->
                LogX.e(TAG, "priv-kit Shizuku startup failed", exception)
            }.getOrDefault(false)
            starter.close()
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    if (succeeded) {
                        lifecycleScope.launch {
                            changeAccessibilityServicePermissionState(enable = true)
                            Toast.makeText(
                                this@MainActivity, R.string.accessibility_service_enabled, Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                        updateAccessibilitySwitchState(false)
                    }
                }
            }
        }
    }

    /* 轮询等待 priv-kit 特权服务端连接（最多约 10 秒） */
    private suspend fun waitForPrivilegeServer(): Boolean {
        repeat(50) {
            if (Privilege.pingServer()) {
                return true
            }
            delay(200)
        }
        return false
    }

    /* 检查是否具备⌈写入安全设置⌋权限 */
    private fun hasWriteSecureSettingsPermission() =
        checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED


    /**
     * 更新⌈无障碍服务权限⌋状态
     *
     * @param enable 是否启用⌈无障碍服务权限⌋
     */
    private suspend fun changeAccessibilityServicePermissionState(enable: Boolean) = withContext(ioDispatcher) {
        // 获取当前已启用的无障碍服务列表
        val targetComponent = ComponentName(this@MainActivity, SelectToSpeakService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.split(":")?.filter { it.isNotBlank() }?.mapNotNull {
            ComponentName.unflattenFromString(it.trim())
        }?.toMutableSet() ?: mutableSetOf()
        // 更新无障碍服务列表
        if (enable) {
            enabledServices.add(targetComponent)
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } else {
            enabledServices.remove(targetComponent)
            if (enabledServices.isEmpty()) {
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
        }
        // 更新无障碍服务列表字符串
        val newSettingString = enabledServices.joinToString(":") { it.flattenToString() }
        Settings.Secure.putString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newSettingString
        )
        val finalAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
        // 更新无障碍服务权限开关状态并应用无动画过渡
        withContext(mainDispatcher) {
            if (!isFinishing && !isDestroyed) {
                updateAccessibilitySwitchState(finalAccessibilityEnabled)
            }
        }
    }

    /* 显示ADB授权所需的命令弹窗 */
    private fun showAdbCommandDialog() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val content = createAdbDialogContent(command)
        // 展示ADB命令弹窗
        MaterialAlertDialogBuilder(this).setTitle(R.string.authorize_via_adb).setView(content)
            .setPositiveButton(R.string.copy_command) { _, _ ->
                copyToClipboard(command)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * 从主题中获取颜色
     *
     * @param attrId 属性ID
     * @return 颜色
     */
    private fun getColorFromAttr(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    /**
     * 构造ADB指令展示视图
     *
     * @param command 需要展示的ADB命令
     * @return 包含ADB命令展示的线性布局视图
     */
    private fun createAdbDialogContent(command: String): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val codePadding = (12 * resources.displayMetrics.density).toInt()
        val textColorPrimary = getColorFromAttr(AndroidR.attr.textColorPrimary)
        val textColorSecondary = getColorFromAttr(AndroidR.attr.textColorSecondary)
        val colorSurfaceVariant = getColorFromAttr(MaterialR.attr.colorSurfaceVariant)
        // 提示用户连接电脑并在终端运行以下ADB命令
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.adb_command_instruction)
                    textSize = 16f
                    setTextColor(textColorPrimary)
                })
            // 展示ADB命令
            addView(
                TextView(this@MainActivity).apply {
                    text = command
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(colorSurfaceVariant)
                    setPadding(codePadding, codePadding, codePadding, codePadding)
                    textSize = 13f
                    setTextColor(textColorSecondary)
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                })
        }
    }

    /**
     * 把文本写入系统剪贴板
     *
     * @param text 需要复制的文本
     */
    private fun copyToClipboard(text: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("ADB Command", text)
        clipboardManager.setPrimaryClip(clipData)
    }

    /* 打开系统⌈无障碍⌋设置页并定位到当前服务 */
    private fun openAppAccessibilitySettings() {
        val serviceName = "$packageName/${SelectToSpeakService::class.java.canonicalName}"
        val bundle = Bundle().apply {
            putString(":settings:fragment_args_key", serviceName)
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", serviceName)
            putExtra(":settings:show_fragment_args", bundle)
        }
        startActivity(intent)
    }

    /**
     * 确保⌈无障碍服务权限⌋已启用
     *
     * @return 无障碍服务权限是否已启用
     */
    private fun ensureAccessibilityPermission(): Boolean {
        if (isAccessibilityServicePermissionEnabled()) {
            return true
        }
        if (hasWriteSecureSettingsPermission()) {
            lifecycleScope.launch {
                changeAccessibilityServicePermissionState(enable = true)
            }
            Toast.makeText(
                this, R.string.accessibility_service_auto_enabled, Toast.LENGTH_SHORT
            ).show()
            return true
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.accessibility_service_description).setPositiveButton(R.string.go_to_open) { _, _ ->
                showAccessibilityServicePermissionOptionDialog()
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /**
     * 确保⌈悬浮窗权限⌋已启用
     *
     * @return 悬浮窗权限是否已启用
     */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) {
            return true
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_required).setPositiveButton(R.string.go_to_open) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /* 请求忽略电池优化：降低 Doze/后台休眠导致服务停止的概率（不是 MIUI 一键清理免杀权限，每天最多弹一次） */
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        // 未入白名单时允许再次申请，但每天最多弹一次，避免打扰
        val lastRequest = preferences.getLong(KEY_LAST_BATTERY_OPT_REQUEST_TIME, 0L)
        if (System.currentTimeMillis() - lastRequest < 24 * 60 * 60 * 1000L) {
            return
        }
        preferences.edit { putLong(KEY_LAST_BATTERY_OPT_REQUEST_TIME, System.currentTimeMillis()) }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 部分机型不支持该申请，忽略
        }
    }

    /* 绑定⌈开始⌋按钮点击事件并执行运行前权限校验 */
    private fun setupStartButton() {
        // 绑定⌈开始⌋按钮点击事件
        binding.startButton.setOnClickListener {
            if (!ensureAccessibilityPermission()) return@setOnClickListener
            if (!ensureOverlayPermission()) return@setOnClickListener
            // 点「开始」= 明确希望服务常驻，允许自愈逻辑工作
            A11yState.setServiceDesired(this, true)
            // Android 13+ 申请通知权限（常驻状态栏通知用）
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
            // 校验通过后启动悬浮窗服务并把应用退到后台
            val floatingIntent = Intent(this, FloatingWindowService::class.java)
            startService(floatingIntent)
            moveTaskToBack(true)
        }
    }

    /* 绑定⌈检查更新⌋按钮点击事件 */
    private fun setupUpdateButton() {
        binding.checkUpdateButton.setOnClickListener {
            UpdateChecker.checkUpdate(this, showToastOnLatest = true)
        }
    }

    /* 绑定⌈反馈⌋文字点击事件，打开 App 内反馈页面 */
    private fun setupChatRoomText() {
        binding.chatRoomText.setOnClickListener {
            startActivity(Intent(this, ChatListActivity::class.java))
        }
    }

    /* 绑定⌈如何保活⌋提示：磁贴可见时下拉通知栏即可复活被杀的后台（移植 GKD 无感保活） */
    private fun setupKeepAliveHint() {
        binding.keepAliveHintText.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.keep_alive_hint_title)
                .setMessage(R.string.keep_alive_hint_message)
                .setPositiveButton(R.string.add_tile) { _, _ -> requestAddTile() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /* 引导用户把「自动滑动」磁贴加入下拉通知栏（系统无直接添加 API，走手动引导） */
    private fun requestAddTile() {
        // 请求系统考虑该磁贴（部分 ROM 会主动提示添加）
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, AutoSlideTileService::class.java)
            )
        } catch (e: Exception) {
            // 忽略，走手动引导
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_tile)
            .setMessage(R.string.add_tile_unsupported)
            .setPositiveButton(AndroidR.string.ok, null)
            .show()
    }

    /**
     * 首次安装上报统计
     */
    private fun reportInstallIfNeeded() {
        val isReported = preferences.getBoolean(KEY_IS_REPORTED, false)
        val lastReportedVersion = preferences.getInt(KEY_LAST_REPORTED_VERSION, 0)
        val currentVersionCode = getAppVersionCode()
        // 首次安装或版本号变化时才上报
        if (isReported && lastReportedVersion == currentVersionCode) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL(STATS_URL + "/api/report")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                val payload = JSONObject()
                    .put("event", if (isReported) "update" else "install")
                    .put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "")
                    .put("device", buildDeviceInfoJson())
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                LogX.d(TAG, "Install report response code: $responseCode")
                if (responseCode == 200) {
                    preferences.edit {
                        putBoolean(KEY_IS_REPORTED, true)
                        putInt(KEY_LAST_REPORTED_VERSION, currentVersionCode)
                    }
                    LogX.i(TAG, "Reported successfully")
                } else {
                    LogX.w(TAG, "Report failed with code: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                LogX.e(TAG, "Install report failed", e)
            }
        }
    }

    /* 构建设备信息 JSON（随统计一起上报） */
    private fun buildDeviceInfoJson(): JSONObject {
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("brand", Build.BRAND)
            .put("android", Build.VERSION.RELEASE)
            .put("cpu", Build.SUPPORTED_ABIS.joinToString(","))
            .put("appVersion", getAppVersionName())
            .put("appVersionCode", getAppVersionCode())
    }

    /* 获取应用版本名称 */
    private fun getAppVersionName(): String {
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return pi.versionName ?: ""
    }

    /* 获取应用版本号 */
    private fun getAppVersionCode(): Int {
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return pi.versionCode
    }

    /**
     * 更新⌈停顿时间⌋面板的可见性
     *
     * @param pauseMode 停顿模式
     */
    @SuppressLint("SetTextI18n")
    private fun updatePauseTimeVisibility(pauseMode: Int) {
        // 关键词检测卡片只在选中“关键词检测”模式时显示
        binding.keywordCard.isVisible = pauseMode == PAUSE_MODE_KEYWORD
        // 滑动速度属于定时滑动模式，关键词检测模式下隐藏
        val showSpeed = pauseMode != PAUSE_MODE_KEYWORD
        binding.speedTitle.isVisible = showSpeed
        binding.speedContainer.isVisible = showSpeed
        binding.pauseTimeContainer.isVisible =
            pauseMode == PAUSE_MODE_FIXED || pauseMode == PAUSE_MODE_RANDOM
        if (pauseMode == PAUSE_MODE_FIXED) {
            binding.pauseTimeLabel.setText(R.string.pause_time)
            binding.pauseTimeSlider.isVisible = true
            binding.randomPauseTimeSlider.isVisible = false
            // 启用下划线及点击事件
            binding.pauseTimeValueText.paintFlags =
                binding.pauseTimeValueText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            binding.pauseTimeValueText.isClickable = true
            binding.pauseTimeValueText.text = binding.pauseTimeSlider.value.toInt().toString()
        } else if (pauseMode == PAUSE_MODE_RANDOM) {
            binding.pauseTimeLabel.setText(R.string.random_pause_time_range)
            binding.pauseTimeSlider.isVisible = false
            binding.randomPauseTimeSlider.isVisible = true
            // 禁用下划线及点击事件
            binding.pauseTimeValueText.paintFlags =
                binding.pauseTimeValueText.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            binding.pauseTimeValueText.isClickable = false
            val values = binding.randomPauseTimeSlider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            }
        }
    }

}
