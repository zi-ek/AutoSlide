// 聊天域：频道、消息、图片、公告

const fs = require('fs');
const { JsonStore } = require('./store');
const {
  CHAT_FILE,
  CHAT_IMAGE_DIR,
  CHAT_MESSAGE_LIMIT,
  CHAT_RETENTION_DAYS,
  CHAT_CLEANUP_INTERVAL_MS,
  LIMIT_CHAT_BODY,
  LIMIT_CHAT_IMAGE,
  LIMIT_JSON_BODY,
} = require('./config');
const { nowCN } = require('./util');
const { sanitize, safeJoin } = require('./paths');
const { readJson, readFields, sendJson } = require('./http');

const chatStore = new JsonStore(CHAT_FILE, () => ({ channels: [] }));

/* 只读场景 */
function readChat() {
  return chatStore.read();
}

/* 读-改-写场景：走队列 + 原子写 */
function updateChat(fn) {
  return chatStore.update(fn);
}

/* 供统计看板渲染公告栏使用 */
function readAnnouncement() {
  return readChat().announcement || { title: '公告栏', content: '', updatedAt: '' };
}

/* 邀请码字符集去掉了容易看错的 I/O/0/1 */
function genChannelCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let code = '';
  for (let i = 0; i < 6; i++) code += chars[Math.floor(Math.random() * chars.length)];
  return code;
}

/* 频道的对外视图：不含 messages 全量，只带最后一条摘要 */
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

/* ==================== 路由 ==================== */

async function handleCreate(req, res) {
  const payload = await readJson(req);
  const name = String(payload.name || '').trim().slice(0, 50);
  const deviceId = String(payload.deviceId || '').slice(0, 64);
  const deviceName = String(payload.deviceName || '').slice(0, 50);
  if (!name || !deviceId) {
    return sendJson(res, 400, { ok: false, error: 'name and deviceId required' });
  }
  const channel = await updateChat((chat) => {
    let code = genChannelCode();
    while (chat.channels.some((c) => c.code === code)) code = genChannelCode();
    const now = nowCN();
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
  sendJson(res, 200, { ok: true, channel: chatChannelModel(channel, deviceId) });
}

async function handleJoin(req, res) {
  const payload = await readJson(req);
  const channelId = String(payload.channelId || '').slice(0, 80);
  const deviceId = String(payload.deviceId || '').slice(0, 64);
  const deviceName = String(payload.deviceName || '').slice(0, 50);
  if (!channelId || !deviceId) {
    return sendJson(res, 400, { ok: false, error: 'channelId and deviceId required' });
  }
  const joined = await updateChat((chat) => {
    const ch = chat.channels.find((c) => c.id === channelId);
    if (!ch) return null;
    if (!(ch.members || []).some((m) => m.deviceId === deviceId)) {
      ch.members.push({ deviceId, name: deviceName || deviceId, joinedAt: nowCN() });
    }
    return ch;
  });
  if (!joined) return sendJson(res, 404, { ok: false, error: 'channel not found' });
  sendJson(res, 200, { ok: true, channel: chatChannelModel(joined, deviceId) });
}

function handleChannels(req, res, url) {
  const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
  const channels = readChat()
    .channels.map((c) => chatChannelModel(c, deviceId))
    .sort((a, b) =>
      String(b.lastMessageTime || b.createdAt).localeCompare(String(a.lastMessageTime || a.createdAt))
    );
  sendJson(res, 200, { ok: true, channels });
}

function handleChannel(req, res, url) {
  const channelId = String(url.searchParams.get('id') || '').slice(0, 80);
  const deviceId = String(url.searchParams.get('deviceId') || '').slice(0, 64);
  const ch = readChat().channels.find((c) => c.id === channelId);
  if (!ch) return sendJson(res, 404, { ok: false, error: 'channel not found' });
  sendJson(res, 200, { ok: true, channel: chatChannelModel(ch, deviceId) });
}

function handleMessages(req, res, url) {
  const channelId = String(url.searchParams.get('channelId') || '').slice(0, 80);
  const after = parseInt(url.searchParams.get('after') || '0', 10) || 0;
  const ch = readChat().channels.find((c) => c.id === channelId);
  if (!ch) return sendJson(res, 404, { ok: false, error: 'channel not found' });
  sendJson(res, 200, { ok: true, messages: (ch.messages || []).filter((m) => m.seq > after) });
}

const IMAGE_TYPES = { '.png': 'image/png', '.gif': 'image/gif', '.webp': 'image/webp' };

function handleImage(req, res, url) {
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
  const ext = file.slice(file.lastIndexOf('.')).toLowerCase();
  res.writeHead(200, {
    'Content-Type': IMAGE_TYPES[ext] || 'image/jpeg',
    'Cache-Control': 'public, max-age=86400',
  });
  fs.createReadStream(p).pipe(res);
}

async function handleSend(req, res) {
  const payload = await readJson(req, LIMIT_CHAT_BODY);
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
    if (!imageBuf.length || imageBuf.length > LIMIT_CHAT_IMAGE) {
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
      // 毫秒时间戳：过期清理靠它判断，比解析本地化的 time 字符串可靠
      ts: Date.now(),
      channelId,
      deviceId,
      name: deviceName || deviceId,
      text,
      type: imageBuf ? 'image' : 'text',
      image: imagePath,
      time: nowCN(),
    };
    ch.messages = ch.messages || [];
    ch.messages.push(created);
    if (ch.messages.length > CHAT_MESSAGE_LIMIT) {
      ch.messages = ch.messages.slice(-CHAT_MESSAGE_LIMIT);
    }
    return created;
  });
  if (!msg) return sendJson(res, 404, { ok: false, error: 'channel not found' });
  sendJson(res, 200, { ok: true, message: msg });
}

