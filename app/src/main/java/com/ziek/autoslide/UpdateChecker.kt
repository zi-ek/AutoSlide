package com.ziek.autoslide

/**
 * 应用更新检查
 *
 * 从 GitHub 上的 update.json 拉取最新版本信息，发现新版本时弹出更新对话框，
 * 用户确认后使用系统 DownloadManager 下载 APK 并调起安装。
 */

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI

/**
 * 应用更新检查
 *
 * @author tianxing
 */
object UpdateChecker {

    /**
     * 更新信息数据类
     *
     * @param versionName 版本名称
     * @param updateLog 更新日志
     * @param downloadUrl 下载URL
     */
    private data class UpdateInfo(
        val versionName: String, 
        val updateLog: String, 
        val downloadUrl: String
    )

    // 远端版本信息 JSON 的地址（本仓库地址，可能被代理加速）
    private const val UPDATE_INFO_URL =
        "https://raw.githubusercontent.com/zi-ek/AutoSlide/master/update.json"

    // 固定网盘下载地址
    private const val LANZOU_DOWNLOAD_URL = "https://q-sj.lanzoum.com/b0pnt04li"

    // 用于加速下载的 GitHub 代理前缀（按顺序尝试，哪个能用用哪个；最后直连）
    private val GITHUB_PROXY_PREFIXES = listOf(
        "https://gh-proxy.org/",
        "https://v4.gh-proxy.org/",
        "https://v6.gh-proxy.org/",
        "https://cdn.gh-proxy.org/",
        "https://ghproxy.net/"
    )
    private const val TAG = "UpdateChecker" // 日志标签
    private const val KEY_LAST_AUTO_CHECK = "lastAutoCheckTime" // 上次自动检查更新的时间戳
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 自动检查间隔：24 小时
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO // 网络请求使用的 IO 线程池
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main) // 主线程协程作用域
    private var pendingUpdateInfo: UpdateInfo? = null   // 待展示的更新信息（页面不可见时先暂存）
    private var updateDialog: AlertDialog? = null       // 当前正在显示的更新对话框
    private var currentDownloadId: Long = -1            // 当前下载任务的 ID
    private var downloadReceiver: BroadcastReceiver? = null // 下载完成广播接收器

    /**
     * 开始检查更新
     *
     * @param activity 活动
     * @param showToastOnLatest 是否在最新版或请求失败时显示吐司提示
     */
    fun checkUpdate(activity: Activity, showToastOnLatest: Boolean = true) {
        if (showToastOnLatest) {
            Toast.makeText(activity, R.string.checking_update, Toast.LENGTH_SHORT).show()
        }
        val activityRef = WeakReference(activity)
        val appContext = activity.applicationContext
        val lifecycleScope = (activity as? LifecycleOwner)?.lifecycleScope ?: scope
        lifecycleScope.launch {
            fetchUpdateInfo(appContext).onSuccess { updateInfo ->
                handleUpdateResult(activityRef, updateInfo, showToastOnLatest)
            }.onFailure {
                handleUpdateFailure(activityRef, it, showToastOnLatest)
            }
        }
    }

    /**
     * 主动检查更新：打开 App 时自动调用，每天最多检查一次
     * 有新版本时自动弹出更新对话框，没有新版本时不打扰用户
     *
     * @param activity 活动
     * @param force 是否强制检查（忽略每日间隔）
     */
    fun checkUpdateIfNeeded(activity: Activity, force: Boolean = false) {
        if (!force) {
            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastCheckTime = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
            val now = System.currentTimeMillis()
            if (now - lastCheckTime < AUTO_CHECK_INTERVAL_MS) {
                return
            }
            prefs.edit { putLong(KEY_LAST_AUTO_CHECK, now) }
        }
        checkUpdate(activity, showToastOnLatest = false)
    }

    /* 处理更新检查成功结果 */
    private fun handleUpdateResult(
        activityRef: WeakReference<Activity>, updateInfo: UpdateInfo?, showToastOnLatest: Boolean
    ) {
        val act = activityRef.get() ?: return
        if (act.isFinishing || act.isDestroyed) return
        if (updateInfo != null) {
            pendingUpdateInfo = updateInfo
            tryShowPendingUpdateDialog(act)
        } else if (showToastOnLatest) {
            Toast.makeText(act, R.string.already_latest_version, Toast.LENGTH_SHORT).show()
        }
    }

    /* 处理更新检查失败结果 */
    private fun handleUpdateFailure(
        activityRef: WeakReference<Activity>, error: Throwable, showToastOnLatest: Boolean
    ) {
        Log.e(TAG, "check update failed", error)
        if (!showToastOnLatest) return
        val act = activityRef.get() ?: return
        if (act.isFinishing || act.isDestroyed) return
        Toast.makeText(act, R.string.check_update_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * 从远端获取更新信息
     *
     * @param context 上下文
     * @return 更新信息
     */
    private suspend fun fetchUpdateInfo(context: Context): Result<UpdateInfo?> = withContext(ioDispatcher) {
        runCatching {
            // 依次尝试各个加速代理，全部失败再试直连
            val candidates = GITHUB_PROXY_PREFIXES.map { it to (it + UPDATE_INFO_URL) } + ("" to UPDATE_INFO_URL)
            var lastError: Exception? = null
            for ((prefix, candidateUrl) in candidates) {
                try {
                    val info = fetchUpdateInfoFromUrl(candidateUrl, context)
                    if (info != null) {
                        // 下载地址也使用同一个可用的加速前缀
                        return@withContext Result.success(
                            info.copy(downloadUrl = prefix + info.downloadUrl)
                        )
                    }
                    return@withContext Result.success(null)
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "更新源不可用: $candidateUrl", e)
                }
            }
            throw lastError ?: IllegalStateException("所有更新源均不可用")
        }
    }

    /* 从指定 URL 读取更新信息（单个源） */
    private fun fetchUpdateInfoFromUrl(url: String, context: Context): UpdateInfo? {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "AutoSlide-App")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${connection.responseCode}"
            }
            val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseStr)
            val remoteVersionCode = json.optInt("versionCode", 0)
            val localVersionCode = getLocalVersionCode(context)
            return if (remoteVersionCode > localVersionCode) {
                UpdateInfo(
                    versionName = json.optString("versionName", ""),
                    updateLog = json.optString("updateLog", ""),
                    downloadUrl = json.optString("downloadUrl", "")
                )
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 当宿主页面恢复可见时尝试展示待处理更新弹窗
     *
     * @param activity 活动
     */
    fun onHostResumed(activity: Activity) {
        tryShowPendingUpdateDialog(activity)
    }

    /**
     * 尝试展示待处理更新弹窗
     *
     * @param activity 活动
     */
    private fun tryShowPendingUpdateDialog(activity: Activity) {
        val updateInfo = pendingUpdateInfo ?: return
        if (!canShowDialog(activity)) return
        Log.d(TAG, "show update dialog")
        val lifecycleOwner = activity as? LifecycleOwner
        
        val message = updateInfo.updateLog.ifEmpty { activity.getString(R.string.update_found_default_message) }

        updateDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_found_title, updateInfo.versionName))
            .setMessage(message)
            .setPositiveButton(R.string.update_now, null)
            .setNeutralButton("网盘下载", null)
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .create().also { dialog ->
                val shownAt = SystemClock.elapsedRealtime()
                val lifecycleObserver = createLifecycleObserver(dialog)
                lifecycleOwner?.lifecycle?.addObserver(lifecycleObserver)
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnShowListener {
                    setupDialogButtons(dialog, shownAt, activity, updateInfo)
                }
                dialog.setOnDismissListener {
                    lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
                    updateDialog = null
                    Log.d(TAG, "dialog dismissed, pending=${pendingUpdateInfo != null}")
                    if (pendingUpdateInfo != null && !activity.isFinishing && !activity.isDestroyed) {
                        tryShowPendingUpdateDialog(activity)
                    }
                }
                dialog.show()
            }
    }

    /**
     * 检查是否可以展示弹窗
     *
     * @param activity 活动
     * @return 是否可以展示弹窗
     */
    private fun canShowDialog(activity: Activity): Boolean {
        if (updateDialog?.isShowing == true) {
            Log.d(TAG, "dialog already showing, skip")
            return false
        }
        if (activity.isFinishing || activity.isDestroyed) {
            Log.d(TAG, "activity finishing/destroyed, skip dialog")
            return false
        }
        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner != null && !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Log.d(TAG, "activity not resumed, skip dialog")
            return false
        }
        return true
    }

    /**
     * 创建生命周期观察者(销毁时关闭弹窗)
     *
     * @param dialog 对话框
     * @return 生命周期观察者
     */
    private fun createLifecycleObserver(dialog: AlertDialog) = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            dialog.dismiss()
            if (updateDialog == dialog) {
                updateDialog = null
            }
        }
    }

    /**
     * 设置对话框按钮
     *
     * @param dialog 对话框
     * @param shownAt 显示时间
     * @param activity 活动
     * @param updateInfo 更新信息
     */
    private fun setupDialogButtons(dialog: AlertDialog, shownAt: Long, activity: Activity, updateInfo: UpdateInfo) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val shownDuration = SystemClock.elapsedRealtime() - shownAt
            Log.d(TAG, "positive clicked after ${shownDuration}ms")
            pendingUpdateInfo = null
            dialog.dismiss()
            downloadAndInstall(activity, updateInfo.downloadUrl, updateInfo.versionName)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            // 复制固定网盘地址
            copyToClipboard(activity, LANZOU_DOWNLOAD_URL)
            
            // 准备展开后的消息内容
            val originalLog = updateInfo.updateLog.ifEmpty { activity.getString(R.string.update_found_default_message) }
            val successHint = "\n\n✅ 网盘地址已复制，请在浏览器中打开并下载\n\n🔐 网盘密码:lanr"
            
            // 使用 SpannableStringBuilder 增加颜色和加粗效果
            val spannable = SpannableStringBuilder(originalLog).apply {
                val start = length
                append(successHint)
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(activity, R.color.primary)),
                    start,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            // 更新弹窗内容，不关闭弹窗
            dialog.setMessage(spannable)
            
            // 禁用按钮并修改文字，提示已复制
            val neutralBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutralBtn.isEnabled = false
            neutralBtn.text = "地址已复制"
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            val shownDuration = SystemClock.elapsedRealtime() - shownAt
            if (shownDuration < 500L) {
                Log.d(TAG, "ignore early negative click after ${shownDuration}ms")
                return@setOnClickListener
            }
            Log.d(TAG, "negative clicked after ${shownDuration}ms")
            pendingUpdateInfo = null
            dialog.dismiss()
        }
    }

    /**
     * 使用DownloadManager下载APK并在完成后自动安装
     *
     * @param activity 活动
     * @param downloadUrl 下载URL
     * @param versionName 版本名称
     */
    private fun downloadAndInstall(activity: Activity, downloadUrl: String, versionName: String) {
        // 检查是否有安装未知应用的权限
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.install_permission_required, Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()
            )
            activity.startActivity(intent)
            return
        }
        val fileName = "AutoSlide-v$versionName.apk"
        val request = DownloadManager.Request(downloadUrl.toUri()).setTitle(activity.getString(R.string.app_name))
            .setDescription(activity.getString(R.string.downloading_update))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        currentDownloadId = downloadManager.enqueue(request)
        Toast.makeText(activity, R.string.downloading_update, Toast.LENGTH_SHORT).show()
        // 使用ApplicationContext注册/注销广播接收器
        val appContext = activity.applicationContext
        // 注销已有的下载广播接收器
        downloadReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        // 创建下载完成广播接收器
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != currentDownloadId) return
                runCatching { appContext.unregisterReceiver(this) }
                if (downloadReceiver == this) {
                    downloadReceiver = null
                }
                installApk(context, downloadManager, id)
            }
        }
        downloadReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /**
     * 安装已下载的APK文件
     *
     * @param context 上下文
     * @param downloadManager 下载管理器
     * @param downloadId 下载ID
     */
    private fun installApk(context: Context, downloadManager: DownloadManager, downloadId: Long) {
        val uri = downloadManager.getUriForDownloadedFile(downloadId)
        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                it.printStackTrace()
                Toast.makeText(context, R.string.install_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 将文本复制到剪贴板
     *
     * @param context 上下文
     * @param text 要复制的文本
     */
    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AutoSlide Download Link", text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * 获取当前应用的版本号
     *
     * @param context 上下文
     * @return 当前应用的版本号
     */
    private fun getLocalVersionCode(context: Context): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(packageInfo)
    }
}
