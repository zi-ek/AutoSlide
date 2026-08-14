package com.ziek.autoslide

/**
 * 无障碍服务存活状态与自愈（移植自 GKD）
 *
 * GKD 的「完美保活」并不是让进程杀不死，而是保证被杀之后一定能自己回来：
 * 1. 进程无论因何原因被拉起（磁贴绑定 / 粘性服务重启 / 打开主界面），都立刻做一次自愈；
 * 2. 自愈动作 = 把本应用的无障碍组件从 ENABLED_ACCESSIBILITY_SERVICES 里摘掉，
 *    等 1 秒，再加回去，强制系统重新绑定无障碍服务；
 * 3. 全程用 Mutex 串行化，避免多个入口同时触发互相打架。
 *
 * 前提：必须持有 WRITE_SECURE_SETTINGS（Shizuku / ADB 授权）。
 * 用户若是「手动开启」无障碍，本文件的自愈逻辑无法生效——这是系统限制，GKD 同样如此。
 */

import android.content.ComponentName
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.database.ContentObserver
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.ziek.autoslide.service.StatusService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object A11yState {

    private const val TAG = "A11yState"

    /* 系统存储无障碍服务列表时使用的分隔符 */
    private const val SEPARATOR = ':'

    /* 摘除组件后必须等待一段时间，否则系统概率不会触发重新绑定（GKD A11Y_AWAIT_FIX_TIME） */
    private const val A11Y_AWAIT_FIX_TIME = 1000L

    /* 加回组件后等待系统完成绑定的时间（GKD A11Y_AWAIT_START_TIME） */
    private const val A11Y_AWAIT_START_TIME = 2000L

    /* 两次自愈之间的最小间隔，防止 ContentObserver 回调与定时自检互相触发形成死循环 */
    private const val FIX_COOLDOWN_MS = 5000L

    /**
     * 进程刚启动时的宽限期：系统可能正在绑定无障碍，此时 a11yRunningFlow 还是 false，
     * 不能据此判定「故障」而去摘除组件，否则每次进程启动都会白白重启一次无障碍。
     */
    private const val STARTUP_GRACE_MS = 10_000L

    /**
     * 「摘除→等待→加回」的最小重试间隔。
     * 这套动作存在致命风险：摘除后的 1 秒里若被 force-stop（MIUI 一键清理），
     * 组件会永久留在「已移除」状态，从此系统再也不会重新绑定无障碍，
     * App 只能靠用户手点图标才能复活。所以严格限频，尽量少暴露这个窗口。
     */
    private const val RESTART_BACKOFF_MS = 120_000L

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fixMutex = Mutex()
    private var lastFixAt = 0L
    private var lastRestartAt = 0L
    private val processStartAt = SystemClock.elapsedRealtime()

    /* 持有观察者的强引用，避免被回收后系统静默停止回调 */
    private var servicesObserver: ContentObserver? = null

    /**
     * 无障碍服务进程是否存活。
     * 在 AutoSlideService.onCreate 置 true、onDestroy 置 false，
     * 比 getInstance()（onServiceConnected 才赋值）更早也更准确。
     */
    val a11yRunningFlow = MutableStateFlow(false)

    /* 本应用的无障碍组件当前是否在系统的启用列表里 */
    val a11yEnabledFlow = MutableStateFlow(false)

    /* 本应用无障碍服务的组件名（伪装成 Google 的 SelectToSpeakService） */
    private lateinit var a11yComponent: ComponentName

    /**
     * 在 Application.onCreate 中调用：注册启用列表监听，并同步一次当前状态
     *
     * @param context 应用上下文
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        a11yComponent = ComponentName(app, SelectToSpeakService::class.java)
        a11yEnabledFlow.value = getSecureA11yServices().contains(a11yComponent)
        // 老版本升级迁移：此前没有这个标记，若无障碍当前就是开着的，
        // 说明用户本来就希望它常驻，直接补上意图，否则升级后保活会静默失效
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SERVICE_DESIRED)) {
            prefs.edit().putBoolean(KEY_SERVICE_DESIRED, a11yEnabledFlow.value).apply()
        }
        // 上次「摘除→加回」被 force-stop 打断：组件很可能还停在已移除状态，
        // 这会彻底切断 force-stop 后的复活路径，必须最优先补回
        if (prefs.getBoolean(KEY_A11Y_RESTART_IN_PROGRESS, false)) {
            LogX.w(TAG, "previous a11y restart was interrupted, restoring component now")
            scope.launch { fixMutex.withLock { doFixA11yService() } }
        }
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                val enabled = getSecureA11yServices().contains(a11yComponent)
                a11yEnabledFlow.value = enabled
                LogX.i(TAG, "a11y list changed: enabled=$enabled running=${a11yRunningFlow.value}")
                // 被 ROM 悄悄移出列表时立刻自愈（内部有 desired 开关与冷却保护）
                fixRestartA11yService()
            }
        }
        servicesObserver = observer
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
        }.onFailure { LogX.w(TAG, "register a11y observer failed", it) }
    }

    /**
     * 用户是否希望服务保持开启。
     *
     * 对应 GKD 的 storeFlow.enableAutomator：只有用户明确表达过「要开」，自愈才会工作。
     * 否则用户在系统设置里手动关闭无障碍会被我们立刻打开，导致根本关不掉。
     */
    private fun isServiceDesired(): Boolean {
        val app = appContext ?: return false
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_DESIRED, false)
    }

    /**
     * 记录用户意图：开关打开 / 点击开始时置 true，用户主动关闭时置 false
     *
     * @param desired 是否希望无障碍服务保持开启
     */
    fun setServiceDesired(context: Context, desired: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_DESIRED, desired)
            .apply()
        LogX.i(TAG, "service desired = $desired")
    }

    /**
     * 统一自愈入口：修无障碍 + 拉起常驻前台服务。
     * 进程每次启动、回到主界面、下拉通知栏时都应调用。
     */
    fun syncFixState() {
        val app = appContext ?: return
        fixRestartA11yService()
        runCatching { StatusService.autoStart(app) }
    }

    /**
     * 无障碍自愈：服务已死但用户希望它活着时，强制系统重新绑定。
     * 使用 Mutex 保证同一时刻只有一次修复在跑（对应 GKD 的 modifyA11yRun）。
     */
    fun fixRestartA11yService() {
        if (fixMutex.isLocked) return
        scope.launch {
            if (fixMutex.isLocked) return@launch
            fixMutex.withLock { doFixA11yService() }
        }
    }

    /**
     * 真正的修复流程。
     *
     * 分两种情况处理，关键在于「组件是否还在系统的启用列表里」：
     * - 不在列表：直接加回。这是被 ROM 关掉、或上一次重启动作被杀死后的残留状态，
     *   加回操作是幂等的、没有危险窗口，因此不限频、优先执行。
     * - 在列表但没绑定：才需要「摘除→等待→加回」强制系统重绑，
     *   这个动作有被 force-stop 打断的风险，所以加宽限期 + 限频保护。
     */
    private suspend fun doFixA11yService() {
        val app = appContext ?: return
        // 用户没表达过「要开」时不自愈，否则在系统设置里关不掉本服务
        if (!isServiceDesired()) {
            return
        }
        // 没有写入安全设置权限时无法修改启用列表，直接放弃（GKD 同样限制）
        if (!app.hasWriteSecureSettingsPermission()) {
            LogX.w(TAG, "no WRITE_SECURE_SETTINGS, cannot auto fix a11y service")
            return
        }
        // AppOps 把无障碍关掉时，改启用列表也救不回来，先打日志暴露原因
        if (!AppOpsState.isAccessibilityAllowed(app)) {
            LogX.w(TAG, "accessibility appop restricted, self-heal may not take effect")
        }
        val names = getSecureA11yServices()

        // ---- 情况一：组件不在启用列表里 ----
        // 这是最要命的状态：force-stop 过的进程只能靠系统重新绑定无障碍来复活，
        // 组件一旦丢失，这条复活路径就断了，只能用户手点图标。发现即刻补回。
        if (!names.contains(a11yComponent)) {
            names.add(a11yComponent)
            enableA11yMasterSwitch()
            putSecureA11yServices(names)
            clearRestartInProgress()
            LogX.i(TAG, "component was missing from enabled list, re-added")
            return
        }

        // ---- 情况二：组件在列表里 ----
        // 系统认为已绑定就什么都不用做
        if (isA11yServiceBound()) {
            return
        }
        // 进程刚起来，系统可能正在绑定，先给它时间，不要急着摘除组件
        if (SystemClock.elapsedRealtime() - processStartAt < STARTUP_GRACE_MS) {
            LogX.d(TAG, "within startup grace period, skip restart")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFixAt < FIX_COOLDOWN_MS) {
            return
        }
        if (now - lastRestartAt < RESTART_BACKOFF_MS) {
            return
        }
        lastFixAt = now
        lastRestartAt = now
        // 落一个标记：万一在下面这 1 秒窗口里被 force-stop，
        // 下次进程启动时 init() 能识别出来并立刻把组件补回去
        markRestartInProgress()
        names.remove(a11yComponent)
        putSecureA11yServices(names)
        LogX.i(TAG, "a11y in list but not bound, removed component, waiting ${A11Y_AWAIT_FIX_TIME}ms")
        delay(A11Y_AWAIT_FIX_TIME)
        names.add(a11yComponent)
        enableA11yMasterSwitch()
        putSecureA11yServices(names)
        clearRestartInProgress()
        delay(A11Y_AWAIT_START_TIME)
        lastFixAt = SystemClock.elapsedRealtime()
        LogX.i(TAG, "a11y restart finished, bound=${isA11yServiceBound()}")
    }

    /* 打开无障碍总开关 */
    private fun enableA11yMasterSwitch() {
        val app = appContext ?: return
        runCatching {
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /**
     * 无障碍服务是否真的在运行。
     *
     * a11yRunningFlow 是进程内变量，在被磁贴等入口拉起的新进程里恒为 false，
     * 只靠它会把健康的无障碍误判成故障，进而反复执行危险的重启动作。
     * 这里再向系统交叉确认一次。
     */
    private fun isA11yServiceBound(): Boolean {
        if (a11yRunningFlow.value) return true
        val app = appContext ?: return false
        return runCatching {
            val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == app.packageName }
        }.getOrDefault(false)
    }

    /* 标记「正在执行重启动作」，用于识别被 force-stop 打断的情况 */
    private fun markRestartInProgress() {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_A11Y_RESTART_IN_PROGRESS, true)?.commit()
    }

    /* 清除重启标记 */
    private fun clearRestartInProgress() {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_A11Y_RESTART_IN_PROGRESS, false)?.commit()
    }

    /* 读取系统当前启用的全部无障碍服务组件 */
    private fun getSecureA11yServices(): MutableSet<ComponentName> {
        val app = appContext ?: return mutableSetOf()
        val value = runCatching {
            Settings.Secure.getString(
                app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        if (value.isNullOrEmpty()) return mutableSetOf()
        return value.split(SEPARATOR)
            .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
            .toMutableSet()
    }

    /* 写回系统的无障碍服务启用列表 */
    private fun putSecureA11yServices(services: Set<ComponentName>) {
        val app = appContext ?: return
        runCatching {
            Settings.Secure.putString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(SEPARATOR.toString()) { it.flattenToString() }
            )
        }.onFailure { LogX.w(TAG, "put a11y services failed", it) }
    }
}
