'use strict';

/**
 * 统计后台看板。
 *
 * 信息层次：概览数字 → 分布图 → 设备明细表。
 *
 * 用色遵循一条原则：颜色不重复表达文字已经说清楚的信息。
 * 概览磁贴各自带标题，所以不按指标上不同的色（那样既不构成有效编码，
 * 也通不过色觉障碍分离度检验）；分布图是单一测度、单一系列，
 * 大小由长度编码，统一用品牌色并在每条末端直接标数值。
 */

const { esc } = require('../util');
const { pageShell } = require('./pages');

/**
 * 按字段统计出现次数，返回按数量降序的 [值, 数量] 数组。
 *
 * 上报的品牌字段大小写不统一（实测同时存在 Redmi/REDMI、Xiaomi/xiaomi），
 * 这里忽略大小写归并，展示时取该组内第一次出现的写法。
 */
function countBy(list, pick) {
  const m = new Map();
  list.forEach((item) => {
    const raw = pick(item);
    const label = raw === undefined || raw === null || raw === '' ? '未知' : String(raw).trim();
    const key = label.toLowerCase();
    const hit = m.get(key);
    if (hit) {
      hit.n += 1;
    } else {
      m.set(key, { label, n: 1 });
    }
  });
  return [...m.values()].sort((a, b) => b.n - a.n).map((v) => [v.label, v.n]);
}

/* 系统版本按数字大小倒序，而不是按数量——版本是有序量 */
function sortByVersionDesc(pairs) {
  return [...pairs].sort((a, b) => {
    const na = parseFloat(a[0]);
    const nb = parseFloat(b[0]);
    if (Number.isNaN(na) && Number.isNaN(nb)) return b[1] - a[1];
    if (Number.isNaN(na)) return 1;
    if (Number.isNaN(nb)) return -1;
    return nb - na;
  });
}

/**
 * 横向条形图。单系列，长度编码数量，末端直接标数值（同时作为低对比度的补偿）。
 *
 * @param {string} title 图表标题
 * @param {Array<[string, number]>} pairs 已排好序的 [标签, 数量]
 * @param {number} total 总数，用于计算占比
 */
function barChart(title, pairs, total) {
  const max = pairs.reduce((m, p) => Math.max(m, p[1]), 0) || 1;
  const rows = pairs
    .map(([label, n]) => {
      const pct = total ? Math.round((n / total) * 1000) / 10 : 0;
      return `<div class="bar-row" title="${esc(label)}：${n} 台（${pct}%）">
        <span class="bar-label">${esc(label)}</span>
        <span class="bar-track"><span class="bar-fill" style="width:${(n / max) * 100}%"></span></span>
        <span class="bar-value">${n}</span>
        <span class="bar-pct">${pct}%</span>
      </div>`;
    })
    .join('');
  return `<section class="chart">
    <h3 class="chart-title">${esc(title)}</h3>
    <div class="bars">${rows || '<p class="chart-empty">暂无数据</p>'}</div>
  </section>`;
}

/**
 * 环形图。用于「版本」这类有序量的占比展示。
 *
 * 配色用单色明度渐变而不是分类色：版本本身有顺序，浅→深正好编码高→低，
 * 分类色反而会把顺序信息抹掉。明度单调的顺序色阶不适用分类色板的
 * 色觉分离度检验，浅色段与背景对比不足由右侧的直接标注补偿。
 *
 * @param {string} title 图表标题
 * @param {Array<[string, number]>} pairs 已按顺序排好的 [标签, 数量]
 * @param {number} total 总数
 */
