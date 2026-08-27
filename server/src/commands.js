// 指令域：后台按需下发的一次性设备指令
//
// 目前只有一种指令：让某台设备立刻重报一次 network 段。
//
// 为什么指令只存内存、不落盘：
//   1. 指令是瞬态的——服务重启后丢失完全可接受，后台再点一次即可；
//   2. 轮询接口会被每台在线设备每分钟敲一次，若每次都读写 JSON 文件，
//      stats.json（当前 450 KB 量级）会被整份重写上千次/小时，纯粹浪费磁盘。
//   只有设备真正回报数据的那一次才落盘。
//
// 接口分工：
//   POST /api/device/refresh   后台下指令        （需管理口令）
//   GET  /api/device/pending   设备问有无指令    （无鉴权，零磁盘 I/O）
//   POST /api/report/network   设备回报 network  （无鉴权，最小间隔兜底）
//   GET  /api/device/status    后台轮询结果      （需管理口令）
//
// ⚠️ /api/device/pending 与 /api/report/network 无法鉴权——App 侧没有任何凭据可用，
//    deviceId 是客户端自报且公开在看板上。因此伪造 deviceId 往看板写假 IP 是可能的，
//    与既有的 /api/report 属同一性质。这里只能用最小间隔限制写入频率，
//    真正的解法是给设备签发 per-device token，那是另一件事。

const { ADMIN_TOKEN, LIMIT_JSON_BODY } = require('./config');
const { readFields, sendJson } = require('./http');
const { nowCN } = require('./util');
const { readStats, updateStats } = require('./stats');

/* deviceId -> 下指令的时刻（ms）；设备取走或过期后移除 */
const pendingRefresh = new Map();

/* deviceId -> 上次接受回报的时刻（ms），仅用于最小间隔限流 */
const lastReportAt = new Map();

/* 指令有效期：设备长期不在线时不要无限堆积 */
const PENDING_TTL_MS = 24 * 60 * 60 * 1000;

/* 同一设备两次回报的最小间隔，挡住高频伪造写入 */
const MIN_REPORT_INTERVAL_MS = 30 * 1000;

/* 待执行指令数量上限，防止内存被刷爆 */
const MAX_PENDING = 1000;

/* 口令校验：没配置一律拒绝（fail closed），与发版、脚本删除同一道门 */
function checkAdmin(req, res, token) {
  if (!ADMIN_TOKEN) {
    sendJson(res, 403, { ok: false, error: 'admin token not configured' });
    return false;
  }
  if (String(token || '') !== ADMIN_TOKEN) {
    sendJson(res, 403, { ok: false, error: 'bad token' });
    return false;
  }
  return true;
}

/* 清掉过期指令；每次读写指令表时顺带跑一次，不另起定时器 */
function sweepExpired() {
  const cutoff = Date.now() - PENDING_TTL_MS;
  for (const [id, at] of pendingRefresh) {
    if (at < cutoff) pendingRefresh.delete(id);
  }
}

/**
 * 只保留看板真正会渲染的网络字段，且逐项限长。
 *
 * 不直接存客户端传来的整个对象：这个接口无鉴权，整块存下等于让任何人
 * 往 stats.json 里塞任意结构的 JSON。
 *
 * @param {object} raw 客户端上报的 network 段
 * @returns {object} 清洗后的字段集合（只含本次带来的键）
 */
function sanitizeNetwork(raw) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const out = {};

  const ipList = (v) =>
    (Array.isArray(v) ? v : [])
      .slice(0, 20)
      .map((x) => String(x || '').slice(0, 64))
      .filter(Boolean);

  if (src.ipv4 !== undefined) out.ipv4 = ipList(src.ipv4);
  if (src.ipv6 !== undefined) out.ipv6 = ipList(src.ipv6);

  // 客户端目前只报 ipv4/ipv6，其余字段看板已有位置，先留好通道
  const scalars = [
    'type', 'networkType', 'ssid', 'bssid', 'rssi', 'frequency',
    'wifiFrequency', 'linkSpeed', 'gateway', 'dns', 'mac',
    'vpn', 'vpnConnected', 'proxy', 'proxyEnabled',
  ];
  for (const key of scalars) {
    if (src[key] === undefined || src[key] === null) continue;
    out[key] = typeof src[key] === 'boolean' ? src[key] : String(src[key]).slice(0, 120);
  }

  return out;
}

