// 授权域：试用期、邀请码、分享奖励时长
//
// 规则（全部由服务端算，客户端只缓存结果）：
//   到期时间 = 试用起点 + (30 天试用 + 邀请奖励天数 + 被邀请奖励天数)
//   试用起点 = max(本模块上线时间, 该设备第一次访问本接口的时间)
//             —— 取 max 是为了让功能上线前就装了 App 的老用户也有完整 30 天，不会升级即锁死。
//
// 奖励曲线（1 人 3 天、10 人 30 天、20 人 90 天，中间线性插值，不留零奖励死区）：
//   n < 10   → 3n
//   10≤n<20  → 30 + 6(n-10)
//   n ≥ 20   → 90 + 3(n-20)
//
// 邀请码只能由「新设备」填写，且一台设备一辈子只能填一次：这是防刷的主闸门，
// 否则两个老用户互填对方的码就能凭空刷出时长。

const crypto = require('crypto');
const path = require('path');

const { JsonStore } = require('./store');
const { DATA_DIR } = require('./config');
const { esc } = require('./util');
const { readJson, sendJson, sendHtml, clientIp } = require('./http');

const LICENSE_FILE = path.join(DATA_DIR, 'license.json');

const DAY_MS = 24 * 60 * 60 * 1000;

/* 试用天数：从试用起点算起 */
const TRIAL_DAYS = 30;

/* 填写别人的邀请码后，被邀请人自己也得到的天数（没有这个奖励就没人愿意填码） */
const INVITEE_BONUS_DAYS = 7;

/* 只有「第一次访问本接口」在这个窗口内的设备才允许填邀请码 */
const BIND_WINDOW_DAYS = 7;

/* 同一出口 IP 在 24 小时内最多接受几次绑定，超过视为刷量。
   运营商 NAT / 校园网下大量真实用户共用一个出口 IP，这个值不能定得太小，
   它真正要拦的是「一台机器造几十个假 ANDROID_ID」那种刷量。 */
const BIND_IP_LIMIT = 10;
const BIND_IP_WINDOW_MS = DAY_MS;

/* 绑定日志保留条数，只为 IP 限流服务，不需要长期留存 */
const BIND_LOG_LIMIT = 2000;

/* 邀请码字符集：去掉了 0/O/1/I/2/Z/5/S/8/B 这些手抄容易认错的字符 */
const CODE_ALPHABET = 'ACDEFGHJKLMNPQRTUVWXY34679';
const CODE_LENGTH = 6;

/* 分享落地页里给新用户的下载入口 */
const DOWNLOAD_PAGE = 'https://github.com/zi-ek/AutoSlide/releases/latest';
const LANZOU_PAGE = 'https://q-sj.lanzoum.com/b0pnt04li';
const LANZOU_PASSWORD = 'lanr';

const licenseStore = new JsonStore(LICENSE_FILE, () => ({
  launchedAt: Date.now(),
  codes: {},
  devices: {},
  bindLog: [],
}));

/* ==================== 规则计算 ==================== */

/**
 * 邀请 n 个人对应的奖励天数。
 * 三个关键点与对外承诺一致：1 人 3 天、10 人 30 天（一月）、20 人 90 天（三月）。
 */
function bonusDaysFor(n) {
  const count = Number(n) || 0;
  if (count <= 0) return 0;
  if (count < 10) return 3 * count;
  if (count < 20) return 30 + 6 * (count - 10);
  return 90 + 3 * (count - 20);
}

/* 生成一个未被占用的邀请码 */
function makeCode(data) {
  for (let attempt = 0; attempt < 100; attempt++) {
    const bytes = crypto.randomBytes(CODE_LENGTH);
    let code = '';
    for (let i = 0; i < CODE_LENGTH; i++) {
      code += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
    }
    if (!data.codes[code]) return code;
  }
  // 理论上走不到：26^6 的空间里连撞 100 次
  return crypto.randomBytes(8).toString('hex').toUpperCase().slice(0, CODE_LENGTH);
}

/**
 * 取出设备记录，没有就地创建（首次访问即视为试用起点）。
 * 只能在 licenseStore.update 内部调用，data 会被就地改写。
 */
function ensureDevice(data, deviceId) {
  if (!data.launchedAt) data.launchedAt = Date.now();
  if (!data.codes) data.codes = {};
  if (!data.devices) data.devices = {};
  let device = data.devices[deviceId];
  if (!device) {
    device = {
      code: makeCode(data),
      firstSeenAt: Date.now(),
      lastSeenAt: Date.now(),
      invitedBy: null,
      invitees: [],
      boundAt: null,
      bindIp: '',
    };
    data.devices[deviceId] = device;
    data.codes[device.code] = deviceId;
  } else {
    device.lastSeenAt = Date.now();
    // 兜底：历史数据缺码、或码表与设备表对不上时补回来
    if (!device.code) device.code = makeCode(data);
    if (!Array.isArray(device.invitees)) device.invitees = [];
    if (data.codes[device.code] !== deviceId) data.codes[device.code] = deviceId;
  }
  return device;
}

