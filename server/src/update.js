// 更新域：版本信息与 APK 分发
//
// 客户端原来从 raw.githubusercontent.com 拉 update.json、去 GitHub Releases 下 APK，
// 国内还得挂一串代理前缀轮流试。改由本服务直接分发，两条路由：
//   GET  /api/update             版本信息 JSON
//   GET  /download?file=xx.apk   APK 原文
//   GET  /admin/release          发版管理页
//   POST /api/release/upload     上传 APK（需管理口令）
//   POST /api/release/meta       写 update.json（需管理口令）
//
// 文件放在 data/releases/ 下。上传接口是往公网分发的安装包的写入口，
// 因此和删除脚本一样挂在 AUTOSLIDE_ADMIN_TOKEN 后面，没配口令直接拒绝。

const fs = require('fs');
const path = require('path');
const { DATA_DIR, LIMIT_APK, LIMIT_JSON_BODY, ADMIN_TOKEN } = require('./config');
const { sanitize, safeJoin } = require('./paths');
const { readFields, sendJson, sendHtml } = require('./http');
const { esc, fmtBytes, nowCN } = require('./util');
const { releaseAdminHtml } = require('./views/admin');

const RELEASE_DIR = path.join(DATA_DIR, 'releases');
const UPDATE_FILE = path.join(RELEASE_DIR, 'update.json');
// 另外两个独立分发的 APK（apk1 / apk2）的槽位映射，供公开下载页 /apps 用
const EXTRA_APPS_FILE = path.join(RELEASE_DIR, 'extra_apps.json');

/**
 * 把 update.json 里的相对下载地址补成绝对地址。
 *
 * 这样 update.json 里只写 "/download?file=AutoSlide-v3.3.0.apk"，
 * 换域名、内网直连、走 Cloudflare 都不用改文件——地址按请求实际用的
 * 协议和域名拼出来。已经是 http(s):// 开头的绝对地址原样保留。
 */