function donutChart(title, pairs, total) {
  if (!pairs.length) {
    return `<section class="chart"><h3 class="chart-title">${esc(title)}</h3><p class="chart-empty">暂无数据</p></section>`;
  }
  // 8 级 clay 顺序色阶（深→浅）。颜色按「数量」深浅编码：数量最多的最深，
  // 与环上的排列顺序解耦——系统版本按版本号排列，但配色跟的是占比大小。
  const RAMP = ['#7f3f2c', '#9c4f37', '#b85f42', '#d17150', '#d98764', '#e09d80', '#e8b39c', '#f0c9b8'];
  // 按数量降序算出每一项的名次，名次即取色下标
  const rankOf = new Map();
  [...pairs].sort((a, b) => b[1] - a[1]).forEach(([label], i) => rankOf.set(label, i));
  const colorOf = (label) => RAMP[Math.min(rankOf.get(label) || 0, RAMP.length - 1)];
  const sum = pairs.reduce((a, p) => a + p[1], 0) || 1;
  const R = 54;          // 半径
  const C = 2 * Math.PI * R;
  let offset = 0;

  const arcs = pairs.map(([label, n]) => {
    const frac = n / sum;
    const len = frac * C;
    // 每段之间留 2px 空隙，段太小时不留，避免看不见
    const gap = len > 6 ? 2 : 0;
    const dash = `${Math.max(len - gap, 0.5)} ${C - Math.max(len - gap, 0.5)}`;
    const seg = `<circle class="donut-seg" r="${R}" cx="70" cy="70" fill="none"
      stroke="${colorOf(label)}" stroke-width="22"
      stroke-dasharray="${dash}" stroke-dashoffset="${-offset}"
      transform="rotate(-90 70 70)"><title>${esc(label)}：${n} 台（${Math.round(frac * 1000) / 10}%）</title></circle>`;
    offset += len;
    return seg;
  }).join('');

  const legend = pairs.map(([label, n]) => {
    const pct = Math.round((n / sum) * 1000) / 10;
    return `<div class="donut-item" title="${esc(label)}：${n} 台（${pct}%）">
      <span class="donut-dot" style="background:${colorOf(label)}"></span>
      <span class="donut-label">${esc(label)}</span>
      <span class="donut-value">${n}</span>
      <span class="donut-pct">${pct}%</span>
    </div>`;
  }).join('');

  return `<section class="chart">
    <h3 class="chart-title">${esc(title)}</h3>
    <div class="donut-wrap">
      <svg class="donut" viewBox="0 0 140 140" role="img" aria-label="${esc(title)}">
        ${arcs}
        <text x="70" y="66" class="donut-center-num">${total}</text>
        <text x="70" y="82" class="donut-center-label">台</text>
      </svg>
      <div class="donut-legend">${legend}</div>
    </div>
  </section>`;
}

/**
 * IP 单元格：连接地址与设备自报的出口地址合并展示。
 *
 * 连接地址是服务端从请求里拿到的（设备走 IPv6 时就是 IPv6）；
 * 出口地址由设备自己探测上报，服务端看不到。两者可能不同，
 * 所以并列在同一格里，各带一个小标签区分。
 */
function ipCell(d) {
  const lines = [];
  if (d.ip) {
    lines.push(`<div class="ip-line"><span class="ip-tag">连接</span><span class="ip-addr">${esc(d.ip)}</span>${
      d.ipLoc ? `<span class="ip-loc">${esc(d.ipLoc)}</span>` : ''
    }</div>`);
  }
  if (d.egressIp) {
    lines.push(`<div class="ip-line"><span class="ip-tag">出口</span><span class="ip-addr">${esc(d.egressIp)}</span>${
      d.egressLoc ? `<span class="ip-loc">${esc(d.egressLoc)}</span>` : ''
    }</div>`);
  }
  return lines.join('') || '-';
}

/* 展示用名称：营销名里已含品牌时不再重复拼接 */
function displayName(d) {
  const brand = String(d.brand || '').trim();
  const model = String(d.model || '').trim();
  if (!brand) return model;
  if (!model) return brand;
  return model.toLowerCase().startsWith(brand.toLowerCase()) ? model : `${brand} ${model}`;
}

/* 概览磁贴：大数字 + 说明，颜色不参与编码 */
function statTile(value, label, mono) {
  return `<div class="tile">
    <div class="tile-num${mono ? ' tile-num-sm' : ''}">${esc(String(value))}</div>
    <div class="tile-label">${esc(label)}</div>
  </div>`;
}

