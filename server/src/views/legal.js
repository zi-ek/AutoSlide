'use strict';

/**
 * 用户协议与隐私政策页面。
 *
 * 内容必须与应用的真实行为一致：AutoSlide 有自建后端，会上报安装信息、
 * 备份录制配置、承载反馈聊天室，与「无服务器、不收集数据」的说法不同，
 * 改动任一联网行为时务必同步更新本文件。
 */

const { pageShell } = require('./pages');

/* 段落：小标题 + 若干段正文 */
function section(title, paragraphs) {
  const body = paragraphs.map((p) => `<p class="legal-p">${p}</p>`).join('');
  return `<section class="legal-sec"><h2 class="legal-h2">${title}</h2>${body}</section>`;
}

/* 表格：表头 + 若干行 */
function table(headers, rows) {
  const head = headers.map((h) => `<th>${h}</th>`).join('');
  const body = rows
    .map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`)
    .join('');
  return `<div class="legal-table-wrap"><table class="legal-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

const legalStyles = `
<style>
  .legal-sec { margin: 28px 0; }
  .legal-h2 { font-size: 17px; font-weight: 600; margin: 0 0 10px; }
  .legal-p { line-height: 1.85; margin: 8px 0; }
  .legal-table-wrap { overflow-x: auto; margin: 12px 0; }
  .legal-table { border-collapse: collapse; width: 100%; font-size: 14px; }
  .legal-table th, .legal-table td { border: 1px solid rgba(128,128,128,.35); padding: 8px 10px; text-align: left; vertical-align: top; }
  .legal-table th { font-weight: 600; }
  .legal-updated { font-size: 13px; opacity: .7; margin-bottom: 18px; }
</style>`;

const UPDATED = '2026 年 8 月 16 日';

function termsHtml() {
  const body = [
    legalStyles,
    `<p class="legal-updated">最后更新：${UPDATED}</p>`,
    section('一、应用信息', [
      '本协议适用于「自动滑屏器」（AutoSlide）Android 应用。',
      '本应用为开源软件，源码托管于 <a href="https://github.com/zi-ek/AutoSlide">github.com/zi-ek/AutoSlide</a>，以 Apache License 2.0 授权。',
      '如需联系开发者，可通过应用内「反馈」功能，或在上述仓库提交 Issue。',
    ]),
    section('二、许可与使用限制', [
      '在遵守 Apache License 2.0 的前提下，你可以自由使用、修改和分发本应用的源码。第三方基于本项目分发的版本与本项目无关，请自行判断其安全性。',
      '你不得将本应用用于任何违反所在地法律法规的用途，包括但不限于绕过其他应用的正常计费、批量注册账号、干扰他人设备正常使用等。',
      '本应用提供的自动滑动、自动点击、动作录制与回放等能力，仅供你在自己的设备上、对你有权操作的内容使用。因违规使用产生的一切后果由使用者自行承担。',
    ]),
    section('三、功能与权限说明', [
      '本应用的核心功能依赖系统无障碍服务读取屏幕内容并执行滑动、点击操作。未授予无障碍权限时，自动化相关功能无法工作。',
      '悬浮窗权限用于显示悬浮控制球。通知权限用于显示常驻运行状态通知。这两项均可拒绝，拒绝后对应功能不可用，但不影响其它功能。',
      '「写入安全设置」权限为可选，仅用于免去手动进入系统设置开启无障碍的步骤。该权限需通过 ADB 或 Shizuku 授予，本应用无法自行获取。',
    ]),
    section('四、免责声明', [
      '本应用按「现状」提供，不对其适用性、稳定性作出任何明示或默示担保。',
      '自动化操作存在误触风险。请勿在涉及支付、转账、删除数据等不可撤销操作的界面上使用本应用的自动点击与回放功能。由此造成的任何损失，开发者不承担责任。',
      '国产系统的后台管理策略各不相同，本应用无法保证在所有设备上都能持续后台运行。',
    ]),
    section('五、协议变更', [
      '本协议可能随应用版本更新而调整，修订后的内容将在本页面公布。继续使用本应用即视为接受变更后的协议。',
    ]),
  ].join('');

  return pageShell({
    title: '用户协议 · 自动滑屏器',
    eyebrow: '自动滑屏器',
    heading: '用户协议',
    body,
  });
}

function privacyHtml() {
  const dataTable = table(
    ['数据类型', '具体内容', '是否上传', '用途'],
    [
      [
        '安装与版本上报',
        'Android ID、设备型号与品牌、系统版本、CPU 架构、应用版本号',
        '<strong>是</strong>，每个版本首次启动时上报一次',
        '统计安装量与版本分布',
      ],
      [
        '录制配置备份',
        '本机配置文件 slide_settings.xml 全文，含你录制的全部动作、关键词与各项设置',
        '<strong>是</strong>，应用启动时自动上传',
        '备份配置，便于误删后找回',
      ],
      [
        '反馈内容',
        '设备标识、设备名称，以及你在反馈中主动输入的文字与图片',
        '<strong>是</strong>，仅在你主动发送时',
        '与开发者沟通问题',
      ],
      ['屏幕文字', '通过 OCR 识别到的屏幕文字', '否，仅存在于本机内存', '关键词检测与自动点击判断'],
      ['屏幕截图', '无障碍服务截取的画面', '否，识别完即丢弃', '作为 OCR 的输入'],
      ['统计数据', '累计滑屏次数、关键词命中次数等', '否，仅存本机', '主界面展示'],
    ]
  );

  const permTable = table(
    ['权限', '是否必需', '用途'],
    [
      ['无障碍服务', '核心功能必需', '读取屏幕内容，执行滑动与点击'],
      ['悬浮窗', '使用悬浮球时必需', '显示悬浮控制球，以及保持后台运行的 1×1 透明窗口'],
      ['通知', '可选', '显示常驻运行状态通知，有助于降低被系统清理的概率'],
      ['网络', '可选', '安装上报、配置备份、反馈、检查更新'],
      ['写入安全设置', '可选', '自动开启无障碍服务，需 ADB 或 Shizuku 授予'],
      ['安装未知应用', '可选', '应用内下载并安装新版本'],
    ]
  );

  const body = [
    legalStyles,
    `<p class="legal-updated">最后更新：${UPDATED}</p>`,
    section('一、我们如何处理你的数据', [
      '本应用有自建服务器，部分数据会上传。下表列出全部数据的处理方式，请在使用前确认你能够接受。',
      dataTable,
      '<strong>请特别注意：</strong>录制配置备份会上传完整的配置文件。如果你录制的动作中包含敏感信息（例如在录制过程中输入过的文字），这些内容会一并上传。请勿录制包含密码、支付信息等敏感内容的操作。',
    ]),
    section('二、权限使用说明', [permTable]),
    section('三、数据的存储与保留', [
      '上传的数据存储在开发者自建的服务器上，用于统计、备份与反馈沟通，不会出售或提供给第三方用于商业用途。',
      '本应用不接入任何第三方广告或数据分析 SDK。文字识别使用的 Google ML Kit 模型随安装包内置，识别过程完全在本机完成，不联网。',
      '检查更新时会访问 GitHub 获取版本信息，该请求不携带任何设备标识。',
    ]),
    section('四、你的权利', [
      '你可以拒绝授予任何权限，应用的对应功能会停止工作，但不会因此无法使用其它功能。',
      '你可以通过应用内的「导出」功能获取本机配置文件，也可以随时清除已录制的动作。',
      '如需删除已上传到服务器的数据，请通过应用内「反馈」联系开发者。',
      '卸载应用即可清除全部本机数据。',
    ]),
    section('五、隐私政策变更', [
      '本政策可能随应用版本更新而调整，修订后的内容将在本页面公布。继续使用本应用即视为接受变更后的政策。',
    ]),
  ].join('');

  return pageShell({
    title: '隐私政策 · 自动滑屏器',
    eyebrow: '自动滑屏器',
    heading: '隐私政策',
    body,
  });
}

module.exports = { termsHtml, privacyHtml };
