'use strict';

/**
 * AutoSlide · Fleet Monitor
 *
 * 增强版统计后台看板
 *
 * 信息层次：
 * 概览数字 → 分布图 → 设备明细表 → 设备 Inspector
 *
 * 设备 Inspector：
 * - Device Overview
 * - CPU
 * - GPU
 * - Memory
 * - Storage
 * - Display
 * - Battery
 * - Network
 * - Android System
 * - Runtime
 * - Sensors
 * - Camera
 * - Connectivity
 * - Capabilities
 */

const { esc } = require('../util');
const { baseStyles } = require('./styles');

/**
 * 按字段统计出现次数
 */
function countBy(list, pick) {
  const m = new Map();

  list.forEach((item) => {
    const raw = pick(item);
    const label =
      raw === undefined || raw === null || raw === ''
        ? '未知'
        : String(raw).trim();

    const key = label.toLowerCase();
    const hit = m.get(key);

    if (hit) {
      hit.n += 1;
    } else {
      m.set(key, { label, n: 1 });
    }
  });

  return [...m.values()]
    .sort((a, b) => b.n - a.n)
    .map((v) => [v.label, v.n]);
}

/**
 * 系统版本按数字大小倒序
 */
function sortByVersionDesc(pairs) {
  return [...pairs].sort((a, b) => {
    const na = parseFloat(a[0]);
    const nb = parseFloat(b[0]);

    if (Number.isNaN(na) && Number.isNaN(nb)) {
      return b[1] - a[1];
    }

    if (Number.isNaN(na)) return 1;
    if (Number.isNaN(nb)) return -1;

    return nb - na;
  });
}

/**
 * 横向条形图
 */
function barChart(title, pairs, total) {
  const max =
    pairs.reduce((m, p) => Math.max(m, p[1]), 0) || 1;

  const rows = pairs
    .map(([label, n]) => {
      const pct = total
        ? Math.round((n / total) * 1000) / 10
        : 0;

      return `
        <div class="bar-row"
             title="${esc(label)}：${n} 台（${pct}%）">
          <span class="bar-label">${esc(label)}</span>
          <span class="bar-track">
            <span class="bar-fill"
                  style="width:${(n / max) * 100}%"></span>
          </span>
          <span class="bar-value">${n}</span>
          <span class="bar-pct">${pct}%</span>
        </div>
      `;
    })
    .join('');

  return `
    <section class="chart">
      <h3 class="chart-title">${esc(title)}</h3>
      <div class="bars">
        ${
          rows ||
          '<p class="chart-empty">暂无数据</p>'
        }
      </div>
    </section>
  `;
}

/**
 * 环形图
 */
function donutChart(title, pairs, total) {
  if (!pairs.length) {
    return `
      <section class="chart">
        <h3 class="chart-title">${esc(title)}</h3>
        <p class="chart-empty">暂无数据</p>
      </section>
    `;
  }

  const RAMP = [
    '#7f3f2c',
    '#9c4f37',
    '#b85f42',
    '#d17150',
    '#d98764',
    '#e09d80',
    '#e8b39c',
    '#f0c9b8'
  ];

  const rankOf = new Map();

  [...pairs]
    .sort((a, b) => b[1] - a[1])
    .forEach(([label], i) => {
      rankOf.set(label, i);
    });

  const colorOf = (label) =>
    RAMP[
      Math.min(
        rankOf.get(label) || 0,
        RAMP.length - 1
      )
    ];

  const sum =
    pairs.reduce((a, p) => a + p[1], 0) || 1;

  const R = 54;
  const C = 2 * Math.PI * R;

  let offset = 0;

  const arcs = pairs
    .map(([label, n]) => {
      const frac = n / sum;
      const len = frac * C;
      const gap = len > 6 ? 2 : 0;

      const visibleLen =
        Math.max(len - gap, 0.5);

      const dash =
        `${visibleLen} ${C - visibleLen}`;

      const seg = `
        <circle
          class="donut-seg"
          r="${R}"
          cx="70"
          cy="70"
          fill="none"
          stroke="${colorOf(label)}"
          stroke-width="22"
          stroke-dasharray="${dash}"
          stroke-dashoffset="${-offset}"
          transform="rotate(-90 70 70)">
          <title>
            ${esc(label)}：
            ${n} 台
            （${Math.round(frac * 1000) / 10}%）
          </title>
        </circle>
      `;

      offset += len;
      return seg;
    })
    .join('');

  const legend = pairs
    .map(([label, n]) => {
      const pct =
        Math.round((n / sum) * 1000) / 10;

      return `
        <div class="donut-item"
             title="${esc(label)}：${n} 台（${pct}%）">
          <span class="donut-dot"
                style="background:${colorOf(label)}">
          </span>

          <span class="donut-label">
            ${esc(label)}
          </span>

          <span class="donut-value">
            ${n}
          </span>

          <span class="donut-pct">
            ${pct}%
          </span>
        </div>
      `;
    })
    .join('');

  return `
    <section class="chart">
      <h3 class="chart-title">
        ${esc(title)}
      </h3>

      <div class="donut-wrap">
        <svg
          class="donut"
          viewBox="0 0 140 140"
          role="img"
          aria-label="${esc(title)}">

          ${arcs}

          <text
            x="70"
            y="66"
            class="donut-center-num">
            ${total}
          </text>

          <text
            x="70"
            y="82"
            class="donut-center-label">
            台
          </text>
        </svg>

        <div class="donut-legend">
          ${legend}
        </div>
      </div>
    </section>
  `;
}

/**
 * IP 单元格
 */
function ipCell(d) {
  const lines = [];

  if (d.ip) {
    lines.push(`
      <div class="ip-line">
        <span class="ip-tag">连接</span>
        <span class="ip-addr">
          ${esc(d.ip)}
        </span>

        ${
          d.ipLoc
            ? `<span class="ip-loc">
                 ${esc(d.ipLoc)}
               </span>`
            : ''
        }
      </div>
    `);
  }

  if (d.egressIp) {
    lines.push(`
      <div class="ip-line">
        <span class="ip-tag">出口</span>
        <span class="ip-addr">
          ${esc(d.egressIp)}
        </span>

        ${
          d.egressLoc
            ? `<span class="ip-loc">
                 ${esc(d.egressLoc)}
               </span>`
            : ''
        }
      </div>
    `);
  }

  return lines.join('') || '-';
}

/**
 * 展示名称
 */
function displayName(d) {
  const brand =
    String(d.brand || '').trim();

  const model =
    String(d.model || '').trim();

  if (!brand) return model;
  if (!model) return brand;

  return model
    .toLowerCase()
    .startsWith(brand.toLowerCase())
    ? model
    : `${brand} ${model}`;
}

/**
 * 概览磁贴
 */
function statTile(value, label, mono) {
  return `
    <div class="tile">
      <div class="tile-num${mono ? ' tile-num-sm' : ''}">
        ${esc(String(value))}
      </div>

      <div class="tile-label">
        ${esc(label)}
      </div>
    </div>
  `;
}

/**
 * 增强版 Dashboard CSS
 */
