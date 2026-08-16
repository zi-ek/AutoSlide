package com.ziek.autoslide

/**
 * 出口 IP 探测。
 *
 * 服务端只能看到设备连接它时用的那个地址——设备走 IPv6 时，服务端就永远拿不到
 * 设备的 IPv4 出口。这里由设备自己访问一次公开的「查我的 IP」页面，
 * 把结果随统计上报一起交给服务端，作为服务端记录之外的补充维度。
 *
 * 实测两种地址各有各的偏差：家庭宽带下 IPv6 更准（能到区县），
 * 移动网络下 IPv6 会落到骨干网注册地（例如人在山西却解析成北京），
 * 反而是 IPv4 出口准确。所以两者都留，不用其一覆盖另一个。
 *
 * 失败一律静默返回 null，绝不影响统计上报本身。
 */

import java.net.HttpURLConnection
import java.net.URL

/**
 * 探测到的出口信息
 *
 * @property ip 出口 IP
 * @property location 归属地文案，取不到时为空串
 */
data class EgressInfo(val ip: String, val location: String)

object IpReporter {

    private const val TAG = "IpReporter"

    /* 「查我的 IP」页面：返回的是访问者自身的出口地址，因此必须由设备发起 */
    private const val PROBE_URL = "https://2026.ip138.com/"

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    /* 页面正文形如：[<a ...>1.2.3.4</a> ...] 来自：中国 山西 太原 */
    private val IPV4_REGEX = Regex("""(\d{1,3}(?:\.\d{1,3}){3})""")
    private val LOCATION_REGEX = Regex("""来自[：:]\s*([^<\r\n]{1,60})""")

    /**
     * 同步探测出口 IP 与归属地，必须在后台线程调用。
     *
     * @return 探测结果；网络失败、解析失败或页面改版时返回 null
     */
    fun probe(): EgressInfo? = runCatching {
        val conn = (URL(PROBE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // 该站点对默认 UA 会返回精简页面，带上常见 UA 才有完整正文
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
        }
        val bytes = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        // 该页面历史上用过 GBK，也出现过 UTF-8，两种都试
        val text = decode(bytes)
        val ip = IPV4_REGEX.find(text)?.groupValues?.get(1).orEmpty()
        if (ip.isEmpty()) return null
        val location = LOCATION_REGEX.find(text)?.groupValues?.get(1)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        LogX.i(TAG, "egress probed: $ip / ${location.ifEmpty { "-" }}")
        EgressInfo(ip, location)
    }.onFailure {
        LogX.w(TAG, "probe egress failed", it)
    }.getOrNull()

    /* 按 UTF-8 → GBK 顺序尝试解码，都失败则用 UTF-8 宽松模式 */
    private fun decode(bytes: ByteArray): String {
        for (charset in listOf(Charsets.UTF_8, charset("GBK"))) {
            runCatching {
                val decoded = charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                return decoded
            }
        }
        return String(bytes, Charsets.UTF_8)
    }
}
