// Easycontrol 设备地址登记表的查看页
//
// 这一层只负责把数据渲染成 HTML，不读存储——数据由调用方传进来。

const { esc } = require('../util');
const { pageShell } = require('./pages');

const registryStyles = `
<style>
  .ec-hint { background:var(--panel); border:1px solid var(--border); border-radius:12px; padding:14px 16px; margin-bottom:18px; }
  .ec-hint p { margin:0; font-size:13px; color:var(--text-dim); line-height:1.7; }
  .ec-card { padding:16px; margin-bottom:14px; }
  .ec-head { display:flex; align-items:baseline; gap:12px; flex-wrap:wrap; margin-bottom:10px; }
  .ec-name { font-size:15px; font-weight:600; }
  .ec-id { font-family:var(--mono); font-size:12px; color:var(--text-faint); }
  .ec-age { margin-left:auto; font-size:12px; font-family:var(--mono); }
  .ec-fresh { color:var(--text-dim); }
  .ec-stale { color:var(--clay); }
  .ec-addr { font-family:var(--mono); font-size:12.5px; line-height:1.9; }
  .ec-addr li { list-style:none; }
  .ec-addr ul { margin:0; padding:0; }
  .ec-tag { display:inline-block; min-width:56px; color:var(--text-faint); font-size:11px; }
  .ec-empty { color:var(--text-faint); text-align:center; padding:40px 0; }
</style>`;

/* 把毫秒时间戳说成人话，比一串数字好认得多 */
function ago(timestamp) {
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 60) return seconds + ' 秒前';
  if (seconds < 3600) return Math.floor(seconds / 60) + ' 分钟前';
  if (seconds < 86400) return Math.floor(seconds / 3600) + ' 小时前';
  return Math.floor(seconds / 86400) + ' 天前';
}

/**
 * 标出每个地址是哪一类，光看一串十六进制分不清哪个能连。
 * 判断只看地址本身，不代表可达性——那要控制端真去连才知道。
 */
function classify(address) {
  const bare = address.startsWith('[') ? address.slice(1, -1) : address;
  if (!bare.includes(':')) {
    if (/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(bare)) return '内网v4';
    return '公网v4';
  }
  const lower = bare.toLowerCase();
  if (lower.startsWith('fc') || lower.startsWith('fd')) return 'ULA';
  if (lower.startsWith('fe8') || lower.startsWith('fe9')) return '链路本地';
  return '全局v6';
}

/**
 * @param devices 形如 { 序列号: { addresses, port, name, updatedAt } }
 * @param staleMs 超过多久算过期，与接口侧保持一致
 */
function renderRegistryPage(devices, staleMs) {
  const entries = Object.entries(devices || {})
    .sort((a, b) => (b[1].updatedAt || 0) - (a[1].updatedAt || 0));

  const body = entries.length === 0
    ? '<div class="card ec-empty">还没有设备上报过</div>'
    : entries.map(([id, record]) => {
      const updatedAt = record.updatedAt || 0;
      const stale = Date.now() - updatedAt > staleMs;
      const addresses = Array.isArray(record.addresses) ? record.addresses : [];
      return `<div class="card ec-card">
        <div class="ec-head">
          <span class="ec-name">${esc(record.name || '未命名设备')}</span>
          <span class="ec-id">${esc(id)}</span>
          <span class="ec-age ${stale ? 'ec-stale' : 'ec-fresh'}">${stale ? '已过期 · ' : ''}${ago(updatedAt)}上报</span>
        </div>
        <div class="ec-addr"><ul>${addresses.map((address) =>
        `<li><span class="ec-tag">${classify(address)}</span>${esc(address)}:${record.port}</li>`).join('')}</ul></div>
      </div>`;
    }).join('');

  return pageShell({
    title: 'Easycontrol 设备地址',
    eyebrow: 'EASYCONTROL',
    heading: '设备地址登记表',
    body: `${registryStyles}
      <div class="ec-hint">
        <p>被控端的助手会在开机、部署完成、以及每 15 分钟各上报一次当前地址；
        控制端连不上时来这里查最新的。地址按上报顺序排列，控制端是<b>并发</b>尝试的，
        所以列在前面不代表更优先。</p>
      </div>
      ${body}`,
  });
}

/* classify / ago 也给看板用：同一份地址在两处必须说同样的话 */
module.exports = { renderRegistryPage, classify, ago };
