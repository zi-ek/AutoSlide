// 行动轨迹：设备周期上报定位点，按设备各存一个 JSONL，看板/Easycontrol 验口令后取用
//
// 与 stats.json 完全分开：轨迹点会持续增长，混进主库会把首页 payload 重新撑大
// （之前专门从 3.26MB 压到 707KB）。这里只在「点开某台设备的轨迹」时按需读对应文件。
//
// 坐标一律存原始 WGS-84（与谷歌地球一致）。地图页用高德瓦片（GCJ-02），
// 前端绘制前临时转 GCJ-02（见 mapHtml 内联的 wgs84ToGcj02）。

const fs = require('fs');
const path = require('path');

const {
  TRACKS_DIR,
  TRACK_RETENTION_DAYS,
  TRACK_MAX_POINTS,
  LIMIT_TRACK_BODY,
  LIMIT_TRACK_BATCH_BODY,
  REPORT_TOKEN,
  ADMIN_TOKEN,
} = require('./config');
const { readJson, sendJson, sendHtml, sendText } = require('./http');
const { sanitizeLocation, nowCN, esc } = require('./util');
const { readStats, updateStats, touchDevice } = require('./stats');

const { resolveDevice, trackIdFor, safeTrackId } = require('./identity');

const RETENTION_MS = TRACK_RETENTION_DAYS * 24 * 60 * 60 * 1000;

/* deviceId 直接进文件名，必须收窄成安全字符，挡掉路径穿越（../ 之类） */
function safeId(deviceId) {
  const id = String(deviceId || '').slice(0, 64);
  return /^[A-Za-z0-9_-]{1,64}$/.test(id) ? id : null;
}