/* POST /api/device/refresh  {deviceId}  后台下发刷新指令 */
async function handleRefreshRequest(req, res) {
  const field = await readFields(req, LIMIT_JSON_BODY);
  if (!checkAdmin(req, res, field('token') || req.headers['x-admin-token'])) return undefined;

  const deviceId = String(field('deviceId') || '').slice(0, 64);
  if (!deviceId) return sendJson(res, 400, { ok: false, error: 'deviceId required' });

  sweepExpired();
  if (pendingRefresh.size >= MAX_PENDING && !pendingRefresh.has(deviceId)) {
    return sendJson(res, 429, { ok: false, error: 'too many pending commands' });
  }

  const requestedAt = Date.now();
  pendingRefresh.set(deviceId, requestedAt);
  return sendJson(res, 200, { ok: true, deviceId, requestedAt });
}

/**
 * GET /api/device/pending?deviceId=..  设备轮询自己的待执行指令。
 *
 * 这是全服调用最密集的接口（每台在线设备每分钟一次），因此刻意做成
 * 纯内存查表：不读 stats.json、不写任何文件。
 */
function handlePending(req, res, url) {
  const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
  if (!deviceId) return sendJson(res, 400, { ok: false, error: 'deviceId required' });

  sweepExpired();
  return sendJson(res, 200, {
    ok: true,
    refreshNetwork: pendingRefresh.has(deviceId),
  });
}

/* POST /api/report/network  {deviceId, network}  设备回报，指令随之清除 */
async function handleNetworkReport(req, res) {
  const field = await readFields(req, LIMIT_JSON_BODY);
  const deviceId = String(field('deviceId') || '').slice(0, 64);
  if (!deviceId) return sendJson(res, 400, { ok: false, error: 'deviceId required' });

  const rawNetwork = field('network');
  const incoming = sanitizeNetwork(
    typeof rawNetwork === 'string' ? JSON.parse(rawNetwork || '{}') : rawNetwork
  );
  if (!Object.keys(incoming).length) {
    return sendJson(res, 400, { ok: false, error: 'network payload empty' });
  }

  const now = Date.now();
  const last = lastReportAt.get(deviceId) || 0;
  if (now - last < MIN_REPORT_INTERVAL_MS) {
    // 指令仍然清掉：设备已经响应过了，不该让它反复重试
    pendingRefresh.delete(deviceId);
    return sendJson(res, 429, { ok: false, error: 'reported too frequently' });
  }
  lastReportAt.set(deviceId, now);

  let found = false;
  await updateStats((stats) => {
    const device = (stats.devices || []).find((d) => d.deviceId === deviceId);
    if (!device) return;
    found = true;
    device.deviceInfo = device.deviceInfo || {};
    // 合并而不是整段替换：客户端这次没带的字段（如将来才采集的 SSID）应当保留
    device.deviceInfo.network = { ...(device.deviceInfo.network || {}), ...incoming };
    device.networkUpdatedAt = nowCN();
    // 设备刚刚应答过指令，说明它确实活着
    device.lastSeen = nowCN();
  });

  pendingRefresh.delete(deviceId);

  if (!found) {
    // 设备还没在统计库里建档（没上报过安装），不新建记录，避免凭 deviceId 凭空造设备
    return sendJson(res, 404, { ok: false, error: 'device not found in stats' });
  }
  return sendJson(res, 200, { ok: true });
}

/* GET /api/device/status?deviceId=..  后台轮询刷新结果 */
async function handleStatus(req, res, url) {
  // 口令只走请求头，不接受查询参数——URL 会进反代与浏览器历史的日志
  if (!checkAdmin(req, res, req.headers['x-admin-token'])) {
    return undefined;
  }
  const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
  if (!deviceId) return sendJson(res, 400, { ok: false, error: 'deviceId required' });

  sweepExpired();
  const device = (readStats().devices || []).find((d) => d.deviceId === deviceId);
  return sendJson(res, 200, {
    ok: true,
    pending: pendingRefresh.has(deviceId),
    requestedAt: pendingRefresh.get(deviceId) || 0,
    networkUpdatedAt: (device && device.networkUpdatedAt) || '',
    network: (device && device.deviceInfo && device.deviceInfo.network) || null,
  });
}

function register(router) {
  router.on('POST', '/api/device/refresh', handleRefreshRequest);
  router.on('GET', '/api/device/pending', handlePending);
  router.on('POST', '/api/report/network', handleNetworkReport);
  router.on('GET', '/api/device/status', handleStatus);
}

module.exports = { register, sanitizeNetwork };
