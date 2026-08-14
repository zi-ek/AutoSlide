package com.ziek.autoslide

/**
 * 录制脚本同步
 *
 * 把本机的 slide_settings.xml（含全部录制记录）上传到自己的统计服务器，
 * 服务器按设备 ID 分目录保存，方便备份和查看每台设备的脚本。
 */

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

object MacroSync {

    private const val TAG = "MacroSync"
    private const val FILENAME = "slide_settings.xml"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null

    /**
     * 延迟上传（防抖）：录制记录连续变化时只上传一次
     *
     * @param context 上下文
     * @param delayMs 延迟毫秒数
     */
    fun schedule(context: Context, delayMs: Long = 2000) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            doUpload(context)
        }
    }

    /**
     * 立即上传本机 slide_settings.xml
     *
     * @param context 上下文
     */
    fun uploadNow(context: Context) {
        scope.launch { doUpload(context) }
    }

    private suspend fun doUpload(context: Context) {
        try {
            val file = File(context.applicationInfo.dataDir, "shared_prefs/$FILENAME")
            if (!file.exists()) {
                Log.d(TAG, "prefs file not found, skip upload")
                return
            }
            val bytes = file.readBytes()
            if (bytes.isEmpty()) {
                return
            }
            val url = java.net.URL(SERVER_BASE_URL + "/api/upload")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/xml; charset=UTF-8")
            connection.setRequestProperty(
                "X-Device-Id",
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            )
            connection.setRequestProperty("X-Device-Name", "${Build.MANUFACTURER} ${Build.MODEL}")
            connection.setRequestProperty("X-Filename", FILENAME)
            connection.outputStream.use { it.write(bytes) }

            val responseCode = connection.responseCode
            Log.d(TAG, "upload slide_settings.xml response: $responseCode, size=${bytes.size}")
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "upload slide_settings.xml failed", e)
        }
    }
}
