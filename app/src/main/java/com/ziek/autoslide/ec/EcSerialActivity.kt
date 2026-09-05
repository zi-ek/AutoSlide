package com.ziek.autoslide.ec

/**
 * 接收 Easycontrol 推来的设备序列号，开启地址上报。
 *
 * 由 Easycontrol 在已经连上本机时调用（它那时有 shell 权限）：
 * ```
 * am start -n com.ziek.autoslide/.ec.EcSerialActivity --es serial <序列号> --ei port <adbd端口>
 * ```
 *
 * 为什么要从外面传进来：登记服务器认设备靠 `ro.serialno`，而**安卓 10 起
 * 这个属性对普通应用不可读**，本应用自己怎么读都是空串。Easycontrol 有 shell 权限，
 * 读得到，借这条已有的部署通道顺手交过来最省事——和它给助手交 ADB 密钥是同一条路。
 *
 * 这个 Activity 是导出的（不导出 shell 的 am start 就调不到），但导出不构成提权：
 * 别的应用最多写进来一个错的设备标识，效果是本机的地址报到了一条无人查询的记录上，
 * 拿不到任何权限，也读不走任何东西。
 */

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.ziek.autoslide.LogX
import com.ziek.autoslide.R

class EcSerialActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serial = intent?.getStringExtra(EXTRA_SERIAL).orEmpty()
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0

        if (EcRegistry.saveSerial(this, serial, port)) {
            // 部署完立刻报一次。不然要等到下一次网络变化或半小时后的心跳，
            // 用户刚部署完却在服务器上查不到这台设备，会以为整件事没生效
            EcNetworkWatcher.start(applicationContext)
            EcNetworkWatcher.reportNow("收到序列号")
            Toast.makeText(this, R.string.ec_serial_saved, Toast.LENGTH_LONG).show()
        } else {
            LogX.w(EcRegistry.TAG, "收到的序列号无效，未启用地址上报")
            Toast.makeText(this, R.string.ec_serial_invalid, Toast.LENGTH_LONG).show()
        }

        finish()
    }

    companion object {
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_PORT = "port"
    }
}
