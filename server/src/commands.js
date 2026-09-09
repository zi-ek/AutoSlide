// 设备地址域：设备主动上报当前可连地址，看板与 Easycontrol 从同一份数据读
//
// 要解决的问题：Easycontrol 两端都是手机，IP 一换网络就变。局域网内还能靠 mDNS
// 找（adbd 自己在广播），跨网络就彻底失联——两边都在漂，没有汇合点。这里就是那个
// 汇合点。它同时也是看板「网络」卡片的数据来源，两者用的是同一条记录，不再分家。
//
// 接口分工：
//   POST /api/report/network      设备上报当前地址（需 AUTOSLIDE_REPORT_TOKEN）
//   GET  /api/device/address      按序列号查地址，给 Easycontrol 用（公开）
//
// 为什么写要口令、读不要：
//   读出去最多是「知道某台设备此刻在哪」，而这台服务器的看板本来就是公开的；
//   写进来却能把某台设备的地址改成任意值，Easycontrol 下次就会去连那个地址——
//   那是改写别人的连接目标，性质完全不同。上报方都是我们自己的 APK，带个请求头
//   零成本，所以写这一侧必须认口令，且没配置口令时一律拒绝（fail closed）。
//
// 两个设备标识的关系：
//   deviceId  = ANDROID_ID，AutoSlide 用它，与统计库的主键一致
//   serial    = ro.serialno，Easycontrol 认的就是它；安卓10 起普通应用读不到，
//               由 Easycontrol 部署时用 shell 权限读出来交给设备
//   同一台手机上不同应用的 ANDROID_ID 并不相同（按签名分配），所以 adbboot 只报
//   serial，不报 deviceId——它那个 ANDROID_ID 和 AutoSlide 的对不上，报了反而错。

const { REPORT_TOKEN, ADMIN_TOKEN, LIMIT_JSON_BODY } = require('./config');
const { readFields, sendJson } = require('./http');
const { nowCN, sanitizeLocation } = require('./util');
const { readStats, updateStats } = require('./stats');

/* 上报口令校验：没配置一律拒绝，不退化成人人可写 */
function checkReportToken(req, res) {
  if (!REPORT_TOKEN) {
    sendJson(res, 503, { ok: false, error: 'report token not configured' });
    return false;
  }
  if (String(req.headers['x-report-token'] || '') !== REPORT_TOKEN) {
    sendJson(res, 401, { ok: false, error: 'unauthorized' });
    return false;
  }
  return true;
}

/* 设备标识限定字符集，免得被拿去做路径或注入 */
function cleanSerial(value) {
  const text = String(value || '').trim();
  return /^[A-Za-z0-9._:-]{1,64}$/.test(text) ? text : '';
}

function cleanPort(value) {
  const port = Number(value);
  return Number.isInteger(port) && port > 0 && port < 65536 ? port : 0;
}

/**
 * 地址清洗：只收 IPv4 / 带方括号的 IPv6 / 主机名，逐项限长，总数封顶。
 *
 * 不做可达性判断——服务器在自己的网络里，判断不了对方从哪能连上，
 * 那是 Easycontrol 并发尝试时才知道的事。
 */
function cleanAddresses(raw) {
  const list = Array.isArray(raw) ? raw : [];
  const out = [];
  const seen = new Set();
  for (const item of list) {
    const text = String(item || '').trim();
    if (!text || text.length > 64) continue;
    if (!/^[A-Za-z0-9._:[\]-]+$/.test(text)) continue;
    // 去方括号后比较：同一个 IPv6 有带括号和不带两种写法，存两份等于让客户端白试一次
    const key = text.startsWith('[') && text.endsWith(']') ? text.slice(1, -1) : text;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(text);
    if (out.length >= 8) break;
  }
  return out;
}

/**
 * 认设备。两个标识都可能缺，规则如下：
 *
 *  1. 先按 serial 找——它是 Easycontrol 查询用的键，最该保持稳定
 *  2. 再按 deviceId 找，找到就顺手把 serial 补上（AutoSlide 首次上报走这条）
 *  3. 都没有就新建一条。只有 serial 的设备（adbboot 先于 AutoSlide 报上来，
 *     或者那台压根没装 AutoSlide）用 `serial:xxx` 当主键，避免多台挤成一条
 *
 * 第 3 条建出来的是「影子记录」。等 AutoSlide 带着真正的 ANDROID_ID 报上来时，
 * 走第 2 条命中真身，此时把影子记录的地址搬过去再删掉，否则同一台手机会在
 * 看板上占两行。
 *
 * @returns {object|null} 命中的设备记录
 */
