package com.ziek.autoslide.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.ziek.autoslide.LocationTracker
import com.ziek.autoslide.LogX

/**
 * 行动轨迹熄屏保活的第三层：定时「复活」。
 *
 * 唤醒锁 + 静音音频（见 [com.ziek.autoslide.LocationTracker]）主要防冻结、降低被杀概率；
 * 万一 MIUI/ColorOS 仍把进程整个清掉，靠这个周期任务把轨迹前台服务（连带采集）重新拉起来。
 *
 * 与「两只脚」ImmortalJobService 对齐点：
 * - 本 JobService 与 [TrackRecordService] 同在 `:trackrecord` 进程（Manifest android:process）；
 *   进程被清后 JobScheduler 到点会重新创建该进程执行 onStartJob，再拉起前台服务。
 * - 两只脚在 API 31+ 不再从 JobService 里直接拉前台服务（后台启动前台服务被系统禁止），
 *   与本实现的处理一致：起不来时静默跳过，等下一轮，绝不崩。
 * - 周期下限被系统卡在 15 分钟，这是兜底而非主力：主力是让进程压根别被杀（前两层）。
 *
 * 生命周期：采集一启动就在 [LocationTracker.start] 里排上（幂等）；某轮任务发现采集已关
 * （用户关开关 / 撤权限）就 [cancel] 掉自己，避免每 15 分钟空跑。
 */
class TrackReviveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            if (!LocationTracker.shouldRun(this)) {
                // 采集已关，撤掉自己，别再周期空跑
                cancel(this)
                return false
            }
            // 该采集、但轨迹前台服务没在跑 → 拉起来（TrackRecordService.start 内部走 startForegroundService）
            if (!TrackRecordService.isForeground.value) {
                LogX.i(TAG, "复活任务：采集应运行但轨迹前台服务不在，尝试拉起")
                TrackRecordService.start(this)
            }
        } catch (e: Exception) {
            // 安卓 12+ 未豁免电池优化时从后台起前台服务会抛，属预期；跳过等下轮
            LogX.w(TAG, "复活任务拉起失败（多为未豁免电池优化）：$e")
        }
        // 无异步工作，直接算完成；周期任务由系统自动再排下一轮
        return false
    }

    // 无正在进行的工作，无需系统重排本次
    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "TrackRevive"
        private const val JOB_ID = 0x5107
        // 系统对 setPeriodic 的下限就是 15 分钟，给足；更密也会被系统拉回 15 分钟
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /** 采集开启时调用：排一个周期复活任务。同 JOB_ID 重复排只替换，不叠加，幂等 */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, TrackReviveJobService::class.java)
            )
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true) // 重启后仍在（需 RECEIVE_BOOT_COMPLETED）
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            runCatching { scheduler.schedule(info) }
                .onFailure { LogX.w(TAG, "排复活任务失败：$it") }
        }

        /** 采集关闭时调用：撤销复活任务 */
        fun cancel(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            runCatching { scheduler.cancel(JOB_ID) }
        }
    }
}