const dashboardStyles = `
<style>
${baseStyles()}

  /* =========================================================
     新界面外壳：页头 / 统计卡 / 工具栏 / 设备表
     ========================================================= */

  .dashboard { max-width:1640px; margin:0 auto; }

  .dashboard-header {
    display:flex; align-items:center; justify-content:space-between;
    flex-wrap:wrap; gap:16px;
    padding-bottom:18px; margin-bottom:22px;
    border-bottom:1px solid var(--border);
  }
  .header-left { display:flex; align-items:center; gap:14px; }
  .brand-mark {
    width:44px; height:44px; flex:none;
    display:flex; align-items:center; justify-content:center;
    border-radius:12px;
    background:linear-gradient(135deg, var(--clay), #c25f3f);
    box-shadow:0 6px 18px rgba(217,119,87,.28);
  }
  .brand-mark-core { color:#fff; font-size:20px; line-height:1; }
  .dashboard-title {
    font-family:var(--serif); font-size:26px; font-weight:600;
    color:var(--text); letter-spacing:-.01em; line-height:1.2;
  }
  .dashboard-subtitle {
    font-family:var(--mono); font-size:11px; letter-spacing:.16em;
    text-transform:uppercase; color:var(--text-faint); margin-top:4px;
  }
  .header-right { display:flex; align-items:center; gap:14px; flex-wrap:wrap; }
  .header-time { font-family:var(--mono); font-size:12px; color:var(--text-dim); }
  .header-live {
    display:flex; align-items:center; gap:7px;
    font-family:var(--mono); font-size:11px; letter-spacing:.1em;
    color:var(--text-dim);
  }
  .live-dot {
    width:7px; height:7px; border-radius:50%;
    background:var(--green); box-shadow:0 0 0 0 rgba(91,146,121,.6);
    animation:livePulse 2s infinite;
  }
  @keyframes livePulse {
    0%   { box-shadow:0 0 0 0 rgba(91,146,121,.55); }
    70%  { box-shadow:0 0 0 7px rgba(91,146,121,0); }
    100% { box-shadow:0 0 0 0 rgba(91,146,121,0); }
  }

  /* ---------- 统计卡 ---------- */
  .dashboard-stats {
    display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr));
    gap:12px; margin-bottom:22px;
  }
  .stat-card {
    display:flex; align-items:center; gap:14px;
    padding:16px 18px;
    background:var(--panel); border:1px solid var(--border);
    border-radius:12px; border-top:2px solid var(--clay);
  }
  .stat-icon {
    width:38px; height:38px; flex:none;
    display:flex; align-items:center; justify-content:center;
    border-radius:10px; font-size:16px;
    background:rgba(217,119,87,.10); color:var(--clay);
  }
  .stat-icon-online  { background:rgba(91,146,121,.12); color:var(--green); }
  .stat-icon-offline { background:rgba(156,154,144,.14); color:var(--text-faint); }
  .stat-content { min-width:0; }
  .stat-label {
    font-family:var(--mono); font-size:10px; letter-spacing:.12em;
    text-transform:uppercase; color:var(--text-faint);
  }
  .stat-value {
    font-family:var(--mono); font-size:26px; font-weight:600;
    color:var(--text); line-height:1.15; letter-spacing:-.02em; margin-top:2px;
  }

  /* ---------- 工具栏 ---------- */
  .device-toolbar {
    display:flex; align-items:center; justify-content:space-between;
    flex-wrap:wrap; gap:12px; margin-bottom:12px;
  }
  .toolbar-left { display:flex; align-items:baseline; gap:10px; }
  .toolbar-title {
    font-family:var(--mono); font-size:11px; letter-spacing:.14em;
    text-transform:uppercase; color:var(--text-faint);
  }
  .toolbar-count { font-family:var(--mono); font-size:12px; color:var(--text-dim); }
  .toolbar-right { display:flex; align-items:center; gap:10px; }
  .search-box {
    display:flex; align-items:center; gap:8px;
    padding:7px 12px; min-width:290px;
    background:var(--panel); border:1px solid var(--border); border-radius:9px;
  }
  .search-box:focus-within { border-color:var(--clay); }
  .search-icon { color:var(--text-faint); font-size:14px; }
  .search-box input {
    flex:1; min-width:0;
    border:none; outline:none; background:transparent;
    color:var(--text); font-size:13px; font-family:var(--sans);
  }
  .search-box kbd {
    font-family:var(--mono); font-size:10px; color:var(--text-faint);
    border:1px solid var(--border); border-radius:4px; padding:1px 5px;
  }

  /* ---------- 设备表 ---------- */
  .device-table-wrap {
    background:var(--panel); border:1px solid var(--border);
    border-radius:12px; overflow:auto;
  }
  .device-table { width:100%; border-collapse:collapse; font-size:13px; }
  .device-table thead th {
    position:sticky; top:0; z-index:1;
    padding:11px 14px; text-align:left; white-space:nowrap;
    background:var(--panel-alt); color:var(--text-dim);
    font-family:var(--mono); font-size:11px; font-weight:500;
    letter-spacing:.06em; text-transform:uppercase;
    border-bottom:1px solid var(--border);
    cursor:pointer; user-select:none;
  }
  .device-table thead th:hover { color:var(--clay); }
  .sort-icon { color:var(--text-faint); font-size:9px; margin-left:3px; }
  .device-table tbody td {
    padding:11px 14px; vertical-align:middle;
    border-bottom:1px solid var(--border-soft);
  }
  .device-table tbody tr:last-child td { border-bottom:none; }
  .device-row:hover { background:var(--panel-alt); }

  .device-cell { display:flex; align-items:center; gap:10px; }
  .device-avatar {
    width:34px; height:34px; flex:none;
    display:flex; align-items:center; justify-content:center;
    border-radius:9px;
    background:rgba(217,119,87,.10); color:var(--clay);
    font-family:var(--mono); font-size:14px; font-weight:600;
  }
  .device-main { min-width:0; }
  .device-name {
    font-weight:600; color:var(--text);
    overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:210px;
  }
  .device-id {
    font-family:var(--mono); font-size:10.5px; color:var(--text-faint);
    overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:210px;
  }
  .device-model { display:flex; flex-direction:column; gap:1px; }
  .device-model strong { font-weight:600; color:var(--text); }
  .device-model span { font-size:11px; color:var(--text-faint); }
  .android-badge {
    display:inline-block; padding:2px 8px; border-radius:20px;
    background:rgba(107,132,163,.12); color:var(--slate);
    font-family:var(--mono); font-size:11px; white-space:nowrap;
  }
  .app-version { display:flex; flex-direction:column; gap:1px; }
  .app-version strong { font-family:var(--mono); font-size:12px; color:var(--text); }
  .app-version span { font-family:var(--mono); font-size:10px; color:var(--text-faint); }

  .device-status {
    display:inline-flex; align-items:center; gap:6px;
    padding:3px 9px; border-radius:20px;
    font-family:var(--mono); font-size:10px; letter-spacing:.06em;
  }
  .device-status.online  { background:rgba(91,146,121,.12); color:var(--green); }
  .device-status.offline { background:rgba(156,154,144,.14); color:var(--text-faint); }
  .device-status-dot { width:6px; height:6px; border-radius:50%; background:currentColor; }

  .device-detail-btn {
    display:inline-flex; align-items:center; gap:6px;
    padding:5px 11px; white-space:nowrap;
    border:1px solid var(--border); border-radius:8px;
    background:var(--panel-alt); color:var(--text-dim);
    font-size:12px; font-family:var(--sans); cursor:pointer;
  }
  .device-detail-btn:hover {
    border-color:var(--clay); color:var(--clay);
    background:rgba(217,119,87,.06);
  }
  .device-detail-arrow { font-family:var(--mono); }

  /* ---------- 空态 / 错误 ---------- */
  .device-empty {
    padding:56px 20px; text-align:center;
    background:var(--panel); border:1px solid var(--border); border-radius:12px;
  }
  .device-empty-icon { font-size:30px; color:var(--text-faint); }
  .device-empty-title { margin-top:10px; font-size:15px; font-weight:600; color:var(--text); }
  .device-empty-sub { margin-top:4px; font-size:12.5px; color:var(--text-faint); }
  .device-error {
    display:flex; gap:12px; align-items:flex-start;
    padding:14px; border-radius:10px;
    background:rgba(217,119,87,.07); border:1px solid rgba(217,119,87,.25);
  }
  .device-error-icon {
    width:24px; height:24px; flex:none;
    display:flex; align-items:center; justify-content:center;
    border-radius:50%; background:var(--clay); color:#fff;
    font-family:var(--mono); font-size:14px;
  }
  .device-error-detail {
    margin-top:4px; font-family:var(--mono); font-size:11px; color:var(--text-dim);
    word-break:break-all;
  }

  .dashboard-footer {
    display:flex; align-items:center; justify-content:space-between;
    flex-wrap:wrap; gap:10px;
    margin-top:26px; padding-top:16px;
    border-top:1px solid var(--border);
    font-family:var(--mono); font-size:10.5px; letter-spacing:.1em;
    color:var(--text-faint); text-transform:uppercase;
  }

  .section-label {
    font-family:var(--mono); font-size:11px; letter-spacing:.14em;
    text-transform:uppercase; color:var(--text-faint);
    margin:26px 0 12px;
  }

  @media (max-width:760px) {
    .search-box { min-width:0; width:100%; }
    .device-toolbar { align-items:stretch; }
    .toolbar-right { width:100%; }
  }


  /* =========================================================
     基础
     ========================================================= */

  .admin-links {
    display:flex;
    align-items:center;
    gap:8px;
    flex-wrap:wrap;
  }

  .admin-links a {
    padding:7px 14px;
    border:1px solid var(--border);
    border-radius:10px;
    background:var(--panel);
    color:var(--text-dim);
    font-size:12.5px;
    white-space:nowrap;
  }

  .admin-links a:hover {
    background:rgba(217,119,87,.08);
    color:var(--clay);
    text-decoration:none;
  }

  .admin-arrow {
    color:var(--clay);
    font-family:var(--mono);
  }

  /* =========================================================
     公告
     ========================================================= */

  .notice {
    background:var(--panel);
    border:1px solid var(--border);
    border-radius:12px;
    padding:16px;
    margin-bottom:20px;
  }

  .notice-head {
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap:12px;
  }

  .notice-head b {
    color:var(--text);
    font-size:14px;
  }

  .notice-body {
    margin:8px 0 0;
    color:var(--text-dim);
    white-space:pre-wrap;
    word-break:break-word;
    font-size:14px;
    line-height:1.7;
  }

  .notice form {
    margin-top:12px;
  }

  .notice input,
  .notice textarea {
    width:100%;
    box-sizing:border-box;
    padding:8px 10px;
    margin-bottom:8px;
    border:1px solid var(--border);
    border-radius:8px;
    background:var(--panel-alt);
    color:var(--text);
    font-size:13px;
    font-family:var(--sans);
  }

  .notice textarea {
    resize:vertical;
  }

  .notice button {
    padding:8px 18px;
    border:none;
    border-radius:8px;
    background:var(--clay);
    color:#fff;
    font-size:13px;
    cursor:pointer;
  }

  .notice .meta {
    margin-left:10px;
    font-size:11px;
    color:var(--text-faint);
  }

  /* =========================================================
     顶部统计
     ========================================================= */

  .tiles {
    display:grid;
    grid-template-columns:
      repeat(auto-fit,minmax(160px,1fr));
    gap:12px;
    margin-bottom:22px;
  }

  .tile {
    background:var(--panel);
    border:1px solid var(--border);
    border-radius:12px;
    padding:16px 18px;
    border-top:2px solid var(--clay);
  }

  .tile-num {
    font-family:var(--mono);
    font-size:30px;
    font-weight:600;
    line-height:1.1;
    color:var(--text);
    letter-spacing:-.02em;
  }

  .tile-num-sm {
    font-size:15px;
    font-weight:500;
    padding-top:10px;
  }

  .tile-label {
    margin-top:6px;
    font-size:12px;
    color:var(--text-dim);
  }

  /* =========================================================
     图表
     ========================================================= */

  .charts {
    display:grid;
    grid-template-columns:
      repeat(auto-fit,minmax(280px,1fr));
    gap:12px;
    margin-bottom:26px;
  }

  .chart {
    background:var(--panel);
    border:1px solid var(--border);
    border-radius:12px;
    padding:16px 18px;
  }

  .chart-title {
    margin:0 0 14px;
    font-size:13px;
    font-weight:600;
    color:var(--text);
  }

  .chart-empty {
    margin:0;
    font-size:13px;
    color:var(--text-faint);
  }

  .bars {
    display:flex;
    flex-direction:column;
    gap:7px;
  }

  .bar-row {
    display:grid;
    grid-template-columns:
      84px 1fr 30px 44px;
    align-items:center;
    gap:10px;
  }

  .bar-label {
    font-size:12px;
    color:var(--text-dim);
    overflow:hidden;
    text-overflow:ellipsis;
    white-space:nowrap;
  }

  .bar-track {
    height:14px;
    background:var(--panel-alt);
    border-radius:4px;
    overflow:hidden;
  }

  .bar-fill {
    display:block;
    height:100%;
    background:var(--clay);
    border-radius:0 4px 4px 0;
    min-width:2px;
  }

  .bar-value {
    font-family:var(--mono);
    font-size:12px;
    color:var(--text);
    text-align:right;
  }

  .bar-pct {
    font-family:var(--mono);
    font-size:11px;
    color:var(--text-faint);
    text-align:right;
  }

  .donut-wrap {
    display:flex;
    align-items:center;
    gap:20px;
    flex-wrap:wrap;
  }

  .donut {
    width:300px;
    height:300px;
    flex:none;
  }

  .donut-seg {
    transition:opacity .15s;
  }

  .donut:hover .donut-seg {
    opacity:.55;
  }

  .donut .donut-seg:hover {
    opacity:1;
  }

  .donut-center-num {
    text-anchor:middle;
    font-family:var(--mono);
    font-size:20px;
    font-weight:600;
    fill:var(--text);
  }

  .donut-center-label {
    text-anchor:middle;
    font-size:10px;
    fill:var(--text-faint);
  }

  .donut-legend {
    flex:1;
    min-width:130px;
    display:flex;
    flex-direction:column;
    gap:4px;
  }

  .donut-item {
    display:grid;
    grid-template-columns:
      10px 1fr 28px 42px;
    align-items:center;
    gap:8px;
  }

  .donut-dot {
    width:10px;
    height:10px;
    border-radius:3px;
  }

  .donut-label {
    font-size:12px;
    color:var(--text-dim);
    overflow:hidden;
    text-overflow:ellipsis;
    white-space:nowrap;
  }

  .donut-value {
    font-family:var(--mono);
    font-size:12px;
    color:var(--text);
    text-align:right;
  }

  .donut-pct {
    font-family:var(--mono);
    font-size:11px;
    color:var(--text-faint);
    text-align:right;
  }

  /* =========================================================
     表格
     ========================================================= */

  .table-tools {
    display:flex;
    align-items:center;
    justify-content:space-between;
    flex-wrap:wrap;
    gap:10px;
    margin-bottom:10px;
  }

  .table-tools input {
    padding:7px 12px;
    min-width:220px;
    border:1px solid var(--border);
    border-radius:8px;
    background:var(--panel);
    color:var(--text);
    font-size:13px;
    font-family:var(--sans);
  }

  .table-count {
    font-family:var(--mono);
    font-size:12px;
    color:var(--text-faint);
  }

  .table-tools-right {
    display:flex;
    align-items:center;
    gap:14px;
    flex-wrap:wrap;
  }

  .table-wrap table thead th {
    cursor:pointer;
    user-select:none;
    white-space:nowrap;
  }

  .table-wrap table thead th::after {
    content:'';
  }

  .table-wrap table thead th.asc::after {
    content:' ▲';
    font-size:9px;
  }

  .table-wrap table thead th.desc::after {
    content:' ▼';
    font-size:9px;
  }

  .ip-cell {
    max-width:300px;
  }

  .ip-line {
    display:flex;
    align-items:baseline;
    gap:6px;
    flex-wrap:wrap;
    line-height:1.7;
  }

  .ip-line + .ip-line {
    margin-top:2px;
  }

  .ip-tag {
    flex:none;
    font-size:10px;
    color:var(--text-faint);
    border:1px solid var(--border);
    border-radius:4px;
    padding:0 4px;
  }

  .ip-addr {
    font-family:var(--mono);
    font-size:12px;
    word-break:break-all;
  }

  .ip-loc {
    font-size:11px;
    color:var(--text-faint);
  }

  tr.hidden-row {
    display:none;
  }

  .app-ver {
    font-family:var(--mono);
    font-size:12px;
    white-space:nowrap;
  }

  .script-badge {
    display:inline-block;
    min-width:20px;
    padding:1px 7px;
    border-radius:10px;
    background:var(--clay);
    color:#fff;
    font-family:var(--mono);
    font-size:11px;
    text-align:center;
  }

  .script-none {
    color:var(--text-faint);
  }

  .table-wrap tbody tr {
    cursor:pointer;
  }

  .table-wrap tbody tr:hover {
    background:var(--panel-alt);
  }

  /* =========================================================
     Modal
     ========================================================= */

  .modal-mask {
    position:fixed;
    inset:0;
    background:rgba(35,34,31,.55);
    display:none;
    align-items:center;
    justify-content:center;
    padding:24px;
    z-index:50;
    backdrop-filter:blur(4px);
  }

  .modal-mask.open {
    display:flex;
  }

  .modal {
    background:var(--panel);
    border:1px solid var(--border);
    border-radius:16px;

    width:min(980px,100%);
    min-width:min(560px,100%);
    max-width:100%;
    max-height:90vh;

    display:flex;
    flex-direction:column;
    overflow:hidden;

    box-shadow:
      0 20px 70px rgba(0,0,0,.25);
  }

  .modal-head {
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap:12px;

    padding:16px 20px;

    border-bottom:1px solid var(--border);

    background:
      linear-gradient(
        135deg,
        var(--panel),
        var(--panel-alt)
      );
  }

  .modal-title {
    font-size:16px;
    font-weight:600;
    color:var(--text);
  }

  .modal-sub {
    font-family:var(--mono);
    font-size:11px;
    color:var(--text-faint);
    margin-top:3px;
  }

  .modal-close {
    border:none;
    background:none;
    font-size:24px;
    line-height:1;
    color:var(--text-faint);
    cursor:pointer;
    padding:0 4px;
  }

  .modal-info {
    padding:18px 20px;
    overflow:auto;
  }

  .modal-actions {
    display:flex;
    gap:10px;
    padding:14px 20px;
    border-top:1px solid var(--border);
  }

  .btn {
    padding:7px 16px;
    border:1px solid var(--border);
    border-radius:8px;
    background:var(--panel-alt);
    color:var(--text);
    font-size:13px;
    cursor:pointer;
    font-family:var(--sans);
    text-decoration:none;
    display:inline-block;
  }

  .btn-primary {
    background:var(--clay);
    border-color:var(--clay);
    color:#fff;
  }

  .btn:hover {
    text-decoration:none;
    opacity:.9;
  }

  .modal-body {
    padding:16px 20px;
    overflow:auto;
  }

  /* =========================================================
     Device Inspector
     ========================================================= */

  .device-overview {
    position:relative;

    display:grid;
    grid-template-columns:
      minmax(0,1fr)
      auto;

    gap:20px;

    padding:18px;
    margin-bottom:16px;

    border:1px solid var(--border);
    border-radius:14px;

    background:
      radial-gradient(
        circle at top right,
        rgba(217,119,87,.12),
        transparent 42%
      ),
      var(--panel-alt);

    overflow:hidden;
  }

  .device-overview::before {
    content:'';
    position:absolute;
    left:0;
    top:0;
    bottom:0;
    width:3px;
    background:var(--clay);
  }

  .device-overview-main {
    min-width:0;
  }

  .device-overview-label {
    font-family:var(--mono);
    font-size:10px;
    letter-spacing:.12em;
    color:var(--text-faint);
    text-transform:uppercase;
  }

  .device-overview-name {
    margin-top:5px;
    font-size:23px;
    font-weight:650;
    color:var(--text);
    line-height:1.2;
    word-break:break-word;
  }

  .device-overview-sub {
    margin-top:7px;
    display:flex;
    gap:8px;
    flex-wrap:wrap;
  }

  .device-chip {
    display:inline-flex;
    align-items:center;
    gap:5px;

    padding:4px 8px;

    border-radius:7px;
    border:1px solid var(--border);

    background:var(--panel);

    color:var(--text-dim);

    font-size:11px;
    font-family:var(--mono);
  }

  .device-chip-live {
    color:var(--clay);
  }

  .device-chip-live::before {
    content:'';
    width:6px;
    height:6px;
    border-radius:50%;
    background:var(--clay);
    box-shadow:0 0 8px var(--clay);
  }

  .device-overview-id {
    margin-top:10px;

    font-family:var(--mono);
    font-size:10px;
    color:var(--text-faint);

    overflow:hidden;
    text-overflow:ellipsis;
    white-space:nowrap;
  }

  .device-overview-score {
    min-width:100px;

    display:flex;
    flex-direction:column;
    justify-content:center;
    align-items:center;

    padding:10px;

    border-left:1px solid var(--border);
  }

  .device-score-number {
    font-family:var(--mono);
    font-size:34px;
    font-weight:700;
    color:var(--text);
    line-height:1;
  }

  .device-score-label {
    margin-top:6px;

    font-family:var(--mono);
    font-size:9px;
    letter-spacing:.1em;
    color:var(--text-faint);
  }

  /* =========================================================
     Quick metrics
     ========================================================= */

  .device-metrics {
    display:grid;
    grid-template-columns:
      repeat(4,minmax(0,1fr));

    gap:10px;
    margin-bottom:16px;
  }

  .device-metric {
    min-width:0;

    padding:12px;

    background:var(--panel);
    border:1px solid var(--border);
    border-radius:11px;
  }

  .device-metric-head {
    display:flex;
    justify-content:space-between;
    align-items:center;
    gap:8px;
  }

  .device-metric-name {
    font-size:10px;
    color:var(--text-faint);
    text-transform:uppercase;
    letter-spacing:.06em;
  }

  .device-metric-value {
    font-family:var(--mono);
    font-size:13px;
    color:var(--text);
  }

  .device-progress {
    height:5px;
    margin-top:9px;

    background:var(--panel-alt);

    border-radius:10px;
    overflow:hidden;
  }

  .device-progress-fill {
    height:100%;
    min-width:0;

    background:var(--clay);

    border-radius:10px;

    transition:
      width .35s ease;
  }

  /* =========================================================
     Inspector cards
     ========================================================= */

  .info-grid {
    display:grid;

    grid-template-columns:
      repeat(2,minmax(0,1fr));

    gap:12px;
  }

  .info-card {
    min-width:0;

    background:var(--panel-alt);

    border:1px solid var(--border-soft);
    border-radius:11px;

    padding:14px;
  }

  .info-card.wide {
    grid-column:1 / -1;
  }

  .info-card h4 {
    margin:0 0 11px;

    font-size:12px;
    font-weight:650;

    color:var(--text);

    display:flex;
    align-items:center;
    gap:7px;
  }

  .info-card h4::before {
    content:'';
    width:3px;
    height:13px;

    border-radius:3px;

    background:var(--clay);
  }

  .info-row {
    display:grid;

    grid-template-columns:
      minmax(75px,auto)
      minmax(0,1fr);

    gap:10px;

    align-items:baseline;

    padding:4px 0;

    border-bottom:
      1px solid rgba(127,127,127,.08);
  }

  .info-row:last-child {
    border-bottom:none;
  }

  .info-key {
    font-size:11px;
    color:var(--text-dim);
  }

  .info-val {
    min-width:0;

    font-size:11px;
    color:var(--text);

    font-family:var(--mono);

    white-space:normal;
    word-break:break-all;

    text-align:right;
  }

  .info-empty {
    font-size:12px;
    color:var(--text-faint);
    margin:0;
  }

  /* =========================================================
     Status badges
     ========================================================= */

  .status-list {
    display:grid;

    grid-template-columns:
      repeat(2,minmax(0,1fr));

    gap:7px;
  }

  .status-item {
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap:8px;

    padding:7px 9px;

    border:1px solid var(--border);
    border-radius:8px;

    background:var(--panel);
  }

  .status-name {
    font-size:11px;
    color:var(--text-dim);
  }

  .status-badge {
    font-family:var(--mono);
    font-size:10px;
    padding:2px 6px;
    border-radius:5px;

    color:var(--text-faint);
    background:var(--panel-alt);
  }

  .status-badge.ok {
    color:var(--clay);
    border:1px solid rgba(217,119,87,.25);
  }

  /* =========================================================
     Sensors / Camera
     ========================================================= */

  .capability-grid {
    display:grid;

    grid-template-columns:
      repeat(2,minmax(0,1fr));

    gap:7px;
  }

  .capability-item {
    display:flex;
    align-items:center;
    gap:7px;

    padding:7px 9px;

    border-radius:8px;

    background:var(--panel);

    border:1px solid var(--border);

    font-size:11px;
    color:var(--text-dim);
  }

  .capability-dot {
    width:6px;
    height:6px;

    flex:none;

    border-radius:50%;

    background:var(--text-faint);
  }

  .capability-dot.ok {
    background:var(--clay);
    box-shadow:0 0 6px rgba(217,119,87,.4);
  }

  /* =========================================================
     Script
     ========================================================= */

  .script-item {
    border:1px solid var(--border-soft);
    border-radius:10px;
    padding:10px 12px;
    margin-bottom:8px;
  }

  .script-name {
    font-size:13px;
    font-weight:600;
    color:var(--text);
  }

  .script-meta {
    font-family:var(--mono);
    font-size:11px;
    color:var(--text-faint);
    margin-top:2px;
  }

  .script-raw {
    margin:8px 0 0;
    padding:10px;
    background:var(--panel-alt);
    border-radius:8px;

    font-family:var(--mono);
    font-size:11px;
    line-height:1.6;

    white-space:pre-wrap;
    word-break:break-all;

    max-height:220px;
    overflow:auto;

    color:var(--text-dim);
    display:none;
  }

  .script-raw.open {
    display:block;
  }

  .script-toggle {
    margin-top:6px;
    font-size:12px;
    color:var(--clay);
    cursor:pointer;
  }

  .modal-sec-title {
    font-size:12px;
    letter-spacing:.08em;
    text-transform:uppercase;
    color:var(--text-faint);
    font-family:var(--mono);
    margin:16px 0 8px;
  }

  .modal-empty {
    color:var(--text-faint);
    font-size:13px;
  }

  /* =========================================================
     Mobile
     ========================================================= */

  @media (max-width:760px) {

    .modal {
      min-width:0;
      width:100%;
      max-height:94vh;
    }

    .device-overview {
      grid-template-columns:1fr;
    }

    .device-overview-score {
      border-left:none;
      border-top:1px solid var(--border);
      padding-top:14px;
    }

    .device-metrics {
      grid-template-columns:
        repeat(2,minmax(0,1fr));
    }

    .info-grid {
      grid-template-columns:1fr;
    }

    .info-card.wide {
      grid-column:auto;
    }
  }

</style>
`;
/* =========================================================
 * Device Inspector helpers
 * ========================================================= */

