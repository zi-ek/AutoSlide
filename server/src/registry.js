// 设备地址登记域：让被控端把自己当前的地址报上来，控制端按需查回去
//
// 要解决的问题：Easycontrol 的两端都是手机，IP 一换网络就变。局域网内还能靠
// mDNS 查（adbd 自己在广播），跨网络就彻底找不到对方了——两边都在漂，没有汇合点。
// 这个模块就是那个汇合点：只存「设备标识 → 当前地址列表」，不碰任何别的东西。
//
// 接口：
//   POST /api/ec/report   被控端上报当前地址（开机、部署、定时各报一次）
//   GET  /api/ec/lookup   控制端按设备标识查当前地址
//   GET  /ec              浏览器里看这张表（同样要口令）
//
// 两个接口都要带共享口令。这张表泄漏出去等于告诉别人「这台手机此刻在这个地址上，
// 而且那个端口开着 adbd」，所以 lookup 绝不能匿名开放。没配置口令时一律拒绝
// （fail closed），不会退化成人人可读写。

const path = require('path');
const { JsonStore } = require('./store');
const { readJson, sendJson, sendText, sendHtml, clientIp } = require('./http');
const { renderRegistryPage } = require('./views/ecRegistry');
const { DATA_DIR } = require('./config');

const REGISTRY_FILE = path.join(DATA_DIR, 'ec-registry.json');

/* 共享口令，只从环境变量读。systemd 单元里配 Environment=EC_REGISTRY_TOKEN=... */
const TOKEN = process.env.EC_REGISTRY_TOKEN || '';

/* 一台设备最多记几个地址：Wi-Fi 一条、双卡各一条，再多是噪声 */
const MAX_ADDRESSES = 6;
/* 超过这个时长没再上报就认为过期，查询时不再返回 */
const STALE_MS = 7 * 24 * 60 * 60 * 1000;
/* 登记表里最多留几台设备，防止被灌垃圾条目撑爆 */
const MAX_DEVICES = 200;

const store = new JsonStore(REGISTRY_FILE, () => ({ devices: {} }));

/* 口令校验。缺口令、配置为空、对不上，一律拒绝 */
function authorized(req, url) {
  if (!TOKEN) return false;
  const fromHeader = req.headers['x-ec-token'];
  const fromQuery = url.searchParams.get('token');
  return fromHeader === TOKEN || fromQuery === TOKEN;
}

/* 设备标识用 ro.serialno，限定字符集，免得被拿去做路径或注入 */
function cleanId(value) {
  if (typeof value !== 'string') return '';
  const trimmed = value.trim();
  return /^[A-Za-z0-9._:-]{1,64}$/.test(trimmed) ? trimmed : '';
}

/**
 * 地址清洗：只收 IPv4 / 带方括号的 IPv6 / 主机名，长度设上限。
 * 不做可达性判断——服务器在自己的网络里，判断不了对方从哪能连上，
 * 那是控制端并发尝试时才知道的事。
 */
function cleanAddress(value) {
  if (typeof value !== 'string') return '';
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > 64) return '';
  return /^[A-Za-z0-9._:\[\]-]+$/.test(trimmed) ? trimmed : '';
}

/**
 * 去掉方括号后的地址，仅用于比较是否重复。
 * IPv6 在本项目里统一写成 [xxx]，但服务器记的来源地址是裸的，
 * 不归一化的话同一个地址会以两种写法各存一份，控制端就要白试一次。
 */
function bareAddress(value) {
  return value.startsWith('[') && value.endsWith(']') ? value.slice(1, -1) : value;
}

function cleanPort(value) {
  const port = Number(value);
  return Number.isInteger(port) && port > 0 && port < 65536 ? port : 0;
}

function register(router) {
  /**
   * 被控端上报。
   * body: { id: "序列号", addresses: ["[240e:...]", "192.168.1.5"], port: 5555, name: "可选备注" }
   */
  router.on('POST', '/api/ec/report', async (req, res, url) => {
    if (!authorized(req, url)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const body = await readJson(req);

    const id = cleanId(body && body.id);
    if (!id) return sendJson(res, 400, { ok: false, error: 'bad id' });

    const addresses = [];
    const seen = new Set();
    const add = (value) => {
      const address = cleanAddress(value);
      if (!address) return;
      const key = bareAddress(address);
      if (seen.has(key) || addresses.length >= MAX_ADDRESSES) return;
      seen.add(key);
      addresses.push(address);
    };

    const raw = Array.isArray(body.addresses) ? body.addresses : [];
    for (const item of raw) add(item);

    // 上报者自己看到的地址未必全（比如它不知道自己的公网出口），
    // 把请求的来源地址也记上：那是这台服务器实际看到它的地方，对同网段的控制端往往正好可用。
    // 归一化去重，免得和上报列表里的同一个地址重复存两份写法
    add(clientIp(req));
    if (!addresses.length) return sendJson(res, 400, { ok: false, error: 'no address' });

    const port = cleanPort(body.port) || 5555;
    const name = typeof body.name === 'string' ? body.name.slice(0, 64) : '';

    await store.update((data) => {
      if (!data.devices) data.devices = {};
      // 满了先清过期的，还满就丢最旧的，避免无限增长
      const ids = Object.keys(data.devices);
      if (!data.devices[id] && ids.length >= MAX_DEVICES) {
        const now = Date.now();
        for (const key of ids) {
          if (now - (data.devices[key].updatedAt || 0) > STALE_MS) delete data.devices[key];
        }
        const left = Object.keys(data.devices);
        if (left.length >= MAX_DEVICES) {
          left.sort((a, b) => (data.devices[a].updatedAt || 0) - (data.devices[b].updatedAt || 0));
          delete data.devices[left[0]];
        }
      }
      data.devices[id] = { addresses, port, name, updatedAt: Date.now() };
      return data;
    });

    return sendJson(res, 200, { ok: true, addresses, port });
  });

  /**
   * 控制端查询。GET /api/ec/lookup?id=序列号
   * 只有在直连全部失败时才该调用它——正常情况不该让服务器知道你在连谁。
   */
  router.on('GET', '/api/ec/lookup', async (req, res, url) => {
    if (!authorized(req, url)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const id = cleanId(url.searchParams.get('id'));
    if (!id) return sendJson(res, 400, { ok: false, error: 'bad id' });

    const data = store.read();
    const record = data.devices && data.devices[id];
    if (!record) return sendJson(res, 404, { ok: false, error: 'not found' });
    // 太久没上报的不给：地址多半早就变了，让控制端白等一轮超时不如直接说没有
    if (Date.now() - (record.updatedAt || 0) > STALE_MS) {
      return sendJson(res, 404, { ok: false, error: 'stale' });
    }
    return sendJson(res, 200, {
      ok: true,
      addresses: record.addresses,
      port: record.port,
      updatedAt: record.updatedAt,
    });
  });

  /**
   * 浏览器查看页。GET /ec?token=口令
   * <p>
   * 和 lookup 一样要口令，而且不能省：这张表泄漏出去等于告诉别人
   * 「这台手机此刻在这个地址上，那个端口开着 adbd」。
   */
  router.on('GET', '/ec', async (req, res, url) => {
    if (!authorized(req, url)) return sendText(res, 401, '需要口令：/ec?token=...');
    const data = store.read();
    return sendHtml(res, renderRegistryPage(data.devices || {}, STALE_MS));
  });
}

/* 供看板复用：把登记表并进设备详情的「网络」卡片，不必再单开一页去对照 */
function readDevices() {
  const data = store.read();
  return (data && data.devices) || {};
}

module.exports = { register, readDevices, isAuthorized: authorized, STALE_MS };
