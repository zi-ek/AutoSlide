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
const { releaseAdminHtml } = require('./views/admin');

const RELEASE_DIR = path.join(DATA_DIR, 'releases');
const UPDATE_FILE = path.join(RELEASE_DIR, 'update.json');

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
  sendHtml(res, releaseAdminHtml(readUpdateInfo(), listApks(), Boolean(ADMIN_TOKEN)));
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
    return sendJson(res, 200, { ok: true, filename, size: received });
  });
  return undefined;
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
}

module.exports = { register, RELEASE_DIR };
