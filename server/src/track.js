// 行动轨迹：设备周期上报定位点，按设备各存一个 JSONL，看板/Easycontrol 验口令后取用
//
// 与 stats.json 完全分开：轨迹点会持续增长，混进主库会把首页 payload 重新撑大
// （之前专门从 3.26MB 压到 707KB）。这里只在「点开某台设备的轨迹」时按需读对应文件。
//
// 坐标一律存原始 WGS-84（与谷歌地球一致）。Leaflet + OSM 用的就是 WGS-84，直接画；
// 只有拼高德链接时才临时转 GCJ-02（见 util.wgs84ToGcj02）。

const fs = require('fs');
const path = require('path');

const {
  TRACKS_DIR,
  TRACK_RETENTION_DAYS,
  TRACK_MAX_POINTS,
  LIMIT_TRACK_BODY,
  REPORT_TOKEN,
  ADMIN_TOKEN,
} = require('./config');
const { readJson, sendJson, sendHtml, sendText } = require('./http');
const { sanitizeLocation, nowCN, esc } = require('./util');
const { updateStats, touchDevice } = require('./stats');

const RETENTION_MS = TRACK_RETENTION_DAYS * 24 * 60 * 60 * 1000;

/* deviceId 直接进文件名，必须收窄成安全字符，挡掉路径穿越（../ 之类） */
function safeId(deviceId) {
  const id = String(deviceId || '').slice(0, 64);
  return /^[A-Za-z0-9_-]{1,64}$/.test(id) ? id : null;
}

function trackFile(id) {
  return path.join(TRACKS_DIR, `${id}.jsonl`);
}

function ensureDir() {
  if (!fs.existsSync(TRACKS_DIR)) fs.mkdirSync(TRACKS_DIR, { recursive: true });
}

/**
 * 追加一个轨迹点。
 * 点格式（紧凑键）：{t:时间ms, lat, lon, acc:精度m, pv:provider, mv:是否移动}
 */
function appendPoint(id, pt) {
  ensureDir();
  fs.appendFileSync(trackFile(id), JSON.stringify(pt) + '\n', 'utf8');
  // 顺手做量级控制：超出上限一截才重写（低频上报，重写不常发生，不至于每次都搬一遍）
  trimIfOversize(id);
}

/* 行数超过上限 + 一截余量时，重写文件只留最近 TRACK_MAX_POINTS 条 */
function trimIfOversize(id) {
  try {
    const file = trackFile(id);
    const lines = fs.readFileSync(file, 'utf8').split('\n').filter(Boolean);
    if (lines.length <= TRACK_MAX_POINTS + 200) return;
    const kept = lines.slice(lines.length - TRACK_MAX_POINTS);
    fs.writeFileSync(file, kept.join('\n') + '\n', 'utf8');
  } catch (e) {
    console.warn('[track] trim 失败：' + e.message);
  }
}

/* 读出某设备的轨迹点；过滤掉超期的与早于 sinceMs 的，按时间升序 */
function readPoints(id, sinceMs) {
  let lines;
  try {
    lines = fs.readFileSync(trackFile(id), 'utf8').split('\n');
  } catch (e) {
    return []; // 没有文件=没轨迹
  }
  const cutoff = Date.now() - RETENTION_MS;
  const floor = Number.isFinite(sinceMs) ? Math.max(cutoff, sinceMs) : cutoff;
  const out = [];
  for (const line of lines) {
    if (!line) continue;
    try {
      const p = JSON.parse(line);
      if (Number.isFinite(p.t) && p.t >= floor) out.push(p);
    } catch (ignored) {
      // 坏行跳过，不让一行脏数据毁掉整条轨迹
    }
  }
  out.sort((a, b) => a.t - b.t);
  return out;
}

/* 口令校验：接受 x-admin-token 头（Easycontrol 用）或 Basic Auth（浏览器用），密码都是 ADMIN_TOKEN */
function isAdmin(req) {
  if (!ADMIN_TOKEN) return false;
  if (String(req.headers['x-admin-token'] || '') === ADMIN_TOKEN) return true;
  const m = /^Basic (.+)$/.exec(String(req.headers['authorization'] || ''));
  if (m) {
    const decoded = Buffer.from(m[1], 'base64').toString('utf8');
    const pass = decoded.slice(decoded.indexOf(':') + 1);
    if (pass === ADMIN_TOKEN) return true;
  }
  return false;
}

/* ============ POST /api/track：设备上报一个定位点 ============ */
async function handleIngest(req, res) {
  if (!REPORT_TOKEN) {
    return sendJson(res, 503, { ok: false, error: 'report token not configured' });
  }
  if (String(req.headers['x-report-token'] || '') !== REPORT_TOKEN) {
    return sendJson(res, 401, { ok: false, error: 'unauthorized' });
  }
  const body = await readJson(req, LIMIT_TRACK_BODY);
  const id = safeId(body.deviceId);
  if (!id) return sendJson(res, 400, { ok: false, error: 'bad deviceId' });

  const loc = sanitizeLocation(body);
  if (!loc) return sendJson(res, 400, { ok: false, error: 'bad location' });

  const now = Date.now();
  const t = Number.isFinite(loc.time) && loc.time > 0 ? loc.time : now;
  const pt = { t, lat: loc.lat, lon: loc.lon };
  if (loc.accuracy !== undefined) pt.acc = Math.round(loc.accuracy);
  if (loc.provider) pt.pv = loc.provider;
  if (body.moving === true || body.moving === 1) pt.mv = 1;

  appendPoint(id, pt);

  // 覆盖设备的「当前位置」：轨迹最新点就是当前位置，不再另存一份单点
  await updateStats((stats) => {
    touchDevice(stats, id, {
      location: {
        lat: loc.lat,
        lon: loc.lon,
        accuracy: loc.accuracy,
        provider: loc.provider,
        time: t,
      },
      locationUpdatedAt: nowCN(),
    });
  });

  sendJson(res, 200, { ok: true });
}