function absolutize(downloadUrl, req) {
  if (!downloadUrl || /^https?:\/\//i.test(downloadUrl)) return downloadUrl;
  // Cloudflare 隧道回源是明文 http，真实协议在 x-forwarded-proto 里
  const proto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() || 'http';
  const host = req.headers.host || '';
  if (!host) return downloadUrl;
  return `${proto}://${host}${downloadUrl.startsWith('/') ? '' : '/'}${downloadUrl}`;
}

/** 版本信息：客户端每次检查更新拉的就是这个 */
function handleUpdateInfo(req, res) {
  if (!fs.existsSync(UPDATE_FILE)) {
    return sendJson(res, 404, { ok: false, error: 'update.json not found' });
  }
  let info;
  try {
    info = JSON.parse(fs.readFileSync(UPDATE_FILE, 'utf8'));
  } catch (e) {
    return sendJson(res, 500, { ok: false, error: 'update.json is not valid JSON' });
  }
  info.downloadUrl = absolutize(info.downloadUrl, req);
  return sendJson(res, 200, info);
}

/** APK 下载：DownloadManager 直接拉这个地址 */
function handleApkDownload(req, res, url) {
  const filename = sanitize(url.searchParams.get('file'), '');
  if (!filename) {
    return sendJson(res, 400, { ok: false, error: 'file required' });
  }
  // 经 safeJoin 做越界校验，避免 ../ 把 releases 目录外的文件读出去
  const filePath = safeJoin(RELEASE_DIR, filename);
  if (!filePath || !fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    return sendJson(res, 404, { ok: false, error: 'file not found' });
  }
  const stat = fs.statSync(filePath);
  res.writeHead(200, {
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${filename}"`,
    // APK 按版本号命名，同名即同一个包，可以放心让 CDN 和客户端长期缓存
    'Cache-Control': 'public, max-age=86400',
  });
  // 安装包动辄几十 MB，用流式而不是 readFileSync，免得整包读进内存
  return fs.createReadStream(filePath).pipe(res);
}


/* 口令校验：没配置一律拒绝（fail closed），与删除脚本同一道门 */
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

/* 读出当前的 update.json，读不到就给一份空壳供页面预填 */
function readUpdateInfo() {
  try {
    return JSON.parse(fs.readFileSync(UPDATE_FILE, 'utf8'));
  } catch (e) {
    return { versionCode: 0, versionName: '', updateLog: '', downloadUrl: '' };
  }
}

/* 已上传的安装包列表，按修改时间倒序 */
function listApks() {
  if (!fs.existsSync(RELEASE_DIR)) return [];
  return fs
    .readdirSync(RELEASE_DIR)
    .filter((f) => f.toLowerCase().endsWith('.apk'))
    .map((f) => {
      const st = fs.statSync(path.join(RELEASE_DIR, f));
      return { filename: f, size: st.size, mtime: st.mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);
}

/** 发版管理页 */
function handleReleasePage(req, res) {
  sendHtml(res, releaseAdminHtml(readUpdateInfo(), listApks(), Boolean(ADMIN_TOKEN), readExtraApps()));
}

/**
 * 上传 APK。
 *
 * 原始字节直接放在请求体里，文件名走查询参数——不用 multipart，
 * 零依赖解析 multipart 太啰嗦，而前端用 fetch 把 File 当 body 发出去同样简单。
 *
 * 落盘先写 .part 再改名：中途断了不会留下一个能被 /download 取走的半截包。
 */
function handleApkUpload(req, res, url) {
  if (!checkAdmin(req, res, req.headers['x-admin-token'])) return undefined;

  const filename = sanitize(url.searchParams.get('file'), '');
  if (!filename || !filename.toLowerCase().endsWith('.apk')) {
    return sendJson(res, 400, { ok: false, error: 'file must be a .apk name' });
  }
  const target = safeJoin(RELEASE_DIR, filename);
  if (!target) {
    return sendJson(res, 400, { ok: false, error: 'bad filename' });
  }
  streamApkToFile(req, res, target, (size) => sendJson(res, 200, { ok: true, filename, size }));
  return undefined;
}

/**
 * 把请求体（APK 原始字节）流式落盘到 target，成功后回调 onOk(size)。
 *
 * 落盘先写 .part 再改名：中途断了不会留下一个能被 /download 取走的半截包。
 * 上传新版和「其它应用」两个入口共用这一段。
 */
function streamApkToFile(req, res, target, onOk) {
  fs.mkdirSync(RELEASE_DIR, { recursive: true });
  const tmp = `${target}.part`;
  const out = fs.createWriteStream(tmp);
  let received = 0;
  let failed = false;

  const abort = (code, error) => {
    if (failed) return;
    failed = true;
    out.destroy();
    fs.rmSync(tmp, { force: true });
    req.destroy();
    if (!res.headersSent) sendJson(res, code, { ok: false, error });
  };

  req.on('data', (chunk) => {
    received += chunk.length;
    if (received > LIMIT_APK) abort(413, `apk too large (> ${LIMIT_APK} bytes)`);
  });
  req.on('error', () => abort(400, 'upload interrupted'));
  req.pipe(out);

  out.on('error', () => abort(500, 'write failed'));
  out.on('finish', () => {
    if (failed) return;
    if (received === 0) return abort(400, 'empty body');
    fs.renameSync(tmp, target);
    onOk(received);
  });
}

/* ============ 其它应用（apk1 / apk2）独立分发 ============ */

function readExtraApps() {
  try {
    return JSON.parse(fs.readFileSync(EXTRA_APPS_FILE, 'utf8'));
  } catch (_) {
    return {};
  }
}

function writeExtraApps(data) {
  fs.mkdirSync(RELEASE_DIR, { recursive: true });
  fs.writeFileSync(EXTRA_APPS_FILE, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
}

/**
 * 上传「其它应用」的 APK 并登记到槽位 apk1 / apk2。
 * 与上传新版共用 [streamApkToFile]；落盘成功后把 {file,name,size} 记进 extra_apps.json，
 * 供公开下载页 /apps 取用。需管理口令。
 */
function handleAppsUpload(req, res, url) {
  if (!checkAdmin(req, res, req.headers['x-admin-token'])) return undefined;

  const slot = String(url.searchParams.get('slot') || '');
  if (slot !== 'apk1' && slot !== 'apk2') {
    return sendJson(res, 400, { ok: false, error: 'slot must be apk1 or apk2' });
  }
  const filename = sanitize(url.searchParams.get('file'), '');
  if (!filename || !filename.toLowerCase().endsWith('.apk')) {
    return sendJson(res, 400, { ok: false, error: 'file must be a .apk name' });
  }
  const target = safeJoin(RELEASE_DIR, filename);
  if (!target) {
    return sendJson(res, 400, { ok: false, error: 'bad filename' });
  }
  const name = String(url.searchParams.get('name') || '').trim().slice(0, 40);

  streamApkToFile(req, res, target, (size) => {
    const apps = readExtraApps();
    apps[slot] = {
      file: filename,
      name: name || filename.replace(/\.apk$/i, ''),
      size,
      updatedAt: nowCN(),
    };
    writeExtraApps(apps);
    sendJson(res, 200, { ok: true, slot, filename, size });
  });
  return undefined;
}

/** 公开下载页：两个大按钮，分别下载 apk1 / apk2（仿好友邀请页风格） */
function handleAppsPage(req, res) {
  sendHtml(res, appsPageHtml(readExtraApps()));
}

function appsPageHtml(apps) {
  const button = (slot, fallback) => {
    const a = (apps && apps[slot]) || null;
    if (a && a.file) {
      return (
        '<a class="dl" href="/download?file=' + encodeURIComponent(a.file) + '">' +
        '<span class="dl-name">' + esc(a.name || fallback) + '</span>' +
        '<span class="dl-sz">' + esc(fmtBytes(a.size || 0)) + ' · 点击下载</span></a>'
      );
    }
    return (
      '<div class="dl dl-empty"><span class="dl-name">' + esc(fallback) + '</span>' +
      '<span class="dl-sz">尚未上传</span></div>'
    );
  };
  const style = [
    'body{margin:0;padding:24px;background:#f5f6f8;color:#1c1c1e;',
    'font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}',
    '.card{max-width:520px;margin:0 auto;background:#fff;border-radius:16px;padding:24px}',
    'h1{font-size:20px;margin:0 0 4px}.sub{color:#8e8e93;font-size:13px;margin:0 0 20px}',
    '.dl{display:flex;flex-direction:column;gap:6px;text-decoration:none;',
    'background:#0a84ff;color:#fff;border-radius:14px;padding:24px 20px;margin:14px 0;text-align:center}',
    '.dl:active{opacity:.85}.dl-name{font-size:22px;font-weight:700}',
    '.dl-sz{font-size:13px;opacity:.9}',
    '.dl-empty{background:#e5e5ea;color:#8e8e93}',
  ].join('');
  return (
    '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>应用下载</title><style>' + style + '</style></head>' +
    '<body><div class="card"><h1>应用下载</h1>' +
    '<p class="sub">点击下方按钮下载对应安装包</p>' +
    button('apk1', '应用一') + button('apk2', '应用二') +
    '</div></body></html>'
  );
}

/**
 * 写 update.json。
 *
 * downloadUrl 由服务端按文件名拼成相对地址，不接受前端传整串 URL——
 * 否则管理页被打开时误填一个外部地址，就成了往全体用户推任意安装包。
 */
async function handleReleaseMeta(req, res) {
  const field = await readFields(req, LIMIT_JSON_BODY);
  if (!checkAdmin(req, res, field('token') || req.headers['x-admin-token'])) return undefined;

  const apk = sanitize(field('apk'), '');
  if (!apk || !fs.existsSync(safeJoin(RELEASE_DIR, apk) || '')) {
    return sendJson(res, 400, { ok: false, error: 'apk not found on server' });
  }
  const versionCode = Number(field('versionCode'));
  if (!Number.isInteger(versionCode) || versionCode <= 0) {
    return sendJson(res, 400, { ok: false, error: 'versionCode must be a positive integer' });
  }

  const info = {
    versionCode,
    versionName: String(field('versionName') || '').trim().slice(0, 32),
    downloadUrl: `/download?file=${encodeURIComponent(apk)}`,
    updateLog: String(field('updateLog') || '').trim().slice(0, 4000),
  };
  fs.mkdirSync(RELEASE_DIR, { recursive: true });
  fs.writeFileSync(UPDATE_FILE, `${JSON.stringify(info, null, 2)}\n`, 'utf8');
  return sendJson(res, 200, { ok: true, info });
}

function register(router) {
  router.on('GET', '/api/update', handleUpdateInfo);
  router.on('GET', '/download', handleApkDownload);
  router.on('GET', '/admin/release', handleReleasePage);
  router.on('POST', '/api/release/upload', handleApkUpload);
  router.on('POST', '/api/release/meta', handleReleaseMeta);
  // 其它应用（apk1/apk2）：上传口走管理口令，下载页公开
  router.on('POST', '/api/apps/upload', handleAppsUpload);
  router.on('GET', '/apps', handleAppsPage);
}

module.exports = { register, RELEASE_DIR };
