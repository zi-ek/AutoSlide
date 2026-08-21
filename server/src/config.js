// 集中配置：端口、数据目录、各类上限
//
// 数据目录默认在 server/data，测试时用 AUTOSLIDE_DATA_DIR 指到临时目录做隔离。
// 注意本文件位于 server/src/ 下，回到 server/ 需要多一层 '..'。

const path = require('path');

const PORT = process.env.PORT || 8080;

const DATA_DIR = process.env.AUTOSLIDE_DATA_DIR || path.join(__dirname, '..', 'data');
const STATS_FILE = path.join(DATA_DIR, 'stats.json');
const UPLOAD_DIR = path.join(DATA_DIR, 'uploads');
const MANIFEST_FILE = path.join(UPLOAD_DIR, 'index.json');
const CHAT_FILE = path.join(DATA_DIR, 'chat.json');
const CHAT_IMAGE_DIR = path.join(DATA_DIR, 'chat-images');

/* 单个频道保留的最近消息条数 */
const CHAT_MESSAGE_LIMIT = 300;
/* 反馈消息与图片的保留天数，超期自动清除 */
const CHAT_RETENTION_DAYS = 7;
/* 清理任务的执行间隔（毫秒），进程启动时也会先跑一次 */
const CHAT_CLEANUP_INTERVAL_MS = 6 * 60 * 60 * 1000;

/* 各接口的请求体上限 */
const LIMIT_JSON_BODY = 64 * 1024;
const LIMIT_UPLOAD_BODY = 2 * 1024 * 1024;
const LIMIT_CHAT_BODY = 8 * 1024 * 1024;
const LIMIT_CHAT_IMAGE = 6 * 1024 * 1024;

/* IP 归属地缓存上限，防止长期运行后无限增长 */
const IP_CACHE_MAX = 5000;

/* 单个 APK 的体积上限：发版上传接口据此中断超大请求 */
const LIMIT_APK = 200 * 1024 * 1024;

/**
 * 管理口令：删除已分享脚本、上传 APK、写 update.json 三处共用。
 *
 * 只从环境变量读，不写进代码库；在 systemd 单元里配：
 *   Environment=AUTOSLIDE_ADMIN_TOKEN=你的口令
 * 没配置时相关接口一律拒绝（fail closed），不会退化成人人可写。
 */
const ADMIN_TOKEN = process.env.AUTOSLIDE_ADMIN_TOKEN || '';

module.exports = {
  PORT,
  DATA_DIR,
  STATS_FILE,
  UPLOAD_DIR,
  MANIFEST_FILE,
  CHAT_FILE,
  CHAT_IMAGE_DIR,
  CHAT_MESSAGE_LIMIT,
  CHAT_RETENTION_DAYS,
  CHAT_CLEANUP_INTERVAL_MS,
  LIMIT_JSON_BODY,
  LIMIT_UPLOAD_BODY,
  LIMIT_CHAT_BODY,
  LIMIT_CHAT_IMAGE,
  IP_CACHE_MAX,
  LIMIT_APK,
  ADMIN_TOKEN,
};