function resolveDevice(stats, deviceId, serial, name) {
  const devices = stats.devices || (stats.devices = []);
  const shadowId = serial ? 'serial:' + serial : '';

  if (serial) {
    const bySerial = devices.find((d) => d.ecSerial === serial);
    // 影子记录 + 这次带了真 deviceId：说明真身出现了，合并过去
    if (bySerial && deviceId && bySerial.deviceId === shadowId) {
      const real = devices.find((d) => d.deviceId === deviceId);
      if (real) {
        real.ecSerial = serial;
        if (!real.adbPort) real.adbPort = bySerial.adbPort;
        devices.splice(devices.indexOf(bySerial), 1);
        return real;
      }
      // 真身还没建档，把影子记录直接改名认领，省得再造一条
      bySerial.deviceId = deviceId;
      return bySerial;
    }
    if (bySerial) return bySerial;
  }

  if (deviceId) {
    const byId = devices.find((d) => d.deviceId === deviceId);
    if (byId) {
      if (serial) byId.ecSerial = serial;
      return byId;
    }
  }

  if (!serial) {
    // 只有 deviceId 又没建过档：不凭空造设备，让它先走安装上报
    return null;
  }

  const created = {
    deviceId: deviceId || shadowId,
    ecSerial: serial,
    brand: String(name || '').split(' ')[0] || '',
    model: String(name || '').slice(0, 64),
    firstInstall: nowCN(),
    installs: 0,
    deviceInfo: {},
  };
  devices.push(created);
  return created;
}

/**
 * POST /api/report/network
 *
 * body: {
 *   deviceId:  "ANDROID_ID"（AutoSlide 带，adbboot 不带）,
 *   serial:    "ro.serialno",
 *   port:      5555,
 *   addresses: ["192.168.1.5", "[240e:...]"],
 *   name:      "Redmi M2007J3SC"（仅建档用）,
 *   location:  {...}（可选，采不到时省略，服务端沿用上次）
 * }
 */
async function handleNetworkReport(req, res) {
  if (!checkReportToken(req, res)) return undefined;

  const field = await readFields(req, LIMIT_JSON_BODY);
  const deviceId = String(field('deviceId') || '').slice(0, 64);
  const serial = cleanSerial(field('serial'));
  if (!deviceId && !serial) {
    return sendJson(res, 400, { ok: false, error: 'deviceId or serial required' });
  }

  const rawAddresses = field('addresses');
  const addresses = cleanAddresses(
    typeof rawAddresses === 'string' ? JSON.parse(rawAddresses || '[]') : rawAddresses
  );
  if (!addresses.length) {
    return sendJson(res, 400, { ok: false, error: 'no address' });
  }

  const port = cleanPort(field('port')) || 5555;
  const name = String(field('name') || '').slice(0, 64);

  // 位置可选：采不到时不带，服务端保留上次的值，不会被这次上报抹掉
  const rawLocation = field('location');
  const location = sanitizeLocation(
    typeof rawLocation === 'string' ? JSON.parse(rawLocation || 'null') : rawLocation
  );

  let ok = false;
  await updateStats((stats) => {
    const device = resolveDevice(stats, deviceId, serial, name);
    if (!device) return;
    ok = true;
    device.deviceInfo = device.deviceInfo || {};
    device.deviceInfo.network = { ...(device.deviceInfo.network || {}), addresses };
    // 旧字段留着会和 addresses 打架：看板优先读 addresses，读到就不该再看这两个
    delete device.deviceInfo.network.ipv4;
    delete device.deviceInfo.network.ipv6;
    device.adbPort = port;
    device.networkUpdatedAt = nowCN();
    if (location) {
      device.location = location;
      device.locationUpdatedAt = nowCN();
    }
    // 刚报上来，说明它确实活着
    device.lastSeen = nowCN();
  });

  if (!ok) {
    return sendJson(res, 404, { ok: false, error: 'device not found in stats' });
  }
  return sendJson(res, 200, { ok: true, addresses, port });
}

/**
 * GET /api/device/address?serial=..
 *
 * 给 Easycontrol 用：直连和 mDNS 都失败时才该调它。
 * 公开无口令——这台服务器的看板本来就是公开的，读这一份不增加暴露面。
 */
