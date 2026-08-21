// 上传域：录制脚本的接收、脚本库清单、管理与下载
//
// ⚠️ 本文件是**重建**的：服务器上「脚本库」那一版被旧版覆盖后没有备份，
// 代码也从没进过 git。重建依据有四处，都是当时留在服务器上的实物：
//   1. server.js 头部注释里记着的接口清单（/api/upload、/api/scripts、
//      /api/scripts/delete、/admin/scripts、/api/download）；
//   2. src/views/admin.js 幸存，scriptAdminHtml(files, tokenConfigured) 的
//      字段用法直接决定了清单记录的形状与删除表单的参数名；
//   3. data/uploads/index.json 幸存，是清单文件的真实 schema；
//   4. v3.4.0 的构建产物：app/build 下还留着那次 debug 编译出的 class，
//      反编译后能直接读到客户端怎么发请求、怎么解析响应——每个接口的
//      请求头与 JSON 键都据此逐一核对过，不是猜的。

const fs = require('fs');
const path = require('path');
const { JsonStore } = require('./store');
const { MANIFEST_FILE, UPLOAD_DIR, LIMIT_UPLOAD_BODY, LIMIT_JSON_BODY, ADMIN_TOKEN } = require('./config');
const { nowCN } = require('./util');
const { sanitize, safeJoin, resolveUploadPath } = require('./paths');
const { readBody, readFields, sendJson, sendHtml, clientIp } = require('./http');
const { lookupIpLocation } = require('./ip');
const { updateStats, touchDevice } = require('./stats');
const { viewHtml } = require('./views/pages');
const { scriptAdminHtml } = require('./views/admin');

const manifestStore = new JsonStore(MANIFEST_FILE, () => ({ files: [] }));

function readManifest() {
  return manifestStore.read();
}

function updateManifest(fn) {
  return manifestStore.update(fn);
}

/**
 * 从上传的正文里读出脚本名与动作数。
 *
 * v3.4.0 的 APK 只发 X-Script-Count 这一个统计头，脚本名和动作数没有对应的头，
 * 所以只能解析正文——正文本身是自描述的：
 *   {"count":1,"scripts":[{"name":"测试一","actions":[...],"actionCount":5}]}
 * 老客户端传的是整份 slide_settings.xml，解析失败就当作没有脚本信息。
 *
 * @param {string} body 请求正文
 * @returns {{scriptName: string, actionCount: number, scriptCount: number}}
 */
function parseScriptMeta(body) {
  const empty = { scriptName: '', actionCount: 0, scriptCount: 0 };
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    return empty;
  }
  const scripts = Array.isArray(parsed.scripts) ? parsed.scripts : [];
  if (!scripts.length) return empty;
  const first = scripts[0] || {};
  const actions = Array.isArray(first.actions) ? first.actions.length : 0;
  return {
    scriptName: String(first.name || '').slice(0, 100),
    actionCount: Number(first.actionCount) || actions,
    scriptCount: scripts.length,
  };
}

async function handleUpload(req, res) {
  const deviceId = sanitize(req.headers['x-device-id'], 'unknown');
  const deviceName = sanitize(req.headers['x-device-name'], 'unknown');
  const filename = sanitize(req.headers['x-filename'] || 'scripts.json', 'scripts.json');
  const body = await readBody(req, LIMIT_UPLOAD_BODY);

  const meta = parseScriptMeta(body.toString('utf8'));
  // 脚本数量优先取请求头；老客户端没有该头时用正文解析出来的数量
  let scriptCount = Number(req.headers['x-script-count']);
  if (!Number.isFinite(scriptCount) || scriptCount < 0) {
    scriptCount = meta.scriptCount;
  }

  const targetFile = safeJoin(UPLOAD_DIR, deviceId, filename);
  fs.mkdirSync(path.dirname(targetFile), { recursive: true });
  fs.writeFileSync(targetFile, body);

  const record = {
    deviceId,
    deviceName,
    filename,
    size: Buffer.byteLength(body),
    scriptCount,
    scriptName: meta.scriptName,
    actionCount: meta.actionCount,
    updatedAt: nowCN(),
  };
  await updateManifest((manifest) => {
    manifest.files = manifest.files || [];
    const idx = manifest.files.findIndex((f) => f.deviceId === deviceId && f.filename === filename);
    if (idx >= 0) {
      manifest.files[idx] = record;
    } else {
      manifest.files.push(record);
    }
    manifest.files.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  });

  // 上传脚本说明设备在线：顺带刷新统计里的 IP 与最近上报时间
  const ip = clientIp(req);
  const ipLoc = await lookupIpLocation(ip);
  await updateStats((stats) => {
    touchDevice(stats, deviceId, {
      ip,
      ipLoc,
      lastSeen: nowCN(),
      scriptCount,
      scriptFile: filename,
      scriptUpdatedAt: nowCN(),
    });
  });

  sendJson(res, 200, { ok: true, saved: filename });
}

