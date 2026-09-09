package com.ziek.autoslide

import android.content.Context
import java.io.File

/**
 * 行动轨迹的「总开关」与「静止自动关计时基准」的跨进程一致存储。
 *
 * 为什么不用 SharedPreferences：轨迹采集跑在独立进程 `:trackrecord`，而这两个值同时被
 * 主进程（用户手动/远程开关、UI 回显、[markUserEnabled] 重置计时）和 `:trackrecord`
 * （静止自动关写 false、落点推进计时）读写。MODE_PRIVATE 的 SharedPreferences **每个进程各缓存
 * 一份整份 map**，一个进程改了某键、另一个进程用陈旧缓存写任意别的键，就会把整份文件覆盖回去——
 * 实测「:trackrecord 把 trackEnabled 写成 false，主进程随后写 serviceDesired 时又用旧缓存把它
 * 覆盖回 true」，导致静止自动关形同虚设（触发了但立刻被覆盖）。
 *
 * 这里改用**纯文件、单值、不缓存**：每次读直接读盘（跨进程永远看到最新），每次写只写这一个值
 * （不会捎带陈旧的兄弟键），从根上消除覆盖问题。写用「临时文件 + rename」保证原子性，
 * 读者要么看到旧值、要么新值，不会读到半截。
 */
object TrackState {

    private const val FILE_ENABLED = "track_enabled"
    private const val FILE_LAST_MOVE = "track_last_move_at"

    private fun dir(context: Context) = context.applicationContext.filesDir

    /** 轨迹总开关；文件不存在时从旧的 SharedPreferences 值迁移一次，老用户开关状态不丢。 */
    fun isEnabled(context: Context): Boolean {
        val f = File(dir(context), FILE_ENABLED)
        if (!f.exists()) {
            val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRACK_ENABLED, DEFAULT_TRACK_ENABLED)
            setEnabled(context, legacy)
            return legacy
        }
        return runCatching { f.readText().trim() == "1" }.getOrDefault(DEFAULT_TRACK_ENABLED)
    }

    fun setEnabled(context: Context, enabled: Boolean) =
        writeAtomic(context, FILE_ENABLED, if (enabled) "1" else "0")

    /** 自动关计时基准（最后一次真实位移的墙钟 ms）；无记录返回 0 */
    fun lastMoveAt(context: Context): Long {
        val f = File(dir(context), FILE_LAST_MOVE)
        if (!f.exists()) return 0L
        return runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
    }

    fun setLastMoveAt(context: Context, atMs: Long) =
        writeAtomic(context, FILE_LAST_MOVE, atMs.toString())

    private fun writeAtomic(context: Context, name: String, value: String) {
        runCatching {
            val d = dir(context)
            val tmp = File(d, "$name.tmp")
            tmp.writeText(value)
            val dst = File(d, name)
            if (!tmp.renameTo(dst)) {
                dst.writeText(value)   // rename 失败（个别文件系统）退化为直接覆写
                tmp.delete()
            }
        }
    }
}
