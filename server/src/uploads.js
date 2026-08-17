// 上传域：录制脚本的接收、清单、浏览与下载

const fs = require('fs');
const path = require('path');
const { JsonStore } = require('./store');
const { MANIFEST_FILE, UPLOAD_DIR, LIMIT_UPLOAD_BODY } = require('./config');
const { nowCN } = require('./util');
const { sanitize, safeJoin, resolveUploadPath } = require('./paths');
const { readBody, sendJson, sendHtml, clientIp } = require('./http');
const { lookupIpLocation } = require('./ip');
const { updateStats, touchDevice } = require('./stats');
const { uploadsHtml, viewHtml } = require('./views/pages');

const manifestStore = new JsonStore(MANIFEST_FILE, () => ({ files: [] }));

function readManifest() {
  return manifestStore.read();
}

function updateManifest(fn) {
  return manifestStore.update(fn);
}

async function handleUpload(req, res) {
  const deviceId = sanitize(req.headers['x-device-id'], 'unknown');
  const deviceName = sanitize(req.headers['x-device-name'], 'unknown');
  const filename = sanitize(req.headers['x-filename'] || 'scripts.json', 'scripts.json');
  const body = await readBody(req, LIMIT_UPLOAD_BODY);

  // 脚本数量优先取请求头；老客户端没有该头时退回解析正文
  let scriptCount = Number(req.headers['x-script-count']);
  if (!Number.isFinite(scriptCount) || scriptCount < 0) {
    try {
      const parsed = JSON.parse(body.toString('utf8'));
      scriptCount = Array.isArray(parsed.scripts) ? parsed.scripts.length : 0;
    } catch (e) {
      scriptCount = 0;
    }
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
  router.on('GET', '/uploads', (req, res) => sendHtml(res, uploadsHtml(readManifest())));
  router.on('GET', '/view', handleView);
  router.on('GET', '/api/download', handleDownload);
}

module.exports = { register };
