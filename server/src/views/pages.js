// 页面模板：外壳 + 统计看板 + 上传列表 + 脚本内容
//
// 这一层只负责把数据渲染成 HTML，不读任何存储——需要什么由调用方传进来。

const { esc, fmtBytes } = require('../util');
const { baseStyles } = require('./styles');

function pageShell({ title, eyebrow, heading, headerRight, breadcrumb, body }) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${esc(title)}</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500;600&family=Source+Serif+4:wght@400;600&display=swap" rel="stylesheet">
  <style>${baseStyles()}</style>
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <div>
        <p class="eyebrow">${esc(eyebrow)}</p>
        ${breadcrumb ? `<p class="breadcrumb">${breadcrumb}</p>` : ''}
        <h1>${esc(heading)}</h1>
      </div>
      ${headerRight || ''}
    </div>
    ${body}
  </div>
</body>
</html>`;
}

function uploadsHtml(manifest) {
  const rows = (manifest.files || [])
    .map(
      (f) => `<tr>
        <td class="strong">${esc(f.deviceName)}</td>
        <td>${esc(f.deviceId)}</td>
        <td>${esc(f.filename)}</td>
        <td>${esc(fmtBytes(f.size))}</td>
        <td>${esc(f.updatedAt)}</td>
        <td class="actions">
          <a href="/view?deviceId=${encodeURIComponent(f.deviceId)}&filename=${encodeURIComponent(f.filename)}">查看内容</a>
          <a class="dl" href="/api/download?deviceId=${encodeURIComponent(f.deviceId)}&filename=${encodeURIComponent(f.filename)}">下载</a>
        </td>
      </tr>`
    )
    .join('');

  const body = `
    <p class="section-label">脚本同步 · ${(manifest.files || []).length} 个文件</p>
    <div class="panel table-wrap">
      <table>
        <thead><tr><th>设备</th><th>设备 ID</th><th>文件名</th><th>大小</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>${rows || '<tr><td class="empty" colspan="6">暂无上传文件</td></tr>'}</tbody>
      </table>
    </div>
  `;

  return pageShell({
    title: 'AutoSlide 脚本同步',
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '脚本同步',
    breadcrumb: '<a href="/">← 返回统计看板</a>',
    body,
  });
}

function viewHtml(content, filename) {
  const body = `
    <p class="section-label">脚本内容 · ${esc(filename)}</p>
    <div class="panel" style="padding: 4px 0;">
      <div style="display:flex;align-items:center;justify-content:space-between;padding:10px 16px;border-bottom:1px solid var(--border-soft);">
        <span style="font-family:var(--mono);font-size:11px;color:var(--text-dim);">${esc(filename)}</span>
        <a href="javascript:location.reload()" style="font-family:var(--mono);font-size:11px;">刷新</a>
      </div>
      <pre style="margin:0;padding:16px;overflow:auto;font-family:var(--mono);font-size:12px;line-height:1.6;color:var(--text-dim);white-space:pre-wrap;word-break:break-all;">${esc(content)}</pre>
    </div>
  `;

  return pageShell({
    title: filename,
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '脚本内容',
    breadcrumb: '<a href="/uploads">← 返回文件列表</a>',
    body,
  });
}

module.exports = { pageShell, uploadsHtml, viewHtml };
