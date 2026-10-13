// 远程首次配对域：被控端引导壳(devguide)把配对弹窗上的「地址/端口/配对码」暂存到这里，
// 异地的 Easycontrol 凭会话号取走，完成 Android 无线调试配对。
//
// 要解决的问题：ADB 无线配对端口只在被控端本地网络可达，跨网时两端没有汇合点。
// 地址域(src/commands.js)需要 ro.serialno 做键，而普通安装的引导壳读不到序列号，
// 所以这里用服务器分配的 6 位短会话号关联两端。
//
// 新流程（主控端发起）：
//   1. 主控端调 alloc 拿到 sid + resultKey，生成落地页链接 /pair?sid=XXX 发给被控端
//   2. 被控端打开链接 → 下载安装 devguide → deep link 唤起 devguide://pair?sid=XXX
//   3. devguide 引导用户手动开无线调试 → 自动读码 → 调 offer 上报载荷（带预置 sid）
//   4. 主控端轮询 fetch 拿到载荷 → 配对 → result 回报结果
//
// 接口分工：
//   POST /api/pair/alloc    主控端预分配会话号（公开）
//   POST /api/pair/offer    引导壳上报/刷新配对载荷（需 AUTOSLIDE_REPORT_TOKEN）
//   GET  /api/pair/fetch    控制端凭 sid 取载荷（公开）
//   POST /api/pair/result   控制端回报配对结果（凭一次性 resultKey）
//   GET  /api/pair/status   引导壳按 offerId 轮询状态（需 token）
//   GET  /pair              被控端落地页：下载 APK + deep link 唤起
//   GET  /api/pair/apk      devguide APK 下载
//
// 纯内存暂存：配对是分钟级的一次性过程，重启服务器丢了就让用户重来，不落盘、
// 不进统计库，避免临时的配对码残留在任何持久文件里。

const fs = require('fs');
const path = require('path');
const { REPORT_TOKEN, LIMIT_JSON_BODY, DATA_DIR } = require('./config');
const { readFields, sendJson, sendHtml } = require('./http');

// 会话号字母表：去掉易混字符（0/O、1/I/L），念给人听也不容易错
const SID_ALPHABET = 'ABCDEFGHJKMNPQRTWXY2346789';
const SID_LENGTH = 6;
const OFFER_TTL_MS = 10 * 60 * 1000; // 配对弹窗约 10 分钟有效期
const ALLOC_TTL_MS = 30 * 60 * 1000; // 预分配阶段更长：要留出下载安装的时间
const TERMINAL_GRACE_MS = 2 * 60 * 1000; // 配对完成后多留 2 分钟，让引导壳轮询到结果
const MAX_OFFERS = 2000; // 防内存刷爆，正常自用远远到不了

const PAIR_APK_DIR = path.join(DATA_DIR, 'pair');
const PAIR_APK_NAME = 'USBDebugGuide-v1.1.0.apk';

/** @type {Map<string, object>} offerId -> 记录 */
const offers = new Map();
const tunnel = require('./pair-tunnel').createPairTunnel(sid => [...offers.values()].find(o => o.sid === sid), isExpired);

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

function randomToken(bytes) {
  return require('crypto').randomBytes(bytes).toString('hex');
}

function allocateSid() {
  for (let i = 0; i < 100; i++) {
    let sid = '';
    for (let j = 0; j < SID_LENGTH; j++) {
      sid += SID_ALPHABET[Math.floor(Math.random() * SID_ALPHABET.length)];
    }
    const clash = [...offers.values()].some((o) => o.sid === sid && !isTerminal(o));
    if (!clash) return sid;
  }
  throw new Error('sid allocate failed');
}

function isTerminal(o) {
  return o.state === 'paired' || o.state === 'failed';
}

/** 过期判断：预分配阶段用 expiresAt，上报载荷后切回正常 TTL */
function isExpired(o, now) {
  if (isTerminal(o)) return now > o.finishedAt + TERMINAL_GRACE_MS;
  if (o.expiresAt) return now > o.expiresAt;
  return now > o.updatedAt + OFFER_TTL_MS;
}

