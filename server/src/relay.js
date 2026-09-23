// 直连兜底 · WebSocket 中转
//
// 场景：Easycontrol 控制端直连被控端失败（跨网络/NAT/蜂窝入站被封）时的兜底。
// 两端都**出站**连到本服务（走现有 http server + Cloudflare 隧道，wss://.../relay/*），
// 由服务端按会话配对、互转二进制帧。中转只搬字节、不懂也不碰 ADB，端到端的 ADB RSA
// 鉴权照常成立——就算中转被入侵，没有密钥也控不了被控端。
//
// 三个入口：
//   WS  GET /relay/control?serial=&token=<report>   被控端 agent 常驻，收 {type:'open'|'poke'} 推送
//   HTTP GET /relay/open?serial=&token=<admin>      控制端请求开会话，返回 {ok,session}
//   HTTP GET /relay/tcpip?serial=&token=<admin>     控制端即时戳一下：让在线 agent 立刻叫 adbboot 重开 tcpip
//   WS  GET /relay/data?session=&side=client|agent  双方各开一条，配对后互转
//
// 令牌分工：agent 用 REPORT_TOKEN（编译进 APK，半公开）；控制端 open 用 ADMIN_TOKEN
// （看板口令，只在机主手里）——即"谁能唤醒这台设备"这道门。data 通道以不可猜的 session 作凭据。
const WebSocket = require('ws');
const crypto = require('crypto');
const { REPORT_TOKEN, ADMIN_TOKEN } = require('./config');
const { sendJson } = require('./http');

const { readStats } = require('./stats');

const AGENTS = new Map();    // serial -> 控制 ws
const SESSIONS = new Map();  // session -> { client, agent, queue:{client:[],agent:[]}, timer }
const SESSION_TTL_MS = 60 * 1000;

let wss = null;

function attach(server) {
  wss = new WebSocket.Server({ noServer: true });
  server.on('upgrade', (req, socket, head) => {
    let url;
    try {
      url = new URL(req.url, 'http://x');
    } catch (e) {
      socket.destroy();
      return;
    }
    const p = url.pathname;
    if (p !== '/relay/control' && p !== '/relay/data') {
      socket.destroy(); // 本服务没有别的 WS，非中转的 upgrade 一律拒绝
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => onSocket(ws, p, url.searchParams));
  });
}

function onSocket(ws, path, q) {
  if (path === '/relay/control') {
    if (!REPORT_TOKEN || q.get('token') !== REPORT_TOKEN) return ws.close(4003, 'bad token');
    const serial = String(q.get('serial') || '');
    if (!/^[A-Za-z0-9._:-]{1,64}$/.test(serial)) return ws.close(4000, 'bad serial');
    const role = q.get('agent') === 'adbboot' ? 'adbboot' : 'legacy';
    const device = (readStats().devices || []).find((d) => d.ecSerial === serial);
    if (role !== 'adbboot' && (device?.agentOwner === 'adbboot' || AGENTS.get(serial)?.agentRole === 'adbboot')) {
      return ws.close(4009, 'moved to adbboot');
    }
    ws.agentRole = role;
    const prev = AGENTS.get(serial);
    if (prev && prev !== ws) { try { prev.close(); } catch (e) {} }
    AGENTS.set(serial, ws);
    ws.on('close', () => { if (AGENTS.get(serial) === ws) AGENTS.delete(serial); });
    ws.on('error', () => {});
    console.log(`[relay] agent online: ${serial}`);
    return;
  }

  // /relay/data
  const session = String(q.get('session') || '');
  const side = String(q.get('side') || '');
  const s = SESSIONS.get(session);
  if (!s || (side !== 'client' && side !== 'agent') || s[side]) return ws.close(4004, 'bad session');
  s[side] = ws;
  const peerSide = side === 'client' ? 'agent' : 'client';

  // 立刻挂转发：对端已在就直发，未到就先缓存——避免配对前发出的字节(如 CNXN)丢失
  ws.on('message', (data) => {
    const peer = s[peerSide];
    if (peer && peer.readyState === WebSocket.OPEN) peer.send(data, { binary: true });
    else s.queue[side].push(data);
  });
  const closeBoth = () => {
    clearTimeout(s.timer);
    SESSIONS.delete(session);
    try { if (s.client) s.client.close(); } catch (e) {}
    try { if (s.agent) s.agent.close(); } catch (e) {}
  };
  ws.on('close', closeBoth);
  ws.on('error', closeBoth);

  if (s.client && s.agent) {
    clearTimeout(s.timer);
    // 双方到齐，把各自积压的帧 flush 给对端
    for (const d of s.queue.client) { if (s.agent.readyState === WebSocket.OPEN) s.agent.send(d, { binary: true }); }
    for (const d of s.queue.agent) { if (s.client.readyState === WebSocket.OPEN) s.client.send(d, { binary: true }); }
    s.queue = { client: [], agent: [] };
    console.log(`[relay] session bridged: ${session}`);
  }
}

// GET /relay/open?serial=&token=<admin>
function handleOpen(req, res, url) {
  if (!ADMIN_TOKEN || url.searchParams.get('token') !== ADMIN_TOKEN) {
    return sendJson(res, 403, { ok: false, error: 'bad token' });
  }
  const serial = String(url.searchParams.get('serial') || '');
  const agent = AGENTS.get(serial);
  if (!agent || agent.readyState !== WebSocket.OPEN) {
    return sendJson(res, 404, { ok: false, error: 'agent offline' });
  }
  const session = crypto.randomBytes(12).toString('hex');
  const s = { client: null, agent: null, queue: { client: [], agent: [] }, timer: null };
  SESSIONS.set(session, s);
  // 到点还没配上对：关掉已连的一端并清理，控制端那侧会因此 read 失败、回退报错
  s.timer = setTimeout(() => {
    if (!(s.client && s.agent)) {
      SESSIONS.delete(session);
      try { if (s.client) s.client.close(); } catch (e) {}
      try { if (s.agent) s.agent.close(); } catch (e) {}
    }
  }, SESSION_TTL_MS);
  agent.send(JSON.stringify({ type: 'open', session }));
  return sendJson(res, 200, { ok: true, session });
}

// GET /relay/tcpip?serial=&token=<admin>
// 「远程再开 tcpip」的即时通道。控制端下发指令后顺手戳这里：agent 在线就立刻推
// {type:'poke'}，被控端 AutoSlide 收到即广播给 adbboot 立即执行，秒级生效；agent 不在线
// 就返回 delivered:false，控制端照旧靠 adbboot 那条 15 分钟 JobScheduler 轮询兜底。
// 因此这里 agent 离线不算错误（仍回 200），只是把是否即时送达如实告诉控制端。
function handlePoke(req, res, url) {
  if (!ADMIN_TOKEN || url.searchParams.get('token') !== ADMIN_TOKEN) {
    return sendJson(res, 403, { ok: false, error: 'bad token' });
  }
  const serial = String(url.searchParams.get('serial') || '');
  const agent = AGENTS.get(serial);
  if (!agent || agent.readyState !== WebSocket.OPEN) {
    return sendJson(res, 200, { ok: true, delivered: false });
  }
  try {
    agent.send(JSON.stringify({ type: 'poke' }));
  } catch (e) {
    return sendJson(res, 200, { ok: true, delivered: false });
  }
  console.log(`[relay] poke tcpip -> ${serial}`);
  return sendJson(res, 200, { ok: true, delivered: true });
}

function register(router) {
  router.on('GET', '/relay/open', handleOpen);
  router.on('GET', '/relay/tcpip', handlePoke);
}

module.exports = { attach, register };