function safeNumber(v) {
  if (v === null || v === undefined || v === '') {
    return null;
  }

  const n = Number(v);

  return Number.isFinite(n) ? n : null;
}

function firstDefined(...values) {
  for (const v of values) {
    if (v !== undefined && v !== null && v !== '') {
      return v;
    }
  }

  return '';
}

function formatNumber(v, digits) {
  const n = safeNumber(v);

  if (n === null) {
    return '';
  }

  return n.toFixed(digits === undefined ? 1 : digits);
}

function fmtPercent(v) {
  const n = safeNumber(v);

  if (n === null) {
    return '';
  }

  return `${Math.max(0, Math.min(100, n))}%`;
}

function fmtBytes(v) {
  const n = safeNumber(v);

  if (n === null || n < 0) {
    return '';
  }

  if (n < 1024) {
    return `${n} B`;
  }

  if (n < 1024 * 1024) {
    return `${(n / 1024).toFixed(1)} KB`;
  }

  if (n < 1024 * 1024 * 1024) {
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
  }

  if (n < 1024 * 1024 * 1024 * 1024) {
    return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }

  return `${(n / 1024 / 1024 / 1024 / 1024).toFixed(2)} TB`;
}

function fmtHz(v) {
  const n = safeNumber(v);

  if (n === null) {
    return '';
  }

  if (n >= 1000000000) {
    return `${(n / 1000000000).toFixed(2)} GHz`;
  }

  if (n >= 1000000) {
    return `${(n / 1000000).toFixed(0)} MHz`;
  }

  if (n >= 1000) {
    return `${(n / 1000).toFixed(0)} kHz`;
  }

  return `${n} Hz`;
}