function sweep() {
  const now = Date.now();
  for (const [id, o] of offers) if (isExpired(o, now)) { tunnel.revoke(id); offers.delete(id); }
}
setInterval(sweep, 60 * 1000).unref();

/* ------------------------- 字段清洗 ------------------------- */

function cleanOfferId(value) {
  const text = String(value || '').trim();
  return /^[a-f0-9]{16,64}$/.test(text) ? text : '';
}

function cleanSid(value) {
  const text = String(value || '').trim().toUpperCase();
  return new RegExp(`^[${SID_ALPHABET}]{${SID_LENGTH}}$`).test(text) ? text : '';
}

/** 地址只认 IPv4 / IPv6（IPv6 允许带方括号，存的时候去掉） */
function cleanHost(value) {
  const text = String(value || '').trim().slice(0, 64).replace(/^\[|\]$/g, '');
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(text)) {
    return text.split('.').every((p) => Number(p) <= 255) ? text : '';
  }
  if (/^[0-9a-fA-F:]{3,45}$/.test(text) && text.includes(':')) return text;
  return '';
}

function cleanPort(value) {
  const port = Number(value);
  return Number.isInteger(port) && port >= 1024 && port < 65536 ? port : 0;
}

function cleanCode(value) {
  const text = String(value || '').trim();
  return /^\d{6}$/.test(text) ? text : '';
}

/* ------------------------- 接口 ------------------------- */

/**
 * POST /api/pair/alloc
 * 主控端预分配会话号。返回 sid + resultKey + offerId，被控端凭 sid 上报载荷。
 * 不需要 token（与 fetch 同级公开）：知道服务器地址才能调，sid 本身就是凭证。
 */
async function handleAlloc(req, res) {
  const field = await readFields(req, LIMIT_JSON_BODY);
  const protocol = field("protocol") === 2 ? 2 : 1;
  sweep();
  if (offers.size >= MAX_OFFERS) {
    return sendJson(res, 503, { ok: false, error: 'server busy, try again later' });
  }
  const offerId = randomToken(16);
  const sid = allocateSid();
  const resultKey = randomToken(16);
  offers.set(offerId, {
    offerId,
    sid,
    resultKey,
    protocol,
    state: 'pending', // 等被控端上报载荷
    detail: '',
    fetched: false,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    expiresAt: Date.now() + ALLOC_TTL_MS,
  });
  console.info('[pair]', offerId.slice(0,8), 'allocated');
  return sendJson(res, 200, { ok: true, sid, resultKey, offerId });
}

/**
 * POST /api/pair/offer
 * body: { sid?, offerId?, host, pairPort, connectPort, code, model }
 *
 * 新流程：带 sid，找到预分配记录填入载荷。
 * 旧流程：带 offerId，首次分配 sid，后续原地刷新。
 */
