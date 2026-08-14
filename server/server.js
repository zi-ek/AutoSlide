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
//   src/chat.js         聊天域：/api/chat/*
//   src/uploads.js      上传域：/api/upload、/api/download、/view、/uploads
//   src/views/          页面模板与样式
//
// 接口一览：
//   POST /api/report   App 上报安装/更新事件 + 设备信息
//   POST /api/upload   App 上传每台设备的 slide_settings.xml（录制脚本同步）
//   GET  /api/stats    返回统计 JSON
//   GET  /api/uploads  返回已上传文件列表
//   GET  /api/download?deviceId=..&filename=..  下载已上传文件
//   GET  /uploads      浏览器查看已上传文件列表（含查看内容入口）
//   GET  /view?deviceId=..&filename=..  浏览器查看脚本内容
//   GET  /api/chat/*   聊天室（频道、消息、图片、公告）
//   GET  /             统计看板页面

const http = require('http');
const { PORT } = require('./src/config');
const { createRouter } = require('./src/http');

const router = createRouter();
require('./src/chat').register(router);
require('./src/stats').register(router);
require('./src/uploads').register(router);

const server = http.createServer((req, res) => router.handle(req, res));

server.listen(PORT, '0.0.0.0', () => {
  // 打印实际绑定端口（PORT=0 时由系统分配，测试据此拿到端口）
  console.log(`AutoSlide stats server listening on http://0.0.0.0:${server.address().port}`);
});
