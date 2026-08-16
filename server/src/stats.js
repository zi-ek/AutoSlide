// 统计域：设备上报、统计读写、统计看板页面

const { JsonStore } = require('./store');
const { STATS_FILE } = require('./config');
const { nowCN } = require('./util');
const { readJson, sendJson, sendHtml, clientIp } = require('./http');
const { lookupIpLocation } = require('./ip');
const { dashboardHtml } = require('./views/dashboard');
const { readAnnouncement } = require('./chat');

const statsStore = new JsonStore(STATS_FILE, () => ({
  install_count: 0,
  update_count: 0,
  unique_devices: 0,
  devices: [],
  last_update: null,
}));

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

/* 读取统计（顺带去重，不落盘） */
function readStats() {
  return dedupeDevices(statsStore.read());
}

/* 读-改-写统计：走队列 + 原子写 */
function updateStats(fn) {
  return statsStore.update((stats) => {
    dedupeDevices(stats);
    fn(stats);
  });
}

/* 记录一次设备心跳（上报与脚本上传共用） */
function touchDevice(stats, deviceId, patch) {
  const devices = stats.devices || [];
  const idx = devices.findIndex((d) => d.deviceId === deviceId);
  if (idx >= 0) Object.assign(devices[idx], patch);
  stats.devices = devices;
  return idx >= 0;
}

async function handleReport(req, res) {
  const payload = await readJson(req);
  const device = payload.device || {};
  const event = payload.event === 'update' ? 'update' : 'install';
  const deviceId = String(payload.deviceId || '').slice(0, 64);
  const ip = clientIp(req);
  const ipLoc = await lookupIpLocation(ip);

  await updateStats((stats) => {
    if (event === 'install') {
      stats.install_count = (stats.install_count || 0) + 1;
    } else {
      stats.update_count = (stats.update_count || 0) + 1;
    }
    const now = nowCN();
    if (deviceId) {
      const devices = stats.devices || [];
      const idx = devices.findIndex((d) => d.deviceId === deviceId);
      const incoming = {
        deviceId,
        model: String(device.model || '').slice(0, 100),
        // 内部代号（Build.MODEL），营销名之外保留一份便于排查
        modelCode: String(device.modelCode || '').slice(0, 100),
        brand: String(device.brand || '').slice(0, 100),
        android: String(device.android || '').slice(0, 50),
        cpu: String(device.cpu || '').slice(0, 100),
        appVersion: String(device.appVersion || '').slice(0, 50),
        appVersionCode: Number(device.appVersionCode) || 0,
        lastSeen: now,
        installs: event === 'install' ? 1 : 0,
        ip,
        ipLoc,
        // 设备自己探测到的出口地址：设备走 IPv6 连过来时，上面的 ip 是 IPv6，
        // 服务端看不到它的 IPv4 出口，只能由设备主动上报补充
        // PlainApp 同款完整设备信息，整块存下供后台二级面板展示
        deviceInfo: payload.deviceInfo && typeof payload.deviceInfo === 'object' ? payload.deviceInfo : undefined,
        egressIp: String(payload.egressIp || '').slice(0, 64),
        egressLoc: String(payload.egressLoc || '').slice(0, 80),
      };
      if (idx >= 0) {
        const d = devices[idx];
        incoming.firstInstall = d.firstInstall;
        // 本次探测失败时沿用上次的结果，避免偶发失败把已有数据抹掉
        if (!incoming.deviceInfo) incoming.deviceInfo = d.deviceInfo;
        if (!incoming.egressIp) incoming.egressIp = d.egressIp || '';
        if (!incoming.egressLoc) incoming.egressLoc = d.egressLoc || '';
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
  sendJson(res, 200, { ok: true, install_count: stats.install_count, unique_devices: stats.unique_devices });
}

function register(router) {
  router.on('POST', '/api/report', handleReport);
  router.on('GET', '/api/stats', (req, res) => sendJson(res, 200, readStats()));
  router.on('GET', '/', (req, res) => sendHtml(res, dashboardHtml(readStats(), readAnnouncement())));
  router.alias('GET', '/index.html', '/');
  router.alias('GET', '/stats', '/');
  router.alias('GET', '/stats/', '/');
}

module.exports = { register, readStats, updateStats, touchDevice };