/* ro.serialno 清洗，与 commands.js 的 cleanSerial 保持一致 */
function cleanSerial(value) {
  const text = String(value || '').trim();
  return /^[A-Za-z0-9._:-]{1,64}$/.test(text) ? text : '';
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

/**
 * 批量追加多个轨迹点（离线积压一次冲刷）。
 * 一次写入整批、只 trim 一次，比逐点 appendPoint 少很多次 IO。
 */
function appendPoints(id, pts) {
  if (!pts.length) return;
  ensureDir();
  const text = pts.map((p) => JSON.stringify(p)).join('\n') + '\n';
  fs.appendFileSync(trackFile(id), text, 'utf8');
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
  // 客户端「先发后删」在崩溃/断线时可能把同一批重发一次，按 (时间,经纬度) 去重，
  // 免得轨迹上落一串完全重合的点。用 Set 认键，不依赖相同点是否相邻。
  const seen = new Set();
  const deduped = [];
  for (const p of out) {
    const key = `${p.t},${p.lat},${p.lon}`;
    if (seen.has(key)) continue;
    seen.add(key);
    deduped.push(p);
  }
  return deduped;
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
  const serial = cleanSerial(body.serial);
  const legacyId = safeId(body.deviceId);
  if (!serial && !legacyId) return sendJson(res, 400, { ok: false, error: 'deviceId or serial required' });

  const loc = sanitizeLocation(body);
  if (!loc) return sendJson(res, 400, { ok: false, error: 'bad location' });

  const now = Date.now();
  const t = Number.isFinite(loc.time) && loc.time > 0 ? loc.time : now;
  const pt = { t, lat: loc.lat, lon: loc.lon };
  if (loc.accuracy !== undefined) pt.acc = Math.round(loc.accuracy);
  if (loc.provider) pt.pv = loc.provider;
  if (body.moving === true || body.moving === 1) pt.mv = 1;

  const { id, deviceId } = await resolveTrackIdentity(serial, legacyId);
  appendPoint(id, pt);

  // 覆盖设备的「当前位置」：轨迹最新点就是当前位置，不再另存一份单点
  await updateStats((stats) => {
    touchDevice(stats, deviceId, {
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

/* ============ POST /api/track/batch：设备一次上报多个点（离线积压冲刷用） ============ */
async function handleIngestBatch(req, res) {
  if (!REPORT_TOKEN) {
    return sendJson(res, 503, { ok: false, error: 'report token not configured' });
  }
  if (String(req.headers['x-report-token'] || '') !== REPORT_TOKEN) {
    return sendJson(res, 401, { ok: false, error: 'unauthorized' });
  }
  const body = await readJson(req, LIMIT_TRACK_BATCH_BODY);
  const serial = cleanSerial(body.serial);
  const legacyId = safeId(body.deviceId);
  if (!serial && !legacyId) return sendJson(res, 400, { ok: false, error: 'deviceId or serial required' });

  const raw = Array.isArray(body.points) ? body.points : null;
  if (!raw) return sendJson(res, 400, { ok: false, error: 'points required' });

  const now = Date.now();
  const pts = [];
  let newest = null; // 最新点，用来覆盖设备「当前位置」
  for (const item of raw) {
    const loc = sanitizeLocation(item);
    if (!loc) continue; // 坏点跳过，不因一个坏点整批 400
    const t = Number.isFinite(loc.time) && loc.time > 0 ? loc.time : now;
    const pt = { t, lat: loc.lat, lon: loc.lon };
    if (loc.accuracy !== undefined) pt.acc = Math.round(loc.accuracy);
    if (loc.provider) pt.pv = loc.provider;
    if (item.moving === true || item.moving === 1) pt.mv = 1;
    pts.push(pt);
    if (!newest || t > newest.t) newest = { t, loc };
  }

  if (!pts.length) return sendJson(res, 400, { ok: false, error: 'no valid points' });

  const { id, deviceId } = await resolveTrackIdentity(serial, legacyId);
  appendPoints(id, pts);

  // 用最新一个点覆盖设备的「当前位置」（与单点上报语义一致）
  await updateStats((stats) => {
    touchDevice(stats, deviceId, {
      location: {
        lat: newest.loc.lat,
        lon: newest.loc.lon,
        accuracy: newest.loc.accuracy,
        provider: newest.loc.provider,
        time: newest.t,
      },
      locationUpdatedAt: nowCN(),
    });
  });

  sendJson(res, 200, { ok: true, accepted: pts.length });
}

/* ============ GET /api/track?deviceId=|serial= &since=：取某设备轨迹（验口令） ============ */
async function resolveTrackIdentity(serial, legacyId) {
  let result;
  await updateStats((stats) => {
    // Serial wins: do not replace an existing AutoSlide deviceId with adbboot's ANDROID_ID.
    const device = serial ? resolveDevice(stats, '', serial, '') :
      (stats.devices || []).find((d) => d.deviceId === legacyId);
    if (device) {
      device.trackId = trackIdFor(device);
      result = { id: device.trackId, deviceId: device.deviceId };
    } else result = { id: legacyId, deviceId: legacyId };
  });
  return result;
}

function handleFetch(req, res, url) {
  if (!isAdmin(req)) return sendJson(res, 403, { ok: false, error: 'forbidden' });
  const requestedId = String(url.searchParams.get('deviceId') || '');
  const serial = cleanSerial(url.searchParams.get('serial'));
  const devices = readStats().devices || [];
  const device = serial ? devices.find((d) => d.ecSerial === serial) :
    devices.find((d) => d.deviceId === requestedId);
  const id = device ? trackIdFor(device) : safeId(requestedId);
  if (!id) return sendJson(res, serial ? 404 : 400, { ok: false, error: 'device not found or invalid id' });
  const since = Number(url.searchParams.get('since'));
  // A legacy deviceId file can coexist with a serial-first file after a rolling upgrade.
  const ids = new Set([id, device && safeTrackId(device.deviceId)].filter(Boolean));
  const seen = new Set();
  const points = [...ids].flatMap((key) => readPoints(key, since)).sort((a,b) => a.t-b.t).filter((p) => {
    const key = `${p.t}:${p.lat}:${p.lon}`;
    if (seen.has(key)) return false;
    seen.add(key); return true;
  }).slice(-TRACK_MAX_POINTS);
  sendJson(res, 200, { ok: true, deviceId: device ? device.deviceId : id, count: points.length, points });
}

/* ============ GET /track?deviceId=：轨迹地图页（Basic Auth 由中间件把关） ============ */
function handleMapPage(req, res, url) {
  const id = esc(String(url.searchParams.get('deviceId') || ''));
  // embed=1：看板设备详情里的那块小预览——不要工具条、不要交互，放大交给外层页面
  const embed = url.searchParams.get('embed') === '1';
  sendHtml(res, mapHtml(id, embed));
}

/**
 * 轨迹地图页，一份代码两副面孔：
 *
 * - 完整页（默认）：顶部工具条能换设备重查，地图能拖能缩放，每个点都能点开看时间；
 * - 预览页（embed=1）：嵌在看板设备卡里的一小块，只画线和起终点，交互全关——
 *   外层盖了一块透明板收走点击用来放大，这边再响应就是两处抢同一下点击。
 *
 * deviceId 进来时已经 esc 过，只能往 HTML 属性里放；要用它取数就从 #dev 的 value 读，
 * 别再拼进 JS 字面量。
 */
function mapHtml(deviceId, embed) {
  return `<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>行动轨迹 ${deviceId}</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<style>
  html,body{margin:0;height:100%;font-family:system-ui,sans-serif}
  #map{height:100%;background:#e9e6e1}
  #bar{position:absolute;z-index:1000;top:10px;left:50px;right:10px;background:#fff;
       padding:8px 12px;border-radius:8px;box-shadow:0 1px 6px rgba(0,0,0,.2);font-size:13px}
  #bar input{width:230px;padding:4px 6px}
  #info b{color:#c0562b}
  .muted{color:#888}
  /* 预览模式没有工具条，那行说明挤成左下角一枚小胶囊 */
  #info.chip{position:absolute;z-index:1000;left:8px;bottom:8px;
       background:rgba(255,255,255,.92);padding:3px 8px;border-radius:6px;
       box-shadow:0 1px 4px rgba(0,0,0,.18);font-size:11px;color:#666;
       max-width:calc(100% - 16px);overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
</style>
</head>
<body>
${embed
  ? `<input type="hidden" id="dev" value="${deviceId}">
<div id="info" class="chip">加载中…</div>`
  : `<div id="bar">
  设备 <input id="dev" value="${deviceId}" placeholder="deviceId">
  <button onclick="load()">加载</button>
  <span id="info" class="muted">—</span>
</div>`}
<div id="map"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script>
  var EMBED = ${embed ? 'true' : 'false'};

  // WGS-84 → GCJ-02：轨迹点是 GPS 原始坐标，高德瓦片是加密坐标，不转会偏出几百米
  function wgs84ToGcj02(lat, lon) {
    if (!(lon > 73.66 && lon < 135.05 && lat > 3.86 && lat < 53.55)) return { lat: lat, lon: lon };
    var A = 6378245.0, EE = 0.00669342162296594323;
    var x = lon - 105.0, y = lat - 35.0;
    var dLat = -100.0 + 2.0*x + 3.0*y + 0.2*y*y + 0.1*x*y + 0.2*Math.sqrt(Math.abs(x));
    dLat += ((20.0*Math.sin(6.0*x*Math.PI) + 20.0*Math.sin(2.0*x*Math.PI)) * 2.0) / 3.0;
    dLat += ((20.0*Math.sin(y*Math.PI) + 40.0*Math.sin(y/3.0*Math.PI)) * 2.0) / 3.0;
    dLat += ((160.0*Math.sin(y/12.0*Math.PI) + 320*Math.sin(y*Math.PI/30.0)) * 2.0) / 3.0;
    var dLon = 300.0 + x + 2.0*y + 0.1*x*x + 0.1*x*y + 0.1*Math.sqrt(Math.abs(x));
    dLon += ((20.0*Math.sin(6.0*x*Math.PI) + 20.0*Math.sin(2.0*x*Math.PI)) * 2.0) / 3.0;
    dLon += ((20.0*Math.sin(x*Math.PI) + 40.0*Math.sin(x/3.0*Math.PI)) * 2.0) / 3.0;
    dLon += ((150.0*Math.sin(x/12.0*Math.PI) + 300.0*Math.sin(x/30.0*Math.PI)) * 2.0) / 3.0;
    var radLat = lat / 180.0 * Math.PI;
    var magic = Math.sin(radLat);
    magic = 1 - EE * magic * magic;
    var sqrtMagic = Math.sqrt(magic);
    dLat = (dLat * 180.0) / (((A * (1 - EE)) / (magic * sqrtMagic)) * Math.PI);
    dLon = (dLon * 180.0) / ((A / sqrtMagic) * Math.cos(radLat) * Math.PI);
    return { lat: lat + dLat, lon: lon + dLon };
  }

  // 预览就是一张不能动的图：拖拽/缩放/双击全关，免得小窗里误触，也不跟外层的放大抢点击
  var map = L.map('map', EMBED ? {
    zoomControl:false, attributionControl:false, dragging:false, touchZoom:false,
    scrollWheelZoom:false, doubleClickZoom:false, boxZoom:false, keyboard:false, tap:false
  } : {}).setView([31.23, 121.47], 12);

  L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
    { maxZoom: 19, subdomains: '1234', attribution: '© 高德地图' }).addTo(map);
  var layer = L.layerGroup().addTo(map);

  // 从预览放大到全屏时窗口尺寸变了，Leaflet 不会自己重算容器，得推它一把
  window.addEventListener('resize', function(){ map.invalidateSize(); });

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
      var latlngs = pts.map(function(p){ var c = wgs84ToGcj02(p.lat, p.lon); return [c.lat, c.lon]; });
      L.polyline(latlngs, { color:'#c0562b', weight:3, opacity:.8 }).addTo(layer);
      // 起点/终点特别标注，中间点用小圆点；预览里只留起终两个，几百个圆点铺上去又糊又慢
      pts.forEach(function(p, i){
        var isEnd = i === 0 || i === pts.length - 1;
        if(EMBED && !isEnd) return;
        var pc = wgs84ToGcj02(p.lat, p.lon);
        var m = L.circleMarker([pc.lat, pc.lon], {
          radius: isEnd ? 7 : 3,
          color: i === pts.length-1 ? '#1a7f37' : (i === 0 ? '#888' : '#c0562b'),
          fillOpacity: .9
        }).addTo(layer);
        if(!EMBED) m.bindPopup(fmt(p.t) + '<br>±' + (p.acc||'?') + 'm' + (p.mv? ' · 移动':'') );
      });
      map.fitBounds(L.latLngBounds(latlngs).pad(0.15));
      info.innerHTML = EMBED
        ? '<b>' + pts.length + '</b> 个点 · 最近 ' + fmt(pts[pts.length-1].t)
        : '<b>' + pts.length + '</b> 个点 · ' + fmt(pts[0].t) + ' → ' + fmt(pts[pts.length-1].t);
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
  router.on('POST', '/api/track/batch', handleIngestBatch);
  router.on('GET', '/api/track', handleFetch);
  router.on('GET', '/track', handleMapPage);
  // 启动即清一次，之后每 6 小时一次（与聊天清理同频）
  cleanup();
  const timer = setInterval(cleanup, 6 * 60 * 60 * 1000);
  // 定时器不应阻止进程退出
  if (timer.unref) timer.unref();
}

module.exports = { register, isAdmin, readPoints, safeId };