async function handleLeave(req, res) {
  const payload = await readJson(req);
  const channelId = String(payload.channelId || '').slice(0, 80);
  const deviceId = String(payload.deviceId || '').slice(0, 64);
  const left = await updateChat((chat) => {
    const ch = chat.channels.find((c) => c.id === channelId);
    if (!ch) return false;
    ch.members = (ch.members || []).filter((m) => m.deviceId !== deviceId);
    return true;
  });
  if (!left) return sendJson(res, 404, { ok: false, error: 'channel not found' });
  sendJson(res, 200, { ok: true });
}

async function handleDelete(req, res) {
  const payload = await readJson(req);
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
  removeChannelImages(outcome.removed);
  sendJson(res, 200, { ok: true });
}

/* 删除频道后清理它上传过的图片 */
function removeChannelImages(channel) {
  if (!fs.existsSync(CHAT_IMAGE_DIR)) return;
  for (const msg of channel.messages || []) {
    if (msg.type !== 'image' || !msg.image) continue;
    const file = decodeURIComponent(String(msg.image).split('file=')[1] || '');
    if (!file) continue;
    try {
      fs.unlinkSync(safeJoin(CHAT_IMAGE_DIR, file));
    } catch (e) {
      /* 文件已不在或路径异常，忽略 */
    }
  }
}

