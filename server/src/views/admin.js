// 管理页模板：脚本库管理 + 发版管理
//
// 这一层只负责把数据渲染成 HTML，不读任何存储——需要什么由调用方传进来。

const { esc, fmtBytes } = require('../util');
const { pageShell } = require('./pages');

const adminStyles = `
<style>
  .admin-hint { background:var(--panel); border:1px solid var(--border); border-radius:12px; padding:14px 16px; margin-bottom:18px; }
  .admin-hint p { margin:0 0 8px; font-size:13px; color:var(--text-dim); line-height:1.7; }
  .admin-hint p:last-of-type { margin-bottom:0; }
  .admin-hint code { font-family:var(--mono); font-size:12px; background:var(--panel-alt); padding:1px 5px; border-radius:4px; }
  .token-row { display:flex; align-items:center; gap:10px; margin-top:10px; }
  .token-row label { font-size:13px; color:var(--text-dim); white-space:nowrap; }
  .token-row input, .token-row select, .token-row textarea {
    flex:1; min-width:0; padding:7px 10px; border:1px solid var(--border); border-radius:8px;
    background:var(--panel-alt); color:var(--text); font-family:var(--mono); font-size:13px;
  }
  .token-row textarea { font-family:var(--sans); line-height:1.7; resize:vertical; }
  .btn-del {
    padding:5px 12px; border:1px solid var(--border); border-radius:7px;
    background:var(--panel); color:var(--clay); font-size:12px; font-family:var(--mono); cursor:pointer;
    white-space:nowrap;
  }
  .btn-del:hover { background:rgba(217,119,87,.1); }
  .admin-empty { color:var(--text-faint); text-align:center; padding:32px 0 !important; }
  .admin-card { padding:16px; margin-bottom:18px; }
  .admin-mono { font-family:var(--mono); font-size:12px; margin:0; }
  .progress-track { height:6px; background:var(--panel-alt); border-radius:999px; overflow:hidden; }
  .progress-fill { height:100%; width:0; background:var(--clay); transition:width .2s; }
</style>`;

/* 口令输入框：两个管理页共用，值只存 localStorage，不渲染进页面源码 */
function tokenBlock(hint) {
  return `<div class="admin-hint">
      ${hint}
      <div class="token-row">
        <label for="adminToken">管理口令</label>
        <input id="adminToken" type="password" placeholder="输入后本浏览器记住" autocomplete="off" />
      </div>
    </div>`;
}

/* 口令读写 + 校验，两个管理页共用的那段脚本 */
const tokenScript = `
  var tokenInput = document.getElementById('adminToken');
  try { tokenInput.value = localStorage.getItem('autoslideAdminToken') || ''; } catch (e) {}
  tokenInput.addEventListener('change', function () {
    try { localStorage.setItem('autoslideAdminToken', tokenInput.value); } catch (e) {}
  });
  function needToken() {
    if (!tokenInput.value) { alert('请先填写管理口令'); return true; }
    return false;
  }`;

/* 没配口令时的提示。主语由各页面传入，不然只说「当前不可用」用户不知道是什么不可用 */
const notConfiguredHint = (what) => `<p style="color:var(--clay);"><b>${what}当前不可用</b>：服务端没有配置管理口令。</p>
   <p>在 systemd 单元里加一行 <code>Environment=AUTOSLIDE_ADMIN_TOKEN=你的口令</code>，
   然后 <code>systemctl daemon-reload &amp;&amp; systemctl restart autoslide-stats</code> 即可启用。</p>`;

/**
 * 脚本库管理页。
 *
 * @param {Array<object>} files 清单记录（已按更新时间倒序）
 * @param {boolean} tokenConfigured 服务端是否配置了 AUTOSLIDE_ADMIN_TOKEN
 */
