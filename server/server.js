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
const https = require('https');
const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');

const PORT = process.env.PORT || 8080;
// 数据目录：默认在本文件旁边，测试时用 AUTOSLIDE_DATA_DIR 指到临时目录做隔离
const DATA_DIR = process.env.AUTOSLIDE_DATA_DIR || path.join(__dirname, 'data');
const DATA_FILE = path.join(DATA_DIR, 'stats.json');
const UPLOAD_DIR = path.join(DATA_DIR, 'uploads');
const MANIFEST_FILE = path.join(UPLOAD_DIR, 'index.json');

/* ==================== JSON 文件存储 ==================== */

/**
 * 带原子写与串行化写队列的 JSON 存储，stats / chat / manifest 三份数据共用。
 *
 * 解决三件事：
 * 1. 原子写：先写临时文件再 rename，进程在写入途中被杀（systemd 重启、断电）
 *    不会在目标文件上留下截断的半截 JSON；
 * 2. 串行化：读-改-写整体排队。目前各处 I/O 是同步的、天然不会交错，
 *    但只要有一处改成异步就会立刻出现并发覆盖，这里提前把边界立住；
 * 3. 损坏保护：解析失败时把原文件改名留档，而不是静默当成空数据继续写——
 *    旧实现在这种情况下会把历史统计整个抹掉。
 */
class JsonStore {
  constructor(file, makeDefaults) {
    this.file = file;
    this.makeDefaults = makeDefaults;
    this.queue = Promise.resolve();
  }

  read() {
    try {
      return JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (e) {
      if (e.code === 'ENOENT') {
        return this.makeDefaults();
      }
      const name = path.basename(this.file);
      const backup = `${this.file}.corrupt-${Date.now()}`;
      try {
        fs.renameSync(this.file, backup);
        console.error(`[store] ${name} 解析失败，已备份到 ${path.basename(backup)}：${e.message}`);
      } catch (renameErr) {
        console.error(`[store] ${name} 解析失败且备份失败：${renameErr.message}`);
      }
      return this.makeDefaults();
    }
  }

  async writeAtomic(data) {
    const tmp = `${this.file}.${process.pid}.tmp`;
    await fsp.mkdir(path.dirname(this.file), { recursive: true });
    await fsp.writeFile(tmp, JSON.stringify(data, null, 2), 'utf8');
    await fsp.rename(tmp, this.file);
  }

  /**
   * 排队执行一次读-改-写。
   * fn 抛异常时调用方能拿到，但队列自身保持 fulfilled——
   * 否则一次异常会让之后所有写入永久 reject，直到进程重启。
   */
  update(fn) {
    const task = this.queue.then(async () => {
      const data = this.read();
      const result = await fn(data);
      await this.writeAtomic(data);
      return result;
    });
    this.queue = task.then(
      () => {},
      () => {}
    );
    return task;
  }
}

const statsStore = new JsonStore(DATA_FILE, () => ({
  install_count: 0,
  update_count: 0,
  unique_devices: 0,
  devices: [],
  last_update: null,
}));

function readStats() {
  return dedupeDevices(statsStore.read());
}

function updateStats(fn) {
  return statsStore.update((stats) => {
    dedupeDevices(stats);
    fn(stats);
  });
}

function readBody(req, maxBytes = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let body = '';
    let settled = false;
    const fail = (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    };
    req.on('data', (c) => {
      if (settled) return;
      body += c;
      if (body.length > maxBytes) {
        // 必须显式 reject。旧实现只 destroy 不 reject：destroy 之后 'end' 不再触发，
        // 这个 Promise 永远挂起，上游的 await 不返回，连接与内存都释放不掉。
        fail(new Error(`request body too large (> ${maxBytes} bytes)`));
        req.destroy();
      }
    });
    req.on('end', () => {
      if (!settled) {
        settled = true;
        resolve(body);
      }
    });
    req.on('error', fail);
    req.on('aborted', () => fail(new Error('request aborted')));
  });
}

function sanitize(name, fallback) {
  const s = String(name || '').replace(/[^A-Za-z0-9._:-]/g, '_').slice(0, 100);
  return s || fallback;
}

/**
 * 拼接并校验路径，确保结果落在 baseDir 之内。
 *
 * 只靠 sanitize() 的字符过滤挡不住穿越：它保留了 '.'，所以 '..' 整段能原样通过，
 * path.join(UPLOAD_DIR, '..', 'chat.json') 就跳到了 data/ 下。
 * 这里改成解析成绝对路径后校验前缀，从机制上堵死。
 */