function fmtUptime(v) {
  const n = safeNumber(v);

  if (n === null || n < 0) {
    return '';
  }

  /*
   * Android 通常可能上传：
   *
   * milliseconds
   * seconds
   *
   * 这里兼容两种。
   */
  const ms = n > 100000000 ? n : n * 1000;

  let sec = Math.floor(ms / 1000);

  const days = Math.floor(sec / 86400);
  sec %= 86400;

  const hours = Math.floor(sec / 3600);
  sec %= 3600;

  const minutes = Math.floor(sec / 60);

  if (days > 0) {
    return `${days}d ${hours}h ${minutes}m`;
  }

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }

  return `${minutes}m`;
}

function calcPercent(used, total) {
  const u = safeNumber(used);
  const t = safeNumber(total);

  if (u === null || t === null || t <= 0) {
    return null;
  }

  return Math.max(
    0,
    Math.min(
      100,
      (u / t) * 100
    )
  );
}

function progressPercent(value) {
  const n = safeNumber(value);

  if (n === null) {
    return 0;
  }

  return Math.max(
    0,
    Math.min(100, n)
  );
}

/* =========================================================
 * 信息卡
 * ========================================================= */

function row(label, value) {
  if (
    value === undefined ||
    value === null ||
    value === ''
  ) {
    return '';
  }

  return `
    <div class="info-row">
      <span class="info-key">
        ${esc(String(label))}
      </span>

      <span class="info-val">
        ${esc(String(value))}
      </span>
    </div>
  `;
}

function card(title, rows, wide) {
  const content =
    Array.isArray(rows)
      ? rows.filter(Boolean).join('')
      : String(rows || '');

  if (!content) {
    return '';
  }

  return `
    <section class="info-card${wide ? ' wide' : ''}">
      <h4>${esc(title)}</h4>
      ${content}
    </section>
  `;
}

/* =========================================================
 * Status
 * ========================================================= */

function statusItem(name, value, ok) {
  return `
    <div class="status-item">
      <span class="status-name">
        ${esc(name)}
      </span>

      <span class="status-badge${ok ? ' ok' : ''}">
        ${esc(String(value))}
      </span>
    </div>
  `;
}

function boolStatus(v) {
  if (
    v === true ||
    v === 1 ||
    v === '1' ||
    v === 'true' ||
    v === 'TRUE' ||
    v === 'yes' ||
    v === 'YES' ||
    v === 'enabled' ||
    v === 'Enabled'
  ) {
    return true;
  }

  return false;
}

function capabilityItem(name, value) {
  const ok = boolStatus(value);

  return `
    <div class="capability-item">
      <span class="capability-dot${ok ? ' ok' : ''}">
      </span>

      <span>
        ${esc(name)}
      </span>

      <span style="margin-left:auto;font-family:var(--mono);font-size:10px;">
        ${ok ? 'YES' : 'NO'}
      </span>
    </div>
  `;
}

/* =========================================================
 * IP
 * ========================================================= */

function ipRows(label, value) {
  if (!value) {
    return [];
  }

  const values =
    Array.isArray(value)
      ? value
      : String(value)
          .split(/[,\s]+/)
          .filter(Boolean);

  return values.map((ip) => {
    return row(
      label,
      ip
    );
  });
}

/* =========================================================
 * CPU / RAM / Storage
 * ========================================================= */

function getCpuInfo(hw, sys, pf) {
  const cpu = hw.cpu || {};

  return {
    name: firstDefined(
      cpu.name,
      hw.cpuName,
      hw.soc,
      hw.socName,
      hw.processor,
      pf.hardware
    ),

    arch: firstDefined(
      cpu.arch,
      hw.cpuArch,
      hw.abi
    ),

    cores: firstDefined(
      cpu.cores,
      hw.cpuCores,
      hw.cores,
      hw.processorCount
    ),

    usage: firstDefined(
      cpu.usage,
      hw.cpuUsage,
      sys.cpuUsage
    ),

    currentFreq: firstDefined(
      cpu.currentFrequency,
      cpu.currentFreq,
      hw.cpuCurrentFreq,
      hw.cpuFrequency
    ),

    maxFreq: firstDefined(
      cpu.maxFrequency,
      cpu.maxFreq,
      hw.cpuMaxFreq
    ),

    temperature: firstDefined(
      cpu.temperature,
      hw.cpuTemperature,
      hw.cpuTemp
    ),

    load1: firstDefined(
      cpu.load1,
      hw.load1
    ),

    load5: firstDefined(
      cpu.load5,
      hw.load5
    ),

    load15: firstDefined(
      cpu.load15,
      hw.load15
    )
  };
}

function getMemoryInfo(hw, sys) {
  const mem = hw.memory || {};

  const total = firstDefined(
    mem.total,
    hw.totalMemory,
    sys.totalMemory
  );

  const used = firstDefined(
    mem.used,
    hw.usedMemory,
    sys.usedMemory
  );

  const available = firstDefined(
    mem.available,
    hw.availableMemory,
    sys.availableMemory
  );

  let percent = firstDefined(
    mem.usage,
    mem.usagePercent,
    hw.memoryUsage,
    hw.memoryUsagePercent
  );

  if (
    percent === '' &&
    used !== '' &&
    total !== ''
  ) {
    percent = calcPercent(
      used,
      total
    );
  }

  return {
    total,
    used,
    available,
    percent
  };
}

function getStorageInfo(hw) {
  const storage = hw.storage || {};

  const total = firstDefined(
    storage.total,
    hw.totalStorage
  );

  const used = firstDefined(
    storage.used,
    hw.usedStorage,
    hw.storageUsed
  );

  const available = firstDefined(
    storage.available,
    hw.availableStorage,
    hw.storageAvailable
  );

  let percent = firstDefined(
    storage.usage,
    storage.usagePercent,
    hw.storageUsage,
    hw.storageUsagePercent
  );

  if (
    percent === '' &&
    used !== '' &&
    total !== ''
  ) {
    percent = calcPercent(
      used,
      total
    );
  }

  return {
    total,
    used,
    available,
    percent
  };
}

/* =========================================================
 * GPU
 * ========================================================= */

function getGpuInfo(hw, pf) {
  const gpu = hw.gpu || {};

  return {
    name: firstDefined(
      gpu.name,
      gpu.renderer,
      hw.gpuName,
      hw.gpuRenderer,
      pf.gpuRenderer
    ),

    vendor: firstDefined(
      gpu.vendor,
      hw.gpuVendor,
      pf.gpuVendor
    ),

    driver: firstDefined(
      gpu.driver,
      gpu.driverVersion,
      hw.gpuDriver,
      pf.gpuDriver
    ),

    usage: firstDefined(
      gpu.usage,
      gpu.usagePercent,
      hw.gpuUsage
    ),

    frequency: firstDefined(
      gpu.frequency,
      gpu.freq,
      hw.gpuFrequency
    ),

    temperature: firstDefined(
      gpu.temperature,
      hw.gpuTemperature,
      hw.gpuTemp
    ),

    vram: firstDefined(
      gpu.vram,
      gpu.memory,
      hw.gpuMemory,
      hw.vram
    )
  };
}

/* =========================================================
 * Display
 * ========================================================= */

function getDisplayInfo(hw, sys) {
  const display = hw.display || {};

  return {
    width: firstDefined(
      display.width,
      hw.displayWidth
    ),

    height: firstDefined(
      display.height,
      hw.displayHeight
    ),

    density: firstDefined(
      display.density,
      hw.displayDensity,
      hw.density
    ),

    dpi: firstDefined(
      display.dpi,
      hw.displayDpi,
      hw.dpi
    ),

    refreshRate: firstDefined(
      display.refreshRate,
      display.refresh,
      hw.refreshRate,
      hw.displayRefreshRate
    ),

    orientation: firstDefined(
      display.orientation,
      hw.orientation
    ),

    brightness: firstDefined(
      display.brightness,
      hw.brightness
    ),

    hdr: firstDefined(
      display.hdr,
      hw.hdr
    ),

    size: firstDefined(
      display.size,
      hw.screenSize,
      hw.displaySize
    )
  };
}

/* =========================================================
 * Battery
 * ========================================================= */

/* 电池字段中文映射：原文件用了这三个常量却没定义，会 ReferenceError */
const HEALTH = { 1: '未知', 2: '良好', 3: '过热', 4: '损坏', 5: '过压', 6: '未知故障', 7: '过冷' };
const STATUS = { 1: '未知', 2: '充电中', 3: '放电中', 4: '未充电', 5: '已充满' };
const PLUGGED = { 0: '电池', 1: '交流充电器', 2: 'USB', 4: '无线' };

function getBatteryInfo(bat) {
  const voltage = safeNumber(
    firstDefined(
      bat.voltage,
      bat.voltageMv
    )
  );

  const current = safeNumber(
    firstDefined(
      bat.current,
      bat.currentMa,
      bat.currentNow
    )
  );

  let power = firstDefined(
    bat.power,
    bat.powerW,
    bat.chargingPower
  );

  if (
    power === '' &&
    voltage !== null &&
    current !== null
  ) {
    power =
      Math.abs(
        voltage * current / 1000000
      );
  }

  return {
    health: bat.health,
    level: bat.level,
    status: bat.status,
    plugged: bat.plugged,
    technology: bat.technology,

    temperature: firstDefined(
      bat.temperature,
      bat.temp
    ),

    voltage,
    capacity: firstDefined(
      bat.capacity,
      bat.capacityMah
    ),

    current,
    power,

    cycleCount: firstDefined(
      bat.cycleCount,
      bat.cycles,
      bat.cycle
    ),

    designCapacity: firstDefined(
      bat.designCapacity,
      bat.designCapacityMah
    )
  };
}

/* =========================================================
 * Network
 * ========================================================= */

