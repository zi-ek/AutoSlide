package com.ltx

/**
 * 全局常量定义文件
 *
 * 集中管理本应用用到的所有配置键名、默认值、模式编号和滑动方向字符串，
 * 方便各模块（主界面、悬浮窗、无障碍服务）共享同一套取值，避免散落硬编码。
 */

// ==================== 本地配置 ====================
// SharedPreferences 配置文件名（所有设置都保存在这个文件里）
const val PREFS_NAME = "slide_settings"

// ---- 配置键名（保存在 SharedPreferences 中的 key）----
const val KEY_SPEED = "speed"                       // 滑动速度（1~100）
const val KEY_PAUSE_MODE = "pauseMode"              // 当前选择的滑动模式编号
const val KEY_PAUSE_TIME = "pauseTime"              // 固定时间模式的停顿秒数
const val KEY_MIN_PAUSE_TIME = "minPauseTime"       // 随机时间模式的最小停顿秒数
const val KEY_MAX_PAUSE_TIME = "maxPauseTime"       // 随机时间模式的最大停顿秒数
const val KEY_KEYWORDS = "keywords"                 // 关键词列表（每行一个，用换行分隔）
const val KEY_KEYWORD_IGNORE_CASE = "keywordIgnoreCase" // 是否忽略大小写
const val KEY_KEYWORD_INTERVAL = "keywordIntervalMs"    // 关键词检测间隔（毫秒）
const val KEY_KEYWORD_COOLDOWN = "keywordCooldownMs"    // 关键词触发后的冷却时间（毫秒）
const val KEY_KEYWORD_MAX_TRIGGERS = "keywordMaxTriggers" // 同一画面最多连续触发次数
const val KEY_KEYWORD_DIRECTION = "keywordDirection"    // 关键词触发时的滑动方向
const val KEY_DOUYIN_AUTOPLAY = "douyinAutoPlay"        // 是否自动打开抖音连播开关（检测到抖音后触发）

// ==================== 默认值 ====================
const val DEFAULT_SPEED = 50                        // 默认滑动速度（中等）
const val DEFAULT_PAUSE_TIME = 15                    // 默认固定停顿 1 秒
const val DEFAULT_MIN_PAUSE_TIME = 1                // 默认随机停顿下限 1 秒
const val DEFAULT_MAX_PAUSE_TIME = 3                // 默认随机停顿上限 3 秒
// 默认关键词（每行一个，供第一次打开应用时预填）
const val DEFAULT_KEYWORDS = "上滑继续\n查看详情\n官方官号\n点击进入\n限时抢购\n购物"
const val DEFAULT_KEYWORD_IGNORE_CASE = true        // 默认忽略大小写
const val DEFAULT_KEYWORD_INTERVAL_MS = 1000        // 默认每 1000ms 检测一次
const val DEFAULT_KEYWORD_COOLDOWN_MS = 500         // 默认触发后冷却 500ms
const val DEFAULT_KEYWORD_MAX_TRIGGERS = 3          // 默认同一画面最多触发 3 次
const val DEFAULT_DOUYIN_AUTOPLAY = true            // 默认开启抖音自动连播

// ==================== 滑动模式编号 ====================
// 主界面四个选项对应的模式值，服务端根据该值决定走哪套滑动逻辑
const val PAUSE_MODE_KEYWORD = 0    // 关键词检测：OCR 识别屏幕文字，命中关键词才滑动
const val PAUSE_MODE_NONE = 1       // 不停顿：连续定时滑动，间隔很短
const val PAUSE_MODE_FIXED = 2      // 固定时间：每次滑动后停顿固定秒数
const val PAUSE_MODE_RANDOM = 3     // 随机时间：每次滑动后停顿随机秒数


// ==================== 滑动方向 ====================
const val DIRECTION_UP = "up"       // 向上滑动
const val DIRECTION_DOWN = "down"   // 向下滑动
const val DIRECTION_LEFT = "left"   // 向左滑动
const val DIRECTION_RIGHT = "right" // 向右滑动
// 关键词触发滑动的默认方向（未设置过或配置无效时使用）
const val DEFAULT_KEYWORD_DIRECTION = DIRECTION_UP

// ==================== 自定义轨迹 ====================
// 四个方向的自定义轨迹存储键名，轨迹是一串“x,y;x,y;...”坐标点
const val KEY_CUSTOM_TRAJECTORY_UP = "customTrajectory_up"
const val KEY_CUSTOM_TRAJECTORY_DOWN = "customTrajectory_down"
const val KEY_CUSTOM_TRAJECTORY_LEFT = "customTrajectory_left"
const val KEY_CUSTOM_TRAJECTORY_RIGHT = "customTrajectory_right"

/**
 * 根据滑动方向获取对应的轨迹存储键名
 *
 * @param direction 方向字符串（up/down/left/right）
 * @return 轨迹存储键名；方向无效时返回 null
 */
fun getTrajectoryKey(direction: String): String? = when (direction) {
    DIRECTION_UP -> KEY_CUSTOM_TRAJECTORY_UP
    DIRECTION_DOWN -> KEY_CUSTOM_TRAJECTORY_DOWN
    DIRECTION_LEFT -> KEY_CUSTOM_TRAJECTORY_LEFT
    DIRECTION_RIGHT -> KEY_CUSTOM_TRAJECTORY_RIGHT
    else -> null
}