function scriptAdminHtml(files, tokenConfigured) {
  const rows = files.length
    ? files
        .map(
          (f) => `<tr>
      <td class="strong">${esc(f.scriptName || '(未命名)')}</td>
      <td>${esc(String(f.actionCount || 0))}</td>
      <td>${esc(f.deviceName || '-')}</td>
      <td>${esc(fmtBytes(f.size || 0))}</td>
      <td>${esc(f.updatedAt || '-')}</td>
      <td>
        <form class="del-form" method="post" action="/api/scripts/delete"
          onsubmit="return prepareDelete(this, '${esc(f.scriptName || f.filename)}')">
          <input type="hidden" name="deviceId" value="${esc(f.deviceId)}" />
          <input type="hidden" name="filename" value="${esc(f.filename)}" />
          <input type="hidden" name="token" value="" />
          <button class="btn-del" type="submit">删除</button>
        </form>
      </td>
    </tr>`
        )
        .join('')
    : '<tr><td colspan="6" class="admin-empty">脚本库还没有内容</td></tr>';

  const hint = tokenConfigured
    ? '<p>删除不可撤销：脚本文件与清单记录会一并抹掉。口令只保存在本浏览器，不会写进页面源码。</p>'
    : notConfiguredHint('删除功能');

  const body = `
    ${adminStyles}
    ${tokenBlock(hint)}

    <p class="section-label">已分享的脚本 · ${files.length}</p>
    <div class="panel">
      <div class="table-wrap">
        <table>
          <thead><tr><th>脚本名</th><th>动作数</th><th>来源设备</th><th>大小</th><th>分享时间</th><th>操作</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </div>

    <script>
      ${tokenScript}
      function prepareDelete(form, name) {
        if (needToken()) return false;
        if (!confirm('确定删除「' + name + '」？此操作不可撤销。')) return false;
        form.querySelector('input[name="token"]').value = tokenInput.value;
        return true;
      }
    </script>
  `;

  return pageShell({
    title: '脚本库管理',
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '脚本库管理',
    breadcrumb: '<a href="/">← 返回统计看板</a>',
    body,
  });
}

/**
 * 发版管理页：上传新版 APK + 写 update.json。
 *
 * 上传把 File 当请求体直接发，不走 multipart；口令通过 X-Admin-Token 头带上。
 * 上传和发布分成两步是有意的：传完包还能再核对一遍版本号和日志，
 * 确认无误再写 update.json——写进去的那一刻才会推给全体用户。
 *
 * @param {object} info 当前的 update.json 内容
 * @param {Array<object>} apks 服务器上已有的安装包
 * @param {boolean} tokenConfigured 服务端是否配置了 AUTOSLIDE_ADMIN_TOKEN
 */
