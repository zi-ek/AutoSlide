// 路径安全：文件名清洗与越界校验
//
// 这一层是上传/下载相关接口的唯一入口，任何来自请求的路径片段都必须过它。

const fs = require('fs');
const path = require('path');
const { UPLOAD_DIR } = require('./config');

/* 把外部传入的名字压成安全字符集，并限制长度 */
function sanitize(name, fallback) {
  const s = String(name || '').replace(/[^A-Za-z0-9._:-]/g, '_').slice(0, 100);
  return s || fallback;
}

/**
 * 拼接并校验路径，确保结果落在 baseDir 之内。
 *
 * 只靠 sanitize() 的字符过滤挡不住穿越：它保留了 '.'，所以 '..' 整段能原样通过，
 * path.join(UPLOAD_DIR, '..', 'chat.json') 就跳到了 data/ 下。
 * 这里改成解析成绝对路径后校验前缀，从机制上堵死。
 */
function safeJoin(baseDir, ...segments) {
  const base = path.resolve(baseDir);
  const target = path.resolve(base, ...segments);
  if (target !== base && !target.startsWith(base + path.sep)) {
    const err = new Error('path escapes base directory');
    err.code = 'EPATHESCAPE';
    throw err;
  }
  return target;
}

/**
 * 解析上传文件的真实路径。
 * 越界、不存在、不是普通文件一律返回 null——对外统一表现为 404，不泄露具体原因。
 */
function resolveUploadPath(deviceId, filename) {
  let filePath;
  try {
    filePath = safeJoin(UPLOAD_DIR, deviceId, filename);
  } catch (e) {
    return null;
  }
  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    return null;
  }
  return filePath;
}

module.exports = { sanitize, safeJoin, resolveUploadPath };