const dashboardStyles = `
<style>
  .toolbar { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px; margin-bottom:18px; }
  .toolbar .links { display:flex; gap:16px; font-size:13px; }

  .notice { background:var(--panel); border:1px solid var(--border); border-radius:12px; padding:16px; margin-bottom:20px; }
  .notice-head { display:flex; align-items:center; justify-content:space-between; gap:12px; }
  .notice-head b { color:var(--text); font-size:14px; }
  .notice-body { margin:8px 0 0; color:var(--text-dim); white-space:pre-wrap; word-break:break-word; font-size:14px; line-height:1.7; }
  .notice form { margin-top:12px; }
  .notice input, .notice textarea {
    width:100%; box-sizing:border-box; padding:8px 10px; margin-bottom:8px;
    border:1px solid var(--border); border-radius:8px; background:var(--panel-alt);
    color:var(--text); font-size:13px; font-family:var(--sans);
  }
  .notice textarea { resize:vertical; }
  .notice button { padding:8px 18px; border:none; border-radius:8px; background:var(--clay); color:#fff; font-size:13px; cursor:pointer; }
  .notice .meta { margin-left:10px; font-size:11px; color:var(--text-faint); }

  .tiles { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:12px; margin-bottom:22px; }
  .tile { background:var(--panel); border:1px solid var(--border); border-radius:12px; padding:16px 18px; border-top:2px solid var(--clay); }
  .tile-num { font-family:var(--mono); font-size:30px; font-weight:600; line-height:1.1; color:var(--text); letter-spacing:-.02em; }
  .tile-num-sm { font-size:15px; font-weight:500; padding-top:10px; }
  .tile-label { margin-top:6px; font-size:12px; color:var(--text-dim); }

  .charts { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:12px; margin-bottom:26px; }
  .chart { background:var(--panel); border:1px solid var(--border); border-radius:12px; padding:16px 18px; }
  .chart-title { margin:0 0 14px; font-size:13px; font-weight:600; color:var(--text); }
  .chart-empty { margin:0; font-size:13px; color:var(--text-faint); }
  .bars { display:flex; flex-direction:column; gap:7px; }
  .bar-row { display:grid; grid-template-columns:84px 1fr 30px 44px; align-items:center; gap:10px; }
  .bar-label { font-size:12px; color:var(--text-dim); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .bar-track { height:14px; background:var(--panel-alt); border-radius:4px; overflow:hidden; }
  .bar-fill { display:block; height:100%; background:var(--clay); border-radius:0 4px 4px 0; min-width:2px; }
  .bar-value { font-family:var(--mono); font-size:12px; color:var(--text); text-align:right; }
  .bar-pct { font-family:var(--mono); font-size:11px; color:var(--text-faint); text-align:right; }

  .donut-wrap { display:flex; align-items:center; gap:120px; flex-wrap:wrap; }
  .donut { width:205px; height:205px; flex:none; }
  .donut-seg { transition:opacity .15s; }
  .donut:hover .donut-seg { opacity:.55; }
  .donut .donut-seg:hover { opacity:1; }
  .donut-center-num { text-anchor:middle; font-family:var(--mono); font-size:20px; font-weight:600; fill:var(--text); }
  .donut-center-label { text-anchor:middle; font-size:10px; fill:var(--text-faint); }
  .donut-legend { flex:1; min-width:130px; display:flex; flex-direction:column; gap:4px; }
  .donut-item { display:grid; grid-template-columns:10px 1fr 28px 42px; align-items:center; gap:8px; }
  .donut-dot { width:10px; height:10px; border-radius:3px; }
  .donut-label { font-size:12px; color:var(--text-dim); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .donut-value { font-family:var(--mono); font-size:12px; color:var(--text); text-align:right; }
  .donut-pct { font-family:var(--mono); font-size:11px; color:var(--text-faint); text-align:right; }
  .table-tools { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px; margin-bottom:10px; }
  .table-tools input {
    padding:7px 12px; min-width:220px; border:1px solid var(--border); border-radius:8px;
    background:var(--panel); color:var(--text); font-size:13px; font-family:var(--sans);
  }
  .table-count { font-family:var(--mono); font-size:12px; color:var(--text-faint); }
  .table-wrap table thead th { cursor:pointer; user-select:none; white-space:nowrap; }
  .table-wrap table thead th::after { content:''; }
  .table-wrap table thead th.asc::after { content:' ▲'; font-size:9px; }
  .table-wrap table thead th.desc::after { content:' ▼'; font-size:9px; }
  .ip-cell { max-width:300px; }
  .ip-line { display:flex; align-items:baseline; gap:6px; flex-wrap:wrap; line-height:1.7; }
  .ip-line + .ip-line { margin-top:2px; }
  .ip-tag { flex:none; font-size:10px; color:var(--text-faint); border:1px solid var(--border); border-radius:4px; padding:0 4px; }
  .ip-addr { font-family:var(--mono); font-size:12px; word-break:break-all; }
  .ip-loc { font-size:11px; color:var(--text-faint); }
  tr.hidden-row { display:none; }
  .app-ver { font-family:var(--mono); font-size:12px; white-space:nowrap; }
  .script-badge { display:inline-block; min-width:20px; padding:1px 7px; border-radius:10px; background:var(--clay); color:#fff; font-family:var(--mono); font-size:11px; text-align:center; }
  .script-none { color:var(--text-faint); }
  .table-wrap tbody tr { cursor:pointer; }
  .table-wrap tbody tr:hover { background:var(--panel-alt); }

  .modal-mask { position:fixed; inset:0; background:rgba(35,34,31,.42); display:none; align-items:center; justify-content:center; padding:24px; z-index:50; }
  .modal-mask.open { display:flex; }
  .modal { background:var(--panel); border:1px solid var(--border); border-radius:14px; width:max-content; min-width:min(560px,100%); max-width:100%; max-height:86vh; display:flex; flex-direction:column; overflow:auto; }
  .modal-head { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:16px 18px; border-bottom:1px solid var(--border); }
  .modal-title { font-size:15px; font-weight:600; color:var(--text); }
  .modal-sub { font-family:var(--mono); font-size:11px; color:var(--text-faint); margin-top:3px; }
  .modal-close { border:none; background:none; font-size:20px; line-height:1; color:var(--text-faint); cursor:pointer; padding:0 4px; }
  .modal-info { padding:16px 18px; overflow:auto; }
  .info-item { display:flex; flex-direction:column; gap:2px; }
  .info-label { font-size:11px; color:var(--text-faint); }
  .info-value { font-family:var(--mono); font-size:12px; color:var(--text); word-break:break-all; }
  .modal-actions { display:flex; gap:10px; padding:14px 18px; border-top:1px solid var(--border); }
  .btn { padding:7px 16px; border:1px solid var(--border); border-radius:8px; background:var(--panel-alt); color:var(--text); font-size:13px; cursor:pointer; font-family:var(--sans); text-decoration:none; display:inline-block; }
  .btn-primary { background:var(--clay); border-color:var(--clay); color:#fff; }
  .btn:hover { text-decoration:none; opacity:.9; }
  .modal-body { padding:16px 18px; overflow:auto; }
  .script-item { border:1px solid var(--border-soft); border-radius:10px; padding:10px 12px; margin-bottom:8px; }
  .script-name { font-size:13px; font-weight:600; color:var(--text); }
  .script-meta { font-family:var(--mono); font-size:11px; color:var(--text-faint); margin-top:2px; }
  .script-raw { margin:8px 0 0; padding:10px; background:var(--panel-alt); border-radius:8px; font-family:var(--mono); font-size:11px; line-height:1.6; white-space:pre-wrap; word-break:break-all; max-height:220px; overflow:auto; color:var(--text-dim); display:none; }
  .script-raw.open { display:block; }
  .script-toggle { margin-top:6px; font-size:12px; color:var(--clay); cursor:pointer; }
  .info-grid { display:grid; grid-template-columns:repeat(2,max-content); gap:14px; }
  .info-card.wide { grid-column:1 / -1; }
  .info-card { background:var(--panel-alt); border:1px solid var(--border-soft); border-radius:10px; padding:12px 14px; }
  .info-card h4 { margin:0 0 10px; font-size:13px; font-weight:600; color:var(--text); }
  .info-row { display:grid; grid-template-columns:72px max-content; gap:8px; align-items:baseline; padding:3px 0; }
  .info-key { font-size:12px; color:var(--text-dim); }
  .info-val { font-size:12px; color:var(--text); font-family:var(--mono); white-space:nowrap; }
  .info-empty { font-size:12px; color:var(--text-faint); margin:0; }
  .modal-sec-title { font-size:12px; letter-spacing:.08em; text-transform:uppercase; color:var(--text-faint); font-family:var(--mono); margin:16px 0 8px; }
  .modal-empty { color:var(--text-faint); font-size:13px; }
</style>`;

