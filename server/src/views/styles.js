// 看板页面的样式表（共享设计系统）

// 共享设计系统：亮色 "机房监控台" 风格，贴合家庭网络实验室的气质。
function baseStyles() {
  return `
    :root {
      --bg: #f5f4ee;
      --panel: #ffffff;
      --panel-alt: #faf9f4;
      --border: #e5e2d8;
      --border-soft: #ece9de;
      --text: #23221f;
      --text-dim: #6b6a63;
      --text-faint: #9c9a90;
      --clay: #d97757;
      --sage: #7a8f6c;
      --slate: #6b84a3;
      --green: #5b9279;
      --mono: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
      --serif: 'Source Serif 4', Georgia, serif;
      --sans: 'Inter', 'Microsoft YaHei', -apple-system, sans-serif;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      background:
        radial-gradient(ellipse 900px 500px at 15% -10%, rgba(217,119,87,.06), transparent 60%),
        radial-gradient(ellipse 700px 400px at 100% 0%, rgba(107,132,163,.05), transparent 60%),
        var(--bg);
      color: var(--text);
      font-family: var(--sans);
      padding: 28px 20px 60px;
    }
    .wrap { max-width: 1640px; margin: 0 auto; }
    a { color: var(--clay); text-decoration: none; }
    a:hover { text-decoration: underline; }
    .topbar {
      display: flex;
      align-items: flex-end;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;
      margin-bottom: 26px;
      padding-bottom: 18px;
      border-bottom: 1px solid var(--border);
    }
    .eyebrow {
      font-family: var(--mono);
      font-size: 11px;
      letter-spacing: .18em;
      color: var(--text-faint);
      text-transform: uppercase;
      margin: 0 0 6px;
    }
    h1 { font-family: var(--serif); font-size: 27px; font-weight: 600; margin: 0; letter-spacing: -.01em; color: var(--text); }
    .breadcrumb { font-family: var(--mono); font-size: 12px; color: var(--text-dim); }
    .live {
      display: flex;
      align-items: center;
      gap: 8px;
      font-family: var(--mono);
      font-size: 11px;
      color: var(--text-dim);
      letter-spacing: .08em;
    }
    .dot {
      width: 7px; height: 7px; border-radius: 50%;
      background: var(--green);
      box-shadow: 0 0 0 0 rgba(91,146,121,.6);
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0% { box-shadow: 0 0 0 0 rgba(91,146,121,.45); }
      70% { box-shadow: 0 0 0 6px rgba(91,146,121,0); }
      100% { box-shadow: 0 0 0 0 rgba(91,146,121,0); }
    }
    @media (prefers-reduced-motion: reduce) { .dot { animation: none; } }
    .cards {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 12px;
      margin-bottom: 28px;
    }
    .card {
      background: var(--panel);
      border: 1px solid var(--border);
      border-left: 2px solid var(--accent, var(--clay));
      border-radius: 8px;
      padding: 16px 18px;
      box-shadow: 0 1px 2px rgba(35,34,31,.03);
    }
    .card .num {
      font-family: var(--mono);
      font-size: 26px;
      font-weight: 600;
      color: var(--text);
      line-height: 1;
    }
    .card .label {
      font-size: 11px;
      letter-spacing: .1em;
      text-transform: uppercase;
      color: var(--text-faint);
      margin-top: 8px;
    }
    .section-label {
      font-family: var(--mono);
      font-size: 11px;
      letter-spacing: .14em;
      text-transform: uppercase;
      color: var(--text-faint);
      margin: 0 0 10px 2px;
    }
    .panel {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 10px;
      overflow: hidden;
      box-shadow: 0 1px 2px rgba(35,34,31,.03);
    }
    .table-wrap { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; min-width: 720px; }
    th, td {
      padding: 11px 14px;
      text-align: left;
      font-size: 12.5px;
      border-bottom: 1px solid var(--border-soft);
      white-space: nowrap;
    }
    th {
      background: var(--panel-alt);
      color: var(--text-faint);
      font-family: var(--mono);
      font-weight: 500;
      font-size: 10.5px;
      letter-spacing: .09em;
      text-transform: uppercase;
    }
    td { color: var(--text-dim); font-family: var(--mono); }
    td.strong { color: var(--text); }
    td .ip-loc { margin-top: 2px; color: var(--text-faint); font-size: 10.5px; }
    tbody tr:last-child td { border-bottom: none; }
    tbody tr:hover td { background: rgba(217,119,87,.06); color: var(--text); }
    .empty { color: var(--text-faint); font-style: normal; text-align: center; padding: 32px 0 !important; }
    .actions a {
      font-family: var(--mono);
      font-size: 11px;
      margin-right: 14px;
      color: var(--clay);
    }
    .actions a.dl { color: var(--slate); }
    .top-links { margin-bottom: 18px; }
    @media (max-width: 640px) {
      body { padding: 20px 14px 40px; }
      .cards { grid-template-columns: repeat(2, 1fr); }
      .topbar { align-items: flex-start; }
      h1 { font-size: 20px; }
    }
  `;
}

module.exports = { baseStyles };