/* 已分享的单条脚本：文件名形如 script_<hash>.json，老的整份配置备份不算 */
function isSharedScript(file) {
  return String(file.filename || '').startsWith('script_') && file.filename.endsWith('.json');
}

/**
 * 脚本库清单，App「我的 → 脚本库」拉这个。
 *
 * 响应外形已用 v3.4.0 的 debug 构建产物（app/build 下的 MacroSync$listLibrary$2.class，
 * 反编译后可读）核对过，客户端确实是这么读的：
 *   new JSONObject(body).optJSONArray("scripts") → 每项取
 *   name / deviceId / filename / actionCount / deviceName / updatedAt
 * 其中 name 为空的项会被客户端跳过，正好对应这里只列 script_*.json、
 * 不列整份配置备份（slide_settings.xml 的 scriptName 是空的）。
 */
function handleScriptList(req, res) {
  const files = (readManifest().files || []).filter(isSharedScript);
  const scripts = files.map((f) => ({
    deviceId: f.deviceId,
    deviceName: f.deviceName,
    filename: f.filename,
    name: f.scriptName || '',
    actionCount: f.actionCount || 0,
    size: f.size || 0,
    updatedAt: f.updatedAt || '',
  }));
  sendJson(res, 200, { ok: true, count: scripts.length, scripts });
}

/**
 * 删除一条已分享的脚本：文件与清单记录一起抹掉。
 *
 * 挂在管理口令后面，与发版接口同一道门；没配口令一律拒绝（fail closed）。
 */
async function handleScriptDelete(req, res) {
  const field = await readFields(req, LIMIT_JSON_BODY);
  const token = field('token') || req.headers['x-admin-token'];
  if (!ADMIN_TOKEN) {
    return sendJson(res, 403, { ok: false, error: 'admin token not configured' });
  }
  if (String(token || '') !== ADMIN_TOKEN) {
    return sendJson(res, 403, { ok: false, error: 'bad token' });
  }

  const deviceId = sanitize(field('deviceId'), '');
  const filename = sanitize(field('filename'), '');
  if (!deviceId || !filename) {
    return sendJson(res, 400, { ok: false, error: 'deviceId and filename required' });
  }

  // 经 resolveUploadPath 做越界校验，避免 ../ 删到上传目录外的文件
  const filePath = resolveUploadPath(deviceId, filename);
  if (filePath) {
    fs.rmSync(filePath, { force: true });
  }
  await updateManifest((manifest) => {
    manifest.files = (manifest.files || []).filter(
      (f) => !(f.deviceId === deviceId && f.filename === filename)
    );
  });

  // 管理页是普通表单提交，浏览器会停在 JSON 响应上；这里让它回到管理页。
  if (String(req.headers.accept || '').includes('text/html')) {
    res.writeHead(303, { Location: '/admin/scripts' });
    return res.end();
  }
  return sendJson(res, 200, { ok: true, deleted: filename });
}

/* 脚本库管理页：清单按更新时间倒序，删除按钮要口令 */
function handleScriptAdminPage(req, res) {
  const files = (readManifest().files || [])
    .slice()
    .sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  sendHtml(res, scriptAdminHtml(files, Boolean(ADMIN_TOKEN)));
}

/* /view 与 /api/download 共用的参数解析，返回 null 表示已经回过响应 */
function resolveRequestedFile(req, res, url) {
  const deviceId = sanitize(url.searchParams.get('deviceId'), '');
  const filename = sanitize(url.searchParams.get('filename'), '');
  if (!deviceId || !filename) {
    sendJson(res, 400, { ok: false, error: 'deviceId and filename required' });
    return null;
  }
  const filePath = resolveUploadPath(deviceId, filename);
  if (!filePath) {
    sendJson(res, 404, { ok: false, error: 'file not found' });
    return null;
  }
  return { filePath, filename };
}

function handleView(req, res, url) {
  const found = resolveRequestedFile(req, res, url);
  if (!found) return;
  sendHtml(res, viewHtml(fs.readFileSync(found.filePath, 'utf8'), found.filename));
}

function handleDownload(req, res, url) {
  const found = resolveRequestedFile(req, res, url);
  if (!found) return;
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Disposition': `attachment; filename="${found.filename}"`,
  });
  res.end(fs.readFileSync(found.filePath));
}

function register(router) {
  router.on('POST', '/api/upload', handleUpload);
  router.on('GET', '/api/uploads', (req, res) => sendJson(res, 200, readManifest()));
  router.on('GET', '/api/scripts', handleScriptList);
  router.on('POST', '/api/scripts/delete', handleScriptDelete);
  router.on('GET', '/admin/scripts', handleScriptAdminPage);
  router.on('GET', '/view', handleView);
  router.on('GET', '/api/download', handleDownload);
}

module.exports = { register };
