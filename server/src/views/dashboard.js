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

const { esc, wgs84ToGcj02 } = require('../util');
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
  '#8b4631',
  '#974c35',
  '#a3533a',
  '#ae593e',
  '#b96043',
  '#c46749',
  '#ce6f4e',
  '#d37756',
  '#d7815e',
  '#da8a67',
  '#dd9373',
  '#e09c7e',
  '#e3a58a',
  '#e6ae95',
  '#e9b7a1',
  '#edc0ac',
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

  const loc = d.location;
  if (loc && Number.isFinite(Number(loc.lat)) && Number.isFinite(Number(loc.lon))) {
    const lat = Number(loc.lat);
    const lon = Number(loc.lon);
    // 高德接受 lon,lat 顺序；坐标本身用 5 位小数（约 1 米级）即可
    // 库里存的是 GPS 原始 WGS-84（真值、与谷歌地球一致），直接展示这一份坐标；
    // 但高德按 GCJ-02 展示，标点前必须做「火星坐标」偏移转换，否则会偏出上百米
    const coord = `${lat.toFixed(6)},${lon.toFixed(6)}`;
    const g = wgs84ToGcj02(lat, lon);
    const mapUrl = `https://uri.amap.com/marker?position=${g.lon.toFixed(6)},${g.lat.toFixed(6)}&coordinate=gaode`;
    const acc = Number.isFinite(Number(loc.accuracy))
      ? `±${Math.round(Number(loc.accuracy))}m`
      : '';
    lines.push(`
      <div class="ip-line">
        <span class="ip-tag">位置</span>
        <a class="ip-addr" href="${esc(mapUrl)}" target="_blank" rel="noopener noreferrer"
           title="WGS-84 原始坐标，点击在高德打开（已转 GCJ-02）">
          ${esc(coord)}
        </a>
        ${acc ? `<span class="ip-loc">${esc(acc)}</span>` : ''}
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
    padding:16px 18px;
    background:var(--panel); border:1px solid var(--border);
    border-radius:12px; border-top:2px solid var(--clay);
  }
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
  .device-table { width:100%; border-collapse:collapse; font-size:12.5px; }
  .device-table thead th {
    position:sticky; top:0; z-index:1;
    padding:11px 14px; text-align:left; white-space:nowrap;
    background:var(--panel-alt); color:var(--text-faint);
    font-family:var(--mono); font-size:10.5px; font-weight:500;
    letter-spacing:.09em; text-transform:uppercase;
    border-bottom:1px solid var(--border);
    cursor:pointer; user-select:none;
  }
  .device-table thead th:hover { color:var(--clay); }
  .sort-icon { color:var(--text-faint); font-size:9px; margin-left:3px; }
  .device-table tbody td {
    padding:11px 14px; vertical-align:middle;
    color:var(--text-dim);
    border-bottom:1px solid var(--border-soft);
  }
  .device-table tbody tr:last-child td { border-bottom:none; }
  .device-row { cursor:pointer; }
  .device-row:hover td { background:var(--panel-alt); color:var(--text); }

  /* 对齐原版的 td.strong：只把颜色提亮，不加粗；等宽字体由 baseStyles 的 td 规则继承 */
  .device-brand {
    color:var(--text); white-space:nowrap;
  }
  .device-id {
    font-family:var(--mono); font-size:10.5px; color:var(--text-faint);
    overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:240px;
  }
  .device-model { display:flex; flex-direction:column; gap:1px; }
  .device-model strong { font-weight:500; color:var(--text); }
  .device-model span { font-size:11px; color:var(--text-faint); }
  .android-badge {
    display:inline-block; padding:2px 8px; border-radius:20px;
    background:rgba(108,255,0,.7); color:var(--slate);
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

  /* 分页隐藏和搜索隐藏是两回事，各用各的类，否则清搜索时会把翻页状态一起清掉 */
  tr.off-page {
    display:none;
  }

  .pager {
    display:flex;
    align-items:center;
    gap:10px;

    margin-top:14px;
    padding:0 2px;

    font-family:var(--mono);
    font-size:11.5px;
    color:var(--text-faint);
  }

  .pager-info {
    margin-right:auto;
    white-space:nowrap;
  }

  .pager button {
    min-width:30px;
    padding:3px 9px;

    border:1px solid var(--border);
    border-radius:6px;

    background:var(--panel);
    color:var(--text-dim);

    font-family:var(--mono);
    font-size:11.5px;

    cursor:pointer;
  }

  .pager button:hover:not(:disabled) {
    background:rgba(217,119,87,.1);
    color:var(--clay);
  }

  .pager button:disabled {
    opacity:.35;
    cursor:default;
  }

  .pager button.current {
    border-color:var(--clay);
    background:rgba(217,119,87,.12);
    color:var(--clay);
    font-weight:600;
  }

  .pager-gap {
    padding:0 2px;
    color:var(--text-faint);
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

    width:min(920px,100%);
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

  /* 脚本操作区：放在弹窗头部左上角，占原来型号标题的位置 */
  .modal-actions {
    display:flex;
    align-items:center;
    gap:10px;
    flex-wrap:wrap;
  }

  .modal-noscript {
    font-size:12px;
    color:var(--text-faint);
  }

  #script-output {
    margin-top:16px;
    padding-top:14px;
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
    /* auto-fill 而不是 auto-fit：只剩一项时也保持一格宽度，不会被拉满整行 */
    grid-template-columns:
      repeat(auto-fill,minmax(190px,1fr));

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

    /* 上报字段很稀疏（CPU 常常只有 2 行、内存/存储各 1 行），
       用 auto-fit 让宽屏排三列，align-items:start 让短卡片保持自身高度、
       不被同行的长卡片拉高留出大片空白，dense 再把短卡回填到空位。 */
    grid-template-columns:
      repeat(auto-fit,minmax(300px,1fr));

    grid-auto-flow:dense;
    align-items:start;

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

  .net-updated {
    margin-left:auto;

    font-family:var(--mono);
    font-size:10px;
    font-weight:400;

    color:var(--text-faint);

    white-space:nowrap;
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
      minmax(72px,max-content)
      minmax(0,1fr);

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

/* 开机时长：兼容毫秒与秒两种上报单位 */
function fmtUptime(v) {
  const n = safeNumber(v);

  if (n === null || n < 0) {
    return '';
  }

  const ms = n > 100000000 ? n : n * 1000;

  let sec = Math.floor(ms / 1000);

  const days = Math.floor(sec / 86400);
  sec %= 86400;

  const hours = Math.floor(sec / 3600);
  sec %= 3600;

  const minutes = Math.floor(sec / 60);

  if (days > 0) {
    return `${days}天 ${hours}小时`;
  }

  if (hours > 0) {
    return `${hours}小时 ${minutes}分`;
  }

  return `${minutes}分`;
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

function card(title, rows, wide, action) {
  const content =
    Array.isArray(rows)
      ? rows.filter(Boolean).join('')
      : String(rows || '');

  // 带动作位的卡片即使暂无数据也要渲染——没上报过 network 的设备
  // 恰恰是最需要那个刷新按钮的，不能因为内容为空就把入口一起藏掉
  if (!content && !action) {
    return '';
  }

  return `
    <section class="info-card${wide ? ' wide' : ''}">
      <h4>${esc(title)}${action || ''}</h4>
      ${content || '<p class="info-empty">尚未上报</p>'}
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

    // 新版设备上报的有序可连地址；老设备没有这个字段，退回 ipv4/ipv6
    addresses: net.addresses,

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
 * 可连地址
 * ========================================================= */

/**
 * 标出每个地址是哪一类，光看一串十六进制分不清哪个能连。
 * 只看地址本身，不代表可达性——那要 Easycontrol 真去连才知道。
 */
function classifyAddress(address) {
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
 * 「网络」卡片里的地址段。
 *
 * 新版设备上报的是一个有序的 `addresses`——只含真正能连上的地址（带 INTERNET
 * 能力的网络，排除了 IMS 这类专用 APN、ULA、蜂窝私有 v4），默认网络排最前，
 * Easycontrol 按这个顺序并发尝试。
 *
 * 还没升级到新版的设备只有旧的 ipv4/ipv6 两个数组，那是「当前默认网络的快照」，
 * 语义不同且可能是几天前的，所以只在没有 addresses 时才拿来兜底显示。
 *
 * @param {object} d 设备记录
 * @param {object} net 归一化后的网络信息
 * @returns {Array<string>} info-row 片段
 */
function addressRows(d, net) {
  const addresses = Array.isArray(net.addresses) ? net.addresses : [];

  if (!addresses.length) {
    // 旧设备：保持原样按 IPv4 / IPv6 分组列出
    return [...ipRows('IPv4', net.ipv4), ...ipRows('IPv6', net.ipv6)];
  }

  const port = d.adbPort || 0;
  return addresses.map((address) =>
    row('可连地址 · ' + classifyAddress(address), port ? address + ':' + port : address));
}

/** 「网络」卡片右上角：最后一次上报的时刻 */
function networkUpdatedAction(d) {
  if (!d.networkUpdatedAt) {
    return '';
  }
  return `<span class="net-updated">更新于 ${esc(d.networkUpdatedAt)}</span>`;
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
  const netRaw = di.network || {};

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

  const uptimeText = fmtUptime(sys.uptime);

  const brand = firstDefined(
    pf.buildBrand,
    manufacturer,
    d.brand
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

  /* =======================================================
   * Overview
   * ======================================================= */

  const overview = `
    <div class="device-overview">

      <div class="device-overview-main">

        <div class="device-overview-label">
          设备概览
        </div>

        <div class="device-overview-name">
          ${esc(deviceName)}
        </div>

        <div class="device-overview-sub">

          ${
            brand
              ? `
                <span class="device-chip">
                  ${esc(brand)}
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

          ${
            appVersion
              ? `
                <span class="device-chip">
                  v${esc(appVersion)}${
                    appBuild
                      ? ` (${esc(String(appBuild))})`
                      : ''
                  }
                </span>
              `
              : ''
          }

          ${
            uptimeText
              ? `
                <span class="device-chip">
                  已开机 ${esc(uptimeText)}
                </span>
              `
              : ''
          }

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
        cpu.name
          ? `
            <div class="device-overview-score">

              <div class="device-score-number">
                ${esc(cpu.name)}
              </div>

              <div class="device-score-label">
                处理器
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

  // 三项都没有时整块不渲染：空的 grid 虽然没高度，margin-bottom 仍会留一段空白
  const hasMetrics =
    cpuUsage !== null ||
    memoryUsage !== null ||
    storageUsage !== null;

  const metrics = !hasMetrics ? '' : `
    <div class="device-metrics">

      ${
        cpuUsage !== null
          ? `
            <div class="device-metric">

              <div class="device-metric-head">
                <span class="device-metric-name">
                  处理器
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
                  内存
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
                  存储
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

    </div>
  `;

  /* =======================================================
   * CPU
   * ======================================================= */


  /* =======================================================
   * GPU
   * ======================================================= */


  /* =======================================================
   * Memory
   * ======================================================= */


  /* =======================================================
   * Storage
   * ======================================================= */


  /* =======================================================
   * Display
   * ======================================================= */

  const resolution =
    display.width &&
    display.height
      ? `${display.width} × ${display.height}`
      : '';


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

    // 设备主动上报的可连地址，网络一变就重报；没升级的老设备退回旧的 IPv4/IPv6
    ...addressRows(d, net),

    row('设备序列号', d.ecSerial),
    row('adb 端口', d.adbPort ? String(d.adbPort) : '')
  ];

  const networkCard = card(
    '网络',
    networkRows,
    false,
    networkUpdatedAction(d)
  );

  /* =======================================================
   * Basic
   * ======================================================= */

  const basicRows = [
      row(
        '设备名称',
        deviceName
      ),

      row(
        '平台',
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
        '语言',
        dev.language
      )
  ];

  const deviceCard = card(
    '设备',
    [
      ...basicRows,

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
      row('Load 15m', cpu.load15),

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
        'Vulkan',
        firstDefined(
          pf.vulkanVersion,
          hw.vulkanVersion
        )
      ),

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
      ),

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
      ),

      row(
        '分辨率',
        resolution
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
          <h4>功能支持</h4>

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

      ${deviceCard}

      ${networkCard}

      ${capabilitiesCard}

      ${sensors}

      ${cameras}

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
   * 分页
   *
   * 搜索和排序都在前端做，所以分页也必须在前端：否则搜索只会在当前页里找，
   * 排序也只排当前页这 20 行，两个功能都会变得不对。
   *
   * 一行可能因为两个互不相干的原因被藏起来——搜索没命中（hidden-row）、
   * 不在当前页（off-page）。两个类分开，清掉搜索时不会顺手把翻页状态也清掉。
   * ======================================================= */

  var PAGE_SIZE = 20;
  var currentPage = 1;

  /* 当前搜索条件下还剩哪些行，按 DOM 顺序——排序改的就是 DOM 顺序，所以这里天然跟着排序走 */
  function visibleRows() {
    return Array.prototype.slice.call(
      document.querySelectorAll('#device-table tbody tr[data-search]')
    ).filter(function (row) {
      return !row.classList.contains('hidden-row');
    });
  }

  function pagerButton(label, page, opts) {
    var o = opts || {};
    return '<button type="button" data-page="' + page + '"' +
      (o.disabled ? ' disabled' : '') +
      (o.current ? ' class="current"' : '') +
      '>' + label + '</button>';
  }

  /**
   * 按当前页把行显示/隐藏，并重画分页条。
   * 搜索、排序、翻页之后都要调它。
   */
  function applyPaging() {
    var rows = visibleRows();
    var total = rows.length;
    var pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

    // 搜索把结果变少时，当前页可能已经越界，往回收
    if (currentPage > pages) currentPage = pages;
    if (currentPage < 1) currentPage = 1;

    var start = (currentPage - 1) * PAGE_SIZE;
    rows.forEach(function (row, i) {
      row.classList.toggle('off-page', i < start || i >= start + PAGE_SIZE);
    });

    var pager = document.getElementById('device-pager');
    if (!pager) return;

    // 只有一页时不占地方
    if (total <= PAGE_SIZE) {
      pager.innerHTML = '';
      return;
    }

    var shown = Math.min(PAGE_SIZE, total - start);
    var html = '<span class="pager-info">第 ' + (start + 1) + '–' + (start + shown) +
      ' 台，共 ' + total + ' 台 · ' + currentPage + '/' + pages + ' 页</span>';

    html += pagerButton('上一页', currentPage - 1, { disabled: currentPage === 1 });

    // 页码：头尾各留一个，当前页左右各一个，中间用省略号收起来，
    // 302 台是 16 页还好，设备再多就不能全列了
    var nums = [];
    for (var p = 1; p <= pages; p++) {
      if (p === 1 || p === pages || Math.abs(p - currentPage) <= 1) nums.push(p);
    }
    var last = 0;
    nums.forEach(function (p) {
      if (last && p - last > 1) html += '<span class="pager-gap">…</span>';
      html += pagerButton(String(p), p, { current: p === currentPage });
      last = p;
    });

    html += pagerButton('下一页', currentPage + 1, { disabled: currentPage === pages });
    pager.innerHTML = html;
  }

  window.__applyPaging = applyPaging;

  /* 翻页按钮是重画出来的，不能各自绑事件，统一在容器上代理 */
  document.addEventListener('click', function (event) {
    var btn = event.target.closest && event.target.closest('#device-pager button[data-page]');
    if (!btn || btn.disabled) return;
    var page = parseInt(btn.getAttribute('data-page'), 10);
    if (!page) return;
    currentPage = page;
    applyPaging();
    // 翻页后回到表格顶部，否则会停在上一页的位置，看着像没反应
    var table = document.getElementById('device-table');
    if (table) table.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });

  /* 搜索和排序都会改变「哪些行、什么顺序」，两者都要回到第一页 */
  window.__resetPage = function () {
    currentPage = 1;
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyPaging);
  } else {
    applyPaging();
  }


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

    // 搜索结果变了，停在第 5 页没有意义
    if (window.__resetPage) window.__resetPage();
    if (window.__applyPaging) window.__applyPaging();

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

    // 顺序变了，第一页装的已经是另一批设备，回到第 1 页才符合预期
    if (window.__resetPage) window.__resetPage();
    if (window.__applyPaging) window.__applyPaging();

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
 * 设备活跃度
 *
 * 刻意不叫「在线」：lastSeen 由客户端打开 App 查授权时触发更新，客户端自带
 * 30 分钟节流，服务端也没有心跳，所以它只能表达「最近使用过」，
 * 表达不了「此刻正挂着」。叫在线会让人误以为是实时连接状态。
 */
const ACTIVE_DAY_MS = 24 * 60 * 60 * 1000;
const ACTIVE_WEEK_MS = 7 * ACTIVE_DAY_MS;

/* 最近一次活跃距今多久（毫秒）；时间无法解析时返回 Infinity */
function activeAge(d) {
  const seen = Date.parse(String(d.lastSeen || '').replace(/-/g, '/'));
  return Number.isNaN(seen) ? Infinity : Date.now() - seen;
}

function isActiveToday(d) {
  return activeAge(d) <= ACTIVE_DAY_MS;
}

function isActiveThisWeek(d) {
  return activeAge(d) <= ACTIVE_WEEK_MS;
}

/* 设备行的状态徽章用一天窗口 */
function isDeviceOnline(d) {
  return isActiveToday(d);
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
        ${online ? '今日活跃' : '沉寂'}
      </span>

    </span>
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
   * 详情面板不再随首页一起下发。
   *
   * 以前每台设备都内嵌一份 <template>，302 台就是 3 MB HTML——虽然 template 是惰性的、
   * 不参与布局，但那 3 MB 仍要一次性下载并解析完，打开页面的头几秒整个标签页是卡住的。
   * 现在点开哪台才去取哪台（GET /api/device/info），首页只剩表格本身。
   */

  return `
    <tr
      class="device-row"
      data-search="${esc(searchText)}"
      data-device-index="${index}"
      data-device-id="${esc(d.deviceId || '')}"
      data-script-file="${esc(d.scriptFile || 'scripts.json')}"
      data-script-count="${esc(String(d.scriptCount || 0))}"
      tabindex="0">

      <td class="device-brand">
        ${esc(brand)}
      </td>


      <td>

        <div class="device-model">

          <strong>
            ${esc(name)}
          </strong>

          <span class="device-id">
            ${esc(
              d.deviceId ||
              'NO DEVICE ID'
            )}
          </span>

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


    </tr>


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

              品牌

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


          </tr>

        </thead>


        <tbody>

          ${rows}

        </tbody>

      </table>

    </div>

    <div class="pager" id="device-pager"></div>
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
        isActiveToday(d)
    ).length;

  const week =
    devices.filter(
      d =>
        isActiveThisWeek(d)
    ).length;

  /* 品牌数与「品牌分布」图共用 countBy 的归一口径，
     否则 Redmi/REDMI 这类大小写变体会被重复计成两个品牌 */
  const brandCount =
    countBy(
      devices,
      d => d.brand
    ).length;

  return `
    <div class="dashboard-stats">

      <div class="stat-card">

        <div class="stat-content">

          <div class="stat-label">
            总设备数
          </div>

          <div class="stat-value">
            ${total}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-content">

          <div class="stat-label">
            今日活跃
          </div>

          <div class="stat-value">
            ${online}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-content">

          <div class="stat-label">
            近 7 天活跃
          </div>

          <div class="stat-value">
            ${week}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-content">

          <div class="stat-label">
            手机品牌数
          </div>

          <div class="stat-value">
            ${brandCount}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-content">

          <div class="stat-label">
            安装次数
          </div>

          <div class="stat-value">
            ${esc(String(stats.install_count || 0))}
          </div>

        </div>

      </div>


      <div class="stat-card">

        <div class="stat-content">

          <div class="stat-label">
            更新次数
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

  <!-- 与 pageShell 同一套字体：本页自己写了完整 <html> 绕过了 pageShell，
       这两行必须自带，否则 --mono 里的 IBM Plex Mono 下载不到，
       所有数字会回退到 Consolas，和站内其它页面对不上 -->
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link
    href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500;600&family=Source+Serif+4:wght@400;600&display=swap"
    rel="stylesheet">

  ${dashboardStyles}

</head>


<body>

  <div class="dashboard">

    <!-- =================================================
         Header
         ================================================= -->

    <header class="dashboard-header">

      <div class="header-left">

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
          设备日志 · Device Log
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
            placeholder="搜索设备 / 型号 / 设备 ID…">

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

          <!-- 左上角放脚本操作：设备名在下方「设备概览」里已经很醒目，标题是重复的 -->
          <div class="modal-actions" id="modal-actions">

            <button
              type="button"
              class="btn btn-primary"
              id="btnView">
              查看内容
            </button>

            <a
              class="btn"
              id="btnDownload"
              download>
              下载
            </a>

            <span class="modal-noscript" id="modal-noscript">
              该设备暂无脚本
            </span>

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

    /* 取过的详情留在内存里，反复点同一台不再走网络 */
    const detailCache = new Map();

    /* 取一台设备的详情 HTML。失败时返回一句给人看的话，不抛 */
    function fetchDetail(deviceId) {
      if (detailCache.has(deviceId)) {
        return Promise.resolve(detailCache.get(deviceId));
      }
      return fetch('/api/device/info?deviceId=' + encodeURIComponent(deviceId))
        .then(function (res) {
          if (!res.ok) throw new Error(String(res.status));
          return res.text();
        })
        .then(function (html) {
          detailCache.set(deviceId, html);
          return html;
        })
        .catch(function (err) {
          // 不写进缓存：下次点开还能重试
          return '<div class="modal-empty">详情加载失败（' + err.message + '），关掉重开可重试</div>';
        });
    }

    /* 记住这次点的是谁：取回来时若用户已经切到别台，就把结果丢掉 */
    let currentDeviceId = null;

    /* 关闭弹窗的代码在另一个 IIFE 里，够不到上面这个变量，只能从 window 上开个口子 */
    window.__cancelDeviceDetail = function () {
      currentDeviceId = null;
    };

    function openDeviceDetail(
      deviceId,
      row
    ) {

      const modal =
        document.getElementById(
          'device-modal'
        );

      const body =
        document.getElementById(
          'device-modal-body'
        );

      const btnView =
        document.getElementById('btnView');

      const btnDl =
        document.getElementById('btnDownload');

      const noScript =
        document.getElementById('modal-noscript');


      if (
        !deviceId ||
        !modal ||
        !body
      ) {
        return;
      }

      currentDeviceId = deviceId;

      // 先给个占位，别让弹窗空着——局域网内通常几十毫秒就回来了
      body.innerHTML = '<div class="modal-empty">正在加载…</div>';

      fetchDetail(deviceId).then(function (html) {
        // 取的过程中用户可能已经关掉或换了一台，那这次结果就不该再往里塞
        if (currentDeviceId !== deviceId) return;
        body.innerHTML = html;
      });


      if (row) {

        /* 标题用「品牌 型号」拼，两列分别在 .device-brand 和 .device-model strong 里 */
        /* 没有脚本就只显示提示，不给出点了会 404 的按钮 */
        const count =
          parseInt(
            row.getAttribute('data-script-count'),
            10
          ) || 0;

        const devId =
          row.getAttribute('data-device-id') || '';

        const file =
          row.getAttribute('data-script-file') ||
          'scripts.json';

        const url =
          '/api/download?deviceId=' +
          encodeURIComponent(devId) +
          '&filename=' +
          encodeURIComponent(file);

        window.__scriptUrl = count ? url : null;

        if (btnView) {
          btnView.style.display = count ? '' : 'none';
        }

        if (btnDl) {
          btnDl.style.display = count ? '' : 'none';
          btnDl.href = url;
          btnDl.setAttribute('download', file);
        }

        if (noScript) {
          noScript.style.display = count ? 'none' : '';
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
     * 「查看内容」：拉取该设备分享的脚本，追加渲染到设备详情下方
     */

    function escapeText(t) {
      return String(t).replace(
        /[&<>]/g,
        function (c) {
          return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c];
        }
      );
    }


    function scriptHolder() {
      const body =
        document.getElementById('device-modal-body');

      if (!body) {
        return null;
      }

      let holder =
        document.getElementById('script-output');

      if (!holder) {
        holder = document.createElement('div');
        holder.id = 'script-output';
        body.appendChild(holder);
      }

      return holder;
    }


    function renderScripts(data) {

      const holder = scriptHolder();

      if (!holder) {
        return;
      }

      const list = (data && data.scripts) || [];

      if (!list.length) {
        holder.innerHTML =
          '<p class="modal-empty">该设备没有脚本内容</p>';
        return;
      }

      holder.innerHTML =
        '<p class="modal-sec-title">录制脚本</p>' +
        list.map(function (sc, i) {

          const n =
            Array.isArray(sc.actions)
              ? sc.actions.length
              : (sc.actionCount || '-');

          const raw =
            JSON.stringify(sc.actions, null, 2);

          return '<div class="script-item">' +
            '<div class="script-name">' +
            escapeText(sc.name || ('脚本 ' + (i + 1))) +
            '</div>' +
            '<div class="script-meta">' + n + ' 个动作</div>' +
            '<div class="script-toggle" data-raw="raw' + i + '">展开原始内容 ▾</div>' +
            '<pre class="script-raw" id="raw' + i + '">' +
            escapeText(raw) +
            '</pre>' +
            '</div>';
        }).join('');

      holder.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }


    document.addEventListener(
      'click',
      function (event) {

        /* 展开/收起原始 JSON */
        const toggle =
          event.target.closest('.script-toggle');

        if (toggle) {
          const pre =
            document.getElementById(
              toggle.getAttribute('data-raw')
            );

          if (pre) {
            const open = pre.classList.toggle('open');
            toggle.textContent =
              open ? '收起原始内容 ▴' : '展开原始内容 ▾';
          }
          return;
        }

        if (!event.target.closest('#btnView')) {
          return;
        }

        if (!window.__scriptUrl) {
          return;
        }

        const holder = scriptHolder();

        if (holder) {
          holder.innerHTML =
            '<p class="modal-empty">加载中…</p>';
        }

        fetch(window.__scriptUrl)
          .then(function (r) { return r.json(); })
          .then(renderScripts)
          .catch(function () {
            if (holder) {
              holder.innerHTML =
                '<p class="modal-empty">加载失败</p>';
            }
          });

      }
    );


    /*
     * 点击整行打开设备详情
     */

    document.addEventListener(
      'click',
      function (event) {

        const row =
          event.target.closest(
            'tr.device-row'
          );


        if (!row) {
          return;
        }


        /* 选中文字（比如想复制 Device ID）时不当成点击 */
        const sel = window.getSelection();

        if (sel && String(sel).length > 0) {
          return;
        }


        openDeviceDetail(
          row.getAttribute('data-device-id'),
          row
        );

      }
    );


    /*
     * 键盘可达：行上按回车/空格同样打开
     */

    document.addEventListener(
      'keydown',
      function (event) {

        if (event.key !== 'Enter' && event.key !== ' ') {
          return;
        }

        const row =
          event.target.closest &&
          event.target.closest('tr.device-row');

        if (!row) {
          return;
        }

        event.preventDefault();

        openDeviceDetail(
          row.getAttribute('data-device-id'),
          row
        );

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

      // 详情是异步取的，关掉后那次请求可能才回来；作废它，别往已关掉的弹窗里塞
      if (window.__cancelDeviceDetail) window.__cancelDeviceDetail();


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

/**
 * 单台设备的详情面板 HTML。
 *
 * 和首页表格里的详情**同一份实现**——首页只是不再内嵌它了，改由
 * GET /api/device/info 按需取（见 stats.js）。
 *
 * @param {object} device 设备记录
 * @returns {string} 面板 HTML 片段
 */
function deviceInfoHtml(device) {
  return buildDeviceInfoHtml(device, null);
}

module.exports = { dashboardHtml, deviceInfoHtml };
