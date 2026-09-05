package com.ziek.autoslide

/**
 * 本机「外部有可能连上」的地址——全应用唯一的一份采集逻辑。
 *
 * 以前这件事有两套实现：[DeviceInfo] 采一份随统计上报，`ec.EcRegistry` 采一份报给
 * Easycontrol，规则还不一样（一个留 ULA、留蜂窝私有 v4、只看默认网络，另一个全排除、
 * 看所有能上网的网络）。同一台手机在看板上因此出现两组对不上的地址，谁也说不清
 * 哪组是对的。现在统一成这一份，只回答一个问题：**别人能从哪连上这台设备**。
 *
 * 排除规矩是踩出来的，一台手机能有好几个全局 IPv6，选错就是今天能连、明天连不上：
 *  - 回环、链路本地（fe80:: / 169.254.）：跨设备根本连不上
 *  - ULA（fc00::/7）：只在本站点可达，跨网络没用
 *  - 蜂窝上的私有 IPv4：运营商 CGNAT 后面的地址，外面连不进来
 *  - VPN 网卡：地址只在隧道里有意义
 *  - 没有 INTERNET 能力的网络：IMS、彩信这类专用 APN 也有全局 IPv6，但那上面没有
 *    adbd。实测一台手机能多出两个这样的地址，控制端每次连接都要为它们白试一轮
 *
 * 临时地址（privacy extensions）会轮换，但 Java 认不出这个标记，硬猜不如都报上去——
 * 控制端是并发尝试的，坏的那条只白试一次。
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

object LocalAddresses {

    private const val TAG = "LocalAddresses"

    /**
     * 采集一次。
     *
     * 走 [ConnectivityManager] 而不是直接遍历网卡，是因为遍历网卡分不出两件事：
     * 这条网络是不是用来上网的、它的承载类型是什么。两者系统都直接告诉你，不用猜。
     *
     * @param context 上下文
     * @return 有序列表，默认网络的地址排在最前（控制端按这个顺序并发尝试）；
     *         IPv6 一律写成 `[xxx]`，这是本项目的统一写法，拼「地址:端口」时必须有
     */
    fun collect(context: Context): List<String> {
        val result = LinkedHashSet<String>()

        val fromManager = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching false
            @Suppress("DEPRECATION") // 没有替代品：要枚举「全部」网络就只有它
            val networks = manager.allNetworks
            val active = manager.activeNetwork
            var any = false
            // 默认网络排在前面：它最可能是通的，让控制端先试它
            for (network in networks.sortedByDescending { it == active }) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val link = manager.getLinkProperties(network) ?: continue
                any = true
                for (linkAddress in link.linkAddresses) add(linkAddress.address, cellular, result)
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
                    for (address in nif.inetAddresses) add(address, cellular, result)
                }
            }.onFailure { LogX.w(TAG, "遍历网卡失败", it) }
        }

        return result.toList()
    }

    /** 把一个地址归类后放进结果集，不合规的直接丢掉 */
    private fun add(address: InetAddress, cellular: Boolean, out: MutableSet<String>) {
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
     * Java 的 [NetworkInterface] 不提供承载类型，这条路上没有别的判据。
     */
    private fun isCellularName(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.startsWith("rmnet") || lower.startsWith("ccmni") ||
            lower.startsWith("pdp") || lower.startsWith("ppp") || lower.startsWith("clat")
    }
}