/* 把设备记录换算成对外的授权状态 */
function toLicenseView(data, deviceId, device, baseUrl) {
  const trialStartAt = Math.max(data.launchedAt || 0, device.firstSeenAt || 0);
  const invitedCount = (device.invitees || []).length;
  const bonusDays = bonusDaysFor(invitedCount) + (device.invitedBy ? INVITEE_BONUS_DAYS : 0);
  const expireAt = trialStartAt + (TRIAL_DAYS + bonusDays) * DAY_MS;
  const now = Date.now();
  const canBind = !device.invitedBy && now - (device.firstSeenAt || 0) <= BIND_WINDOW_DAYS * DAY_MS;
  return {
    ok: true,
    deviceId,
    code: device.code,
    trialDays: TRIAL_DAYS,
    trialStartAt,
    invitedCount,
    bonusDays,
    inviteeBonusDays: INVITEE_BONUS_DAYS,
    expireAt,
    expired: now >= expireAt,
    remainDays: Math.max(0, Math.ceil((expireAt - now) / DAY_MS)),
    canBind,
    invitedBy: device.invitedBy || '',
    inviteUrl: baseUrl ? baseUrl + '/invite?code=' + device.code : '',
    serverTime: now,
  };
}

/* 从请求还原对外可访问的站点地址，用于拼分享链接 */
function baseUrlOf(req) {
  const proto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() || 'https';
  const host = req.headers.host || 'localhost';
  return proto + '://' + host;
}

/* ==================== 接口 ==================== */

/* GET /api/license?deviceId=..  查询授权状态（首次访问即建档，试用从这一刻起算） */
async function handleQuery(req, res, url) {
  const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
  if (!deviceId) return sendJson(res, 400, { ok: false, reason: 'deviceId 不能为空' });

  const view = await licenseStore.update((data) => {
    const device = ensureDevice(data, deviceId);
    return toLicenseView(data, deviceId, device, baseUrlOf(req));
  });
  sendJson(res, 200, view);
}

/* 统计某个 IP 在窗口期内的绑定次数 */
function recentBindCount(data, ip) {
  if (!ip) return 0;
  const since = Date.now() - BIND_IP_WINDOW_MS;
  return (data.bindLog || []).filter((r) => r.ip === ip && r.at >= since).length;
}

/* POST /api/license/bind  {deviceId, code}  新设备填写邀请码 */
async function handleBind(req, res) {
  const payload = await readJson(req);
  const deviceId = String(payload.deviceId || '').slice(0, 64);
  const code = String(payload.code || '').trim().toUpperCase().slice(0, 16);
  if (!deviceId || !code) return sendJson(res, 400, { ok: false, reason: '参数不完整' });
  const ip = clientIp(req);

  const result = await licenseStore.update((data) => {
    const device = ensureDevice(data, deviceId);

    // 1. 一台设备只能填一次，填过就锁死
    if (device.invitedBy) return { ok: false, reason: '这台设备已经填过邀请码了' };
    // 2. 只有新设备能填：老用户互填对方的码是最容易出现的刷量方式
    if (Date.now() - (device.firstSeenAt || 0) > BIND_WINDOW_DAYS * DAY_MS) {
      return { ok: false, reason: '邀请码只能在首次安装后 ' + BIND_WINDOW_DAYS + ' 天内填写' };
    }
    // 3. 码得存在
    const inviterId = data.codes[code];
    if (!inviterId || !data.devices[inviterId]) return { ok: false, reason: '邀请码不存在' };
    // 4. 不能填自己的码
    if (inviterId === deviceId) return { ok: false, reason: '不能填写自己的邀请码' };
    const inviter = data.devices[inviterId];
    // 5. 互填成环：A 已经填过 B 的码，B 就不能再回填 A 的码
    if (inviter.invitedBy === deviceId) return { ok: false, reason: '不能互相填写邀请码' };
    // 6. 同一出口 IP 短时间内绑定过多，按刷量拒绝
    if (recentBindCount(data, ip) >= BIND_IP_LIMIT) {
      return { ok: false, reason: '同一网络绑定次数过多，请稍后再试' };
    }

    device.invitedBy = inviterId;
    device.boundAt = Date.now();
    device.bindIp = ip;
    if (!Array.isArray(inviter.invitees)) inviter.invitees = [];
    if (!inviter.invitees.includes(deviceId)) inviter.invitees.push(deviceId);

    data.bindLog = [...(data.bindLog || []), { at: Date.now(), ip, deviceId, code }].slice(-BIND_LOG_LIMIT);

    return toLicenseView(data, deviceId, device, baseUrlOf(req));
  });

  sendJson(res, result.ok ? 200 : 400, result);
}

