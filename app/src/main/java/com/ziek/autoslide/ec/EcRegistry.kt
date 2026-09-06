package com.ziek.autoslide.ec

/**
 * 把本机当前的可连地址报到服务端。
 *
 * 要解决的问题一半不属于本应用：Easycontrol 远程控制的两端都是手机，IP 一换网络就变。
 * 同一局域网内还能靠 mDNS 找（adbd 自己在广播），一旦被控端跑到蜂窝上，多播传不过去，
 * 控制端就彻底找不到对方了——两边都在漂，没有汇合点。服务端那条设备记录就是汇合点。
 *
 * 另一半属于本应用：同一次上报也是**看板「网络」卡片的数据来源**。两者以前各走各的
 * 接口、各存各的表，同一台手机因此显示两组对不上的地址；现在合并成一条上报、一份存储。
 *
 * 那为什么由本应用来报？Easycontrol 的助手（com.ziek.adbboot）也在报，但它靠
 * JobScheduler，而**周期任务的下限是 15 分钟**，意味着网络刚切换的那一刻，服务端上的
 * 地址最多可能过期 15 分钟——正好是用户最需要连上的时候。本应用是常驻的（前台服务 +
 * 无障碍服务把进程留住），可以直接挂 [android.net.ConnectivityManager.NetworkCallback]，
 * 网络一变几秒内就报出去。两者互补：服务端按序列号认同一条记录，谁报得晚谁生效。
 *
 * **只有被 Easycontrol 部署过（拿到了序列号）的设备才会启用这条上报**，
 * 见 [isEnabled]。没有序列号时本模块完全不联网。
 */

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.ziek.autoslide.LogX
import com.ziek.autoslide.SERVER_BASE_URL
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object EcRegistry {

    const val TAG = "EcRegistry"

    /**
     * 上报口令。
     *
     * 服务端只对**写**这一侧要口令：读（看板首页、Easycontrol 按序列号查地址）完全公开，
     * 因为看板本来就是公开的，读出去最多是「知道某台设备此刻在哪」；写却能把某台设备的
     * 地址改成任意值，Easycontrol 下次就会去连那个地址——那是改写别人的连接目标。
     *
     * 它编译进 APK，反编译就能拿到，等于半公开；但这道门挡的是顺手改写，不是定向攻击。
     * 服务端由 AUTOSLIDE_REPORT_TOKEN 配置，改这里必须同步改服务端和
     * Easycontrol 助手 `com.ziek.adbboot.Registry.TOKEN`。
     */
    // internal：行动轨迹上报(LocationTracker)复用同一个上报口令
    internal const val TOKEN = "z2RFXWsLtwLaZH4IphLasxYJ9Skn1KGbNxkgWFf8"

    /*
     * 超时给得比一般接口宽。实测这台小米在同一条 Wi-Fi 上 POST 一次要 1~5 秒不等
     * （Cloudflare 边缘握手 + 回源），偶尔更久；给 8 秒的话正常网络下也会零星超时，
     * 白白丢掉一次上报。这是后台的静默上报，慢一点没人等，不值得为了快而漏报。
     */
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 20000

    /** 上报接口。和统计的 network 段写同一条设备记录 */
    private const val REPORT_PATH = "/api/report/network"

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
     * @param addresses 由 [com.ziek.autoslide.LocalAddresses] 采到的地址列表，调用方负责判空
     * @param reason 触发原因，只进日志，方便排查是哪条路径报的
     * @return 是否上报成功；失败只是这次没报上，重试与否由调用方决定
     */
    fun report(context: Context, addresses: List<String>, reason: String): Boolean {
        val serial = serial(context)
        if (serial.isEmpty() || addresses.isEmpty()) return false

        val body = JSONObject().apply {
            // 两个标识都带：deviceId 让服务端认到统计库里已有的那条记录，
            // serial 是 Easycontrol 查询用的键。服务端首次见到时会把两者关联起来。
            put("deviceId", deviceId(context))
            put("serial", serial)
            put("addresses", JSONArray(addresses))
            put("port", port(context))
            put("name", Build.BRAND + " " + Build.MODEL)
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(SERVER_BASE_URL + REPORT_PATH).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Report-Token", TOKEN)
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

    /** 与统计上报同一个设备标识：固定签名下 ANDROID_ID 稳定，且跨应用不可关联 */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
}
