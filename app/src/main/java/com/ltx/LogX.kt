package com.ltx

import android.util.Log
import li.songe.loc.Loc

/**
 * 统一日志入口。
 *
 * 通过 kotlin-loc 编译器插件，在编译期把每个调用点的
 * 类名.方法名(文件名:行号) 注入到 [Loc] 参数中，
 * 让 logcat 里的关键日志自带代码位置，排查问题不用再猜出处。
 */
object LogX {

    private fun prefix(loc: String): String =
        if (loc.isBlank()) "" else "[$loc] "

    fun d(tag: String, message: String, @Loc loc: String = "") =
        Log.d(tag, prefix(loc) + message)

    fun d(tag: String, message: String, tr: Throwable, @Loc loc: String = "") =
        Log.d(tag, prefix(loc) + message, tr)

    fun i(tag: String, message: String, @Loc loc: String = "") =
        Log.i(tag, prefix(loc) + message)

    fun i(tag: String, message: String, tr: Throwable, @Loc loc: String = "") =
        Log.i(tag, prefix(loc) + message, tr)

    fun w(tag: String, message: String, @Loc loc: String = "") =
        Log.w(tag, prefix(loc) + message)

    fun w(tag: String, message: String, tr: Throwable, @Loc loc: String = "") =
        Log.w(tag, prefix(loc) + message, tr)

    fun e(tag: String, message: String, @Loc loc: String = "") =
        Log.e(tag, prefix(loc) + message)

    fun e(tag: String, message: String, tr: Throwable, @Loc loc: String = "") =
        Log.e(tag, prefix(loc) + message, tr)
}
