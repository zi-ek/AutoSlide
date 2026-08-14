// IP 归属地查询（免费公开接口，失败或超时一律降级为空字符串，绝不影响主流程）

const https = require('https');
const { IP_CACHE_MAX } = require('./config');

/* IP 归属地查询缓存：ip -> 归属地 */
const ipLocationCache = new Map();

/**
 * 查询 IP 归属地（免费接口，失败或超时返回空字符串，不影响主流程）
 */
function lookupIpLocation(ip) {
  if (!ip) return Promise.resolve('');
  // 内网地址无需查询
  if (/^(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip)) {
    return Promise.resolve('内网');
  }
  if (ipLocationCache.has(ip)) {
    return Promise.resolve(ipLocationCache.get(ip));
  }
  return new Promise((resolve) => {
    const req = https.get(
      `https://opendata.baidu.com/api.php?query=${encodeURIComponent(ip)}&co=&resource_id=6006&oe=utf8`,
      { headers: { 'User-Agent': 'Mozilla/5.0' }, timeout: 3000 },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          try {
            const j = JSON.parse(data);
            const loc = String((j.data && j.data[0] && j.data[0].location) || '')
              .replace(/^[，,\s]+/, '')
              .trim();
            // 缓存加上限：长期运行下这个 Map 原本会无限增长
            if (ipLocationCache.size >= IP_CACHE_MAX) {
              ipLocationCache.delete(ipLocationCache.keys().next().value);
            }
            ipLocationCache.set(ip, loc);
            resolve(loc);
          } catch (e) {
            resolve('');
          }
        });
      }
    );
    req.on('timeout', () => req.destroy());
    req.on('error', () => resolve(''));
  });
}

module.exports = { lookupIpLocation };