function releaseAdminHtml(info, apks, tokenConfigured) {
  const current = String(info.downloadUrl || '');
  const options = apks.length
    ? apks
        .map(
          (a) =>
            `<option value="${esc(a.filename)}"${current.includes(a.filename) ? ' selected' : ''}>` +
            `${esc(a.filename)} · ${esc(fmtBytes(a.size))}</option>`
        )
        .join('')
    : '<option value="">（服务器上还没有 APK）</option>';

  const hint = tokenConfigured
    ? '<p>上传只是把包放到服务器，<b>不会立刻推给用户</b>；在下面选中它、填好版本号并写入 <code>update.json</code> 之后才会生效。</p>'
    : notConfiguredHint('上传与发布');

  const body = `
    ${adminStyles}
    ${tokenBlock(hint)}

    <p class="section-label">当前线上版本</p>
    <div class="panel admin-card">
      <p class="admin-mono">
        v${esc(info.versionName || '-')} · versionCode ${esc(String(info.versionCode || 0))}
        <span style="color:var(--text-faint);">　·　${esc(info.downloadUrl || '未设置')}</span>
      </p>
    </div>

    <p class="section-label">上传新版 APK</p>
    <div class="panel admin-card">
      <div class="token-row" style="margin-top:0;">
        <input id="apkFile" type="file" accept=".apk,application/vnd.android.package-archive" />
        <button class="btn-del" id="btnUpload" type="button">上传</button>
      </div>
      <div id="uploadBar" style="display:none;margin-top:10px;">
        <div class="progress-track"><div class="progress-fill" id="uploadFill"></div></div>
        <p class="admin-mono" id="uploadText" style="margin-top:6px;"></p>
      </div>
    </div>

    <p class="section-label">发布</p>
    <div class="panel admin-card" style="margin-bottom:0;">
      <div class="token-row" style="margin-top:0;">
        <label for="apkSelect">安装包</label>
        <select id="apkSelect">${options}</select>
      </div>
      <div class="token-row">
        <label for="versionCode">versionCode</label>
        <input id="versionCode" type="number" min="1" value="${esc(String(info.versionCode || ''))}" />
        <label for="versionName">versionName</label>
        <input id="versionName" type="text" value="${esc(info.versionName || '')}" placeholder="3.4.0" />
      </div>
      <div class="token-row" style="align-items:flex-start;">
        <label for="updateLog" style="padding-top:8px;">更新日志</label>
        <textarea id="updateLog" rows="6">${esc(info.updateLog || '')}</textarea>
      </div>
      <div class="token-row">
        <button class="btn-del" id="btnPublish" type="button">写入 update.json</button>
        <span class="admin-mono" id="publishText"></span>
      </div>
    </div>

    <script>
      ${tokenScript}

      document.getElementById('btnUpload').addEventListener('click', function () {
        if (needToken()) return;
        var f = document.getElementById('apkFile').files[0];
        if (!f) { alert('请先选择 APK 文件'); return; }
        if (!/\\.apk$/i.test(f.name)) { alert('只能上传 .apk 文件'); return; }

        var bar = document.getElementById('uploadBar');
        var fill = document.getElementById('uploadFill');
        var text = document.getElementById('uploadText');
        bar.style.display = 'block';
        text.textContent = '上传中…';

        // 用 XHR 而不是 fetch：几十 MB 的包需要进度反馈，而 fetch 没有上传进度事件
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/release/upload?file=' + encodeURIComponent(f.name));
        xhr.setRequestHeader('X-Admin-Token', tokenInput.value);
        xhr.upload.onprogress = function (e) {
          if (!e.lengthComputable) return;
          var pct = Math.round((e.loaded / e.total) * 100);
          fill.style.width = pct + '%';
          text.textContent = '上传中… ' + pct + '%';
        };
        xhr.onload = function () {
          var ok = xhr.status >= 200 && xhr.status < 300;
          text.textContent = ok ? '上传完成' : ('上传失败：' + xhr.responseText);
          if (ok) setTimeout(function () { location.reload(); }, 800);
        };
        xhr.onerror = function () { text.textContent = '上传失败：网络错误'; };
        xhr.send(f);
      });

      document.getElementById('btnPublish').addEventListener('click', function () {
        if (needToken()) return;
        var apk = document.getElementById('apkSelect').value;
        if (!apk) { alert('请先选择安装包'); return; }
        if (!confirm('确定发布？所有用户下次检查更新都会看到这个版本。')) return;

        var out = document.getElementById('publishText');
        out.textContent = '写入中…';
        fetch('/api/release/meta', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            token: tokenInput.value,
            apk: apk,
            versionCode: document.getElementById('versionCode').value,
            versionName: document.getElementById('versionName').value,
            updateLog: document.getElementById('updateLog').value
          })
        })
          .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
          .then(function (res) {
            out.textContent = res.ok ? ('已发布 v' + res.j.info.versionName) : ('失败：' + (res.j.error || ''));
            if (res.ok) setTimeout(function () { location.reload(); }, 800);
          })
          .catch(function (e) { out.textContent = '失败：' + e; });
      });
    </script>
  `;

  return pageShell({
    title: '发版管理',
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '发版管理',
    breadcrumb: '<a href="/">← 返回统计看板</a>',
    body,
  });
}

module.exports = { scriptAdminHtml, releaseAdminHtml };