/* GET /invite?code=..  分享落地页：新用户从这里下载并抄走邀请码 */
function handleInvitePage(req, res, url) {
  const code = String(url.searchParams.get('code') || '').trim().toUpperCase().slice(0, 16);
  const data = licenseStore.read();
  const valid = Boolean(code && data.codes && data.codes[code]);
  sendHtml(res, invitePageHtml(code, valid));
}

/* GET /invites  邀请榜：自己排查刷量用，与统计看板一样不做鉴权 */
function handleInviteBoard(req, res) {
  sendHtml(res, inviteBoardHtml(licenseStore.read()));
}

/* ==================== 页面 ==================== */

const PAGE_STYLE = [
  'body{margin:0;padding:24px;background:#f5f6f8;color:#1c1c1e;',
  'font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}',
  '.card{max-width:520px;margin:0 auto;background:#fff;border-radius:16px;padding:24px}',
  'h1{font-size:20px;margin:0 0 16px}',
  '.code{font-size:32px;font-weight:700;letter-spacing:6px;color:#0a84ff;text-align:center;',
  'padding:16px;background:#f0f6ff;border-radius:12px;margin:16px 0}',
  'ol{padding-left:20px;line-height:1.9}a{color:#0a84ff}',
  '.tip{color:#8e8e93;font-size:13px;margin-top:16px}',
  'table{width:100%;border-collapse:collapse;background:#fff;font-size:14px}',
  'th,td{padding:8px 10px;border-bottom:1px solid #eee;text-align:left}th{background:#fafafa}',
].join('');

function invitePageHtml(code, valid) {
  const download =
    '<a href="' + DOWNLOAD_PAGE + '">GitHub 下载</a> ｜ ' +
    '<a href="' + LANZOU_PAGE + '">网盘下载</a>（密码 ' + LANZOU_PASSWORD + '）';
  const body = valid
    ? '<p>你的好友邀请你使用 <b>自动滑屏器</b>，装好后填下面这个邀请码，你们双方都能得到使用时长：</p>' +
      '<div class="code">' + esc(code) + '</div>' +
      '<ol><li>下载安装：' + download + '</li>' +
      '<li>打开 App，同意使用声明</li>' +
      '<li>在主界面点「分享得时长」，填入上面的邀请码</li></ol>' +
      '<p class="tip">邀请码只能在首次安装后 ' + BIND_WINDOW_DAYS +
      ' 天内填写，每台设备只能填一次；填写成功双方各自到账。</p>'
    : '<p>这个邀请码不存在或已失效。</p><p>你仍然可以直接下载使用：' + download + '</p>';
  return '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>邀请你使用自动滑屏器</title><style>' + PAGE_STYLE + '</style></head>' +
    '<body><div class="card"><h1>自动滑屏器 · 好友邀请</h1>' + body + '</div></body></html>';
}

function inviteBoardHtml(data) {
  const devices = data.devices || {};
  const rows = Object.entries(devices)
    .map(([deviceId, d]) => toLicenseView(data, deviceId, d, ''))
    .sort((a, b) => b.invitedCount - a.invitedCount || b.expireAt - a.expireAt)
    .slice(0, 500)
    .map((r) =>
      '<tr><td>' + esc(r.deviceId) + '</td><td>' + esc(r.code) + '</td><td>' + r.invitedCount +
      '</td><td>' + r.bonusDays + '</td><td>' +
      new Date(r.expireAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) + '</td><td>' +
      (r.expired ? '已到期' : r.remainDays + ' 天') + '</td><td>' + esc(r.invitedBy) + '</td></tr>'
    )
    .join('');
  return '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>邀请与时长</title><style>' + PAGE_STYLE + '</style></head><body>' +
    '<h1>邀请与时长（共 ' + Object.keys(devices).length + ' 台设备）</h1>' +
    '<table><tr><th>设备ID</th><th>邀请码</th><th>邀请人数</th><th>奖励天数</th>' +
    '<th>到期时间</th><th>剩余</th><th>被谁邀请</th></tr>' + rows + '</table></body></html>';
}

function register(router) {
  router.on('GET', '/api/license', handleQuery);
  router.on('POST', '/api/license/bind', handleBind);
  router.on('GET', '/invite', handleInvitePage);
  router.on('GET', '/invites', handleInviteBoard);
}

module.exports = { register, bonusDaysFor, TRIAL_DAYS, INVITEE_BONUS_DAYS, BIND_WINDOW_DAYS };
