package com.ziek.autoslide

import android.app.job.*
import android.content.*
import java.io.File

/** Upgrade-only cleanup: no new positions are collected by AutoSlide. */
class LegacyMigrationJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runCatching { LegacyTrackUpload.flush(applicationContext) }
                .onFailure { LogX.w("LegacyMigration", "旧轨迹补传失败", it) }
            jobFinished(params, File(filesDir, "track_queue.jsonl").exists())
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Cancel the old persisted revive task even if the user never reopens settings.
            scheduler.cancel(0x5107)
            runCatching { File(context.filesDir, "track_enabled").writeText("0") }
            if (!File(context.filesDir, "track_queue.jsonl").exists()) return
            val job = JobInfo.Builder(0x5108, ComponentName(context, LegacyMigrationJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(job)
        }
    }
}

class LegacyMigrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { LegacyMigrationJob.schedule(context) }
}