function getNetworkInfo(net) {
  const wifi = net.wifi || {};

  return {
    type: firstDefined(
      net.type,
      net.networkType,
      wifi.type
    ),

    ipv4: net.ipv4,
    ipv6: net.ipv6,

    ssid: firstDefined(
      wifi.ssid,
      net.ssid
    ),

    bssid: firstDefined(
      wifi.bssid,
      net.bssid
    ),

    rssi: firstDefined(
      wifi.rssi,
      wifi.signal,
      net.rssi
    ),

    frequency: firstDefined(
      wifi.frequency,
      wifi.frequencyMhz,
      net.wifiFrequency
    ),

    linkSpeed: firstDefined(
      wifi.linkSpeed,
      wifi.linkSpeedMbps,
      net.linkSpeed
    ),

    gateway: firstDefined(
      net.gateway,
      wifi.gateway
    ),

    dns: firstDefined(
      net.dns,
      wifi.dns
    ),

    vpn: firstDefined(
      net.vpn,
      net.vpnConnected
    ),

    proxy: firstDefined(
      net.proxy,
      net.proxyEnabled
    ),

    mac: firstDefined(
      net.mac,
      wifi.mac
    )
  };
}

/* =========================================================
 * Runtime
 * ========================================================= */

function getRuntimeInfo(di) {
  const runtime =
    di.runtime ||
    di.platform?.runtime ||
    {};

  return {
    java: firstDefined(
      runtime.javaVersion,
      di.platform?.javaVmVersion
    ),

    art: firstDefined(
      runtime.artVersion,
      runtime.art
    ),

    heapUsed: firstDefined(
      runtime.heapUsed,
      runtime.usedHeap
    ),

    heapMax: firstDefined(
      runtime.heapMax,
      runtime.maxHeap
    ),

    processCount: firstDefined(
      runtime.processCount,
      runtime.processes
    ),

    threadCount: firstDefined(
      runtime.threadCount,
      runtime.threads
    )
  };
}

/* =========================================================
 * Sensors
 * ========================================================= */

function renderSensors(sensors) {
  if (!sensors) {
    return '';
  }

  if (!Array.isArray(sensors)) {
    return '';
  }

  if (!sensors.length) {
    return '';
  }

  const items = sensors
    .map((sensor) => {
      if (typeof sensor === 'string') {
        return `
          <div class="capability-item">
            <span class="capability-dot ok"></span>
            <span>${esc(sensor)}</span>
          </div>
        `;
      }

      const name = firstDefined(
        sensor.name,
        sensor.type,
        sensor.sensor
      );

      if (!name) {
        return '';
      }

      return `
        <div class="capability-item">
          <span class="capability-dot ok"></span>
          <span>${esc(name)}</span>
        </div>
      `;
    })
    .filter(Boolean)
    .join('');

  if (!items) {
    return '';
  }

  return `
    <section class="info-card">
      <h4>传感器</h4>
      <div class="capability-grid">
        ${items}
      </div>
    </section>
  `;
}

/* =========================================================
 * Camera
 * ========================================================= */

function renderCameras(cameras) {
  if (!cameras) {
    return '';
  }

  if (!Array.isArray(cameras)) {
    return '';
  }

  if (!cameras.length) {
    return '';
  }

  const items = cameras
    .map((camera, index) => {
      if (typeof camera === 'string') {
        return row(
          `Camera ${index}`,
          camera
        );
      }

      const name = firstDefined(
        camera.name,
        camera.facing,
        camera.id,
        `Camera ${index}`
      );

      const resolution = firstDefined(
        camera.resolution,
        camera.size
      );

      const megapixels = firstDefined(
        camera.megapixels,
        camera.mp
      );

      const fps = firstDefined(
        camera.fps,
        camera.frameRate
      );

      const ois = firstDefined(
        camera.ois,
        camera.opticalStabilization
      );

      const hdr = firstDefined(
        camera.hdr
      );

      return `
        <div style="margin-bottom:8px;">
          ${row('摄像头', name)}
          ${row('分辨率', resolution)}
          ${row('像素', megapixels ? `${megapixels} MP` : '')}
          ${row('FPS', fps)}
          ${row('OIS', boolStatus(ois) ? '支持' : '')}
          ${row('HDR', boolStatus(hdr) ? '支持' : '')}
        </div>
      `;
    })
    .join('');

  return card(
    '摄像头',
    [items]
  );
}

/* =========================================================
 * Device score
 * ========================================================= */

function calculateDeviceScore(
  cpuUsage,
  memoryUsage,
  storageUsage,
  batteryLevel
) {
  /*
   * 这是“状态评分”，不是性能跑分。
   *
   * 数值越高代表当前状态越健康。
   */

  let score = 100;
  let count = 0;

  const cpu = safeNumber(cpuUsage);

  if (cpu !== null) {
    score += 100 - cpu;
    count++;
  }

  const mem = safeNumber(memoryUsage);

  if (mem !== null) {
    score += 100 - mem;
    count++;
  }

  const storage = safeNumber(storageUsage);

  if (storage !== null) {
    score += 100 - storage;
    count++;
  }

  const battery = safeNumber(batteryLevel);

  if (battery !== null) {
    score += battery;
    count++;
  }

  if (!count) {
    return null;
  }

  return Math.round(
    score / (count + 1)
  );
}

/* =========================================================
 * 增强版 renderInfo
 * ========================================================= */

