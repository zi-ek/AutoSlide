// AutoSlide 安装统计后台（零依赖，Node.js 直接运行）
// 接口：
//   POST /api/report   App 上报安装/更新事件 + 设备信息
//   POST /api/upload   App 上传每台设备的 slide_settings.xml（录制脚本同步）
//   GET  /api/stats    返回统计 JSON
//   GET  /api/uploads  返回已上传文件列表
//   GET  /api/download?deviceId=..&filename=..  下载已上传文件
//   GET  /uploads      浏览器查看已上传文件列表（含查看内容入口）
//   GET  /view?deviceId=..&filename=..  浏览器查看脚本内容
//   GET  /             简单的统计看板页面

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8080;
const DATA_DIR = path.join(__dirname, 'data');
const DATA_FILE = path.join(DATA_DIR, 'stats.json');
const UPLOAD_DIR = path.join(DATA_DIR, 'uploads');
const MANIFEST_FILE = path.join(UPLOAD_DIR, 'index.json');

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

function readBody(req, maxBytes = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (c) => {
      body += c;
      if (body.length > maxBytes) req.destroy();
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

function sanitize(name, fallback) {
  const s = String(name || '').replace(/[^A-Za-z0-9._:-]/g, '_').slice(0, 100);
  return s || fallback;
}

function readManifest() {
  try {
    return JSON.parse(fs.readFileSync(MANIFEST_FILE, 'utf8'));
  } catch (e) {
    return { files: [] };
  }
}

function writeManifest(manifest) {
  fs.writeFileSync(MANIFEST_FILE, JSON.stringify(manifest, null, 2));
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
  <p><a href="/uploads">查看上传的录制脚本 →</a></p>
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

function uploadsHtml(manifest) {
  const rows = (manifest.files || [])
    .map(
      (f) => `<tr>
        <td>${esc(f.deviceName)}</td>
        <td>${esc(f.deviceId)}</td>
        <td>${esc(f.filename)}</td>
        <td>${f.size}</td>
        <td>${esc(f.updatedAt)}</td>
        <td>
          <a href="/view?deviceId=${encodeURIComponent(f.deviceId)}&filename=${encodeURIComponent(f.filename)}">查看内容</a>
          <a href="/api/download?deviceId=${encodeURIComponent(f.deviceId)}&filename=${encodeURIComponent(f.filename)}">下载</a>
        </td>
      </tr>`
    )
    .join('');
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>AutoSlide 脚本同步</title>
  <style>
    body { font-family: "Microsoft YaHei", sans-serif; margin: 20px; background: #f5f6fa; color: #333; }
    table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,.06); }
    th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #eee; font-size: 13px; }
    th { background: #4a6cf7; color: #fff; }
    tr:hover { background: #f0f4ff; }
    a { color: #4a6cf7; text-decoration: none; }
    a:hover { text-decoration: underline; }
  </style>
</head>
<body>
  <h2>AutoSlide 脚本同步</h2>
  <p><a href="/">← 返回统计看板</a></p>
  <table>
    <thead><tr><th>设备</th><th>设备ID</th><th>文件名</th><th>大小</th><th>更新时间</th><th>操作</th></tr></thead>
    <tbody>${rows || '<tr><td colspan="6">暂无上传文件</td></tr>'}</tbody>
  </table>
</body>
</html>`;
}

function viewHtml(content, filename) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${esc(filename)}</title>
  <style>
    body { font-family: "Microsoft YaHei", monospace; margin: 20px; background: #f5f6fa; color: #333; }
    pre { background: #fff; border-radius: 12px; padding: 16px; overflow: auto; box-shadow: 0 2px 8px rgba(0,0,0,.06); font-size: 12px; white-space: pre-wrap; word-break: break-all; }
    a { color: #4a6cf7; text-decoration: none; }
  </style>
</head>
<body>
  <p><a href="/uploads">← 返回文件列表</a> | <a href="javascript:location.reload()">刷新</a></p>
  <h3>${esc(filename)}</h3>
  <pre>${esc(content)}</pre>
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

  // 上传录制脚本（slide_settings.xml）
  if (req.method === 'POST' && url.pathname === '/api/upload') {
    try {
      const deviceId = sanitize(req.headers['x-device-id'], 'unknown');
      const deviceName = sanitize(req.headers['x-device-name'], 'unknown');
      const filename = sanitize(req.headers['x-filename'] || 'slide_settings.xml', 'slide_settings.xml');
      const body = await readBody(req, 2 * 1024 * 1024);

      const deviceDir = path.join(UPLOAD_DIR, deviceId);
      fs.mkdirSync(deviceDir, { recursive: true });
      fs.writeFileSync(path.join(deviceDir, filename), body);

      const now = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
      const manifest = readManifest();
      const idx = manifest.files.findIndex((f) => f.deviceId === deviceId && f.filename === filename);
      const record = {
        deviceId,
        deviceName,
        filename,
        size: Buffer.byteLength(body),
        updatedAt: now,
      };
      if (idx >= 0) {
        manifest.files[idx] = record;
      } else {
        manifest.files.push(record);
      }
      manifest.files.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
      writeManifest(manifest);

      return sendJson(res, 200, { ok: true, saved: filename });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 已上传文件列表
  if (req.method === 'GET' && url.pathname === '/api/uploads') {
    return sendJson(res, 200, readManifest());
  }

  // 浏览器查看文件列表
  if (req.method === 'GET' && url.pathname === '/uploads') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(uploadsHtml(readManifest()));
  }

  // 浏览器查看脚本内容
  if (req.method === 'GET' && url.pathname === '/view') {
    const deviceId = sanitize(url.searchParams.get('deviceId'), '');
    const filename = sanitize(url.searchParams.get('filename'), '');
    if (!deviceId || !filename) {
      return sendJson(res, 400, { ok: false, error: 'deviceId and filename required' });
    }
    const filePath = path.join(UPLOAD_DIR, deviceId, filename);
    if (!fs.existsSync(filePath)) {
      return sendJson(res, 404, { ok: false, error: 'file not found' });
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(viewHtml(fs.readFileSync(filePath, 'utf8'), filename));
  }

  // 下载已上传文件
  if (req.method === 'GET' && url.pathname === '/api/download') {
    const deviceId = sanitize(url.searchParams.get('deviceId'), '');
    const filename = sanitize(url.searchParams.get('filename'), '');
    if (!deviceId || !filename) {
      return sendJson(res, 400, { ok: false, error: 'deviceId and filename required' });
    }
    const filePath = path.join(UPLOAD_DIR, deviceId, filename);
    if (!fs.existsSync(filePath)) {
      return sendJson(res, 404, { ok: false, error: 'file not found' });
    }
    res.writeHead(200, {
      'Content-Type': 'application/xml; charset=utf-8',
      'Content-Disposition': `attachment; filename="${filename}"`,
    });
    return res.end(fs.readFileSync(filePath));
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
