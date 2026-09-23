// AutoSlide 统计后台入口（零依赖，Node.js 直接运行）
//
// 本文件只负责装配：建路由表 → 让各领域模块注册自己的接口 → 监听端口。
// 具体实现分布在 src/ 下：
//   src/config.js       端口、目录、各类上限
//   src/store.js        JsonStore：原子写 + 串行化写队列
//   src/http.js         请求体读取、响应封装、路由表
//   src/paths.js        文件名清洗与路径越界校验
//   src/ip.js           IP 归属地查询
//   src/util.js         时间戳 / HTML 转义 / 字节格式化
//   src/stats.js        统计域：/api/report、/api/stats、统计看板
//   src/license.js      授权域：/api/license、/api/license/bind、邀请落地页
//   src/chat.js         聊天域：/api/chat/*
//   src/uploads.js      上传域：/api/upload、/api/scripts、/api/download、/admin/scripts
//   src/update.js       更新域：/api/update、/download（版本信息与 APK 分发）
//   src/commands.js     地址域：设备上报当前可连地址，看板与 Easycontrol 共用同一份
//   src/views/          页面模板与样式
//
// 接口一览：
//   POST /api/report   App 上报安装/更新事件 + 设备信息
//   POST /api/upload   App 上传录制脚本（整份配置备份 / 单条分享脚本）
//   GET  /api/scripts  脚本库清单（App「我的 → 脚本库」用）
//   POST /api/scripts/delete  删除一条已分享脚本（需 AUTOSLIDE_ADMIN_TOKEN）
//   GET  /admin/scripts  脚本库管理页
//   GET  /api/update   客户端检查更新用的版本信息
//   GET  /download?file=..apk  下载安装包
//   GET  /admin/release  发版管理页（需 AUTOSLIDE_ADMIN_TOKEN）
//   GET  /api/stats    返回统计 JSON
//   GET  /api/uploads  返回已上传文件列表
//   GET  /api/download?deviceId=..&filename=..  下载已上传文件
//   GET  /view?deviceId=..&filename=..  浏览器查看脚本内容
//   GET  /api/chat/*   聊天室（频道、消息、图片、公告）
//   GET  /api/license  查询试用/奖励时长与邀请码
//   POST /api/license/bind  新设备填写邀请码
//   GET  /invite?code= 分享落地页；GET /invites 邀请榜
//   POST /api/report/network      设备上报当前可连地址（需 AUTOSLIDE_REPORT_TOKEN）
//   GET  /api/device/address?serial=..  Easycontrol 按序列号查地址（公开）
//   POST /api/pair/offer          远程引导壳上报配对载荷（需 AUTOSLIDE_REPORT_TOKEN）
//   GET  /api/pair/fetch?sid=..   Easycontrol 凭会话号取配对载荷（公开）
//   POST /api/pair/result         Easycontrol 回报配对结果
//   GET  /api/pair/status         引导壳轮询配对状态（需 AUTOSLIDE_REPORT_TOKEN）
//   GET  /             统计看板页面

const http = require('http');
const { PORT, ADMIN_TOKEN } = require('./src/config');
const { createRouter } = require('./src/http');

const router = createRouter();
require('./src/chat').register(router);
require('./src/stats').register(router);
require('./src/uploads').register(router);
require('./src/legal').register(router);
require('./src/license').register(router);
require('./src/update').register(router);
require('./src/commands').register(router);
require('./src/track').register(router);
require('./src/relay').register(router);
require('./src/pair').register(router);

// 首页看板锁：给人看的页面挂 Basic Auth，密码即 AUTOSLIDE_ADMIN_TOKEN。
// 只锁「浏览器打开的看板」，机器调用的接口（上报、授权、更新、地址、聊天）与
// 法律页(/privacy /terms)、脚本分享页(/view) 一律放行，锁了会把 App 和 Easycontrol 打瘫。
// 轨迹是敏感数据，/track 与 /api/track 也在此列（/api/track 另接受 x-admin-token 供 Easycontrol）。
const LOCKED_PAGES = new Set([
  '/', '/index.html', '/stats', '/stats/', '/api/stats', '/api/device/info', '/track', '/api/track',
]);

/* Basic Auth 校验：用户名忽略，密码等于 ADMIN_TOKEN 即放行 */
function dashboardAuthOk(req) {
  const m = /^Basic (.+)$/.exec(String(req.headers['authorization'] || ''));
  if (!m) return false;
  const decoded = Buffer.from(m[1], 'base64').toString('utf8');
  const pass = decoded.slice(decoded.indexOf(':') + 1);
  return pass === ADMIN_TOKEN;
}

const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname;
  if (LOCKED_PAGES.has(pathname)) {
    // /api/track 的 GET 由模块内 isAdmin 处理（兼容 x-admin-token），这里放它过，避免 Basic 挑战挡住 Easycontrol
    const isTrackApi = pathname === '/api/track';
    if (!isTrackApi) {
      if (!ADMIN_TOKEN) {
        // fail-closed：没配口令一律拒绝，绝不退化成人人可看（轨迹是敏感数据）
        res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('看板口令(AUTOSLIDE_ADMIN_TOKEN)未配置，已按安全策略拒绝访问。');
        return;
      }
      if (!dashboardAuthOk(req)) {
        res.writeHead(401, {
          'WWW-Authenticate': 'Basic realm="AutoSlide Dashboard", charset="UTF-8"',
          'Content-Type': 'text/plain; charset=utf-8',
        });
        res.end('需要登录');
        return;
      }
    }
  }
  router.handle(req, res);
});

// 直连兜底的 WebSocket 中转，挂到同一个 http server 上（走现有 Cloudflare 隧道）
require('./src/relay').attach(server);

server.listen(PORT, '0.0.0.0', () => {
  // 打印实际绑定端口（PORT=0 时由系统分配，测试据此拿到端口）
  console.log(`AutoSlide stats server listening on http://0.0.0.0:${server.address().port}`);
});
