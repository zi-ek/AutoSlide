// AutoSlide 安装统计后台（零依赖，Node.js 直接运行）
// 接口：
//   POST /api/report   App 上报安装/更新事件 + 设备信息
//   GET  /api/stats    返回统计 JSON
//   GET  /             简单的统计看板页面

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8080;
const DATA_DIR = path.join(__dirname, 'data');
const DATA_FILE = path.join(DATA_DIR, 'stats.json');

function ensureData() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
  if (!fs.existsSync(DATA_FILE)) {
    fs.writeFileSync(
      DATA_FILE,
      JSON.stringify({ install_count: 0, update_count: 0, unique_devices: 0, devices: [], last_update: null }, null, 2)
    );
  }
}

function readStats() {
  ensureData();
  try {
    return JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  } catch (e) {
    return { install_count: 0, update_count: 0, unique_devices: 0, devices: [], last_update: null };
  }
}

function writeStats(stats) {
  fs.writeFileSync(DATA_FILE, JSON.stringify(stats, null, 2));
}

// 简易写入队列，避免并发请求互相覆盖
let writeChain = Promise.resolve();
function updateStats(fn) {
  writeChain = writeChain.then(() => {
    const stats = readStats();
    fn(stats);
    writeStats(stats);
  });
  return writeChain;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (c) => {
      body += c;
      if (body.length > 64 * 1024) req.destroy();
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

function sendJson(res, code, obj) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(JSON.stringify(obj, null, 2));
}

function esc(s) {
  return String(s || '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function dashboardHtml(stats) {
  const rows = (stats.devices || [])
    .map(
      (d) => `<tr>
        <td>${esc(d.model)}</td>
        <td>${esc(d.brand)}</td>
        <td>${esc(d.android)}</td>
        <td>${esc(d.cpu)}</td>
        <td>${esc(d.appVersion)} (${esc(d.appVersionCode)})</td>
        <td>${esc(d.installs)}</td>
        <td>${esc(d.firstInstall)}</td>
        <td>${esc(d.lastSeen)}</td>
      </tr>`
    )
    .join('');
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AutoSlide 统计后台</title>
  <style>
    body { font-family: "Microsoft YaHei", sans-serif; margin: 20px; background: #f5f6fa; color: #333; }
    .cards { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 20px; }
    .card { background: #fff; border-radius: 12px; padding: 18px 24px; box-shadow: 0 2px 8px rgba(0,0,0,.06); }
    .card .num { font-size: 28px; font-weight: bold; color: #4a6cf7; }
    .card .label { font-size: 13px; color: #888; margin-top: 4px; }
    table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,.06); }
    th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #eee; font-size: 13px; }
    th { background: #4a6cf7; color: #fff; }
    tr:hover { background: #f0f4ff; }
  </style>
</head>
<body>
  <h2>AutoSlide 统计后台</h2>
  <div class="cards">
    <div class="card"><div class="num">${stats.install_count || 0}</div><div class="label">安装次数</div></div>
    <div class="card"><div class="num">${stats.update_count || 0}</div><div class="label">更新次数</div></div>
    <div class="card"><div class="num">${stats.unique_devices || 0}</div><div class="label">设备数</div></div>
    <div class="card"><div class="num">${esc(stats.last_update || '-')}</div><div class="label">最近上报</div></div>
  </div>
  <table>
    <thead><tr><th>型号</th><th>品牌</th><th>系统</th><th>CPU</th><th>应用版本</th><th>安装次数</th><th>首次安装</th><th>最近上报</th></tr></thead>
    <tbody>${rows || '<tr><td colspan="8">暂无数据</td></tr>'}</tbody>
  </table>
</body>
</html>`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  // App 上报
  if (req.method === 'POST' && url.pathname === '/api/report') {
    try {
      const raw = await readBody(req);
      const payload = JSON.parse(raw || '{}');
      const device = payload.device || {};
      const event = payload.event === 'update' ? 'update' : 'install';
      const deviceId = String(payload.deviceId || '').slice(0, 64);

      await updateStats((stats) => {
        if (event === 'install') {
          stats.install_count = (stats.install_count || 0) + 1;
        } else {
          stats.update_count = (stats.update_count || 0) + 1;
        }
        const now = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
        if (deviceId) {
          const devices = stats.devices || [];
          const idx = devices.findIndex((d) => d.deviceId === deviceId);
          const record = {
            deviceId,
            model: String(device.model || '').slice(0, 100),
            brand: String(device.brand || '').slice(0, 100),
            android: String(device.android || '').slice(0, 50),
            cpu: String(device.cpu || '').slice(0, 100),
            appVersion: String(device.appVersion || '').slice(0, 50),
            appVersionCode: Number(device.appVersionCode) || 0,
            firstInstall: idx >= 0 ? devices[idx].firstInstall : now,
            lastSeen: now,
            installs: idx >= 0 ? (devices[idx].installs || 1) + (event === 'install' ? 1 : 0) : 1,
          };
          if (idx >= 0) {
            devices[idx] = record;
          } else {
            devices.push(record);
          }
          devices.sort((a, b) => String(b.lastSeen).localeCompare(String(a.lastSeen)));
          stats.devices = devices;
          stats.unique_devices = devices.length;
        }
        stats.last_update = now;
      });

      const stats = readStats();
      return sendJson(res, 200, { ok: true, install_count: stats.install_count, unique_devices: stats.unique_devices });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 统计 JSON
  if (req.method === 'GET' && url.pathname === '/api/stats') {
    return sendJson(res, 200, readStats());
  }

  // 统计看板
  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(dashboardHtml(readStats()));
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not Found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`AutoSlide stats server listening on http://0.0.0.0:${PORT}`);
});