function safeJoin(baseDir, ...segments) {
  const base = path.resolve(baseDir);
  const target = path.resolve(base, ...segments);
  if (target !== base && !target.startsWith(base + path.sep)) {
    const err = new Error('path escapes base directory');
    err.code = 'EPATHESCAPE';
    throw err;
  }
  return target;
}

/**
 * 解析上传文件的真实路径，越界或不存在一律返回 null（对外统一表现为 404，不泄露原因）
 */
function resolveUploadPath(deviceId, filename) {
  let filePath;
  try {
    filePath = safeJoin(UPLOAD_DIR, deviceId, filename);
  } catch (e) {
    return null;
  }
  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    return null;
  }
  return filePath;
}

const manifestStore = new JsonStore(MANIFEST_FILE, () => ({ files: [] }));

function readManifest() {
  return manifestStore.read();
}

function updateManifest(fn) {
  return manifestStore.update(fn);
}

/* IP 归属地查询缓存：ip -> 归属地 */
const ipLocationCache = new Map();

/**
 * 查询 IP 归属地（免费接口，失败或超时返回空字符串，不影响主流程）
 */
function lookupIpLocation(ip) {
  if (!ip) return Promise.resolve('');
  // 内网地址无需查询
  if (/^(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip)) {
    return Promise.resolve('内网');
  }
  if (ipLocationCache.has(ip)) {
    return Promise.resolve(ipLocationCache.get(ip));
  }
  return new Promise((resolve) => {
    const req = https.get(
      `https://opendata.baidu.com/api.php?query=${encodeURIComponent(ip)}&co=&resource_id=6006&oe=utf8`,
      { headers: { 'User-Agent': 'Mozilla/5.0' }, timeout: 3000 },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          try {
            const j = JSON.parse(data);
            const loc = String((j.data && j.data[0] && j.data[0].location) || '')
              .replace(/^[，,\s]+/, '')
              .trim();
            ipLocationCache.set(ip, loc);
            resolve(loc);
          } catch (e) {
            resolve('');
          }
        });
      }
    );
    req.on('timeout', () => req.destroy());
    req.on('error', () => resolve(''));
  });
}

/* ==================== 聊天（频道）数据 ==================== */
const CHAT_FILE = path.join(DATA_DIR, 'chat.json');
const CHAT_MESSAGE_LIMIT = 300;

const chatStore = new JsonStore(CHAT_FILE, () => ({ channels: [] }));

/* 只读场景 */
function readChat() {
  return chatStore.read();
}

/* 读-改-写场景：走队列 + 原子写 */
function updateChat(fn) {
  return chatStore.update(fn);
}

/* 聊天图片目录 */
const CHAT_IMAGE_DIR = path.join(DATA_DIR, 'chat-images');

function genChannelCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let code = '';
  for (let i = 0; i < 6; i++) code += chars[Math.floor(Math.random() * chars.length)];
  return code;
}

function chatChannelModel(ch, deviceId) {
  const members = (ch.members || []).map((m) => ({
    deviceId: m.deviceId,
    name: m.name,
    joinedAt: m.joinedAt,
  }));
  const last = (ch.messages || []).slice(-1)[0];
  return {
    id: ch.id,
    code: ch.code,
    name: ch.name,
    creatorId: ch.creatorId,
    createdAt: ch.createdAt,
    members,
    joined: members.some((m) => m.deviceId === deviceId),
    lastMessageText: last ? (last.type === 'image' ? '[图片]' : last.text) : '',
    lastMessageTime: last ? last.time : '',
  };
}
/**
 * 设备去重：同一设备 ID 视为同一台设备（ANDROID_ID 固定签名下保持稳定）。
 * 只按 deviceId 合并，绝不按品牌/型号合并。
 */