function renderInfo(d, tr) {

  const di = d.deviceInfo || {};

  const dev = di.device || {};
  const sys = di.system || {};
  const hw = di.hardware || {};
  const pf = di.platform || {};
  const batRaw = di.battery || {};
  const netRaw = di.network || {};

  const bat = getBatteryInfo(batRaw);
  const net = getNetworkInfo(netRaw);

  const cpu = getCpuInfo(
    hw,
    sys,
    pf
  );

  const gpu = getGpuInfo(
    hw,
    pf
  );

  const memory = getMemoryInfo(
    hw,
    sys
  );

  const storage = getStorageInfo(
    hw
  );

  const display = getDisplayInfo(
    hw,
    sys
  );

  const runtime = getRuntimeInfo(
    di
  );

  /* =======================================================
   * 基础设备
   * ======================================================= */

  const deviceName = firstDefined(
    dev.name,
    d.model,
    'Unknown Device'
  );

  const manufacturer = firstDefined(
    dev.manufacturer,
    d.brand
  );

  const model = firstDefined(
    dev.model,
    tr?.getAttribute?.('data-modelcode'),
    d.model
  );

  const androidVersion = firstDefined(
    sys.osVersion,
    d.android
  );

  const platform =
    firstDefined(
      dev.platform,
      'ANDROID'
    );

  const appVersion = firstDefined(
    dev.appVersion,
    d.appVersion
  );

  const appBuild = firstDefined(
    dev.appBuildNumber,
    d.appVersionCode
  );

  /* =======================================================
   * Quick metrics
   * ======================================================= */

  const cpuUsage = safeNumber(
    cpu.usage
  );

  const memoryUsage = safeNumber(
    memory.percent
  );

  const storageUsage = safeNumber(
    storage.percent
  );

  const batteryLevel = safeNumber(
    bat.level
  );

  const score = calculateDeviceScore(
    cpuUsage,
    memoryUsage,
    storageUsage,
    batteryLevel
  );

  /* =======================================================
   * Overview
   * ======================================================= */

  const overview = `
    <div class="device-overview">

      <div class="device-overview-main">

        <div class="device-overview-label">
          DEVICE OVERVIEW
        </div>

        <div class="device-overview-name">
          ${esc(deviceName)}
        </div>

        <div class="device-overview-sub">

          ${
            manufacturer
              ? `
                <span class="device-chip">
                  ${esc(manufacturer)}
                </span>
              `
              : ''
          }

          ${
            model
              ? `
                <span class="device-chip">
                  ${esc(model)}
                </span>
              `
              : ''
          }

          ${
            androidVersion
              ? `
                <span class="device-chip">
                  Android ${esc(androidVersion)}
                </span>
              `
              : ''
          }

          <span class="device-chip device-chip-live">
            CONNECTED
          </span>

        </div>

        ${
          d.deviceId
            ? `
              <div
                class="device-overview-id"
                title="${esc(String(d.deviceId))}">
                ID:
                ${esc(String(d.deviceId))}
              </div>
            `
            : ''
        }

      </div>

      ${
        score !== null
          ? `
            <div class="device-overview-score">

              <div class="device-score-number">
                ${score}
              </div>

              <div class="device-score-label">
                DEVICE STATUS
              </div>

            </div>
          `
          : ''
      }

    </div>
  `;

  /* =======================================================
   * Quick metrics
   * ======================================================= */

  const metrics = `
    <div class="device-metrics">

      ${
        cpuUsage !== null
          ? `
            <div class="device-metric">

              <div class="device-metric-head">
                <span class="device-metric-name">
                  CPU
                </span>

                <span class="device-metric-value">
                  ${fmtPercent(cpuUsage)}
                </span>
              </div>

              <div class="device-progress">
                <div
                  class="device-progress-fill"
                  style="width:${progressPercent(cpuUsage)}%">
                </div>
              </div>

            </div>
          `
          : ''
      }

      ${
        memoryUsage !== null
          ? `
            <div class="device-metric">

              <div class="device-metric-head">
                <span class="device-metric-name">
                  RAM
                </span>

                <span class="device-metric-value">
                  ${fmtPercent(memoryUsage)}
                </span>
              </div>

              <div class="device-progress">
                <div
                  class="device-progress-fill"
                  style="width:${progressPercent(memoryUsage)}%">
                </div>
              </div>

            </div>
          `
          : ''
      }

      ${
        storageUsage !== null
          ? `
            <div class="device-metric">

              <div class="device-metric-head">
                <span class="device-metric-name">
                  STORAGE
                </span>

                <span class="device-metric-value">
                  ${fmtPercent(storageUsage)}
                </span>
              </div>

              <div class="device-progress">
                <div
                  class="device-progress-fill"
                  style="width:${progressPercent(storageUsage)}%">
                </div>
              </div>

            </div>
          `
          : ''
      }

      ${
        batteryLevel !== null
          ? `
            <div class="device-metric">

              <div class="device-metric-head">
                <span class="device-metric-name">
                  BATTERY
                </span>

                <span class="device-metric-value">
                  ${fmtPercent(batteryLevel)}
                </span>
              </div>

              <div class="device-progress">
                <div
                  class="device-progress-fill"
                  style="width:${progressPercent(batteryLevel)}%">
                </div>
              </div>

            </div>
          `
          : ''
      }

    </div>
  `;

  /* =======================================================
   * CPU
   * ======================================================= */

  const cpuCard = card(
    'CPU / SoC',
    [
      row('处理器', cpu.name),
      row('架构', cpu.arch),
      row('核心数', cpu.cores),
      row(
        '当前频率',
        cpu.currentFreq !== ''
          ? fmtHz(cpu.currentFreq)
          : ''
      ),
      row(
        '最大频率',
        cpu.maxFreq !== ''
          ? fmtHz(cpu.maxFreq)
          : ''
      ),
      row(
        '使用率',
        cpuUsage !== null
          ? fmtPercent(cpuUsage)
          : ''
      ),
      row(
        '温度',
        cpu.temperature !== ''
          ? `${cpu.temperature} ℃`
          : ''
      ),
      row('Load 1m', cpu.load1),
      row('Load 5m', cpu.load5),
      row('Load 15m', cpu.load15)
    ]
  );

  /* =======================================================
   * GPU
   * ======================================================= */

  const gpuCard = card(
    'GPU / Graphics',
    [
      row('GPU', gpu.name),
      row('厂商', gpu.vendor),
      row('驱动', gpu.driver),
      row(
        '使用率',
        gpu.usage !== ''
          ? fmtPercent(gpu.usage)
          : ''
      ),
      row(
        '频率',
        gpu.frequency !== ''
          ? fmtHz(gpu.frequency)
          : ''
      ),
      row(
        '温度',
        gpu.temperature !== ''
          ? `${gpu.temperature} ℃`
          : ''
      ),
      row(
        '显存',
        gpu.vram !== ''
          ? fmtBytes(gpu.vram)
          : ''
      ),
      row(
        'OpenGL ES',
        pf.glEsVersion
      ),
      row(
        'Vulkan',
        firstDefined(
          pf.vulkanVersion,
          hw.vulkanVersion
        )
      )
    ]
  );

  /* =======================================================
   * Memory
   * ======================================================= */

  const memoryCard = card(
    'Memory',
    [
      row(
        '总内存',
        memory.total !== ''
          ? fmtBytes(memory.total)
          : ''
      ),

      row(
        '已使用',
        memory.used !== ''
          ? fmtBytes(memory.used)
          : ''
      ),

      row(
        '可用',
        memory.available !== ''
          ? fmtBytes(memory.available)
          : ''
      ),

      row(
        '使用率',
        memoryUsage !== null
          ? fmtPercent(memoryUsage)
          : ''
      )
    ]
  );

  /* =======================================================
   * Storage
   * ======================================================= */

  const storageCard = card(
    'Storage',
    [
      row(
        '总容量',
        storage.total !== ''
          ? fmtBytes(storage.total)
          : ''
      ),

      row(
        '已使用',
        storage.used !== ''
          ? fmtBytes(storage.used)
          : ''
      ),

      row(
        '可用',
        storage.available !== ''
          ? fmtBytes(storage.available)
          : ''
      ),

      row(
        '使用率',
        storageUsage !== null
          ? fmtPercent(storageUsage)
          : ''
      )
    ]
  );

  /* =======================================================
   * Display
   * ======================================================= */

  const resolution =
    display.width &&
    display.height
      ? `${display.width} × ${display.height}`
      : '';

  const displayCard = card(
    'Display',
    [
      row(
        '分辨率',
        resolution
      ),

      row(
        'Density',
        display.density
      ),

      row(
        'DPI',
        display.dpi
      ),

      row(
        '刷新率',
        display.refreshRate !== ''
          ? `${display.refreshRate} Hz`
          : ''
      ),

      row(
        '方向',
        display.orientation
      ),

      row(
        '亮度',
        display.brightness !== ''
          ? `${display.brightness}`
          : ''
      ),

      row(
        'HDR',
        boolStatus(display.hdr)
          ? '支持'
          : display.hdr !== ''
            ? '不支持'
            : ''
      ),

      row(
        '尺寸',
        display.size
      )
    ]
  );

  /* =======================================================
   * Battery
   * ======================================================= */

  const batteryPower =
    bat.power !== ''
      ? `${formatNumber(bat.power, 2)} W`
      : '';

  const batteryCard = card(
    'Battery',
    [
      row(
        '健康',
        HEALTH && bat.health
          ? HEALTH[bat.health]
          : bat.health
      ),

      row(
        '电量',
        batteryLevel !== null
          ? `${batteryLevel}%`
          : ''
      ),

      row(
        '状态',
        STATUS && bat.status
          ? STATUS[bat.status]
          : bat.status
      ),

      row(
        '电源',
        PLUGGED && bat.plugged
          ? PLUGGED[bat.plugged]
          : bat.plugged
      ),

      row(
        '技术',
        bat.technology
      ),

      row(
        '温度',
        bat.temperature !== ''
          ? `${bat.temperature} ℃`
          : ''
      ),

      row(
        '电压',
        bat.voltage !== null
          ? `${bat.voltage} mV`
          : ''
      ),

      row(
        '容量',
        bat.capacity !== ''
          ? `${bat.capacity} mAh`
          : ''
      ),

      row(
        '电流',
        bat.current !== null
          ? `${bat.current} mA`
          : ''
      ),

      row(
        '充电功率',
        batteryPower
      ),

      row(
        '循环次数',
        bat.cycleCount
      ),

      row(
        '设计容量',
        bat.designCapacity !== ''
          ? `${bat.designCapacity} mAh`
          : ''
      )
    ]
  );

  /* =======================================================
   * Network
   * ======================================================= */

  const networkRows = [
    row('网络类型', net.type),
    row('Wi-Fi SSID', net.ssid),
    row('Wi-Fi BSSID', net.bssid),

    row(
      'RSSI',
      net.rssi !== ''
        ? `${net.rssi} dBm`
        : ''
    ),

    row(
      '频率',
      net.frequency !== ''
        ? `${net.frequency} MHz`
        : ''
    ),

    row(
      '链路速度',
      net.linkSpeed !== ''
        ? `${net.linkSpeed} Mbps`
        : ''
    ),

    row('网关', net.gateway),
    row('DNS', net.dns),
    row('MAC', net.mac),

    row(
      'VPN',
      boolStatus(net.vpn)
        ? 'Connected'
        : net.vpn !== ''
          ? 'Disconnected'
          : ''
    ),

    row(
      'Proxy',
      boolStatus(net.proxy)
        ? 'Enabled'
        : net.proxy !== ''
          ? 'Disabled'
          : ''
    ),

    ...ipRows(
      'IPv4',
      net.ipv4
    ),

    ...ipRows(
      'IPv6',
      net.ipv6
    )
  ];

  const networkCard = card(
    'Network',
    networkRows,
    true
  );

  /* =======================================================
   * Android
   * ======================================================= */

  const androidCard = card(
    'Android / Platform',
    [
      row(
        '系统',
        sys.osName ||
          'Android'
      ),

      row(
        '版本',
        androidVersion
      ),

      row(
        'SDK',
        pf.sdkVersion
          ? `SDK ${pf.sdkVersion}`
          : ''
      ),

      row(
        '安全补丁',
        pf.securityPatch
      ),

      row(
        'Bootloader',
        pf.bootloader
      ),

      row(
        'Build Number',
        pf.buildNumber
      ),

      row(
        'Build ID',
        pf.buildId
      ),

      row(
        'Build Type',
        pf.buildType
      ),

      row(
        'Build Tags',
        pf.buildTags
      ),

      row(
        'Baseband',
        pf.radioVersion
      ),

      row(
        'Hardware',
        pf.hardware
      ),

      row(
        'Board',
        pf.board
      ),

      row(
        'Device',
        pf.device
      ),

      row(
        'Brand',
        pf.buildBrand
      ),

      row(
        'Fingerprint',
        pf.fingerprint
      ),

      row(
        '语言',
        dev.language
      ),

      row(
        'Timezone',
        firstDefined(
          pf.timezone,
          sys.timezone
        )
      ),

      row(
        'Build Time',
        pf.buildTime
          ? new Date(
              Number(pf.buildTime)
            ).toLocaleString(
              'zh-CN'
            )
          : ''
      )
    ]
  );

  /* =======================================================
   * Runtime
   * ======================================================= */

  const runtimeCard = card(
    'Runtime',
    [
      row(
        'Java VM',
        runtime.java
      ),

      row(
        'ART',
        runtime.art
      ),

      row(
        'Heap Used',
        runtime.heapUsed !== ''
          ? fmtBytes(runtime.heapUsed)
          : ''
      ),

      row(
        'Heap Max',
        runtime.heapMax !== ''
          ? fmtBytes(runtime.heapMax)
          : ''
      ),

      row(
        '进程数',
        runtime.processCount
      ),

      row(
        '线程数',
        runtime.threadCount
      ),

      row(
        'Kernel',
        sys.kernelVersion
      ),

      row(
        'Uptime',
        fmtUptime(sys.uptime)
      )
    ]
  );

  /* =======================================================
   * Basic
   * ======================================================= */

  const basicCard = card(
    'Device',
    [
      row(
        '设备名称',
        deviceName
      ),

      row(
        'Platform',
        platform
      ),

      row(
        '厂商',
        manufacturer
      ),

      row(
        '型号',
        model
      ),

      row(
        'Language',
        dev.language
      ),

      row(
        'Device ID',
        d.deviceId
      ),

      row(
        '应用版本',
        appVersion
          ? `${appVersion}${
              appBuild
                ? ` (${appBuild})`
                : ''
            }`
          : ''
      )
    ]
  );

  /* =======================================================
   * Capabilities
   * ======================================================= */

  const caps =
    di.capabilities ||
    pf.capabilities ||
    {};

  const capabilityNames = [
    ['ADB', caps.adb],
    ['Root', caps.root],
    ['Shizuku', caps.shizuku],
    [
      'Accessibility',
      caps.accessibility
    ],
    ['NFC', caps.nfc],
    [
      'Bluetooth',
      caps.bluetooth
    ],
    [
      'USB OTG',
      caps.usbOtg ||
      caps.otg
    ],
    [
      'Vulkan',
      caps.vulkan
    ],
    ['5G', caps.fiveG || caps['5g']],
    ['Wi-Fi', caps.wifi],
    ['HDR', caps.hdr],
    ['Camera', caps.camera]
  ];

  const hasCapabilityData =
    capabilityNames.some(
      ([, value]) =>
        value !== undefined &&
        value !== null &&
        value !== ''
    );

  const capabilitiesCard =
    hasCapabilityData
      ? `
        <section class="info-card">
          <h4>Capabilities</h4>

          <div class="capability-grid">
            ${capabilityNames
              .map(([name, value]) =>
                capabilityItem(
                  name,
                  value
                )
              )
              .join('')}
          </div>
        </section>
      `
      : '';

  /* =======================================================
   * Sensors
   * ======================================================= */

  const sensors =
    renderSensors(
      di.sensors ||
      hw.sensors
    );

  /* =======================================================
   * Cameras
   * ======================================================= */

  const cameras =
    renderCameras(
      di.cameras ||
      hw.cameras
    );

  /* =======================================================
   * Final HTML
   * ======================================================= */

  return `
    ${overview}

    ${metrics}

    <div class="info-grid">

      ${basicCard}

      ${cpuCard}

      ${gpuCard}

      ${memoryCard}

      ${storageCard}

      ${displayCard}

      ${batteryCard}

      ${runtimeCard}

      ${networkCard}

      ${androidCard}

      ${capabilitiesCard}

      ${sensors}

      ${cameras}

    </div>
  `;
}

/* =========================================================
 * Device detail modal
 * ========================================================= */

function deviceModal(d) {

  const name =
    displayName(d);

  return `
    <div
      id="device-modal"
      class="modal-mask"
      role="dialog"
      aria-modal="true">

      <div class="modal">

        <div class="modal-head">

          <div>
            <div class="modal-title">
              ${esc(name)}
            </div>

            <div class="modal-sub">
              ${esc(
                String(
                  d.deviceId || ''
                )
              )}
            </div>
          </div>

          <button
            type="button"
            class="modal-close"
            onclick="closeDeviceModal()">
            ×
          </button>

        </div>

        <div
          id="device-modal-body"
          class="modal-body">

          <div class="modal-empty">
            加载设备信息…
          </div>

        </div>

      </div>

    </div>
  `;
}

/* =========================================================
 * 前端脚本
 * ========================================================= */