async function handleOffer(req, res) {
  if (!checkReportToken(req, res)) return undefined;
  sweep();
  if (offers.size >= MAX_OFFERS) {
    return sendJson(res, 503, { ok: false, error: 'server busy, try again later' });
  }
  const field = await readFields(req, LIMIT_JSON_BODY);
  const sid = cleanSid(field('sid'));
  const offerId = cleanOfferId(field('offerId'));
  const host = cleanHost(field('host'));
  const pairPort = cleanPort(field('pairPort'));
  const connectPort = cleanPort(field('connectPort'));
  const code = cleanCode(field('code'));
  if (!host || !pairPort || !connectPort || !code) {
    return sendJson(res, 400, { ok: false, error: 'host, pairPort, connectPort, code required' });
  }
  const model = String(field('model') || '').trim().slice(0, 64);

  let record;

  // 新流程：凭 sid 找到预分配记录
  if (sid) {
    record = [...offers.values()].find((o) => o.sid === sid && !isTerminal(o));
    if (!record) {
      return sendJson(res, 404, { ok: false, error: 'sid not found, please ask controller to re-allocate' });
    }
    if (isExpired(record, Date.now())) {
      offers.delete(record.offerId);
      return sendJson(res, 404, { ok: false, error: 'sid expired, please ask controller to re-allocate' });
    }
  } else if (offerId) {
    // 旧流程：凭 offerId 找到记录
    record = offers.get(offerId);
    if (record && isExpired(record, Date.now())) {
      offers.delete(offerId);
      record = undefined;
    }
    if (record && isTerminal(record)) {
      return sendJson(res, 409, { ok: false, error: 'session already finished' });
    }
    if (!record) {
      record = {
        offerId,
        sid: allocateSid(),
        resultKey: randomToken(16),
        state: 'waiting',
        detail: '',
        fetched: false,
        createdAt: Date.now(),
      };
      offers.set(offerId, record);
    }
  } else {
    return sendJson(res, 400, { ok: false, error: 'sid or offerId required' });
  }

  if (record.protocol === 2 && !record.agentKey) return sendJson(res, 409, {ok:false,error:'device must join first'});
  if (record.agentKey && req.headers['x-pair-key'] !== record.agentKey) return sendJson(res, 401, {ok:false,error:'bad device key'});
  console.info('[pair]', record.offerId.slice(0,8), 'payload received');
  record.host = host;
  record.pairPort = pairPort;
  record.connectPort = connectPort;
  record.code = code;
  record.model = model;
  record.updatedAt = Date.now();
  record.expiresAt = null; // 上报载荷后切回正常 TTL
  if (record.state === 'pending') record.state = 'waiting';
  return sendJson(res, 200, { ok: true, sid: record.sid, offerId: record.offerId, state: record.state });
}

/**
 * GET /api/pair/fetch?sid=..
 * 控制端取载荷。sid 本身就是能力凭证（6 位、有效期）。
 */
function handleFetch(req, res, url) {
  sweep();
  const sid = cleanSid(url.searchParams.get('sid'));
  if (!sid) return sendJson(res, 400, { ok: false, error: 'sid required' });
  const record = [...offers.values()].find((o) => o.sid === sid);
  if (!record || isExpired(record, Date.now())) {
    return sendJson(res, 404, { ok: false, error: '会话不存在或已过期，请让对方重新发起' });
  }
  if ((record.protocol === 2 || record.agentKey) && req.headers['x-pair-controller-key'] !== record.resultKey) return sendJson(res, 401, {ok:false,error:'bad controller key'});
  if (record.state === 'pending') {
    return sendJson(res, 202, { ok: false, error: 'waiting for device', stage: record.stage || '等待对方打开邀请链接', online: Date.now() - (record.agentSeenAt || 0) < 20000 });
  }
  if (isTerminal(record)) {
    return sendJson(res, 409, { ok: false, error: 'session already finished' });
  }
  record.fetched = true;
  record.state = 'fetched';
  return sendJson(res, 200, {
    ok: true,
    sid: record.sid,
    resultKey: (record.protocol === 2 || record.agentKey) ? undefined : record.resultKey,
    host: record.host,
    pairPort: record.pairPort,
    connectPort: record.connectPort,
    code: record.code,
    model: record.model,
    relay: !!record.agentKey,
  });
}

/**
 * POST /api/pair/result
 * body: { sid, resultKey, state: 'paired'|'failed', detail? }
 */
async function handleResult(req, res) {
  const field = await readFields(req, LIMIT_JSON_BODY);
  const sid = cleanSid(field('sid'));
  const resultKey = String(field('resultKey') || '').trim();
  const state = String(field('state') || '').trim();
  if (!sid || !resultKey || (state !== 'paired' && state !== 'failed' && state !== 'configuring')) {
    return sendJson(res, 400, { ok: false, error: 'sid, resultKey, state required' });
  }
  const record = [...offers.values()].find((o) => o.sid === sid);
  if (!record) return sendJson(res, 404, { ok: false, error: 'session not found' });
  if (record.resultKey !== resultKey) return sendJson(res, 401, { ok: false, error: 'bad result key' });
  if (isTerminal(record)) return sendJson(res, 409, {ok:false,error:'session already finished'});
  console.info('[pair]', record.offerId.slice(0,8), state);
  record.state = state;
  if (state === 'configuring') record.expiresAt = Date.now() + 30 * 60 * 1000;
  if (isTerminal(record)) tunnel.revoke(record.offerId);
  record.detail = String(field('detail') || '').trim().slice(0, 200);
  record.finishedAt = Date.now();
  return sendJson(res, 200, { ok: true });
}

