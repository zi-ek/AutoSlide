package com.ziek.autoslide.input

/**
 * 自动滑屏输入框架
 *
 * 参考 PlainApp 的 ScreenMirrorControlInput 结构改造：
 * - 每个动作用一个枚举类型 + 归一化坐标(0~1) + 时长描述；
 * - SWIPE 额外保存完整路径点，回放时还原真实滑动轨迹；
 * - delayMs 记录该动作执行前需要等待的时间（录制时两次操作之间的间隔）；
 * - 一组动作以 JSON 数组形式保存，录制/回放完全解耦。
 */

import org.json.JSONArray
import org.json.JSONObject
import android.util.Log

/* 输入动作类型（对应 PlainApp 的 ScreenMirrorControlAction，只保留触摸类） */
enum class AutoSlideInputAction {
    TAP,        // 点击
    LONG_PRESS, // 长按
    SWIPE,      // 滑动（含路径点）
    BACK,       // 返回（系统返回键/边缘滑动返回）
    WAIT_FOR,   // 等待条件：等屏幕出现/消失指定文字（宏回放时使用）
    FIND_AND_TAP // 按文字点击：回放时先在节点树/OCR 中找到指定文字再点击（不依赖录制坐标）
}

/**
 * 一条输入动作
 *
 * @param action 动作类型
 * @param x 起点 X（归一化 0~1）
 * @param y 起点 Y（归一化 0~1）
 * @param endX 终点 X（归一化 0~1）
 * @param endY 终点 Y（归一化 0~1）
 * @param duration 手势持续时间（毫秒）
 * @param delayMs 执行本动作前的等待时间（毫秒），由录制时的操作间隔生成
 * @param points SWIPE 的完整路径点，x,y 交替且归一化到 0~1；点击/长按为空
 * @param waitText WAIT_FOR 要等待的文字
 * @param waitDisappear true=等文字消失，false=等文字出现
 * @param waitTimeoutMs 等待超时时间（毫秒），超时后回放中止
 * @param targetId 录制时点击位置的控件 id（回放时优先按控件定位，找不到则退回坐标）
 * @param targetText 录制时点击位置的控件文字/OCR 识别文字（回放时按文字定位）
 * @param launchOnce 仅首轮执行：循环回放时第一轮执行，从第二轮起跳过
 *                   （用于“回桌面点击图标启动 App”这类只需执行一次的动作）
 */
data class AutoSlideInput(
    val action: AutoSlideInputAction,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val endX: Float = 0.5f,
    val endY: Float = 0.5f,
    val duration: Long = 300L,
    val delayMs: Long = 150L,
    val points: List<Float> = emptyList(),
    val waitText: String = "",
    val waitDisappear: Boolean = false,
    val waitTimeoutMs: Long = 30_000L,
    val targetId: String = "",
    val targetText: String = "",
    val launchOnce: Boolean = false
) {
    /* 序列化为 JSON 对象 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("endX", endX.toDouble())
        put("endY", endY.toDouble())
        put("duration", duration)
        put("delay", delayMs)
        put("waitText", waitText)
        put("waitDisappear", waitDisappear)
        put("waitTimeout", waitTimeoutMs)
        put("targetId", targetId)
        put("targetText", targetText)
        put("launchOnce", launchOnce)
        if (points.isNotEmpty()) {
            put("points", JSONArray().apply { points.forEach { put(it.toDouble()) } })
        }
    }

    companion object {
        /* 从 JSON 对象解析；解析失败返回 null */
        fun fromJson(obj: JSONObject): AutoSlideInput? {
            return try {
                AutoSlideInput(
                    action = AutoSlideInputAction.valueOf(obj.optString("action")),
                    x = obj.optDouble("x", 0.5).toFloat(),
                    y = obj.optDouble("y", 0.5).toFloat(),
                    endX = obj.optDouble("endX", 0.5).toFloat(),
                    endY = obj.optDouble("endY", 0.5).toFloat(),
                    duration = obj.optLong("duration", 300L).coerceAtLeast(0L),
                    delayMs = obj.optLong("delay", 150L).coerceIn(0L, 120_000L),
                    points = obj.optJSONArray("points")?.let { arr ->
                        (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                    } ?: emptyList(),
                    waitText = obj.optString("waitText", ""),
                    waitDisappear = obj.optBoolean("waitDisappear", false),
                    waitTimeoutMs = obj.optLong("waitTimeout", 30_000L).coerceIn(1_000L, 120_000L),
                    targetId = obj.optString("targetId", ""),
                    targetText = obj.optString("targetText", ""),
                    launchOnce = obj.optBoolean("launchOnce", false)
                )
            } catch (e: Exception) {
                Log.e("AutoSlideInputCodec", "fromJson failed, action=${obj.optString("action")}", e)
                null
            }
        }
    }
}

/* 输入序列编解码器 */
object AutoSlideInputCodec {

    /** 把一组输入序列编码为 JSON 字符串 */
    fun encode(inputs: List<AutoSlideInput>): String =
        JSONArray().apply { inputs.forEach { put(it.toJson()) } }.toString()

    /** 解析新版 JSON 格式；失败或为空返回 null */
    fun decode(json: String): List<AutoSlideInput>? {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> AutoSlideInput.fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e("AutoSlideInputCodec", "decode failed, json=${json.take(300)}", e)
            null
        }.takeIf { !it.isNullOrEmpty() }
    }

    /**
     * 兼容旧版“x,y;x,y;...”单路径格式：把一整段路径转成一次带完整路径点的 SWIPE
     *
     * @param raw 旧版轨迹字符串
     * @param screenWidth 当前屏幕宽度（像素）
     * @param screenHeight 当前屏幕高度（像素）
     */
    fun decodeLegacyPath(raw: String, screenWidth: Int, screenHeight: Int): List<AutoSlideInput>? {
        val rawPoints = raw.split(';').mapNotNull { part ->
            val xy = part.split(',')
            if (xy.size == 2) {
                val x = xy[0].toFloatOrNull() ?: return@mapNotNull null
                val y = xy[1].toFloatOrNull() ?: return@mapNotNull null
                x to y
            } else {
                null
            }
        }
        if (rawPoints.size < 2) {
            return null
        }
        val w = screenWidth.coerceAtLeast(1).toFloat()
        val h = screenHeight.coerceAtLeast(1).toFloat()
        val start = rawPoints.first()
        val end = rawPoints.last()
        val normalized = rawPoints.flatMap { listOf((it.first / w).coerceIn(0f, 1f), (it.second / h).coerceIn(0f, 1f)) }
        return listOf(
            AutoSlideInput(
                action = AutoSlideInputAction.SWIPE,
                x = (start.first / w).coerceIn(0f, 1f),
                y = (start.second / h).coerceIn(0f, 1f),
                endX = (end.first / w).coerceIn(0f, 1f),
                endY = (end.second / h).coerceIn(0f, 1f),
                duration = 300L,
                delayMs = 0L,
                points = normalized
            )
        )
    }
}
