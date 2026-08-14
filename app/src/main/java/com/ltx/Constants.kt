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
const val KEY_KEYWORDS = "keywords"                 // 关键词列表（用中文逗号分隔）
const val KEY_KEYWORD_IGNORE_CASE = "keywordIgnoreCase" // 是否忽略大小写
const val KEY_KEYWORD_INTERVAL = "keywordInterval"      // 关键词检测间隔（秒）
const val KEY_KEYWORD_COOLDOWN = "keywordCooldown"      // 关键词触发后的冷却时间（秒）
const val KEY_KEYWORD_MAX_TRIGGERS = "keywordMaxTriggers" // 同一画面最多连续触发次数
const val KEY_KEYWORD_DIRECTION = "keywordDirection"    // 关键词触发时的滑动方向
const val KEY_DOUYIN_AUTOPLAY = "douyinAutoPlay"        // 是否自动打开抖音连播开关（检测到抖音后触发）
const val KEY_LAST_BATTERY_OPT_REQUEST_TIME = "lastBatteryOptRequestTime" // 上次申请电池优化白名单的时间戳
// 用户是否希望无障碍服务保持开启（对应 GKD 的 enableAutomator）：
// 只有为 true 时自愈逻辑才会把被 ROM 关掉的无障碍重新打开，
// 否则用户在系统设置里手动关闭本服务会被立刻打开，导致根本关不掉
const val KEY_SERVICE_DESIRED = "serviceDesired"
// 「摘除→加回」重启无障碍的过程标记：若进程在这段窗口里被 force-stop，
// 组件会永久留在已移除状态，下次启动时据此标记立刻补回
const val KEY_A11Y_RESTART_IN_PROGRESS = "a11yRestartInProgress"
// 悬浮窗是否应处于显示状态：进程被清理后复活时据此自动把悬浮球恢复出来，
// 否则无障碍虽然活了，用户看到的仍是「App 被清理了」
const val KEY_FLOATING_DESIRED = "floatingDesired"

// ==================== 统计数据 ====================
const val KEY_STATS_TOTAL_SWIPES = "stats_total_swipes"
const val KEY_STATS_KEYWORD_MATCHES = "stats_keyword_matches"
const val KEY_STATS_SAVED_DISTANCE = "stats_saved_distance"
const val KEY_IS_REPORTED = "is_reported"
const val KEY_LAST_REPORTED_VERSION = "last_reported_version"
// 统计后台地址（Cloudflare Tunnel 绑定域名）
const val STATS_URL = "https://pve.8450696.shop"

// ==================== 默认值 ====================
const val DEFAULT_SPEED = 50                        // 默认滑动速度（中等）
const val DEFAULT_PAUSE_TIME = 15                    // 默认固定停顿 15 秒
const val DEFAULT_MIN_PAUSE_TIME = 5                // 默认随机停顿下限 5 秒
const val DEFAULT_MAX_PAUSE_TIME = 30                // 默认随机停顿上限 30 秒
// 默认关键词（用中文逗号分隔，供第一次打开应用时预填）
const val DEFAULT_KEYWORDS = "上滑，继续，查看详情，点击进入，限时，抢购，购物"
const val DEFAULT_KEYWORD_IGNORE_CASE = true        // 默认忽略大小写
const val DEFAULT_KEYWORD_INTERVAL = 2           // 默认每 2 秒检测一次
const val DEFAULT_KEYWORD_COOLDOWN = 3           // 默认触发后冷却 3 秒
const val DEFAULT_KEYWORD_MAX_TRIGGERS = 3          // 默认同一画面最多触发 3 次
const val DEFAULT_DOUYIN_AUTOPLAY = true            // 默认开启抖音自动连播

/**
 * 把关键词文本拆分成关键词列表。
 * 同时支持中文逗号（，）、英文逗号（,）和换行分隔，
 * 避免用户从别处复制粘贴关键词列表时因英文逗号导致整词匹配失败。
 */
fun parseKeywords(text: String): List<String> =
    text.split('，', ',', '\n', '\r').map { it.trim() }.filter { it.isNotBlank() }

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

// ==================== 录制库 ====================
// 支持保存多条录制记录：每条记录以 “macro_名称” 为键存到本地，
// 值为 PlainApp 输入框架的 JSON 数组（点击/长按/滑动/等待）
const val KEY_MACRO_PREFIX = "macro_"

/**
 * 兼容读取关键词时间参数（单位：秒）
 * 旧版本可能把秒数存成了 Float，新版本统一为整数；这里自动转换，避免类型不兼容闪退
 *
 * @param prefs SharedPreferences
 * @param key 配置键名
 * @param default 默认值（秒）
 * @return 整数秒
 */
