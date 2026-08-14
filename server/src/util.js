// 通用小工具：时间戳、HTML 转义、字节数格式化

/**
 * 统一的时间戳格式。
 * 原先这行 toLocaleString 在 8 处重复出现，任何一处改了时区就会和其它数据对不上。
 */
function nowCN() {
  return new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
}

/* HTML 转义，所有拼进页面的外部数据都必须过这一道 */
function esc(s) {
  return String(s || '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtBytes(n) {
  const num = Number(n) || 0;
  if (num < 1024) return num + ' B';
  if (num < 1024 * 1024) return (num / 1024).toFixed(1) + ' KB';
  return (num / (1024 * 1024)).toFixed(1) + ' MB';
}

module.exports = { nowCN, esc, fmtBytes };