const dashboardScript = `
<script>

(function () {

  'use strict';

  /* =======================================================
   * Device modal
   * ======================================================= */

  window.openDeviceModal = function (row) {

    const modal =
      document.getElementById(
        'device-modal'
      );

    const body =
      document.getElementById(
        'device-modal-body'
      );

    if (!modal || !body) {
      return;
    }

    let data = {};

    try {
      data = JSON.parse(
        row.getAttribute(
          'data-device-info'
        ) || '{}'
      );
    } catch (e) {
      console.error(
        'device info parse error',
        e
      );
    }

    body.innerHTML =
      data.html ||
      '<div class="modal-empty">暂无设备信息</div>';

    modal.classList.add('open');

    document.body.style.overflow =
      'hidden';
  };


  window.closeDeviceModal = function () {

    const modal =
      document.getElementById(
        'device-modal'
      );

    if (!modal) {
      return;
    }

    modal.classList.remove(
      'open'
    );

    document.body.style.overflow =
      '';
  };


  /* 点击遮罩关闭 */

  document.addEventListener(
    'click',
    function (event) {

      const modal =
        document.getElementById(
          'device-modal'
        );

      if (
        modal &&
        event.target === modal
      ) {
        closeDeviceModal();
      }

    }
  );


  /* ESC 关闭 */

  document.addEventListener(
    'keydown',
    function (event) {

      if (
        event.key === 'Escape'
      ) {
        closeDeviceModal();
      }

    }
  );


  /* =======================================================
   * 搜索设备
   * ======================================================= */

  window.filterDevices = function (value) {

    const keyword =
      String(value || '')
        .trim()
        .toLowerCase();

    const rows =
      document.querySelectorAll(
        '#device-table tbody tr[data-search]'
      );

    let visible = 0;

    rows.forEach(function (row) {

      const text =
        row.getAttribute(
          'data-search'
        ) || '';

      const hit =
        !keyword ||
        text
          .toLowerCase()
          .includes(keyword);

      row.classList.toggle(
        'hidden-row',
        !hit
      );

      if (hit) {
        visible++;
      }

    });

    const count =
      document.getElementById(
        'device-visible-count'
      );

    if (count) {
      count.textContent =
        visible + ' 台';
    }

  };


  /* =======================================================
   * 表格排序
   * ======================================================= */

  window.sortDeviceTable = function (
    index,
    type
  ) {

    const table =
      document.getElementById(
        'device-table'
      );

    if (!table) {
      return;
    }

    const tbody =
      table.querySelector(
        'tbody'
      );

    if (!tbody) {
      return;
    }

    const rows =
      Array.from(
        tbody.querySelectorAll(
          'tr[data-search]'
        )
      );

    const headers =
      table.querySelectorAll(
        'thead th'
      );

    let direction =
      'asc';

    const th =
      headers[index];

    if (th) {

      if (
        th.classList.contains(
          'asc'
        )
      ) {
        direction = 'desc';
      }

      headers.forEach(
        function (h) {
          h.classList.remove(
            'asc',
            'desc'
          );
        }
      );

      th.classList.add(
        direction
      );
    }

    rows.sort(
      function (a, b) {

        const av =
          a.children[index]
            ? a.children[index]
                .innerText
                .trim()
            : '';

        const bv =
          b.children[index]
            ? b.children[index]
                .innerText
                .trim()
            : '';

        if (type === 'number') {

          const an =
            parseFloat(
              av.replace(
                /[^0-9.-]/g,
                ''
              )
            ) || 0;

          const bn =
            parseFloat(
              bv.replace(
                /[^0-9.-]/g,
                ''
              )
            ) || 0;

          return direction === 'asc'
            ? an - bn
            : bn - an;
        }

        const result =
          av.localeCompare(
            bv,
            undefined,
            {
              numeric:true,
              sensitivity:'base'
            }
          );

        return direction === 'asc'
          ? result
          : -result;
      }
    );

    rows.forEach(
      function (row) {
        tbody.appendChild(row);
      }
    );

  };


  /* =======================================================
   * Script 展开
   * ======================================================= */

  window.toggleScript = function (
    id
  ) {

    const el =
      document.getElementById(
        id
      );

    if (!el) {
      return;
    }

    el.classList.toggle(
      'open'
    );

  };


  /* =======================================================
   * 页面初始化
   * ======================================================= */

  document.addEventListener(
    'DOMContentLoaded',
    function () {

      const input =
        document.getElementById(
          'device-search'
        );

      if (input) {

        input.addEventListener(
          'input',
          function () {
            filterDevices(
              this.value
            );
          }
        );

      }

    }
  );

})();

</script>
`;
/* =========================================================
 * 第三段：Dashboard 页面主体
 * ========================================================= */

/**
 * HTML 转义
 * 防止设备名称、型号、Device ID 等内容直接注入 HTML。
 */
/* esc 已从 ../util 导入；此处重复定义会导致 SyntaxError，已移除 */


/**
 * 获取设备显示名称
 */
function displayName(d) {

  const di =
    d.deviceInfo || {};

  const dev =
    di.device || {};

  return firstDefined(
    dev.name,
    d.name,
    d.model,
    d.brand,
    d.deviceId,
    'Unknown Device'
  );
}


/**
 * 获取设备型号
 */
function displayModel(d) {

  const di =
    d.deviceInfo || {};

  const dev =
    di.device || {};

  return firstDefined(
    dev.model,
    d.model,
    '-'
  );
}


/**
 * 获取 Android 版本
 */
function displayAndroid(d) {

  const di =
    d.deviceInfo || {};

  const sys =
    di.system || {};

  return firstDefined(
    sys.osVersion,
    d.android,
    '-'
  );
}


/**
 * 获取设备品牌
 */
function displayBrand(d) {

  const di =
    d.deviceInfo || {};

  const dev =
    di.device || {};

  return firstDefined(
    dev.manufacturer,
    d.brand,
    '-'
  );
}


/**
 * 获取设备在线状态
 */
const ONLINE_WINDOW_MS = 10 * 60 * 1000;

function isDeviceOnline(d) {
  /* 统计数据里没有 online/connected，只有 lastSeen 文本时间。
     沿用现用版口径：最近上报在窗口期内即视为在线。 */
  if (d.online === true || d.connected === true || d.status === 'online') {
    return true;
  }

  const seen = Date.parse(String(d.lastSeen || '').replace(/-/g, '/'));

  if (Number.isNaN(seen)) {
    return false;
  }

  return Date.now() - seen <= ONLINE_WINDOW_MS;
}


/**
 * 状态 Badge
 */
function deviceStatusBadge(d) {

  const online =
    isDeviceOnline(d);

  return `
    <span class="device-status ${online ? 'online' : 'offline'}">

      <span class="device-status-dot"></span>

      <span>
        ${online ? 'ONLINE' : 'OFFLINE'}
      </span>

    </span>
  `;
}


/**
 * 设备头像
 */
function deviceAvatar(d) {

  const brand =
    displayBrand(d);

  const model =
    displayModel(d);

  const text =
    firstDefined(
      brand,
      model,
      'A'
    );

  return `
    <div class="device-avatar">

      <span>
        ${esc(
          String(text)
            .substring(0, 1)
            .toUpperCase()
        )}
      </span>

    </div>
  `;
}


/**
 * 构造设备搜索字段
 */
function buildDeviceSearchText(d) {

  const di =
    d.deviceInfo || {};

  const dev =
    di.device || {};

  const sys =
    di.system || {};

  const hw =
    di.hardware || {};

  const pf =
    di.platform || {};

  return [
    d.deviceId,

    d.name,
    d.model,
    d.brand,
    d.android,
    d.cpu,

    dev.name,
    dev.manufacturer,
    dev.model,
    dev.language,

    sys.osName,
    sys.osVersion,
    sys.kernelVersion,

    hw.cpuArch,
    hw.cpuName,
    hw.gpuName,

    pf.buildNumber,
    pf.device,
    pf.board,
    pf.hardware,
    pf.fingerprint

  ]
    .filter(
      v =>
        v !== undefined &&
        v !== null &&
        v !== ''
    )
    .join(' ')
    .toLowerCase();
}


/**
 * 获取设备信息 HTML
 */
function buildDeviceInfoHtml(
  d,
  tr
) {

  try {

    return renderInfo(
      d,
      tr
    );

  } catch (error) {

    console.error(
      'renderInfo error:',
      error
    );

    return `
      <div class="device-error">

        <div class="device-error-icon">
          !
        </div>

        <div>

          <strong>
            设备信息解析失败
          </strong>

          <div class="device-error-detail">
            ${esc(
              error.message ||
              String(error)
            )}
          </div>

        </div>

      </div>
    `;
  }
}


/**
 * 构造单个设备 Row
 */
function renderDeviceRow(
  d,
  index
) {

  const name =
    displayName(d);

  const model =
    displayModel(d);

  const brand =
    displayBrand(d);

  const android =
    displayAndroid(d);

  const online =
    isDeviceOnline(d);

  const searchText =
    buildDeviceSearchText(d);

  /*
   * 这里暂时不直接把完整 HTML
   * 放进 data-device-info。
   *
   * 原因：
   *
   * 1. Device ID 可能包含特殊字符
   * 2. HTML 属性长度可能非常大
   * 3. JSON + HTML 双重转义很容易出问题
   *
   * 所以使用隐藏节点保存内容。
   */

  const infoId =
    `device-info-${index}-${Math.random()
      .toString(36)
      .slice(2, 10)}`;

  return `
    <tr
      class="device-row"
      data-search="${esc(searchText)}"
      data-device-index="${index}"
      data-info-id="${infoId}"
      tabindex="0">

      <td>

        <div class="device-cell">

          ${deviceAvatar(d)}

          <div class="device-main">

            <div class="device-name">
              ${esc(name)}
            </div>

            <div class="device-id">

              ${esc(
                d.deviceId ||
                'NO DEVICE ID'
              )}

            </div>

          </div>

        </div>

      </td>


      <td>

        <div class="device-model">

          <strong>
            ${esc(model)}
          </strong>

          ${
            brand &&
            brand !== model
              ? `
                <span>
                  ${esc(brand)}
                </span>
              `
              : ''
          }

        </div>

      </td>


      <td>

        <span class="android-badge">
          Android ${esc(android)}
        </span>

      </td>


      <td>

        ${
          d.appVersion ||
          d.deviceInfo?.device?.appVersion
            ? `
              <div class="app-version">

                <strong>
                  ${esc(
                    d.appVersion ||
                    d.deviceInfo?.device
                      ?.appVersion
                  )}
                </strong>

                ${
                  d.appVersionCode ||
                  d.deviceInfo?.device
                    ?.appBuildNumber
                    ? `
                      <span>
                        build ${
                          esc(
                            d.appVersionCode ||
                            d.deviceInfo?.device
                              ?.appBuildNumber
                          )
                        }
                      </span>
                    `
                    : ''
                }

              </div>
            `
            : '-'
        }

      </td>


      <td data-sort="${esc(String(d.scriptCount || 0))}">
        ${
          d.scriptCount
            ? `<span class="script-badge">${esc(String(d.scriptCount))}</span>`
            : '<span class="script-none">-</span>'
        }
      </td>


      <td data-sort="${esc(String(d.installs || 0))}">
        <strong>${esc(String(d.installs || 0))}</strong>
      </td>


      <td class="app-ver">${esc(d.lastSeen || '-')}</td>


      <td class="ip-cell">${ipCell(d)}</td>


      <td>

        ${deviceStatusBadge(d)}

      </td>


      <td>

        <button
          type="button"
          class="device-detail-btn"
          data-open-device="${infoId}">

          <span>
            查看详情
          </span>

          <span class="device-detail-arrow">
            →
          </span>

        </button>

      </td>

    </tr>


    <!-- ===================================================
         隐藏设备详情
         =================================================== -->

    <template id="${infoId}">
      ${buildDeviceInfoHtml(d, null)}
    </template>
  `;
}


/**
 * 设备列表
 */
