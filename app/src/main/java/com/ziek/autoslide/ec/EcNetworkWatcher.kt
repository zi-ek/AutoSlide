package com.ziek.autoslide.ec

/**
 * 盯着网络变化，一变就把新地址报给 Easycontrol 的登记服务器。
 *
 * 这是本模块存在的全部理由。Easycontrol 的助手（com.ziek.adbboot）也在报，
 * 但它用 JobScheduler，**周期任务的下限被系统卡死在 15 分钟**，
 * 于是「切了网络马上想连」这个最常见的场景恰好落在它的盲区里：
 * 服务器上还是旧地址，控制端照着旧地址连，一路超时。
 * 本应用是常驻的，直接挂系统的网络回调，从网络稳定到报完通常只要几秒。
 *
 * 挂在哪：[com.ziek.autoslide.App.onCreate]。进程无论因何原因被拉起都会走到那里，
 * 而网络回调是进程级的，进程活着它就活着——这正好和本应用的保活链路重合，
 * 不需要再单开一个服务。**代价也要说清楚：进程被杀就不再上报**，
 * 此时只剩助手那条 15 分钟的定时兜底。想让它真正常驻，得开着常驻状态通知。
 *
 * 几个刻意的取舍：
 * - **不用 AlarmManager、不持 WakeLock**：为了报个 IP 把休眠中的手机叫醒不值当。
 *   Doze 期间心跳会被推迟，设备一醒来就补上。
 * - **防抖 4 秒**：切网络时回调会连着来好几次（旧网卡掉、新网卡上、地址再补齐），
 *   立刻就报会把中间态报上去，还多打几次请求。
 * - **地址没变就不报**：除非离上次成功超过一个心跳周期，让服务器那条记录的时间戳保持新鲜。
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.ziek.autoslide.LocalAddresses
import com.ziek.autoslide.LogX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

object EcNetworkWatcher {

    private const val TAG = "EcNetworkWatcher"

    /* 收到回调后再等这么久，等网络彻底稳定；期间的重复事件会被合并成一次 */
    private const val DEBOUNCE_MS = 4_000L

    /* 上报失败后的重试间隔与次数。刚切完网络时常常还没真正通，得给它一点时间 */
    private const val RETRY_DELAY_MS = 30_000L
    private const val MAX_ATTEMPTS = 3

    /* 心跳：地址没变也隔这么久重报一次，让服务器上的记录时间保持新鲜 */
    private const val HEARTBEAT_MS = 30 * 60 * 1000L

    private val started = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 触发通道。CONFLATED 意味着只保留最后一个事件——
     * 切网络时几十毫秒内能来七八个回调，这里天然把它们并成一次，
     * 不需要自己写计时器去合并。
     */
    private val trigger = Channel<String>(Channel.CONFLATED)

    @Volatile
    private var lastReported: List<String> = emptyList()

    @Volatile
    private var lastSuccessAt = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = fire("网络接入")

        override fun onLost(network: Network) = fire("网络断开")

        /* 地址变了但网络没断：换 Wi-Fi、IPv6 前缀更新、临时地址轮换都走这条 */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            fire("地址变化")
    }

    /**
     * 开始监听。
     *
     * 可重入：已经在跑就直接返回。没有序列号时**什么都不做也不留痕迹**，
     * 这样等 [EcSerialActivity] 收到序列号后再调一次就能正常启动。
     *
     * @param context 任意上下文，内部只持有 applicationContext
     */
    fun start(context: Context) {
        val appContext = context.applicationContext
        // 先判有没有序列号，再抢启动标记：没被 Easycontrol 部署过的设备
        // 不该为这个功能注册任何回调、产生任何联网行为
        if (!EcRegistry.isEnabled(appContext)) return
        if (!started.compareAndSet(false, true)) return

        registerCallback(appContext)
        scope.launch { consume(appContext) }
        scope.launch { heartbeat() }
        // 进程可能是在一次网络切换之后才被拉起来的，那段时间没人在听，先补报一次
        fire("进程启动")
        LogX.i(TAG, "已开始监听网络变化，设备标识 ${EcRegistry.serial(appContext)}")
    }

    /**
     * 立刻报一次，并跳过「地址没变就不报」的去重。
     *
     * 给刚拿到序列号的场景用：那一刻服务器上还没有这台设备，
     * 等下一次网络变化可能要等很久，用户会以为部署没生效。
     *
     * @param reason 触发原因，只进日志
     */
    fun reportNow(reason: String) {
        lastReported = emptyList()
        fire(reason)
    }

    private fun fire(reason: String) {
        trigger.trySend(reason)
    }

    private fun registerCallback(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            LogX.w(TAG, "拿不到 ConnectivityManager，网络变化监听未启用")
            return
        }
        // 只监听、不请求：registerNetworkCallback 不会把任何网络拉起来，也不影响系统的选路。
        // 不限定 transport，Wi-Fi 和蜂窝的变化都要，因为两边的地址都会报上去。
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure {
                // 缺 ACCESS_NETWORK_STATE 会走到这里。此时只剩心跳那条路，
                // 频率退化到 30 分钟，和助手的定时上报差不多
                LogX.w(TAG, "注册网络回调失败，只剩心跳兜底", it)
            }
    }

    /* 消费触发事件：一次只处理一个，处理期间来的事件会被合并到下一轮 */
    private suspend fun consume(context: Context) {
        for (reason in trigger) {
            delay(DEBOUNCE_MS)
            runCatching { reportWithRetry(context, reason) }
                .onFailure { LogX.w(TAG, "上报流程异常", it) }
        }
    }

    private suspend fun reportWithRetry(context: Context, first: String) {
        var reason = first
        for (attempt in 1..MAX_ATTEMPTS) {
            // 每次重试都重新采一遍：这几十秒里网络很可能又变了，报旧的没有意义
            val addresses = LocalAddresses.collect(context)
            if (addresses.isEmpty()) {
                // 正处在没有任何可用地址的状态（飞行模式、切换中途）。
                // 重试也是白搭，等下一次回调把我们叫醒
                LogX.i(TAG, "[$reason] 当前没有可用地址，跳过")
                return
            }
            val now = System.currentTimeMillis()
            if (addresses == lastReported && now - lastSuccessAt < HEARTBEAT_MS) {
                LogX.d(TAG, "[$reason] 地址与上次相同且未到心跳时间，跳过")
                return
            }
            if (EcRegistry.report(context, addresses, reason)) {
                lastReported = addresses
                lastSuccessAt = System.currentTimeMillis()
                return
            }
            if (attempt == MAX_ATTEMPTS) break
            // 等着重试，但这 30 秒里网络要是又变了就立刻用新事件重来——
            // 否则一次失败会把后面真正的变化压住一分钟，正是最不该迟到的时候。
            // 重试次数不因此重置：这里省的是等待，不是要无限重试。
            val next = withTimeoutOrNull(RETRY_DELAY_MS) { trigger.receive() }
            if (next != null) {
                reason = next
                delay(DEBOUNCE_MS)
            }
        }
        LogX.w(TAG, "[$reason] 连续 $MAX_ATTEMPTS 次上报失败，等下次网络变化或心跳再试")
    }

    /**
     * 心跳。
     *
     * 不用 AlarmManager，所以 Doze 期间会被推迟——这是有意的：
     * 为了刷新一条地址记录去唤醒睡着的手机，代价远大于收益。
     * 设备一醒来（用户点亮屏幕、收到推送）这条 delay 就会继续走完。
     */
    private suspend fun heartbeat() {
        while (true) {
            delay(HEARTBEAT_MS)
            fire("心跳")
        }
    }
}
