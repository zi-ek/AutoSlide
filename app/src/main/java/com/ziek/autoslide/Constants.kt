package com.ziek.autoslide

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
const val KEY_SKIP_KEYWORDS = "skipKeywords"         // 自动点击「跳过」按钮的匹配关键词列表（用中文逗号分隔）
const val KEY_AUTO_TAP_ENABLED = "autoTapEnabled"    // 自动点击总开关（关闭后跳过/关键词/回放三条路径全部停止）
const val KEY_DOUYIN_AUTOPLAY = "douyinAutoPlay"        // 是否自动打开抖音连播开关（检测到抖音后触发）
// 用户是否希望无障碍服务保持开启（对应 GKD 的 enableAutomator）：
// 由无障碍服务自身生命周期维护——onCreate 置 true、onDestroy 置 false。
// 进程被杀时 onDestroy 不执行，标记留在 true，下次进程起来即可自愈；
// 用户在系统设置里手动关闭时 onDestroy 会执行，标记变 false，不会被强行拉回来。
const val KEY_SERVICE_DESIRED = "serviceDesired"
// 用户是否开启了常驻状态通知（对应 GKD 的 enableStatusService）
const val KEY_STATUS_SERVICE_ENABLED = "statusServiceEnabled"
// 是否已同意首启的使用声明（对应 GKD 的 terms_accepted）
const val KEY_TERMS_ACCEPTED = "terms_accepted"
// 悬浮窗是否应处于显示状态：无障碍服务重新连接时据此把悬浮球恢复出来
const val KEY_FLOATING_DESIRED = "floatingDesired"

// ==================== 统计数据 ====================
const val KEY_STATS_TOTAL_SWIPES = "stats_total_swipes"
const val KEY_STATS_KEYWORD_MATCHES = "stats_keyword_matches"
const val KEY_STATS_SAVED_DISTANCE = "stats_saved_distance"
const val KEY_IS_REPORTED = "is_reported"
const val KEY_LAST_REPORTED_VERSION = "last_reported_version"
// ==================== 后端服务 ====================
// 统计上报、录制脚本备份、聊天室共用同一台后端（Cloudflare Tunnel 绑定域名）。
// 地址在 gradle.properties 的 autoslide.serverBaseUrl 里配置，构建时注入 BuildConfig，
// 全工程只有这一个来源——换服务器不需要动任何 Kotlin 代码。
// 注意：这里不能用 const val，因为 BuildConfig 是 Java 静态字段，不构成 Kotlin 编译期常量。
val SERVER_BASE_URL: String = BuildConfig.SERVER_BASE_URL

// 首启声明弹窗里「用户协议」「隐私政策」两个链接的地址。
// 页面由后端 server/src/legal.js 提供，内容在 server/src/views/legal.js；
// 改动应用的任何联网行为时，务必同步更新隐私政策里的数据表。
val URL_TERMS: String = "$SERVER_BASE_URL/terms"
val URL_PRIVACY: String = "$SERVER_BASE_URL/privacy"

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
// 默认自动点击「跳过」按钮的匹配关键词（用中文逗号分隔）
const val DEFAULT_SKIP_KEYWORDS = "跳过"
// 自动点击默认开启（保持原有行为）
const val DEFAULT_AUTO_TAP_ENABLED = true
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
const val KEY_MACRO_LOOP_COUNT = "macroLoopCount"     // 回放时记住上次使用的循环次数

/**
 * 兼容读取关键词时间参数（单位：秒）
 * 旧版本可能把秒数存成了 Float，新版本统一为整数；这里自动转换，避免类型不兼容闪退
 *
 * @param prefs SharedPreferences
 * @param key 配置键名
 * @param default 默认值（秒）
 * @return 整数秒
 */