/* 取消息时间：优先用 ts；老消息没有该字段时回退解析本地化的 time 字符串 */
function messageTime(msg) {
  if (Number.isFinite(msg.ts)) return msg.ts;
  const parsed = Date.parse(String(msg.time || '').replace(/\//g, '-'));
  // 解析不出来的一律当作「刚刚」，宁可多留也不误删
  return Number.isFinite(parsed) ? parsed : Date.now();
}

/* 删除单条图片消息对应的文件 */
function removeMessageImage(msg) {
  if (msg.type !== 'image' || !msg.image) return;
  const file = decodeURIComponent(String(msg.image).split('file=')[1] || '');
  if (!file) return;
  try {
    fs.unlinkSync(safeJoin(CHAT_IMAGE_DIR, file));
  } catch (e) {
    /* 文件已不在或路径异常，忽略 */
  }
}

/**
 * 清除超过保留期的反馈消息与其图片。
 *
 * 反馈只用于沟通当下的问题，长期留存没有价值且扩大数据面，
 * 因此按 CHAT_RETENTION_DAYS 定期清除。频道本身保留，只删消息。
 *
 * @returns {Promise<{messages:number, images:number}>} 本次清除的数量
 */
async function cleanupExpiredMessages() {
  const cutoff = Date.now() - CHAT_RETENTION_DAYS * 24 * 60 * 60 * 1000;
  let removedMessages = 0;
  let removedImages = 0;

  await updateChat((chat) => {
    for (const ch of chat.channels || []) {
      const kept = [];
      for (const msg of ch.messages || []) {
        if (messageTime(msg) >= cutoff) {
          kept.push(msg);
          continue;
        }
        if (msg.type === 'image' && msg.image) {
          removeMessageImage(msg);
          removedImages += 1;
        }
        removedMessages += 1;
      }
      ch.messages = kept;
    }
  });

  if (removedMessages) {
    console.log(`[chat] 已清除 ${removedMessages} 条超过 ${CHAT_RETENTION_DAYS} 天的反馈消息，含 ${removedImages} 张图片`);
  }
  return { messages: removedMessages, images: removedImages };
}

/* 清除无引用的图片：频道被删或数据异常时残留的文件 */
function cleanupOrphanImages(chat) {
  if (!fs.existsSync(CHAT_IMAGE_DIR)) return 0;
  const referenced = new Set();
  for (const ch of chat.channels || []) {
    for (const msg of ch.messages || []) {
      if (msg.type !== 'image' || !msg.image) continue;
      referenced.add(decodeURIComponent(String(msg.image).split('file=')[1] || ''));
    }
  }
  let removed = 0;
  for (const file of fs.readdirSync(CHAT_IMAGE_DIR)) {
    if (referenced.has(file)) continue;
    try {
      fs.unlinkSync(safeJoin(CHAT_IMAGE_DIR, file));
      removed += 1;
    } catch (e) {
      /* 忽略 */
    }
  }
  if (removed) console.log(`[chat] 已清除 ${removed} 个无引用的图片文件`);
  return removed;
}

/* 启动时先跑一次，之后按间隔定期执行 */
function startCleanupTimer() {
  const run = async () => {
    try {
      await cleanupExpiredMessages();
      cleanupOrphanImages(readChat());
    } catch (e) {
      console.error('[chat] 清理失败', e);
    }
  };
  run();
  const timer = setInterval(run, CHAT_CLEANUP_INTERVAL_MS);
  // 定时器不应阻止进程退出
  if (timer.unref) timer.unref();
}

async function handleSaveAnnouncement(req, res) {
  const field = await readFields(req, LIMIT_JSON_BODY);
  const title = String(field('title') || '').trim().slice(0, 50);
  const content = String(field('content') || '').trim().slice(0, 2000);
  const announcement = await updateChat((chat) => {
    chat.announcement = { title: title || '公告栏', content, updatedAt: nowCN() };
    return chat.announcement;
  });
  // 后台是普通表单提交，浏览器会停在 JSON 响应上；这里让它回到看板。
  // App 走的是 JSON 请求（Accept 不含 text/html），仍然拿到原来的响应体。
  if (String(req.headers.accept || '').includes('text/html')) {
    res.writeHead(303, { Location: '/' });
    return res.end();
  }
  sendJson(res, 200, { ok: true, announcement });
}

function register(router) {
  router.on('GET', '/api/chat/announcement', (req, res) =>
    sendJson(res, 200, { ok: true, announcement: readAnnouncement() })
  );
  router.on('POST', '/api/chat/announcement', handleSaveAnnouncement);
  router.on('POST', '/api/chat/create', handleCreate);
  router.on('POST', '/api/chat/join', handleJoin);
  router.on('GET', '/api/chat/channels', handleChannels);
  router.on('GET', '/api/chat/channel', handleChannel);
  router.on('GET', '/api/chat/messages', handleMessages);
  router.on('GET', '/api/chat/image', handleImage);
  router.on('POST', '/api/chat/send', handleSend);
  router.on('POST', '/api/chat/leave', handleLeave);
  router.on('POST', '/api/chat/delete', handleDelete);
  startCleanupTimer();
}

module.exports = { register, readAnnouncement };