/**
 * GET /api/pair/status?offerId=..
 * 引导壳轮询：拿到 sid 用于显示，state 变化驱动自己收工/重试。
 */
function handleStatus(req, res, url) {
  if (!checkReportToken(req, res)) return undefined;
  const offerId = cleanOfferId(url.searchParams.get('offerId'));
  if (!offerId) return sendJson(res, 400, { ok: false, error: 'offerId required' });
  const record = offers.get(offerId);
  if (!record) return sendJson(res, 404, { ok: false, error: 'not found' });
  return sendJson(res, 200, {
    ok: true,
    sid: record.sid,
    state: isExpired(record, Date.now()) && !isTerminal(record) ? 'expired' : record.state,
    detail: record.detail || '',
    fetched: record.fetched,
  });
}

/* ------------------------- 落地页 + APK 下载 ------------------------- */

/**
 * GET /pair?sid=XXXXXX
 * 被控端打开的落地页：下载安装 devguide → deep link 唤起配对。
 */
function handleLandingPage(req, res, url) {
  const sid = cleanSid(url.searchParams.get('sid'));
  if (!sid) return sendHtml(res, '<h1>缺少会话号</h1><p>请从控制端获取的链接打开。</p>');

  const apkUrl = '/api/pair/apk';
  const deepLink = `devguide://pair?sid=${sid}`;
  const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>远程协助配对</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,system-ui,sans-serif;background:#f5f5f5;color:#333;line-height:1.6}
.wrap{max-width:480px;margin:0 auto;padding:24px 20px 48px}
.card{background:#fff;border-radius:16px;padding:24px 20px;margin-bottom:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
h1{font-size:22px;margin-bottom:8px}
.sid{font-family:monospace;font-size:36px;font-weight:bold;letter-spacing:4px;text-align:center;color:#1a73e8;margin:12px 0}
.step{display:flex;align-items:flex-start;gap:12px;margin-bottom:16px}
.step:last-child{margin-bottom:0}
.dot{flex-shrink:0;width:28px;height:28px;border-radius:50%;background:#1a73e8;color:#fff;font-size:14px;font-weight:bold;display:flex;align-items:center;justify-content:center}
.step-body{flex:1}
.step-body h3{font-size:15px;margin-bottom:2px}
.step-body p{font-size:13px;color:#666}
.btn{display:block;width:100%;padding:14px;border:none;border-radius:12px;font-size:16px;font-weight:bold;text-align:center;text-decoration:none;cursor:pointer;transition:opacity .2s}
.btn:active{opacity:.7}
.btn-primary{background:#1a73e8;color:#fff}
.btn-secondary{background:#e8f0fe;color:#1a73e8;margin-top:10px}
.hint{text-align:center;font-size:12px;color:#999;margin-top:8px}
</style>
</head>
<body>
<div class="wrap">
  <div class="card" style="text-align:center">
    <h1>远程协助配对</h1>
    <p>会话号</p>
    <div class="sid">${sid}</div>
    <p style="font-size:13px;color:#999">请在需要被协助的手机上打开本页</p>
  </div>

  <div class="card">
    <div class="step">
      <div class="dot">1</div>
      <div class="step-body">
        <h3>下载安装引导程序</h3>
        <p>点击下方按钮下载 USBDebugGuide，下载完成后点击安装</p>
      </div>
    </div>
    <a class="btn btn-primary" href="${apkUrl}" download>下载引导程序</a>
    <div class="hint">如果浏览器拦截下载，请在设置中允许此网站下载文件</div>
  </div>

  <div class="card">
    <div class="step">
      <div class="dot">2</div>
      <div class="step-body">
        <h3>开启配对</h3>
        <p>安装完成后，点击下方按钮打开引导程序并自动带入会话号，再点击开始协助</p>
      </div>
    </div>
    <a class="btn btn-primary" href="${deepLink}">开启配对</a>
    <div class="hint">如果没反应，说明还没安装成功，请先完成上一步</div>
  </div>

  <div class="card">
    <div class="step">
      <div class="dot">3</div>
      <div class="step-body">
        <h3>按引导操作</h3>
        <p>点击开始协助，按步骤开启授权。连接成功后由主控端继续配置；悬浮提示可选，也可下拉通知查看进度。</p>
      </div>
    </div>
  </div>
</div>
</body>
</html>`;
  return sendHtml(res, html);
}

/**
 * GET /api/pair/apk
 * devguide APK 下载。
 */
function handleApkDownload(req, res, apkName = PAIR_APK_NAME) {
  const filePath = path.join(PAIR_APK_DIR, apkName);
  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    return sendJson(res, 404, { ok: false, error: 'APK not found on server' });
  }
  const stat = fs.statSync(filePath);
  res.writeHead(200, {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${apkName}"`,
    'Cache-Control': 'no-cache',
  });
  return fs.createReadStream(filePath).pipe(res);
}

function register(router) {
  tunnel.register(router);
  router.on('POST', '/api/pair/join', async (req, res) => {
    if (!checkReportToken(req, res)) return;
    sweep();
    const field = await readFields(req, LIMIT_JSON_BODY);
    const record = [...offers.values()].find(o => o.sid === cleanSid(field('sid')));
    if (!record || isTerminal(record)) return sendJson(res, 404, {ok:false,error:'邀请已失效，请让主控端生成新链接'});
    if (record.agentKey && record.agentKey !== field('agentKey')) return sendJson(res, 409, {ok:false,error:'此邀请已被使用，请生成新链接'});
    record.agentKey = record.agentKey || randomToken(24);
    record.agentSeenAt = Date.now();
    console.info('[pair]', record.offerId.slice(0,8), 'guide online');
    record.stage = '对方已上线，正在准备授权';
    return sendJson(res, 200, {ok:true, offerId:record.offerId, agentKey:record.agentKey});
  });
  router.on('GET', '/api/pair/controller-status', (req, res, url) => {
    const record = [...offers.values()].find(o => o.sid === cleanSid(url.searchParams.get('sid')));
    if (!record || req.headers['x-pair-controller-key'] !== record.resultKey) return sendJson(res, 403, {ok:false,error:'bad controller key'});
    return sendJson(res, 200, {ok:true,state:record.state,detail:record.detail || ''});
  });
  router.on('POST', '/api/pair/agent', async (req, res) => {
    const field = await readFields(req, LIMIT_JSON_BODY);
    const record = offers.get(cleanOfferId(field('offerId')));
    if (!record || isExpired(record, Date.now())) return sendJson(res, 404, {ok:false,error:'邀请已过期，请重新生成链接'});
    if (!record.agentKey || req.headers['x-pair-key'] !== record.agentKey) return sendJson(res, 401, {ok:false,error:'bad device key'});
    if (field('cancel') && !isTerminal(record)) {
      record.state = 'failed'; record.detail = '被控端已停止协助'; record.finishedAt = Date.now(); tunnel.revoke(record.offerId);
    }
    record.stage = String(field('stage') || '').slice(0, 120);
    return sendJson(res, 200, {ok:true, state:record.state, detail:record.detail || '', tunnels:isTerminal(record) ? [] : tunnel.poll(record)});
  });
  router.on('POST', '/api/pair/alloc', handleAlloc);
  router.on('POST', '/api/pair/offer', handleOffer);
  router.on('GET', '/api/pair/fetch', handleFetch);
  router.on('POST', '/api/pair/result', handleResult);
  router.on('GET', '/api/pair/status', handleStatus);
  router.on('GET', '/pair', handleLandingPage);
  router.on('GET', '/api/pair/apk', (req,res) => handleApkDownload(req,res));
  router.on('GET', '/api/pair/controller-apk', (req,res) => handleApkDownload(req,res,'Easycontrol-v1.5.24-preview.apk'));
}

module.exports = { register, attach: tunnel.attach };