/* ============ GET /api/track?deviceId=&since=：取某设备轨迹（验口令） ============ */
function handleFetch(req, res, url) {
  if (!isAdmin(req)) return sendJson(res, 403, { ok: false, error: 'forbidden' });
  const id = safeId(url.searchParams.get('deviceId'));
  if (!id) return sendJson(res, 400, { ok: false, error: 'bad deviceId' });
  const since = Number(url.searchParams.get('since'));
  const points = readPoints(id, since);
  sendJson(res, 200, { ok: true, deviceId: id, count: points.length, points });
}

/* ============ GET /track?deviceId=：轨迹地图页（Basic Auth 由中间件把关） ============ */
function handleMapPage(req, res, url) {
  const id = esc(String(url.searchParams.get('deviceId') || ''));
  sendHtml(res, mapHtml(id));
}

function mapHtml(deviceId) {
  return `<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>行动轨迹 ${deviceId}</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<style>
  html,body{margin:0;height:100%;font-family:system-ui,sans-serif}
  #map{height:100%}
  #bar{position:absolute;z-index:1000;top:10px;left:50px;right:10px;background:#fff;
       padding:8px 12px;border-radius:8px;box-shadow:0 1px 6px rgba(0,0,0,.2);font-size:13px}
  #bar input{width:230px;padding:4px 6px}
  #bar b{color:#c0562b}
  .muted{color:#888}
</style>
</head>
<body>
<div id="bar">
  设备 <input id="dev" value="${deviceId}" placeholder="deviceId">
  <button onclick="load()">加载</button>
  <span id="info" class="muted">—</span>
</div>
<div id="map"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script>
  var map = L.map('map').setView([31.23, 121.47], 12);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
  var layer = L.layerGroup().addTo(map);

  function fmt(t){ return new Date(t).toLocaleString('zh-CN'); }

  async function load(){
    var id = document.getElementById('dev').value.trim();
    var info = document.getElementById('info');
    if(!id){ info.textContent='请输入 deviceId'; return; }
    info.textContent = '加载中…';
    layer.clearLayers();
    try{
      var r = await fetch('/api/track?deviceId=' + encodeURIComponent(id), { credentials:'same-origin' });
      if(!r.ok){ info.textContent = '取轨迹失败（' + r.status + '）'; return; }
      var d = await r.json();
      var pts = d.points || [];
      if(!pts.length){ info.textContent = '这台设备暂无轨迹点'; return; }
      var latlngs = pts.map(function(p){ return [p.lat, p.lon]; });
      L.polyline(latlngs, { color:'#c0562b', weight:3, opacity:.8 }).addTo(layer);
      // 起点/终点特别标注，中间点用小圆点
      pts.forEach(function(p, i){
        var isEnd = i === 0 || i === pts.length - 1;
        var m = L.circleMarker([p.lat, p.lon], {
          radius: isEnd ? 7 : 3,
          color: i === pts.length-1 ? '#1a7f37' : (i === 0 ? '#888' : '#c0562b'),
          fillOpacity: .9
        }).addTo(layer);
        m.bindPopup(fmt(p.t) + '<br>±' + (p.acc||'?') + 'm' + (p.mv? ' · 移动':'') );
      });
      map.fitBounds(L.latLngBounds(latlngs).pad(0.15));
      info.innerHTML = '<b>' + pts.length + '</b> 个点 · ' + fmt(pts[0].t) + ' → ' + fmt(pts[pts.length-1].t);
    }catch(e){ info.textContent = '出错：' + e; }
  }
  if (document.getElementById('dev').value.trim()) load();
</script>
</body>
</html>`;
}

/* ============ 超期清理：删掉整条都过期的文件，其余留给读取时按 cutoff 过滤 ============ */
function cleanup() {
  ensureDir();
  let removed = 0;
  let files;
  try {
    files = fs.readdirSync(TRACKS_DIR).filter((f) => f.endsWith('.jsonl'));
  } catch (e) {
    return;
  }
  const cutoff = Date.now() - RETENTION_MS;
  for (const f of files) {
    const full = path.join(TRACKS_DIR, f);
    try {
      const lines = fs.readFileSync(full, 'utf8').split('\n').filter(Boolean);
      const kept = lines.filter((line) => {
        try {
          const p = JSON.parse(line);
          return Number.isFinite(p.t) && p.t >= cutoff;
        } catch (ignored) {
          return false;
        }
      });
      if (kept.length === 0) {
        fs.unlinkSync(full);
        removed++;
      } else if (kept.length !== lines.length) {
        fs.writeFileSync(full, kept.join('\n') + '\n', 'utf8');
      }
    } catch (e) {
      console.warn('[track] 清理 ' + f + ' 失败：' + e.message);
    }
  }
  if (removed) console.log(`[track] 已清除 ${removed} 台设备的整段过期轨迹`);
}

function register(router) {
  router.on('POST', '/api/track', handleIngest);
  router.on('GET', '/api/track', handleFetch);
  router.on('GET', '/track', handleMapPage);
  // 启动即清一次，之后每 6 小时一次（与聊天清理同频）
  cleanup();
  const timer = setInterval(cleanup, 6 * 60 * 60 * 1000);
  // 定时器不应阻止进程退出
  if (timer.unref) timer.unref();
}

module.exports = { register, isAdmin, readPoints, safeId };