function renderDeviceTable(
  devices
) {

  if (
    !Array.isArray(devices)
  ) {
    devices = [];
  }

  const rows =
    devices
      .map(
        (d, index) =>
          renderDeviceRow(
            d,
            index
          )
      )
      .join('');

  if (!rows) {

    return `
      <div class="device-empty">

        <div class="device-empty-icon">
          ◇
        </div>

        <div class="device-empty-title">
          暂无设备
        </div>

        <div class="device-empty-sub">
          等待 Android 客户端连接…
        </div>

      </div>
    `;
  }

  return `
    <div class="device-table-wrap">

      <table
        id="device-table"
        class="device-table">

        <thead>

          <tr>

            <th
              onclick="sortDeviceTable(0,'text')">

              设备

              <span class="sort-icon">
                ↕
              </span>

            </th>


            <th
              onclick="sortDeviceTable(1,'text')">

              型号

              <span class="sort-icon">
                ↕
              </span>

            </th>


            <th
              onclick="sortDeviceTable(2,'text')">

              Android

              <span class="sort-icon">
                ↕
              </span>

            </th>


            <th
              onclick="sortDeviceTable(3,'text')">

              App

              <span class="sort-icon">
                ↕
              </span>

            </th>


            <th
              onclick="sortDeviceTable(4,'number')">
              脚本
              <span class="sort-icon">↕</span>
            </th>


            <th
              onclick="sortDeviceTable(5,'number')">
              安装
              <span class="sort-icon">↕</span>
            </th>


            <th
              onclick="sortDeviceTable(6,'text')">
              最近上报
              <span class="sort-icon">↕</span>
            </th>


            <th>

              IP / 归属地

            </th>


            <th>

              状态

            </th>


            <th>

              操作

            </th>

          </tr>

        </thead>


        <tbody>

          ${rows}

        </tbody>

      </table>

    </div>
  `;
}


/* =========================================================
 * Dashboard 顶部统计
 * ========================================================= */

function renderDashboardStats(
  devices,
  stats
) {

  stats = stats || {};

  if (
    !Array.isArray(devices)
  ) {
    devices = [];
  }

  const total =
    devices.length;

  const online =
    devices.filter(
      d =>
        isDeviceOnline(d)
    ).length;

  const offline =
    total - online;

  return `
    <div class="dashboard-stats">

      <div class="stat-card">

        <div class="stat-icon">
          ◈
        </div>

        <div class="stat-content">

          <div class="stat-label">
            DEVICES
          </div>

          <div class="stat-value">
            ${total}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-icon stat-icon-online">
          ●
        </div>

        <div class="stat-content">

          <div class="stat-label">
            ONLINE
          </div>

          <div class="stat-value">
            ${online}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-icon stat-icon-offline">
          ○
        </div>

        <div class="stat-content">

          <div class="stat-label">
            OFFLINE
          </div>

          <div class="stat-value">
            ${offline}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-icon">
          ◈
        </div>

        <div class="stat-content">

          <div class="stat-label">
            BRANDS · 品牌数
          </div>

          <div class="stat-value">
            ${new Set(devices.map((d) => d.brand).filter(Boolean)).size}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-icon">
          ⤓
        </div>

        <div class="stat-content">

          <div class="stat-label">
            INSTALLS
          </div>

          <div class="stat-value">
            ${esc(String(stats.install_count || 0))}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-icon">
          ⟳
        </div>

        <div class="stat-content">

          <div class="stat-label">
            UPDATES
          </div>

          <div class="stat-value">
            ${esc(String(stats.update_count || 0))}
          </div>

        </div>

      </div>

    </div>
  `;
}


/* =========================================================
 * Dashboard
 * ========================================================= */

function dashboardHtml(stats, announcement) {

  /* 真实调用是 dashboardHtml(readStats(), readAnnouncement())。
     也兼容直接传设备数组，方便本地预览。 */
  stats = stats || {};

  const devices = Array.isArray(stats)
    ? stats
    : (Array.isArray(stats.devices) ? stats.devices : []);

  if (Array.isArray(stats)) {
    stats = { devices };
  }

  const ann = announcement || { title: '公告栏', content: '', updatedAt: '' };

  const title = 'AutoSlide 数据端';
  const subtitle = 'AutoSlide · Fleet Monitor';

  /* 设备构成分布图：品牌 / 系统版本 / 应用版本 */
  const brands = countBy(devices, (d) => d.brand);

  const charts = [
    donutChart('品牌分布', brands, devices.length),
    barChart('系统版本', sortByVersionDesc(countBy(devices, (d) => d.android)), devices.length),
    barChart('应用版本', sortByVersionDesc(countBy(devices, (d) => d.appVersion)), devices.length),
  ].join('');

  const noticeBlock = `
    <div class="notice">
      <div class="notice-head">
        <b>${esc(ann.title || '公告栏')}</b>
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
  `;

  const adminLinks = `
    <div class="admin-links">
      <a href="/admin/release">发版管理 <span class="admin-arrow">→</span></a>
      <a href="/admin/scripts">脚本管理 <span class="admin-arrow">→</span></a>
      <a href="/invites">邀请与时长 <span class="admin-arrow">→</span></a>
    </div>
  `;


  return `
<!DOCTYPE html>

<html lang="zh-CN">

<head>

  <meta charset="UTF-8">

  <meta
    name="viewport"
    content="width=device-width,initial-scale=1">

  <title>
    ${esc(title)}
  </title>


  ${dashboardStyles}

</head>


<body>

  <div class="dashboard">

    <!-- =================================================
         Header
         ================================================= -->

    <header class="dashboard-header">

      <div class="header-left">

        <div class="brand-mark">

          <span class="brand-mark-core">
            ◈
          </span>

        </div>


        <div>

          <div class="dashboard-title">
            ${esc(title)}
          </div>

          <div class="dashboard-subtitle">
            ${esc(subtitle)}
          </div>

        </div>

      </div>


      <div class="header-right">

        ${adminLinks}

        <div class="header-time">
          <span id="live-time">
            --
          </span>
        </div>

        <div class="header-live">

          <span class="live-dot"></span>

          LIVE

        </div>

      </div>

    </header>


    <!-- =================================================
         Stats
         ================================================= -->

    ${noticeBlock}

    ${renderDashboardStats(devices, stats)}

    <p class="section-label">设备构成 · Fleet Composition</p>
    <div class="charts">${charts}</div>


    <!-- =================================================
         Toolbar
         ================================================= -->

    <section class="device-toolbar">

      <div class="toolbar-left">

        <div class="toolbar-title">
          CONNECTED DEVICES
        </div>

        <div
          id="device-visible-count"
          class="toolbar-count">

          ${devices.length} 台 · 最近上报 ${esc(stats.last_update || '-')}

        </div>

      </div>


      <div class="toolbar-right">

        <div class="search-box">

          <span class="search-icon">
            ⌕
          </span>

          <input
            id="device-search"
            type="search"
            autocomplete="off"
            placeholder="搜索设备 / 型号 / Device ID…">

          <kbd>
            /
          </kbd>

        </div>

      </div>

    </section>


    <!-- =================================================
         Device Table
         ================================================= -->

    <main>

      ${renderDeviceTable(devices)}

    </main>


    <!-- =================================================
         Modal
         ================================================= -->

    <div
      id="device-modal"
      class="modal-mask"
      role="dialog"
      aria-modal="true"
      aria-hidden="true">

      <div class="modal">

        <div class="modal-head">

          <div>

            <div
              id="modal-device-title"
              class="modal-title">

              Device Information

            </div>

            <div
              id="modal-device-sub"
              class="modal-sub">

            </div>

          </div>


          <button
            type="button"
            class="modal-close"
            onclick="closeDeviceModal()">

            ×

          </button>

        </div>


        <div
          id="device-modal-body"
          class="modal-body">

          <div class="modal-empty">
            请选择设备
          </div>

        </div>

      </div>

    </div>


    <!-- =================================================
         Footer
         ================================================= -->

    <footer class="dashboard-footer">

      <div>
        ANDROID DEVICE MONITOR
      </div>

      <div>
        ${devices.length} DEVICE(S)
      </div>

    </footer>

  </div>


  ${dashboardScript}


  <script>

  /* =====================================================
   * Enhanced device interaction
   * ===================================================== */

  (function () {

    'use strict';


    /*
     * 打开设备详情
     */

    function openTemplate(
      templateId,
      row
    ) {

      const template =
        document.getElementById(
          templateId
        );

      const modal =
        document.getElementById(
          'device-modal'
        );

      const body =
        document.getElementById(
          'device-modal-body'
        );

      const title =
        document.getElementById(
          'modal-device-title'
        );

      const subtitle =
        document.getElementById(
          'modal-device-sub'
        );


      if (
        !template ||
        !modal ||
        !body
      ) {
        return;
      }


      /*
       * template.content
       * 是 DocumentFragment。
       */

      body.innerHTML = '';

      body.appendChild(
        template.content.cloneNode(
          true
        )
      );


      if (row) {

        const name =
          row.querySelector(
            '.device-name'
          );

        const deviceId =
          row.querySelector(
            '.device-id'
          );


        if (title) {

          title.textContent =
            name
              ? name.textContent.trim()
              : 'Device Information';

        }


        if (subtitle) {

          subtitle.textContent =
            deviceId
              ? deviceId.textContent.trim()
              : '';

        }

      }


      modal.classList.add(
        'open'
      );

      modal.setAttribute(
        'aria-hidden',
        'false'
      );


      document.body.style.overflow =
        'hidden';


      /*
       * 弹窗打开动画
       */

      requestAnimationFrame(
        function () {

          modal.classList.add(
            'visible'
          );

        }
      );

    }


    /*
     * 绑定详情按钮
     */

    document.addEventListener(
      'click',
      function (event) {

        const button =
          event.target.closest(
            '[data-open-device]'
          );


        if (!button) {
          return;
        }


        const templateId =
          button.getAttribute(
            'data-open-device'
          );


        const row =
          button.closest(
            'tr.device-row'
          );


        openTemplate(
          templateId,
          row
        );

      }
    );


    /*
     * 点击整行也可以打开
     */

    document.addEventListener(
      'dblclick',
      function (event) {

        const row =
          event.target.closest(
            'tr.device-row'
          );


        if (!row) {
          return;
        }


        const button =
          row.querySelector(
            '[data-open-device]'
          );


        if (button) {

          button.click();

        }

      }
    );


    /*
     * ESC
     */

    document.addEventListener(
      'keydown',
      function (event) {

        if (
          event.key === 'Escape'
        ) {

          closeDeviceModal();

        }

      }
    );


    /*
     * "/" 快速搜索
     */

    document.addEventListener(
      'keydown',
      function (event) {

        if (
          event.key === '/' &&
          !(
            event.target instanceof
            HTMLInputElement
          ) &&
          !(
            event.target instanceof
            HTMLTextAreaElement
          )
        ) {

          event.preventDefault();

          const input =
            document.getElementById(
              'device-search'
            );


          if (input) {

            input.focus();

          }

        }

      }
    );


    /*
     * 实时钟
     */

    function updateClock() {

      const el =
        document.getElementById(
          'live-time'
        );


      if (!el) {
        return;
      }


      const now =
        new Date();


      el.textContent =
        now.toLocaleString(
          'zh-CN',
          {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false
          }
        );

    }


    updateClock();

    setInterval(
      updateClock,
      1000
    );


    /*
     * Modal 遮罩关闭
     */

    document.addEventListener(
      'click',
      function (event) {

        const modal =
          document.getElementById(
            'device-modal'
          );


        if (
          modal &&
          event.target === modal
        ) {

          closeDeviceModal();

        }

      }
    );


  })();


  /*
   * 覆盖关闭函数
   */

  window.closeDeviceModal =
    function () {

      const modal =
        document.getElementById(
          'device-modal'
        );


      if (!modal) {
        return;
      }


      modal.classList.remove(
        'visible'
      );


      setTimeout(
        function () {

          modal.classList.remove(
            'open'
          );

          modal.setAttribute(
            'aria-hidden',
            'true'
          );

        },
        160
      );


      document.body.style.overflow =
        '';

    };


  </script>


</body>

</html>
`;
}

module.exports = { dashboardHtml };
