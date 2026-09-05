package com.ziek.autoslide.ec

/**
 * 把本机当前的地址报到 Easycontrol 的登记服务器。
 *
 * 要解决的问题不属于本应用：Easycontrol 远程控制的两端都是手机，IP 一换网络就变。
 * 同一局域网内还能靠 mDNS 找（adbd 自己在广播），一旦被控端跑到蜂窝上，多播传不过去，
 * 控制端就彻底找不到对方了——两边都在漂，没有汇合点。服务器上那张
 * 「序列号 → 当前地址列表」的表就是汇合点，被控端报上去，控制端查回来。
 *
 * 那为什么由本应用来报？Easycontrol 的助手（com.ziek.adbboot）也在报，
 * 但它靠 JobScheduler，而**周期任务的下限是 15 分钟**，意味着网络刚切换的那一刻，
 * 服务器上的地址最多可能过期 15 分钟——正好是用户最需要连上的时候。
 * 本应用是常驻的（前台服务 + 无障碍服务把进程留住），可以直接挂
 * [android.net.ConnectivityManager.NetworkCallback]，网络一变几秒内就报出去。
 * 两者是互补关系，不冲突：服务器按序列号覆盖同一条记录，谁报得晚谁生效。
 *
 * **只有被 Easycontrol 部署过（拿到了序列号）的设备才会启用这条上报**，
 * 见 [isEnabled]。没有序列号时本模块完全不联网。
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.ziek.autoslide.LogX
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

object EcRegistry {

    const val TAG = "EcRegistry"

    /**
     * 登记服务器。
     *
     * 注意：它和本应用自己的后端（`SERVER_BASE_URL`）目前是同一台机器，
     * 但**不要合并成一个常量**——那是 Easycontrol 的服务，两者随时可能分开部署，
     * 到那时共用一个常量会静默地把上报打到错误的地方。
     *
     * 改这里必须同步改另外两处，否则上报和查询会对不上：
     * - Easycontrol 控制端 `RegistryTools.BASE_URL`
     * - Easycontrol 助手 `com.ziek.adbboot.Registry.BASE_URL`
     */
    private const val BASE_URL = "https://pve.8450696.shop"

    /**
     * 共享口令，上报和查询用同一个，服务器上由 EC_REGISTRY_TOKEN 配置。
     *
     * 它编译进 APK，反编译就能拿到，等于半公开——这是 Easycontrol 那边定下的取舍：
     * 服务器只存地址，这张表的价值仅限于「知道某台设备此刻在哪」，自用场景可以接受。
     * 同样要和上面列的另外两处保持一致。
     */
    private const val TOKEN = "z2RFXWsLtwLaZH4IphLasxYJ9Skn1KGbNxkgWFf8"

    /*
     * 超时给得比一般接口宽。实测这台小米在同一条 Wi-Fi 上 POST 一次要 1~5 秒不等
     * （Cloudflare 边缘握手 + 回源），偶尔更久；给 8 秒的话正常网络下也会零星超时，
     * 白白丢掉一次上报。这是后台的静默上报，慢一点没人等，不值得为了快而漏报。
     */
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 20000

    /** adbd 的默认端口。助手把设备切到 tcpip 后就是它 */
    const val DEFAULT_PORT = 5555

    private const val PREFS = "ec_registry"
    private const val KEY_SERIAL = "serial"
    private const val KEY_PORT = "port"

    /** 和服务器 cleanId() 一致的字符集，先在本地挡一道，免得把垃圾报上去 */
    private val SERIAL_REGEX = Regex("[A-Za-z0-9._:-]{1,64}")

    /**
     * 记下 Easycontrol 交过来的序列号与端口。
     *
     * 安卓 10 起 ro.serialno 对普通应用不可读，本应用自己拿不到；
     * Easycontrol 部署助手时有 shell 权限，读出来后借 [EcSerialActivity] 传进来。
     *
     * @param context 上下文
     * @param serial 设备序列号，不合法时整条丢弃
     * @param port adbd 端口，传 0 或越界值表示沿用默认值
     * @return 是否保存成功
     */
    fun saveSerial(context: Context, serial: String, port: Int): Boolean {
        val clean = serial.trim()
        if (!SERIAL_REGEX.matches(clean)) {
            LogX.w(TAG, "序列号不合法，已忽略")
            return false
        }
        val realPort = if (port in 1..65535) port else DEFAULT_PORT
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERIAL, clean)
            .putInt(KEY_PORT, realPort)
            .apply()
        LogX.i(TAG, "已记下序列号，adbd 端口 $realPort")
        return true
    }

    /**
     * 设备标识；没有被 Easycontrol 部署过时为空串
     *
     * @param context 上下文
     */
    fun serial(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERIAL, "").orEmpty()

    private fun port(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)

    /**
     * 本功能是否启用。
     *
     * 判据就是「有没有序列号」：没有序列号既报不上去（服务器认不出这是谁），
     * 也说明这台设备根本没被 Easycontrol 当作被控端，不该产生任何联网行为。
     *
     * @param context 上下文
     */
    fun isEnabled(context: Context): Boolean = serial(context).isNotEmpty()

    /**
     * 上报一次。
     *
     * @param context 上下文
     * @param addresses 由 [globalAddresses] 采到的地址列表，调用方负责判空
     * @param reason 触发原因，只进日志，方便排查是哪条路径报的
     * @return 是否上报成功；失败只是这次没报上，重试与否由调用方决定
     */
    fun report(context: Context, addresses: List<String>, reason: String): Boolean {
        val serial = serial(context)
        if (serial.isEmpty() || addresses.isEmpty()) return false

        val body = JSONObject().apply {
            put("id", serial)
            put("addresses", JSONArray(addresses))
            put("port", port(context))
            put("name", Build.BRAND + " " + Build.MODEL)
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(BASE_URL + "/api/ec/report").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Ec-Token", TOKEN)
                // 不复用连接。实测每隔十几分钟才发一次的请求，池子里那条早被对端关了，
                // HttpURLConnection 却照用不误，于是抛 "unexpected end of stream"——
                // 一次白丢的上报。这种频率下 keep-alive 本来也省不到什么。
                setRequestProperty("Connection", "close")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            LogX.i(TAG, "[$reason] 上报 ${addresses.size} 个地址 $addresses，服务器返回 $code")
            code == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            // 没网、服务器没起来、口令不对，都只是这次没报上
            LogX.w(TAG, "[$reason] 上报失败：$e")
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 本机所有「外部有可能连上」的地址。
     *
     * 挑地址的规矩和 Easycontrol 那两端一致，因为踩过同样的坑：
     * 一台手机能有好几个全局 IPv6，选错就是今天能连、明天连不上。
     *  - 回环、链路本地（fe80:: / 169.254.）：跨设备根本连不上
     *  - ULA（fc00::/7）：只在本站点可达，跨网络没用
     *  - 蜂窝上的私有 IPv4：那是运营商 CGNAT 后面的地址，外面连不进来
     *  - VPN 网卡的地址：只在隧道里有意义
     * 临时地址（privacy extensions）会轮换，但 Java 认不出这个标记，
     * 硬猜不如全都报上去——控制端是并发尝试的，坏的那条只白试一次。
     *
     * 相比 Easycontrol 助手里按网卡名猜承载类型（rmnet/ccmni/…）的老办法，
     * 这里走 [ConnectivityManager]，承载类型是系统直接给的，判断确定；
     * 而且能按 NET_CAPABILITY_INTERNET 把 IMS 之类的专用网络挡在外面——
     * 那些网卡也有全局 IPv6，报上去纯属占位置（服务器每台设备只留 6 个地址）。
     *
     * @param context 上下文
     * @return IPv6 一律写成 `[xxx]`，这是 Easycontrol 里的统一写法，拼「地址:端口」时必须有
     */
    fun globalAddresses(context: Context): List<String> {
        val result = LinkedHashSet<String>()

        val fromManager = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching false
            @Suppress("DEPRECATION") // 没有替代品：要枚举「全部」网络就只有它
            val networks = manager.allNetworks
            val active = manager.activeNetwork
            // 默认网络排在前面。服务器每台设备只留前 6 个地址，排前面的更可能被留下
            var any = false
            for (network in networks.sortedByDescending { it == active }) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                // 只要能上网的那几条：IMS、彩信等专用网络也有全局地址，但连不到 adbd
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val link = manager.getLinkProperties(network) ?: continue
                any = true
                for (linkAddress in link.linkAddresses) collect(linkAddress.address, cellular, result)
            }
            any
        }.getOrElse {
            LogX.w(TAG, "按网络枚举地址失败", it)
            false
        }

        if (!fromManager) {
            // 拿不到网络列表时（缺权限、系统异常）退回遍历网卡。
            // 这条路分不清承载类型，只能按网卡名猜，所以只作兜底。
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val cellular = isCellularName(nif.name)
                    for (address in nif.inetAddresses) collect(address, cellular, result)
                }
            }.onFailure { LogX.w(TAG, "遍历网卡失败", it) }
        }

        return result.toList()
    }

    /** 把一个地址归类后放进结果集，不合规的直接丢掉 */
    private fun collect(address: InetAddress, cellular: Boolean, out: MutableSet<String>) {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return
        // 私有 IPv4 分两种：Wi-Fi 上的多半和控制端同一个局域网，是最快最稳的一条，必须报；
        // 蜂窝上的是 CGNAT 后面的地址，外面根本连不进来。两者地址段一样，只能靠承载类型分。
        if (address.isSiteLocalAddress && cellular) return
        if (address is Inet6Address) {
            if (isUniqueLocal(address)) return
            val host = address.hostAddress?.substringBefore('%') ?: return
            out.add("[$host]")
        } else {
            out.add(address.hostAddress ?: return)
        }
    }

    /**
     * fc00::/7：ULA，站点内私有地址，首字节 0xfc 或 0xfd。
     *
     * 得自己按前缀认：isSiteLocalAddress() 对 IPv6 的 ULA 返回 **false**，
     * 它判的是 IPv4 的私有段和早已废弃的 fec0::/10。
     */
    private fun isUniqueLocal(address: Inet6Address): Boolean {
        val first = address.address.firstOrNull()?.toInt()?.and(0xfe) ?: return false
        return first == 0xfc
    }

    /**
     * 兜底路径下按网卡名猜是不是蜂窝：高通叫 rmnet_dataN，联发科叫 ccmniN，
     * 老设备还有 pdp/ppp，clat 是 464XLAT 的翻译网卡。
     * Java 的 [NetworkInterface] 不提供承载类型，没有别的判据。
     */
    private fun isCellularName(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.startsWith("rmnet") || lower.startsWith("ccmni") ||
            lower.startsWith("pdp") || lower.startsWith("ppp") || lower.startsWith("clat")
    }
}