function dedupeDevices(stats) {
  const devices = stats.devices || [];
  const map = new Map();
  for (const d of devices) {
    const idKey = 'id:' + d.deviceId;
    const existing = map.get(idKey);
    if (!existing) {
      map.set(idKey, d);
      continue;
    }
    // 合并：安装次数相加，首次安装取更早，最近上报取更晚，设备ID保留安装次数多的那一个
    const installs = (existing.installs || 0) + (d.installs || 0);
    const prefer = (d.installs || 0) > (existing.installs || 0) ? d : existing;
    const merged = { ...prefer, installs };
    if (String(d.firstInstall || '').localeCompare(String(existing.firstInstall || '')) < 0) {
      merged.firstInstall = d.firstInstall;
    }
    if (String(d.lastSeen || '').localeCompare(String(existing.lastSeen || '')) > 0) {
      merged.lastSeen = d.lastSeen;
    }
    map.set(idKey, merged);
  }
  stats.devices = [...map.values()].sort((a, b) => String(b.lastSeen).localeCompare(String(a.lastSeen)));
  stats.unique_devices = stats.devices.length;
  return stats;
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

function fmtBytes(n) {
  const num = Number(n) || 0;
  if (num < 1024) return num + ' B';
  if (num < 1024 * 1024) return (num / 1024).toFixed(1) + ' KB';
  return (num / (1024 * 1024)).toFixed(1) + ' MB';
}

// 共享设计系统：亮色 "机房监控台" 风格，贴合家庭网络实验室的气质。
function baseStyles() {
  return `
    :root {
      --bg: #f5f4ee;
      --panel: #ffffff;
      --panel-alt: #faf9f4;
      --border: #e5e2d8;
      --border-soft: #ece9de;
      --text: #23221f;
      --text-dim: #6b6a63;
      --text-faint: #9c9a90;
      --clay: #d97757;
      --sage: #7a8f6c;
      --slate: #6b84a3;
      --green: #5b9279;
      --mono: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
      --serif: 'Source Serif 4', Georgia, serif;
      --sans: 'Inter', 'Microsoft YaHei', -apple-system, sans-serif;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background:
        radial-gradient(ellipse 900px 500px at 15% -10%, rgba(217,119,87,.06), transparent 60%),
        radial-gradient(ellipse 700px 400px at 100% 0%, rgba(107,132,163,.05), transparent 60%),
        var(--bg);
      color: var(--text);
      font-family: var(--sans);
      padding: 28px 20px 60px;
    }
    .wrap { max-width: 1640px; margin: 0 auto; }
    a { color: var(--clay); text-decoration: none; }
    a:hover { text-decoration: underline; }
    .topbar {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;
      margin-bottom: 26px;
      padding-bottom: 18px;
      border-bottom: 1px solid var(--border);
    }
    .eyebrow {
      font-family: var(--mono);
      font-size: 11px;
      letter-spacing: .18em;
      color: var(--text-faint);
      text-transform: uppercase;
      margin: 0 0 6px;
    }
    h1 { font-family: var(--serif); font-size: 27px; font-weight: 600; margin: 0; letter-spacing: -.01em; color: var(--text); }
    .breadcrumb { font-family: var(--mono); font-size: 12px; color: var(--text-dim); }
    .live {
      display: flex;
      align-items: center;
      gap: 8px;
      font-family: var(--mono);
      font-size: 11px;
      color: var(--text-dim);
      letter-spacing: .08em;
    }
    .dot {
      width: 7px; height: 7px; border-radius: 50%;
      background: var(--green);
      box-shadow: 0 0 0 0 rgba(91,146,121,.6);
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0% { box-shadow: 0 0 0 0 rgba(91,146,121,.45); }
      70% { box-shadow: 0 0 0 6px rgba(91,146,121,0); }
      100% { box-shadow: 0 0 0 0 rgba(91,146,121,0); }
    }
    @media (prefers-reduced-motion: reduce) { .dot { animation: none; } }
    .cards {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 12px;
      margin-bottom: 28px;
    }
    .card {
      background: var(--panel);
      border: 1px solid var(--border);
      border-left: 2px solid var(--accent, var(--clay));
      border-radius: 8px;
      padding: 16px 18px;
      box-shadow: 0 1px 2px rgba(35,34,31,.03);
    }
    .card .num {
      font-family: var(--mono);
      font-size: 26px;
      font-weight: 600;
      color: var(--text);
      line-height: 1;
    }
    .card .label {
      font-size: 11px;
      letter-spacing: .1em;
      text-transform: uppercase;
      color: var(--text-faint);
      margin-top: 8px;
    }
    .section-label {
      font-family: var(--mono);
      font-size: 11px;
      letter-spacing: .14em;
      text-transform: uppercase;
      color: var(--text-faint);
      margin: 0 0 10px 2px;
    }
    .panel {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 10px;
      overflow: hidden;
      box-shadow: 0 1px 2px rgba(35,34,31,.03);
    }
    .table-wrap { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; min-width: 720px; }
    th, td {
      padding: 11px 14px;
      text-align: left;
      font-size: 12.5px;
      border-bottom: 1px solid var(--border-soft);
      white-space: nowrap;
    }
    th {
      background: var(--panel-alt);
      color: var(--text-faint);
      font-family: var(--mono);
      font-weight: 500;
      font-size: 10.5px;
      letter-spacing: .09em;
      text-transform: uppercase;
    }
    td { color: var(--text-dim); font-family: var(--mono); }
    td.strong { color: var(--text); }
    td .ip-loc { margin-top: 2px; color: var(--text-faint); font-size: 10.5px; }
    tbody tr:last-child td { border-bottom: none; }
    tbody tr:hover td { background: rgba(217,119,87,.06); color: var(--text); }
    .empty { color: var(--text-faint); font-style: normal; text-align: center; padding: 32px 0 !important; }
    .actions a {
      font-family: var(--mono);
      font-size: 11px;
      margin-right: 14px;
      color: var(--clay);
    }
    .actions a.dl { color: var(--slate); }
    .top-links { margin-bottom: 18px; }
    @media (max-width: 640px) {
      body { padding: 20px 14px 40px; }
      .cards { grid-template-columns: repeat(2, 1fr); }
      .topbar { align-items: flex-start; }
      h1 { font-size: 20px; }
    }
  `;
}

function pageShell({ title, eyebrow, heading, headerRight, breadcrumb, body }) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${esc(title)}</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500;600&family=Source+Serif+4:wght@400;600&display=swap" rel="stylesheet">
  <style>${baseStyles()}</style>
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <div>
        <p class="eyebrow">${esc(eyebrow)}</p>
        ${breadcrumb ? `<p class="breadcrumb">${breadcrumb}</p>` : ''}
        <h1>${esc(heading)}</h1>
      </div>
      ${headerRight || ''}
    </div>
    ${body}
  </div>
</body>
</html>`;
}

function dashboardHtml(stats) {
  const ann = (readChat().announcement) || { title: '公告栏', content: '', updatedAt: '' };
  const rows = (stats.devices || [])
    .map(
      (d) => `<tr>
        <td class="strong">${esc(d.brand)}</td>
        <td>${esc(d.model)}</td>
        <td>${esc(d.deviceId)}</td>
        <td>${esc(d.android)}</td>
        <td>${esc(d.cpu)}</td>
        <td>${esc(d.appVersion)} <span style="color:var(--text-faint)">(${esc(d.appVersionCode)})</span></td>
        <td class="strong">${esc(d.installs)}</td>
        <td>${esc(d.firstInstall)}</td>
        <td>${esc(d.lastSeen)}</td>
        <td>${esc(d.ip || '-')}${d.ipLoc ? `<div class="ip-loc">${esc(d.ipLoc)}</div>` : ''}</td>
      </tr>`
    )
    .join('');

  const headerRight = `<div class="live"><span class="dot"></span>LIVE · 最近上报 ${esc(stats.last_update || '-')}</div>`;

  const body = `
    <div class="top-links"><a href="/uploads">查看上传的录制脚本 →</a></div>
    <div class="panel" style="padding:16px;margin-bottom:18px;">
      <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;">
        <b style="color:var(--text);">公告栏</b>
        <a href="#" onclick="document.getElementById('announceEdit').style.display='block';return false;">编辑</a>
      </div>
      <p style="margin:8px 0 0;color:var(--text-dim);white-space:pre-wrap;word-break:break-word;">${esc(ann.content || '暂无公告')}</p>
      <form id="announceEdit" method="post" action="/api/chat/announcement" style="display:none;margin-top:12px;">
        <input name="title" value="${esc(ann.title || '公告栏')}" placeholder="公告标题" style="width:100%;box-sizing:border-box;padding:8px 10px;margin-bottom:8px;border:1px solid var(--border);border-radius:8px;background:var(--panel-alt);color:var(--text);font-size:13px;" />
        <textarea name="content" rows="4" placeholder="公告内容" style="width:100%;box-sizing:border-box;padding:8px 10px;margin-bottom:8px;border:1px solid var(--border);border-radius:8px;background:var(--panel-alt);color:var(--text);font-size:13px;resize:vertical;">${esc(ann.content || '')}</textarea>
        <button type="submit" style="padding:8px 18px;border:none;border-radius:8px;background:var(--clay);color:#fff;font-size:13px;cursor:pointer;">保存公告</button>
        <span style="margin-left:10px;font-size:11px;color:var(--text-faint);">更新于 ${esc(ann.updatedAt || '-')}</span>
      </form>
    </div>
    <div class="cards">
      <div class="card" style="--accent: var(--clay)"><div class="num">${stats.install_count || 0}</div><div class="label">安装次数</div></div>
      <div class="card" style="--accent: var(--sage)"><div class="num">${stats.update_count || 0}</div><div class="label">更新次数</div></div>
      <div class="card" style="--accent: var(--slate)"><div class="num">${stats.unique_devices || 0}</div><div class="label">设备数</div></div>
      <div class="card" style="--accent: var(--green)"><div class="num" style="font-size:15px;">${esc(stats.last_update || '-')}</div><div class="label">最近上报</div></div>
    </div>
    <p class="section-label">设备日志 · Device Log</p>
    <div class="panel table-wrap">
      <table>
        <thead><tr><th>品牌</th><th>型号</th><th>设备 ID</th><th>系统版本</th><th>CPU</th><th>应用版本</th><th>安装次数</th><th>首次安装</th><th>最近上报</th><th>设备 IP / 归属地</th></tr></thead>
        <tbody>${rows || '<tr><td class="empty" colspan="10">暂无数据</td></tr>'}</tbody>
      </table>
    </div>
  `;

  return pageShell({
    title: 'AutoSlide 统计后台',
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '统计后台',
    headerRight,
    body,
  });
}

function uploadsHtml(manifest) {
  const rows = (manifest.files || [])
    .map(
      (f) => `<tr>
        <td class="strong">${esc(f.deviceName)}</td>
        <td>${esc(f.deviceId)}</td>
        <td>${esc(f.filename)}</td>
        <td>${esc(fmtBytes(f.size))}</td>
        <td>${esc(f.updatedAt)}</td>
        <td class="actions">
          <a href="/view?deviceId=${encodeURIComponent(f.deviceId)}&filename=${encodeURIComponent(f.filename)}">查看内容</a>
          <a class="dl" href="/api/download?deviceId=${encodeURIComponent(f.deviceId)}&filename=${encodeURIComponent(f.filename)}">下载</a>
        </td>
      </tr>`
    )
    .join('');

  const body = `
    <p class="section-label">脚本同步 · ${(manifest.files || []).length} 个文件</p>
    <div class="panel table-wrap">
      <table>
        <thead><tr><th>设备</th><th>设备 ID</th><th>文件名</th><th>大小</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>${rows || '<tr><td class="empty" colspan="6">暂无上传文件</td></tr>'}</tbody>
      </table>
    </div>
  `;

  return pageShell({
    title: 'AutoSlide 脚本同步',
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '脚本同步',
    breadcrumb: '<a href="/">← 返回统计看板</a>',
    body,
  });
}

function viewHtml(content, filename) {
  const body = `
    <p class="section-label">脚本内容 · ${esc(filename)}</p>
    <div class="panel" style="padding: 4px 0;">
      <div style="display:flex;align-items:center;justify-content:space-between;padding:10px 16px;border-bottom:1px solid var(--border-soft);">
        <span style="font-family:var(--mono);font-size:11px;color:var(--text-dim);">${esc(filename)}</span>
        <a href="javascript:location.reload()" style="font-family:var(--mono);font-size:11px;">刷新</a>
      </div>
      <pre style="margin:0;padding:16px;overflow:auto;font-family:var(--mono);font-size:12px;line-height:1.6;color:var(--text-dim);white-space:pre-wrap;word-break:break-all;">${esc(content)}</pre>
    </div>
  `;

  return pageShell({
    title: filename,
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '脚本内容',
    breadcrumb: '<a href="/uploads">← 返回文件列表</a>',
    body,
  });
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
      // 客户端 IP：优先 Cloudflare 透传的真实 IP
      const clientIp = String(
        req.headers['cf-connecting-ip'] ||
          String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() ||
          req.socket.remoteAddress ||
          ''
      ).slice(0, 64);

      const ipLoc = await lookupIpLocation(clientIp);

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
          const incoming = {
            deviceId,
            model: String(device.model || '').slice(0, 100),
            brand: String(device.brand || '').slice(0, 100),
            android: String(device.android || '').slice(0, 50),
            cpu: String(device.cpu || '').slice(0, 100),
            appVersion: String(device.appVersion || '').slice(0, 50),
            appVersionCode: Number(device.appVersionCode) || 0,
            lastSeen: now,
            installs: event === 'install' ? 1 : 0,
            ip: clientIp,
            ipLoc,
          };
          if (idx >= 0) {
            const d = devices[idx];
            incoming.firstInstall = d.firstInstall;
            incoming.installs = (d.installs || 0) + (event === 'install' ? 1 : 0);
            devices[idx] = { ...d, ...incoming };
          } else {
            incoming.firstInstall = now;
            devices.push(incoming);
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

  // 聊天：公告（App 反馈公告栏读取）
  if (req.method === 'GET' && url.pathname === '/api/chat/announcement') {
    const chat = readChat();
    return sendJson(res, 200, {
      ok: true,
      announcement: chat.announcement || { title: '公告栏', content: '', updatedAt: '' },
    });
  }

  // 聊天：保存公告（统计后台页面表单提交）
  if (req.method === 'POST' && url.pathname === '/api/chat/announcement') {
    try {
      const raw = await readBody(req, 64 * 1024);
      const ct = String(req.headers['content-type'] || '');
      let title = '';
      let content = '';
      if (ct.includes('application/json')) {
        const p = JSON.parse(raw || '{}');
        title = String(p.title || '').trim().slice(0, 50);
        content = String(p.content || '').trim().slice(0, 2000);
      } else {
        const p = new URLSearchParams(raw);
        title = String(p.get('title') || '').trim().slice(0, 50);
        content = String(p.get('content') || '').trim().slice(0, 2000);
      }
      const announcement = await updateChat((chat) => {
        chat.announcement = {
          title: title || '公告栏',
          content,
          updatedAt: new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }),
        };
        return chat.announcement;
      });
      return sendJson(res, 200, { ok: true, announcement });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 聊天：创建频道
  if (req.method === 'POST' && url.pathname === '/api/chat/create') {
    try {
      const payload = JSON.parse((await readBody(req)) || '{}');
      const name = String(payload.name || '').trim().slice(0, 50);
      const deviceId = String(payload.deviceId || '').slice(0, 64);
      const deviceName = String(payload.deviceName || '').slice(0, 50);
      if (!name || !deviceId) {
        return sendJson(res, 400, { ok: false, error: 'name and deviceId required' });
      }
      const channel = await updateChat((chat) => {
        let code = genChannelCode();
        while (chat.channels.some((c) => c.code === code)) code = genChannelCode();
        const now = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
        const created = {
          id: 'ch_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8),
          code,
          name,
          creatorId: deviceId,
          createdAt: now,
          members: [{ deviceId, name: deviceName || deviceId, joinedAt: now }],
          messages: [],
          lastSeq: 0,
        };
        chat.channels.push(created);
        return created;
      });
      return sendJson(res, 200, { ok: true, channel: chatChannelModel(channel, deviceId) });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 聊天：加入频道
  if (req.method === 'POST' && url.pathname === '/api/chat/join') {
    try {
      const payload = JSON.parse((await readBody(req)) || '{}');
      const channelId = String(payload.channelId || '').slice(0, 80);
      const deviceId = String(payload.deviceId || '').slice(0, 64);
      const deviceName = String(payload.deviceName || '').slice(0, 50);
      if (!channelId || !deviceId) {
        return sendJson(res, 400, { ok: false, error: 'channelId and deviceId required' });
      }
      const joined = await updateChat((chat) => {
        const ch = chat.channels.find((c) => c.id === channelId);
        if (!ch) return null;
        const now = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
        if (!(ch.members || []).some((m) => m.deviceId === deviceId)) {
          ch.members.push({ deviceId, name: deviceName || deviceId, joinedAt: now });
        }
        return ch;
      });
      if (!joined) return sendJson(res, 404, { ok: false, error: 'channel not found' });
      return sendJson(res, 200, { ok: true, channel: chatChannelModel(joined, deviceId) });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 聊天：全部公开频道列表（无需邀请码，点开即加入）
  if (req.method === 'GET' && url.pathname === '/api/chat/channels') {
    const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
    const chat = readChat();
    const channels = chat.channels
      .map((c) => chatChannelModel(c, deviceId))
      .sort((a, b) =>
        String(b.lastMessageTime || b.createdAt).localeCompare(String(a.lastMessageTime || a.createdAt))
      );
    return sendJson(res, 200, { ok: true, channels });
  }

  // 聊天：频道详情（成员列表）
  if (req.method === 'GET' && url.pathname === '/api/chat/channel') {
    const channelId = String(url.searchParams.get('id') || '').slice(0, 80);
    const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
    const chat = readChat();
    const ch = chat.channels.find((c) => c.id === channelId);
    if (!ch) return sendJson(res, 404, { ok: false, error: 'channel not found' });
    return sendJson(res, 200, { ok: true, channel: chatChannelModel(ch, deviceId) });
  }

  // 聊天：拉取消息（after=上次消息 seq）
  if (req.method === 'GET' && url.pathname === '/api/chat/messages') {
    const channelId = String(url.searchParams.get('channelId') || '').slice(0, 80);
    const after = parseInt(url.searchParams.get('after') || '0', 10) || 0;
    const chat = readChat();
    const ch = chat.channels.find((c) => c.id === channelId);
    if (!ch) return sendJson(res, 404, { ok: false, error: 'channel not found' });
    const messages = (ch.messages || []).filter((m) => m.seq > after);
    return sendJson(res, 200, { ok: true, messages });
  }

  // 聊天：读取图片消息
  if (req.method === 'GET' && url.pathname === '/api/chat/image') {
    const file = sanitize(url.searchParams.get('file') || '', '');
    if (!file) return sendJson(res, 400, { ok: false, error: 'file required' });
    let p;
    try {
      p = safeJoin(CHAT_IMAGE_DIR, file);
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: 'invalid file' });
    }
    if (!fs.existsSync(p) || !fs.statSync(p).isFile()) {
      return sendJson(res, 404, { ok: false, error: 'image not found' });
    }
    const type = file.endsWith('.png') ? 'image/png'
      : file.endsWith('.gif') ? 'image/gif'
      : file.endsWith('.webp') ? 'image/webp'
      : 'image/jpeg';
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'public, max-age=86400' });
    return fs.createReadStream(p).pipe(res);
  }

  // 聊天：发送消息
  if (req.method === 'POST' && url.pathname === '/api/chat/send') {
    try {
      const payload = JSON.parse((await readBody(req, 8 * 1024 * 1024)) || '{}');
      const channelId = String(payload.channelId || '').slice(0, 80);
      const deviceId = String(payload.deviceId || '').slice(0, 64);
      const deviceName = String(payload.deviceName || '').slice(0, 50);
      const text = String(payload.text || '').trim().slice(0, 2000);
      const imageRaw = String(payload.image || '');
      if (!channelId || !deviceId || (!text && !imageRaw)) {
        return sendJson(res, 400, { ok: false, error: 'channelId, deviceId and text/image required' });
      }
      // 图片先解码校验再进写队列：校验失败时不该已经消耗掉一个 seq
      let imageBuf = null;
      let imageExt = 'png';
      if (imageRaw) {
        const m = imageRaw.match(/^data:image\/(png|jpe?g|gif|webp);base64,(.+)$/i);
        imageBuf = Buffer.from(m ? m[2] : imageRaw, 'base64');
        if (!imageBuf.length || imageBuf.length > 6 * 1024 * 1024) {
          return sendJson(res, 400, { ok: false, error: 'image invalid or too large' });
        }
        imageExt = m ? (m[1].toLowerCase() === 'jpeg' ? 'jpg' : m[1].toLowerCase()) : 'png';
      }
      const msg = await updateChat((chat) => {
        const ch = chat.channels.find((c) => c.id === channelId);
        if (!ch) return null;
        ch.lastSeq = (ch.lastSeq || 0) + 1;
        let imagePath = '';
        if (imageBuf) {
          fs.mkdirSync(CHAT_IMAGE_DIR, { recursive: true });
          const file = ch.id + '_' + ch.lastSeq + '.' + imageExt;
          fs.writeFileSync(safeJoin(CHAT_IMAGE_DIR, file), imageBuf);
          imagePath = '/api/chat/image?file=' + encodeURIComponent(file);
        }
        const created = {
          seq: ch.lastSeq,
          channelId,
          deviceId,
          name: deviceName || deviceId,
          text,
          type: imageBuf ? 'image' : 'text',
          image: imagePath,
          time: new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }),
        };
        ch.messages = ch.messages || [];
        ch.messages.push(created);
        if (ch.messages.length > CHAT_MESSAGE_LIMIT) {
          ch.messages = ch.messages.slice(-CHAT_MESSAGE_LIMIT);
        }
        return created;
      });
      if (!msg) return sendJson(res, 404, { ok: false, error: 'channel not found' });
      return sendJson(res, 200, { ok: true, message: msg });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 聊天：退出频道
  if (req.method === 'POST' && url.pathname === '/api/chat/leave') {
    try {
      const payload = JSON.parse((await readBody(req)) || '{}');
      const channelId = String(payload.channelId || '').slice(0, 80);
      const deviceId = String(payload.deviceId || '').slice(0, 64);
      const left = await updateChat((chat) => {
        const ch = chat.channels.find((c) => c.id === channelId);
        if (!ch) return false;
        ch.members = (ch.members || []).filter((m) => m.deviceId !== deviceId);
        return true;
      });
      if (!left) return sendJson(res, 404, { ok: false, error: 'channel not found' });
      return sendJson(res, 200, { ok: true });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 聊天：删除频道（仅创建者）
  if (req.method === 'POST' && url.pathname === '/api/chat/delete') {
    try {
      const payload = JSON.parse((await readBody(req)) || '{}');
      const channelId = String(payload.channelId || '').slice(0, 80);
      const deviceId = String(payload.deviceId || '').slice(0, 64);
      const outcome = await updateChat((chat) => {
        const idx = chat.channels.findIndex((c) => c.id === channelId);
        if (idx < 0) return { status: 404, error: 'channel not found' };
        if (chat.channels[idx].creatorId !== deviceId) {
          return { status: 403, error: 'only creator can delete' };
        }
        return { status: 200, removed: chat.channels.splice(idx, 1)[0] };
      });
      if (outcome.status !== 200) {
        return sendJson(res, outcome.status, { ok: false, error: outcome.error });
      }
      // 清理该频道已上传的图片
      if (fs.existsSync(CHAT_IMAGE_DIR)) {
        for (const msg of outcome.removed.messages || []) {
          if (msg.type === 'image' && msg.image) {
            const file = decodeURIComponent(String(msg.image).split('file=')[1] || '');
            if (!file) continue;
            try {
              fs.unlinkSync(safeJoin(CHAT_IMAGE_DIR, file));
            } catch (e) {
              /* 文件已不在或路径异常，忽略 */
            }
          }
        }
      }
      return sendJson(res, 200, { ok: true });
    } catch (e) {
      return sendJson(res, 400, { ok: false, error: String(e.message || e) });
    }
  }

  // 上传录制脚本（slide_settings.xml）
  if (req.method === 'POST' && url.pathname === '/api/upload') {
    try {
      const deviceId = sanitize(req.headers['x-device-id'], 'unknown');
      const deviceName = sanitize(req.headers['x-device-name'], 'unknown');
      const filename = sanitize(req.headers['x-filename'] || 'slide_settings.xml', 'slide_settings.xml');
      const body = await readBody(req, 2 * 1024 * 1024);

      const targetFile = safeJoin(UPLOAD_DIR, deviceId, filename);
      fs.mkdirSync(path.dirname(targetFile), { recursive: true });
      fs.writeFileSync(targetFile, body);

      const now = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
      const record = {
        deviceId,
        deviceName,
        filename,
        size: Buffer.byteLength(body),
        updatedAt: now,
      };
      await updateManifest((manifest) => {
        manifest.files = manifest.files || [];
        const idx = manifest.files.findIndex((f) => f.deviceId === deviceId && f.filename === filename);
        if (idx >= 0) {
          manifest.files[idx] = record;
        } else {
          manifest.files.push(record);
        }
        manifest.files.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
      });

      // 上传脚本说明设备在线：顺带刷新统计里的 IP 与最近上报时间
      const clientIp = String(
        req.headers['cf-connecting-ip'] ||
          String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() ||
          req.socket.remoteAddress ||
          ''
      ).slice(0, 64);
      const ipLoc = await lookupIpLocation(clientIp);
      await updateStats((stats) => {
        const devices = stats.devices || [];
        const idx = devices.findIndex((d) => d.deviceId === deviceId);
        if (idx >= 0) {
          devices[idx].ipLoc = ipLoc;
          devices[idx].ip = clientIp;
          devices[idx].lastSeen = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
        }
      });

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
    const filePath = resolveUploadPath(deviceId, filename);
    if (!filePath) {
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
    const filePath = resolveUploadPath(deviceId, filename);
    if (!filePath) {
      return sendJson(res, 404, { ok: false, error: 'file not found' });
    }
    res.writeHead(200, {
      'Content-Type': 'application/xml; charset=utf-8',
      'Content-Disposition': `attachment; filename="${filename}"`,
    });
    return res.end(fs.readFileSync(filePath));
  }

  // 统计看板（根路径和 /stats 均可访问）
  if (
    req.method === 'GET' &&
    (url.pathname === '/' ||
      url.pathname === '/index.html' ||
      url.pathname === '/stats' ||
      url.pathname === '/stats/')
  ) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(dashboardHtml(readStats()));
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not Found');
});

server.listen(PORT, '0.0.0.0', () => {
  // 打印实际绑定端口（PORT=0 时由系统分配，测试据此拿到端口）
  console.log(`AutoSlide stats server listening on http://0.0.0.0:${server.address().port}`);
});
