'use strict';

/**
 * 后台看板本地预览。
 *
 * 从线上拉真实统计数据，用本地的 views/dashboard.js 渲染成 HTML 并起一个静态服务，
 * 方便改样式时即时看效果——不需要在本地跑完整服务端，也不需要每次都部署。
 *
 * 用法：
 *   node preview.js            拉线上数据并起服务
 *   node preview.js --offline  复用上次缓存的数据（离线时用）
 *
 * 改完 views/dashboard.js 后，回到终端按 Ctrl+C 停掉再重跑，或直接刷新页面
 * （页面每次请求都会重新渲染，改完存盘刷新即可，无需重启）。
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

const PORT = 8899;
const STATS_URL = 'https://pve.8450696.shop/api/stats';
const CACHE_FILE = path.join(__dirname, '.preview-stats.json');

/* 拉线上统计数据，失败时回落到本地缓存 */
function fetchStats() {
  return new Promise((resolve) => {
    if (process.argv.includes('--offline')) {
      return resolve(readCache());
    }
    const req = https.get(STATS_URL, { timeout: 15000 }, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => {
        try {
          const json = JSON.parse(body);
          fs.writeFileSync(CACHE_FILE, body, 'utf8');
          console.log(`已拉取线上数据：${json.devices ? json.devices.length : 0} 台设备`);
          resolve(json);
        } catch (e) {
          console.warn('线上数据解析失败，改用缓存');
          resolve(readCache());
        }
      });
    });
    req.on('timeout', () => req.destroy());
    req.on('error', () => {
      console.warn('拉取失败，改用缓存');
      resolve(readCache());
    });
  });
}

function readCache() {
  try {
    return JSON.parse(fs.readFileSync(CACHE_FILE, 'utf8'));
  } catch (e) {
    console.warn('没有缓存数据，使用空数据集');
    return { install_count: 0, update_count: 0, unique_devices: 0, devices: [], last_update: '' };
  }
}

async function main() {
  const stats = await fetchStats();
  const announcement = { title: '公告栏', content: '（本地预览）欢迎各位反馈录相关问题。', updatedAt: '预览' };

  http
    .createServer((req, res) => {
      // 每次请求都重新 require，改完样式存盘刷新即可生效，不用重启
      delete require.cache[require.resolve('./src/views/dashboard')];
      delete require.cache[require.resolve('./src/views/pages')];
      delete require.cache[require.resolve('./src/views/styles')];
      try {
        const { dashboardHtml } = require('./src/views/dashboard');
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(dashboardHtml(stats, announcement));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('渲染出错：\n\n' + e.stack);
        console.error(e.message);
      }
    })
    .listen(PORT, () => {
      console.log(`\n预览地址： http://localhost:${PORT}/`);
      console.log('改 src/views/dashboard.js 后直接刷新页面即可，无需重启。\n');
    });
}

main();