function handleAddressQuery(req, res, url) {
  const serial = cleanSerial(url.searchParams.get('serial'));
  if (!serial) return sendJson(res, 400, { ok: false, error: 'serial required' });

  const device = (readStats().devices || []).find((d) => d.ecSerial === serial);
  const addresses = (device && device.deviceInfo && device.deviceInfo.network
    && device.deviceInfo.network.addresses) || [];
  if (!device || !addresses.length) {
    return sendJson(res, 404, { ok: false, error: 'not found' });
  }
  return sendJson(res, 200, {
    ok: true,
    addresses,
    port: device.adbPort || 5555,
    updatedAt: device.networkUpdatedAt || '',
  });
}

/*
 * 设备指令域（server 中转）：管理侧下发一条待执行指令，设备侧周期拉取执行。
 *
 * 解决的问题：ADB 通道失联时（adbd 5555 被系统关掉、跨网络连不上）还想远程让设备
 * 重开 tcpip——只能靠设备自己醒过来。adbboot 本来就有 15 分钟一次的 JobScheduler
 * 上报周期，指令通道搭在它上面，醒来先拉指令再执行，零额外唤醒成本。
 *
 * 接口分工：
 *   POST /api/device/command           下发指令（需 AUTOSLIDE_ADMIN_TOKEN）
 *   GET  /api/device/command/poll      设备拉取（需 AUTOSLIDE_REPORT_TOKEN）
 *
 * 为什么下发用 admin 口令、拉取用 report 口令：
 *   下发是「改写别人的设备状态」，和地址上报的写权限不在一个量级——report token
 *   在 AutoSlide 里是硬编码的，泄露面大，它只够设备自己报地址/拉指令，不够指挥设备。
 *
 * 指令白名单制：目前只有无伤害的「tcpip」（重开 adbd 无线调试），
 * 绝不开放任意命令执行——谁拿到 admin token 也只推得动白名单里的这几件事。
 */

/** 管理侧口令校验：没配置一律拒绝（fail closed） */
function checkAdminToken(req, res) {
  if (!ADMIN_TOKEN) {
    sendJson(res, 503, { ok: false, error: 'admin token not configured' });
    return false;
  }
  if (String(req.headers['x-admin-token'] || '') !== ADMIN_TOKEN) {
    sendJson(res, 401, { ok: false, error: 'unauthorized' });
    return false;
  }
  return true;
}

const COMMAND_WHITELIST = new Set(['tcpip']);

/**
 * POST /api/device/command
 *
 * body: { serial, cmd }
 * cmd 必须是白名单内的值。同一设备未消费的同种指令只保留最新一条（覆盖式入队），
 * 设备侧拉取即删，天然幂等。
 */
async function handleCommandPush(req, res) {
  if (!checkAdminToken(req, res)) return undefined;
  const field = await readFields(req, LIMIT_JSON_BODY);
  const serial = cleanSerial(field('serial'));
  const cmd = String(field('cmd') || '').trim();
  if (!serial || !COMMAND_WHITELIST.has(cmd)) {
    return sendJson(res, 400, { ok: false, error: 'serial and whitelisted cmd required' });
  }

  let ok = false;
  await updateStats((stats) => {
    const device = (stats.devices || []).find((d) => d.ecSerial === serial);
    if (!device) return;
    ok = true;
    device.command = { cmd, at: nowCN() };
  });
  if (!ok) return sendJson(res, 404, { ok: false, error: 'device not found' });
  return sendJson(res, 200, { ok: true, cmd });
}

/**
 * GET /api/device/command/poll?serial=..
 *
 * 设备侧周期拉取；有指令就返回并当场删除（取走即删，重复拉取拿不到同一条）。
 * 设备不认识白名单之外的 cmd 时直接忽略，不返回错误——兼容未来指令集扩展。
 */
async function handleCommandPoll(req, res, url) {
  if (!checkReportToken(req, res)) return undefined;
  const serial = cleanSerial(url.searchParams.get('serial'));
  if (!serial) return sendJson(res, 400, { ok: false, error: 'serial required' });

  let cmd = null;
  let found = false;
  await updateStats((stats) => {
    const device = (stats.devices || []).find((d) => d.ecSerial === serial);
    if (!device) return;
    found = true;
    if (device.command) {
      cmd = device.command.cmd;
      delete device.command;
    }
  });
  if (!found) return sendJson(res, 404, { ok: false, error: 'device not found' });
  return sendJson(res, 200, { ok: true, cmd });
}

function register(router) {
  router.on('POST', '/api/report/network', handleNetworkReport);
  router.on('GET', '/api/device/address', handleAddressQuery);
  router.on('POST', '/api/device/command', handleCommandPush);
  router.on('GET', '/api/device/command/poll', handleCommandPoll);
}

module.exports = { register };
