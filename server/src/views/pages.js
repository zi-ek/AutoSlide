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

function dashboardHtml(stats, announcement) {
  const ann = announcement || { title: '公告栏', content: '', updatedAt: '' };
  const rows = (stats.devices || [])
    .map(
      (d) => `<tr>
        <td class="strong">${esc(d.brand)}</td>
        <td>${esc(d.model)}</td>
        <td>${esc(d.deviceId)}</td>
        <td>${esc(d.android)}</td>
        <td>${esc(d.cpu)}</td>
        <td>${esc(d.appVersion)} <span style="color:var(--text-faint)">(${esc(d.appVersionCode)})</span></td>
        <td class="strong">${esc(d.installs)}</td>
        <td>${esc(d.firstInstall)}</td>
        <td>${esc(d.lastSeen)}</td>
        <td>${esc(d.ip || '-')}${d.ipLoc ? `<div class="ip-loc">${esc(d.ipLoc)}</div>` : ''}</td>
      </tr>`
    )
    .join('');

  const headerRight = `<div class="live"><span class="dot"></span>LIVE · 最近上报 ${esc(stats.last_update || '-')}</div>`;

  const body = `
    <div class="top-links"><a href="/uploads">查看上传的录制脚本 →</a></div>
    <div class="panel" style="padding:16px;margin-bottom:18px;">
      <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;">
        <b style="color:var(--text);">公告栏</b>
        <a href="#" onclick="document.getElementById('announceEdit').style.display='block';return false;">编辑</a>
      </div>
      <p style="margin:8px 0 0;color:var(--text-dim);white-space:pre-wrap;word-break:break-word;">${esc(ann.content || '暂无公告')}</p>
      <form id="announceEdit" method="post" action="/api/chat/announcement" style="display:none;margin-top:12px;">
        <input name="title" value="${esc(ann.title || '公告栏')}" placeholder="公告标题" style="width:100%;box-sizing:border-box;padding:8px 10px;margin-bottom:8px;border:1px solid var(--border);border-radius:8px;background:var(--panel-alt);color:var(--text);font-size:13px;" />
        <textarea name="content" rows="4" placeholder="公告内容" style="width:100%;box-sizing:border-box;padding:8px 10px;margin-bottom:8px;border:1px solid var(--border);border-radius:8px;background:var(--panel-alt);color:var(--text);font-size:13px;resize:vertical;">${esc(ann.content || '')}</textarea>
        <button type="submit" style="padding:8px 18px;border:none;border-radius:8px;background:var(--clay);color:#fff;font-size:13px;cursor:pointer;">保存公告</button>
        <span style="margin-left:10px;font-size:11px;color:var(--text-faint);">更新于 ${esc(ann.updatedAt || '-')}</span>
      </form>
    </div>
    <div class="cards">
      <div class="card" style="--accent: var(--clay)"><div class="num">${stats.install_count || 0}</div><div class="label">安装次数</div></div>
      <div class="card" style="--accent: var(--sage)"><div class="num">${stats.update_count || 0}</div><div class="label">更新次数</div></div>
      <div class="card" style="--accent: var(--slate)"><div class="num">${stats.unique_devices || 0}</div><div class="label">设备数</div></div>
      <div class="card" style="--accent: var(--green)"><div class="num" style="font-size:15px;">${esc(stats.last_update || '-')}</div><div class="label">最近上报</div></div>
    </div>
    <p class="section-label">设备日志 · Device Log</p>
    <div class="panel table-wrap">
      <table>
        <thead><tr><th>品牌</th><th>型号</th><th>设备 ID</th><th>系统版本</th><th>CPU</th><th>应用版本</th><th>安装次数</th><th>首次安装</th><th>最近上报</th><th>设备 IP / 归属地</th></tr></thead>
        <tbody>${rows || '<tr><td class="empty" colspan="10">暂无数据</td></tr>'}</tbody>
      </table>
    </div>
  `;

  return pageShell({
    title: 'AutoSlide 统计后台',
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '统计后台',
    headerRight,
    body,
  });
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

module.exports = { pageShell, dashboardHtml, uploadsHtml, viewHtml };
