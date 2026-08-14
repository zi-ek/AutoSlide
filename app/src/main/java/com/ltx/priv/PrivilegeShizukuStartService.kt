package com.ltx.priv

import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import androidx.annotation.Keep
import priv.kit.core.PrivilegeExternalStartupHost
import kotlin.system.exitProcess

/**
 * Shizuku 远程进程（UserService）中的 priv-kit 外部启动桥接端点。
 *
 * Shizuku 以 shell（或 root）身份实例化本服务，本服务把 priv-kit 的原生
 * 启动器命令交给 [PrivilegeExternalStartupHost] 执行，从而拉起 app_process
 * 特权服务端并完成 Binder 握手。
 */
@Keep
class PrivilegeShizukuStartService @Keep constructor() :
    IPrivilegeShizukuStartService.Stub() {

    private val host = PrivilegeExternalStartupHost()

    override fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        host.start(
            commandLine = commandLine,
            stdout = stdout,
            stderr = stderr,
            resultReceiver = resultReceiver,
        )
    }

    override fun destroy() {
        host.close()
        exitProcess(0)
    }
}