/* 表格排序与搜索：纯前端，无外部依赖 */
const dashboardScript = `
<script>
// 公告保存：拦截表单提交，就地更新，避免浏览器跳到 JSON 响应上
(function () {
  var form = document.getElementById('announceEdit');
  if (!form) return;
  form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var btn = form.querySelector('button[type=submit]');
    var meta = form.querySelector('.meta');
    if (btn) { btn.disabled = true; btn.textContent = '保存中…'; }
    fetch(form.action, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(new FormData(form)).toString()
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data || !data.ok) throw new Error('save failed');
        var body = document.querySelector('.notice-body');
        if (body) body.textContent = data.announcement.content || '暂无公告';
        if (meta) meta.textContent = '更新于 ' + (data.announcement.updatedAt || '-');
        form.style.display = 'none';
        if (btn) { btn.disabled = false; btn.textContent = '保存公告'; }
      })
      .catch(function () {
        // 保存失败时保持表单打开，内容还在，用户可以直接重试
        if (btn) { btn.disabled = false; btn.textContent = '保存失败，点击重试'; }
      });
  });
})();

(function () {
  var input = document.getElementById('deviceSearch');
  var table = document.getElementById('deviceTable');
  if (!table) return;
  var tbody = table.tBodies[0];
  var counter = document.getElementById('deviceCount');
  var rows = Array.prototype.slice.call(tbody.rows);

  function refreshCount() {
    var n = rows.filter(function (r) { return !r.classList.contains('hidden-row'); }).length;
    if (counter) counter.textContent = n + ' / ' + rows.length + ' 台';
  }

  if (input) {
    input.addEventListener('input', function () {
      var q = input.value.trim().toLowerCase();
      rows.forEach(function (r) {
        r.classList.toggle('hidden-row', q !== '' && r.textContent.toLowerCase().indexOf(q) === -1);
      });
      refreshCount();
    });
  }

  Array.prototype.forEach.call(table.tHead.rows[0].cells, function (th, idx) {
    th.addEventListener('click', function () {
      var desc = !th.classList.contains('desc');
      Array.prototype.forEach.call(table.tHead.rows[0].cells, function (o) {
        o.classList.remove('asc', 'desc');
      });
      th.classList.add(desc ? 'desc' : 'asc');
      var sorted = rows.slice().sort(function (a, b) {
        var x = a.cells[idx].getAttribute('data-sort') || a.cells[idx].textContent.trim();
        var y = b.cells[idx].getAttribute('data-sort') || b.cells[idx].textContent.trim();
        var nx = parseFloat(x), ny = parseFloat(y);
        var r = (!isNaN(nx) && !isNaN(ny)) ? nx - ny : x.localeCompare(y, 'zh');
        return desc ? -r : r;
      });
      sorted.forEach(function (r) { tbody.appendChild(r); });
    });
  });

  // 点击带脚本的行 -> 打开二级面板
  var mask = document.getElementById('scriptModal');
  if (mask) {
    var mTitle = document.getElementById('modalTitle');
    var mSub = document.getElementById('modalSub');
    var mBody = document.getElementById('modalBody');
    var btnView = document.getElementById('btnView');
    var btnDl = document.getElementById('btnDownload');
    var current = null;

    function close() { mask.classList.remove('open'); }
    document.getElementById('modalClose').addEventListener('click', close);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(); });

    function esc(t) {
      return String(t).replace(/[&<>]/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c];
      });
    }

    function render(data) {
      var list = (data && data.scripts) || [];
      if (!list.length) { mBody.innerHTML = '<p class="modal-empty">该设备没有脚本内容</p>'; return; }
      mBody.innerHTML = list.map(function (sc, i) {
        var n = Array.isArray(sc.actions) ? sc.actions.length : (sc.actionCount || '-');
        var raw = JSON.stringify(sc.actions, null, 2);
        return '<div class="script-item">'
          + '<div class="script-name">' + esc(sc.name || ('脚本 ' + (i + 1))) + '</div>'
          + '<div class="script-meta">' + n + ' 个动作</div>'
          + '<div class="script-toggle" data-idx="' + i + '">展开原始内容 ▾</div>'
          + '<pre class="script-raw" id="raw' + i + '">' + esc(raw) + '</pre>'
          + '</div>';
      }).join('');
      Array.prototype.forEach.call(mBody.querySelectorAll('.script-toggle'), function (t) {
        t.addEventListener('click', function () {
          var pre = document.getElementById('raw' + t.getAttribute('data-idx'));
          var open = pre.classList.toggle('open');
          t.textContent = open ? '收起原始内容 ▴' : '展开原始内容 ▾';
        });
      });
    }

    btnView.addEventListener('click', function () {
      if (!current) return;
      mBody.innerHTML = '<p class="modal-empty">加载中…</p>';
      fetch(current.url)
        .then(function (r) { return r.json(); })
        .then(render)
        .catch(function () { mBody.innerHTML = '<p class="modal-empty">加载失败</p>'; });
    });

    var mInfo = document.getElementById('modalInfo');
    var mActions = document.getElementById('modalActions');

    function row(label, value) {
      if (value === undefined || value === null || value === '') return '';
      return '<div class="info-row"><span class="info-key">' + esc(label)
        + '</span><span class="info-val" title="' + esc(value) + '">' + esc(value) + '</span></div>';
    }
    function card(title, rows, wide) {
      var body = rows.filter(Boolean).join('');
      if (!body) return '';
      return '<div class="info-card' + (wide ? ' wide' : '') + '"><h4>' + esc(title) + '</h4>' + body + '</div>';
    }
    function fmtBytes(n) {
      n = Number(n) || 0;
      if (!n) return '';
      var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0;
      while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
      return n.toFixed(i >= 3 ? 1 : 0) + ' ' + u[i];
    }
    function fmtUptime(ms) {
      ms = Number(ms) || 0;
      if (!ms) return '';
      var t = Math.floor(ms / 1000);
      var h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), sec = t % 60;
      function p2(x) { return String(x).length < 2 ? '0' + x : String(x); }
      return p2(h) + ':' + p2(m) + ':' + p2(sec);
    }
    var HEALTH = { 1: '未知', 2: '良好', 3: '过热', 4: '损坏', 5: '过压', 6: '未知故障', 7: '过冷' };
    var STATUS = { 1: '未知', 2: '充电中', 3: '放电中', 4: '未充电', 5: '已充满' };
    var PLUGGED = { 0: '电池', 1: '交流充电器', 2: 'USB', 4: '无线' };

    // 按 PlainApp 的五张卡片渲染完整设备信息
    function renderInfo(d, tr) {
      var di = d.deviceInfo || {};
      var dev = di.device || {}, sys = di.system || {}, hw = di.hardware || {},
          pf = di.platform || {}, bat = di.battery || {};
      var html = [
        card('设备', [
          row('设备名称', dev.name || d.model),
          row('platform', dev.platform || 'ANDROID'),
          row('厂商', dev.manufacturer || d.brand),
          row('型号', dev.model || tr.getAttribute('data-modelcode') || d.model),
          row('language', dev.language),
          row('设备 ID', d.deviceId),
          row('应用版本', (dev.appVersion || d.appVersion || '') +
            ((dev.appBuildNumber || d.appVersionCode) ? ' (' + (dev.appBuildNumber || d.appVersionCode) + ')' : ''))
        ]),
        card('系统', [
          row('系统名称', sys.osName || 'Android'),
          row('系统版本', sys.osVersion || d.android),
          row('内核', sys.kernelVersion),
          row('运行时间', fmtUptime(sys.uptime))
        ]),
        card('硬件', [
          row('CPU 架构', hw.cpuArch || d.cpu),
          row('总内存', fmtBytes(hw.totalMemory)),
          row('总存储', fmtBytes(hw.totalStorage)),
          row('屏幕分辨率', hw.displayWidth ? (hw.displayWidth + ' × ' + hw.displayHeight) : ''),
          row('屏幕密度', hw.displayDensity)
        ]),
        card('电池', [
          row('健康', HEALTH[bat.health]),
          row('剩余', (bat.level !== undefined && bat.level >= 0) ? bat.level + '%' : ''),
          row('状态', STATUS[bat.status]),
          row('电源', PLUGGED[bat.plugged]),
          row('技术', bat.technology),
          row('温度', bat.temperature ? bat.temperature + ' ℃' : ''),
          row('电压', bat.voltage ? bat.voltage + ' mV' : ''),
          row('容量', bat.capacity ? bat.capacity + ' mAh' : '')
        ]),
        card('平台信息', [
          row('Android 版本', (sys.osVersion || d.android || '') + (pf.sdkVersion ? ' (SDK ' + pf.sdkVersion + ')' : '')),
          row('安全补丁', pf.securityPatch),
          row('引导程序', pf.bootloader),
          row('构建编号', pf.buildNumber),
          row('基带', pf.radioVersion),
          row('硬件', pf.hardware),
          row('主板', pf.board),
          row('设备', pf.device),
          row('品牌', pf.buildBrand),
          row('Java 虚拟机', pf.javaVmVersion),
          row('OpenGL ES', pf.glEsVersion),
          row('构建指纹', pf.fingerprint),
          row('构建时间', pf.buildTime ? new Date(Number(pf.buildTime)).toLocaleString('zh-CN') : '')
        ], true)
      ].filter(Boolean).join('');
      return html || '<p class="info-empty">该设备尚未上报完整信息，升级客户端后可见</p>';
    }

    Array.prototype.forEach.call(document.querySelectorAll('.table-wrap tbody tr'), function (tr) {
      if (!tr.getAttribute('data-device')) return;
      tr.addEventListener('click', function () {
        var id = tr.getAttribute('data-device');
        var file = tr.getAttribute('data-file') || 'scripts.json';
        var count = parseInt(tr.getAttribute('data-count'), 10) || 0;
        var d = {};
        try { d = JSON.parse(tr.getAttribute('data-json') || '{}'); } catch (e) { d = {}; }

        mTitle.textContent = tr.getAttribute('data-name') || '设备详情';
        mSub.textContent = count ? (count + ' 个脚本') : '暂无脚本';
        mInfo.innerHTML = '<div class="info-grid">' + renderInfo(d, tr) + '</div>';

        // 没有脚本时不显示查看/下载按钮，只看设备信息
        if (count) {
          current = { url: '/api/download?deviceId=' + encodeURIComponent(id) + '&filename=' + encodeURIComponent(file) };
          btnDl.href = current.url;
          btnDl.setAttribute('download', file);
          mActions.style.display = '';
          mBody.style.display = '';
          mBody.innerHTML = '<p class="modal-empty">点击「查看内容」加载脚本</p>';
        } else {
          current = null;
          mActions.style.display = 'none';
          mBody.style.display = 'none';
        }
        mask.classList.add('open');
      });
    });
  }

  refreshCount();
})();
</script>`;

