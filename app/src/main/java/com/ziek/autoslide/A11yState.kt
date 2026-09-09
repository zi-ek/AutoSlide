package com.ziek.autoslide

/**
 * 无障碍存活状态与自愈（严格对照 GKD 的实现移植）
 *
 * 对应 GKD 源码位置：
 * - `service/GkdTileService.kt` 的 modifyA11yRun / fixA11yService / fixRestartAutomatorService
 * - `App.kt` 的 getSecureA11yServices / putSecureA11yServices
 * - `MainActivity.kt` 的 syncFixState
 *
 * 核心思路：不追求进程杀不死，而是保证「只要进程被任何理由重新拉起，就立刻把无障碍修回来」。
 * 修复动作 = 把本应用的无障碍组件从 ENABLED_ACCESSIBILITY_SERVICES 摘掉，等 1 秒，
 * 再加回去，强制系统重新绑定。前提是持有 WRITE_SECURE_SETTINGS（Shizuku / ADB 授权）。
 *
 * 注意：这里刻意不加「启动宽限期」「重启限频」「摘除中断标记」「AccessibilityManager 交叉校验」
 * 等 GKD 没有的保护——加了就不是 GKD 的行为了。防抖只在调用点做（磁贴 3 秒、autoStart 1 秒、
 * 主界面首次 resume 跳过），与 GKD 一致。
 */

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.PowerManager
import android.provider.Settings
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.ziek.autoslide.priv.PrivilegeGrant
import com.ziek.autoslide.service.AutoSlideService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object A11yState {

    private const val TAG = "A11yState"

    /* 系统存储无障碍服务列表时使用的分隔符（GKD: ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR） */
    private const val SEPARATOR = ':'

    /* 摘除组件后必须等待一段时间，否则系统概率不会触发重新绑定（GKD: A11Y_AWAIT_FIX_TIME） */
    private const val A11Y_AWAIT_FIX_TIME = 1000L

    /* 加回组件后等待系统完成绑定的时间（GKD: A11Y_AWAIT_START_TIME） */
    private const val A11Y_AWAIT_START_TIME = 2000L

    /* 看门狗巡检间隔；连续探活失败多少次才判定「崩溃/僵尸态」（防亮屏瞬间/安全界面的误判） */
    private const val WATCHDOG_INTERVAL_MS = 20_000L
    private const val UNHEALTHY_CONFIRM = 2

    private var appContext: Context? = null

    /* 对应 GKD 的 appScope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /* 对应 GKD 的 modifyA11yMutex */
    private val modifyA11yMutex = Mutex()

    /* 对应 GKD 的 syncStateMutex */
    private val syncStateMutex = Mutex()

    /* 崩溃看门狗：巡检协程 + 连续探活失败计数 */
    private var watchdogJob: Job? = null
    private var unhealthyCount = 0

    /**
     * 无障碍服务是否在运行。
     *
     * 对应 GKD 的 `A11yService.isRunning`：在 onCreate 置 true、onDestroy 置 false
     * （不是 onServiceConnected，onCreate 更早也更准）。
     */
    val a11yRunningFlow = MutableStateFlow(false)

    /* 本应用的无障碍组件当前是否在系统的启用列表里（仅用于通知文案，不触发自愈） */
    val a11yEnabledFlow = MutableStateFlow(false)

    /* 本应用无障碍服务的组件名（GKD: A11yService.a11yCn） */
    private lateinit var a11yComponent: ComponentName

    /**
     * 在 Application.onCreate 中调用。
     *
     * 对应 GKD 在 A11yExt.kt 里注册的 ContentObserver：只同步 [a11yEnabledFlow] 供界面/通知展示，
     * **不在回调里触发自愈**——GKD 的自愈只由 syncFixState / 磁贴绑定这些明确入口驱动。
     *
     * @param context 应用上下文
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        a11yComponent = ComponentName(app, SelectToSpeakService::class.java)
        a11yEnabledFlow.value = getSecureA11yServices().contains(a11yComponent)
        val observer = contentObserver {
            a11yEnabledFlow.value = getSecureA11yServices().contains(a11yComponent)
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            servicesObserver = observer
        }.onFailure { LogX.w(TAG, "register a11y observer failed", it) }
        startWatchdog()
    }

    /* 持有强引用，避免被回收后系统静默停止回调 */
    private var servicesObserver: ContentObserver? = null

    /* GKD: App.kt 的 contentObserver helper */
    private fun contentObserver(listener: () -> Unit) = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = listener()
    }

    // ==================== 用户意图（GKD: storeFlow.enableAutomator）====================

    /**
     * 用户是否希望无障碍保持开启。
     *
     * GKD 的关键设计：这个值**由无障碍服务自身的生命周期维护**，而不是界面开关——
     * `A11yService.onCreate` 置 true、`onDestroy` 置 false。于是：
     * - 进程被系统杀掉时 onDestroy 不会执行，标记留在 true → 下次进程起来会自愈；
     * - 用户在系统设置里手动关闭无障碍时 onDestroy 会执行，标记变 false → 不会被强行拉回来，关得掉。
     */
    fun isServiceDesired(): Boolean {
        val app = appContext ?: return false
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_DESIRED, false)
    }

    /**
     * 更新用户意图（GKD: updateEnableAutomator）
     *
     * @param context 上下文
     * @param desired 是否希望无障碍保持开启
     */
    fun setServiceDesired(context: Context, desired: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SERVICE_DESIRED, false) == desired) return
        prefs.edit().putBoolean(KEY_SERVICE_DESIRED, desired).apply()
        LogX.i(TAG, "service desired = $desired")
    }

    // ==================== 自愈入口 ====================

    /**
     * 统一自愈入口（GKD: syncFixState）。
     * 进程启动、回到主界面、外部调用时调用；用 Mutex 串行，避免多个入口同时触发。
     *
     * 顺序与 GKD 一致，关键是先补授权再修无障碍：
     * `WRITE_SECURE_SETTINGS` 会随覆盖安装/撤权丢失，丢了之后 [fixA11yService] 第一道检查就返回，
     * 整条保活链路会从起点断掉。
     */
    fun syncFixState() {
        val app = appContext ?: return
        scope.launch {
            if (syncStateMutex.isLocked) {
                LogX.d(TAG, "syncFixState isLocked")
            }
            syncStateMutex.withLock {
                // GKD: privilegeContextFlow.value?.grantSelf()
                PrivilegeGrant.ensureServerAndGrantSelf(app)
                fixRestartA11yService()
            }
        }
    }

    /**
     * 自愈无障碍（GKD: fixRestartAutomatorService）。
     * 只有用户意图为「要开」时才动手。
     */
    fun fixRestartA11yService() = modifyA11yRun {
        if (isServiceDesired()) {
            fixA11yService()
        }
    }

    /**
     * 串行执行修改无障碍的动作（GKD: modifyA11yRun）。
     * 已有修复在跑时**直接放弃**而不是排队——避免多入口触发后堆积成连环重启。
     */
    private fun modifyA11yRun(block: suspend () -> Unit) {
        if (modifyA11yMutex.isLocked) return
        scope.launch {
            if (modifyA11yMutex.isLocked) return@launch
            modifyA11yMutex.withLock { block() }
        }
    }

    /**
     * 真正的修复动作（GKD: fixA11yService）。
     *
     * 组件已在启用列表里说明无障碍出故障了，必须先摘除再加回才能让系统重新绑定；
     * 组件不在列表里（被 ROM 移出）则直接加回即可。
     */
    private suspend fun fixA11yService() {
        val app = appContext ?: return
        if (a11yRunningFlow.value) return
        if (!app.hasWriteSecureSettingsPermission()) {
            LogX.w(TAG, "no WRITE_SECURE_SETTINGS, cannot auto fix a11y service")
            return
        }
        val names = getSecureA11yServices()
        val a11yBroken = names.contains(a11yComponent)
        if (a11yBroken) {
            // 无障碍出现故障，重启服务
            names.remove(a11yComponent)
            putSecureA11yServices(names)
            // 必须等待一段时间，否则概率不会触发系统重启无障碍
            delay(A11Y_AWAIT_FIX_TIME)
        }
        names.add(a11yComponent)
        putSecureA11yServices(names)
        delay(A11Y_AWAIT_START_TIME)
        if (!a11yRunningFlow.value) {
            LogX.w(TAG, "restart a11y service failed")
        } else {
            LogX.i(TAG, "a11y service restarted, broken=$a11yBroken")
        }
    }

    // ==================== 崩溃看门狗（补 GKD 没有的「僵尸态」自愈）====================

    /**
     * 亮屏周期看门狗：修复「无障碍已崩溃但 [a11yRunningFlow] 仍为 true」的僵尸态。
     *
     * 背景：进程崩过一次后，系统会把无障碍服务记进 crashed 列表，服务实例还会被重启（onCreate 又跑、
     * a11yRunningFlow 置 true），但它 rootInActiveWindow 恒 null、收不到事件、下发不了手势——静默空转
     * （见 FloatingWindowService.safeUpdateViewLayout 注释）。此时 [fixA11yService] 的
     * `if (a11yRunningFlow.value) return` 会误以为一切正常而跳过，常规自愈救不回来。
     *
     * 这里用 [AutoSlideService.looksHealthy] 做真实探活，连续失败即绕过运行标记强制重开。
     * 在 [init]（Application.onCreate）里启动；幂等。
     */
    fun startWatchdog() {
        val app = appContext ?: return
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                runCatching { watchdogTick(app) }
                    .onFailure { LogX.w(TAG, "watchdog tick failed", it) }
            }
        }
    }

    private suspend fun watchdogTick(app: Context) {
        // 用户没要开、或没有改写权限 → 不管（也重置计数）
        if (!isServiceDesired() || !app.hasWriteSecureSettingsPermission()) {
            unhealthyCount = 0
            return
        }
        // 只在亮屏时判定：熄屏时 rootInActiveWindow 本就可能为 null、也没有事件，避免误判误重开
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive != true) {
            unhealthyCount = 0
            return
        }
        // 被 ROM 移出启用列表 → 立即加回（这条 fixA11yService 也能处理，但它会被运行标记挡住，故直接强制重绑）
        if (!getSecureA11yServices().contains(a11yComponent)) {
            unhealthyCount = 0
            forceRebindA11yService()
            return
        }
        // 在启用列表里：探活。能拿到窗口根 / 近期有事件 = 正常
        if (AutoSlideService.looksHealthy()) {
            unhealthyCount = 0
            return
        }
        unhealthyCount++
        LogX.w(TAG, "无障碍探活失败 $unhealthyCount/$UNHEALTHY_CONFIRM（亮屏、仍在启用列表 → 疑似僵尸态）")
        if (unhealthyCount >= UNHEALTHY_CONFIRM) {
            unhealthyCount = 0
            forceRebindA11yService()
        }
    }

    /**
     * 强制重开无障碍：**不看 [a11yRunningFlow]**（僵尸态下它恒为 true），直接摘除+加回强制系统重绑。
     * 专治「已崩溃但运行标记仍为 true」——常规 [fixA11yService] 对这种态会直接 return。
     */
    private fun forceRebindA11yService() = modifyA11yRun {
        val app = appContext ?: return@modifyA11yRun
        if (!app.hasWriteSecureSettingsPermission()) return@modifyA11yRun
        LogX.w(TAG, "无障碍疑似崩溃（僵尸态），强制重开")
        val names = getSecureA11yServices()
        names.remove(a11yComponent)
        putSecureA11yServices(names)
        delay(A11Y_AWAIT_FIX_TIME)
        names.add(a11yComponent)
        putSecureA11yServices(names)
        delay(A11Y_AWAIT_START_TIME)
        LogX.i(TAG, "无障碍强制重开完成，healthy=${AutoSlideService.looksHealthy()}")
    }

    // ==================== 系统设置读写 ====================

    /* 读取系统当前启用的全部无障碍服务组件（GKD: App.getSecureA11yServices） */
    private fun getSecureA11yServices(): MutableSet<ComponentName> {
        val app = appContext ?: return mutableSetOf()
        val value = runCatching {
            Settings.Secure.getString(
                app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        if (value.isNullOrEmpty()) return mutableSetOf()
        return value.split(SEPARATOR)
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .toMutableSet()
    }

    /**
     * 写回系统的无障碍服务启用列表（GKD: App.putSecureA11yServices，用 flattenToShortString）。
     *
     * 必须连带写 ACCESSIBILITY_ENABLED 总开关：组件在启用列表里但总开关为 0 时，
     * AMS 根本不会 bind，摘除再加回的自愈动作会永远空转（实测 MIUI 13 上服务被杀后
     * 总开关会被置 0，此后自愈每轮都停在 "restart a11y service failed"）。
     * 顺序也不能反 —— 先写列表再开总开关，否则 AMS 在总开关变化时发现无服务可绑，会把它重新置 0。
     */
    private fun putSecureA11yServices(services: Set<ComponentName>) {
        val app = appContext ?: return
        runCatching {
            Settings.Secure.putString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(SEPARATOR.toString()) { it.flattenToShortString() }
            )
            Settings.Secure.putInt(
                app.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                if (services.isEmpty()) 0 else 1
            )
        }.onFailure { LogX.w(TAG, "put a11y services failed", it) }
    }

}