function dashboardHtml(stats, announcement) {
  const ann = announcement || { title: '公告栏', content: '', updatedAt: '' };
  const devices = stats.devices || [];

  const rows = devices
    .map(
      (d) => `<tr data-device="${esc(d.deviceId || '')}" data-name="${esc(displayName(d))}" data-file="${esc(d.scriptFile || 'scripts.json')}" data-count="${esc(String(d.scriptCount || 0))}" data-json="${esc(JSON.stringify(d))}" data-modelcode="${esc(d.modelCode || '')}" data-android="${esc(d.android || '')}" data-appver="${esc(d.appVersion || '')}" data-appcode="${esc(String(d.appVersionCode || ''))}" data-cpu="${esc(d.cpu || '')}">
        <td class="strong">${esc(d.brand)}</td>
        <td>${esc(d.model)}</td>
        <td data-sort="${esc(String(d.scriptCount || 0))}">${
          d.scriptCount ? `<span class="script-badge">${esc(String(d.scriptCount))}</span>` : '<span class="script-none">—</span>'
        }</td>
        <td data-sort="${esc(String(d.appVersionCode || 0))}">${
          d.appVersion ? `<span class="app-ver">${esc(d.appVersion)}</span>` : '<span class="script-none">—</span>'
        }</td>
        <td class="strong" data-sort="${esc(String(d.installs || 0))}">${esc(d.installs)}</td>
        <td>${esc(d.firstInstall)}</td>
        <td>${esc(d.lastSeen)}</td>
        <td class="ip-cell">${ipCell(d)}</td>
      </tr>`
    )
    .join('');

  const total = devices.length;
  const charts = [
    barChart('品牌分布', countBy(devices, (d) => d.brand), total),
    donutChart('系统版本', sortByVersionDesc(countBy(devices, (d) => d.android)), total),
    donutChart('应用版本', countBy(devices, (d) => d.appVersion), total),
  ].join('');

  const headerRight = `<div class="live"><span class="dot"></span>LIVE · 最近上报 ${esc(stats.last_update || '-')}</div>`;

  const body = `
    ${dashboardStyles}
    <div class="notice">
      <div class="notice-head">
        <b>公告栏</b>
        <a href="#" onclick="document.getElementById('announceEdit').style.display='block';return false;">编辑</a>
      </div>
      <p class="notice-body">${esc(ann.content || '暂无公告')}</p>
      <form id="announceEdit" method="post" action="/api/chat/announcement" style="display:none;">
        <input name="title" value="${esc(ann.title || '公告栏')}" placeholder="公告标题" />
        <textarea name="content" rows="4" placeholder="公告内容">${esc(ann.content || '')}</textarea>
        <button type="submit">保存公告</button>
        <span class="meta">更新于 ${esc(ann.updatedAt || '-')}</span>
      </form>
    </div>

    <div class="tiles">
      ${statTile(stats.install_count || 0, '安装次数')}
      ${statTile(stats.update_count || 0, '更新次数')}
      ${statTile(stats.unique_devices || 0, '设备数')}
      ${statTile(stats.last_update || '-', '最近上报', true)}
    </div>

    <p class="section-label">设备构成 · Fleet Composition</p>
    <div class="charts">${charts}</div>

    <p class="section-label">设备日志 · Device Log</p>
    <div class="table-tools">
      <input id="deviceSearch" type="search" placeholder="搜索品牌 / 型号 / 设备 ID / IP…" />
      <span class="table-count" id="deviceCount"></span>
    </div>
    <div class="panel table-wrap">
      <table id="deviceTable">
        <thead><tr><th>品牌</th><th>型号</th><th>脚本</th><th>应用版本</th><th>安装次数</th><th>首次安装</th><th>最近上报</th><th>IP / 归属地</th></tr></thead>
        <tbody>${rows || '<tr><td class="empty" colspan="8">暂无数据</td></tr>'}</tbody>
      </table>
    </div>

    <div class="modal-mask" id="scriptModal">
      <div class="modal">
        <div class="modal-head">
          <div>
            <div class="modal-title" id="modalTitle">录制脚本</div>
            <div class="modal-sub" id="modalSub"></div>
          </div>
          <button class="modal-close" id="modalClose" aria-label="关闭">&times;</button>
        </div>
        <div class="modal-info" id="modalInfo"></div>
        <div class="modal-actions" id="modalActions">
          <button class="btn btn-primary" id="btnView">查看内容</button>
          <a class="btn" id="btnDownload" download>下载</a>
        </div>
        <div class="modal-body" id="modalBody"><p class="modal-empty">点击「查看内容」加载脚本</p></div>
      </div>
    </div>
    ${dashboardScript}
  `;

  return pageShell({
    title: '自动滑屏器app统计台',
    eyebrow: 'AutoSlide · Fleet Monitor',
    heading: '滑屏器 统计台',
    headerRight,
    body,
  });
}

module.exports = { dashboardHtml };
