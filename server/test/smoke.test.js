// AutoSlide 统计后台冒烟测试（零依赖，node --test 运行）
//
// 目的不是覆盖率，而是给重构提供一条基线：
// 起一个真实的服务进程（数据目录隔离到临时目录），把全部对外接口打一遍，
// 断言状态码与关键字段。重构过程中每改一步都能秒验证行为没变。

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

const SERVER_JS = path.join(__dirname, '..', 'server.js');

let child;
let baseUrl;
let dataDir;

/* ==================== 测试夹具 ==================== */

before(async () => {
  dataDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'autoslide-test-'));
  child = spawn(process.execPath, [SERVER_JS], {
    env: {
      ...process.env,
      PORT: '0', // 交给下面的监听日志回报实际端口
      AUTOSLIDE_DATA_DIR: dataDir,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  // 服务启动后会打印监听地址，从中解析端口
  baseUrl = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('服务启动超时')), 10000);
    let buf = '';
    child.stdout.on('data', (c) => {
      buf += c;
      const m = buf.match(/http:\/\/0\.0\.0\.0:(\d+)/);
      if (m) {
        clearTimeout(timer);
        resolve(`http://127.0.0.1:${m[1]}`);
      }
    });
    child.stderr.on('data', (c) => process.stderr.write(`[server] ${c}`));
    child.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`服务意外退出，code=${code}`));
    });
  });
});

after(async () => {
  if (child && !child.killed) child.kill();
  if (dataDir) await fsp.rm(dataDir, { recursive: true, force: true });
});

/* ==================== 请求helper ==================== */

async function req(method, pathname, { body, headers } = {}) {
  const res = await fetch(baseUrl + pathname, {
    method,
    headers: {
      ...(body !== undefined && typeof body === 'object' ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : typeof body === 'object' ? JSON.stringify(body) : body,
    redirect: 'manual',
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch (e) {
    /* HTML 响应，保持 json = null */
  }
  return { status: res.status, headers: res.headers, text, json };
}

const DEVICE_A = 'test-device-aaa';
const DEVICE_B = 'test-device-bbb';

/* ==================== 统计上报 ==================== */

test('POST /api/report 首次安装计数', async () => {
  const r = await req('POST', '/api/report', {
    body: {
      event: 'install',
      deviceId: DEVICE_A,
      device: { model: 'Pixel 8', brand: 'Google', android: '14', cpu: 'arm64-v8a', appVersion: '3.1.0', appVersionCode: 32 },
    },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.install_count, 1);
  assert.equal(r.json.unique_devices, 1);
});

test('POST /api/report 同设备再次上报不增加设备数', async () => {
  const r = await req('POST', '/api/report', {
    body: { event: 'update', deviceId: DEVICE_A, device: { model: 'Pixel 8', brand: 'Google' } },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.unique_devices, 1);
});

test('POST /api/report 新设备增加设备数', async () => {
  const r = await req('POST', '/api/report', {
    body: { event: 'install', deviceId: DEVICE_B, device: { model: 'Mi 14', brand: 'Xiaomi' } },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.unique_devices, 2);
});

test('POST /api/report 非法 JSON 返回 400', async () => {
  const r = await req('POST', '/api/report', { body: 'not json', headers: { 'Content-Type': 'application/json' } });
  assert.equal(r.status, 400);
  assert.equal(r.json.ok, false);
});

test('GET /api/stats 返回统计结构', async () => {
  const r = await req('GET', '/api/stats');
  assert.equal(r.status, 200);
  assert.equal(r.json.install_count, 2);
  assert.equal(r.json.update_count, 1);
  assert.equal(r.json.devices.length, 2);
  assert.ok(r.json.devices.every((d) => typeof d.deviceId === 'string'));
});

test('并发上报不丢计数', async () => {
  const before = (await req('GET', '/api/stats')).json.install_count;
  const N = 20;
  await Promise.all(
    Array.from({ length: N }, (_, i) =>
      req('POST', '/api/report', {
        body: { event: 'install', deviceId: `concurrent-${i}`, device: { model: 'X', brand: 'Y' } },
      })
    )
  );
  const after = (await req('GET', '/api/stats')).json.install_count;
  assert.equal(after, before + N, '并发写入串行化失败，计数被覆盖');
});

/* ==================== 聊天 ==================== */

let channelId;

test('POST /api/chat/create 创建频道', async () => {
  const r = await req('POST', '/api/chat/create', {
    body: { name: '测试频道', deviceId: DEVICE_A, deviceName: 'Pixel 8' },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.channel.name, '测试频道');
  assert.equal(r.json.channel.creatorId, DEVICE_A);
  assert.equal(r.json.channel.joined, true);
  assert.match(r.json.channel.code, /^[A-Z0-9]{6}$/);
  channelId = r.json.channel.id;
});

test('POST /api/chat/create 缺参数返回 400', async () => {
  const r = await req('POST', '/api/chat/create', { body: { name: '', deviceId: '' } });
  assert.equal(r.status, 400);
});

test('POST /api/chat/join 加入频道', async () => {
  const r = await req('POST', '/api/chat/join', {
    body: { channelId, deviceId: DEVICE_B, deviceName: 'Mi 14' },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.channel.members.length, 2);
  assert.equal(r.json.channel.joined, true);
});

test('POST /api/chat/join 频道不存在返回 404', async () => {
  const r = await req('POST', '/api/chat/join', { body: { channelId: 'ch_nope', deviceId: DEVICE_B } });
  assert.equal(r.status, 404);
});

test('GET /api/chat/channels 列出频道并标记加入状态', async () => {
  const r = await req('GET', `/api/chat/channels?deviceId=${DEVICE_A}`);
  assert.equal(r.status, 200);
  assert.ok(r.json.channels.length >= 1);
  assert.equal(r.json.channels.find((c) => c.id === channelId).joined, true);

  const other = await req('GET', '/api/chat/channels?deviceId=someone-else');
  assert.equal(other.json.channels.find((c) => c.id === channelId).joined, false);
});

test('GET /api/chat/channel 返回频道详情', async () => {
  const r = await req('GET', `/api/chat/channel?id=${channelId}&deviceId=${DEVICE_A}`);
  assert.equal(r.status, 200);
  assert.equal(r.json.channel.id, channelId);
  assert.equal(r.json.channel.members.length, 2);
});

test('POST /api/chat/send 发送文字消息', async () => {
  const r = await req('POST', '/api/chat/send', {
    body: { channelId, deviceId: DEVICE_A, deviceName: 'Pixel 8', text: '你好' },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.message.text, '你好');
  assert.equal(r.json.message.type, 'text');
  assert.equal(r.json.message.seq, 1);
});

test('POST /api/chat/send 空内容返回 400', async () => {
  const r = await req('POST', '/api/chat/send', { body: { channelId, deviceId: DEVICE_A, text: '' } });
  assert.equal(r.status, 400);
});

test('GET /api/chat/messages 按 after 增量拉取', async () => {
  await req('POST', '/api/chat/send', { body: { channelId, deviceId: DEVICE_B, text: '第二条' } });

  const all = await req('GET', `/api/chat/messages?channelId=${channelId}&after=0`);
  assert.equal(all.status, 200);
  assert.equal(all.json.messages.length, 2);

  const incr = await req('GET', `/api/chat/messages?channelId=${channelId}&after=1`);
  assert.equal(incr.json.messages.length, 1);
  assert.equal(incr.json.messages[0].text, '第二条');
});

test('POST /api/chat/send 图片消息落盘并可读取', async () => {
  // 1x1 透明 PNG
  const png =
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
  const r = await req('POST', '/api/chat/send', {
    body: { channelId, deviceId: DEVICE_A, image: png },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.message.type, 'image');
  assert.match(r.json.message.image, /^\/api\/chat\/image\?file=/);

  const img = await fetch(baseUrl + r.json.message.image);
  assert.equal(img.status, 200);
  assert.equal(img.headers.get('content-type'), 'image/png');
});

test('并发发消息不丢消息', async () => {
  const create = await req('POST', '/api/chat/create', {
    body: { name: '并发频道', deviceId: DEVICE_A, deviceName: 'A' },
  });
  const id = create.json.channel.id;
  const N = 20;
  await Promise.all(
    Array.from({ length: N }, (_, i) =>
      req('POST', '/api/chat/send', { body: { channelId: id, deviceId: DEVICE_A, text: `msg-${i}` } })
    )
  );
  const r = await req('GET', `/api/chat/messages?channelId=${id}&after=0`);
  assert.equal(r.json.messages.length, N, '并发写入 chat.json 互相覆盖，消息丢失');
  const seqs = new Set(r.json.messages.map((m) => m.seq));
  assert.equal(seqs.size, N, 'seq 出现重复');
});

test('公告读写', async () => {
  const empty = await req('GET', '/api/chat/announcement');
  assert.equal(empty.status, 200);
  assert.equal(typeof empty.json.announcement.title, 'string');

  const saveJson = await req('POST', '/api/chat/announcement', {
    body: { title: '标题A', content: '内容A' },
  });
  assert.equal(saveJson.status, 200);
  assert.equal(saveJson.json.announcement.content, '内容A');

  // 看板页面用的是表单编码提交
  const saveForm = await req('POST', '/api/chat/announcement', {
    body: 'title=%E6%A0%87%E9%A2%98B&content=%E5%86%85%E5%AE%B9B',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  });
  assert.equal(saveForm.status, 200);
  assert.equal(saveForm.json.announcement.title, '标题B');

  const read = await req('GET', '/api/chat/announcement');
  assert.equal(read.json.announcement.content, '内容B');
});

test('POST /api/chat/leave 退出频道', async () => {
  const r = await req('POST', '/api/chat/leave', { body: { channelId, deviceId: DEVICE_B } });
  assert.equal(r.status, 200);
  const detail = await req('GET', `/api/chat/channel?id=${channelId}&deviceId=${DEVICE_B}`);
  assert.equal(detail.json.channel.members.length, 1);
  assert.equal(detail.json.channel.joined, false);
});

test('POST /api/chat/delete 非创建者返回 403，创建者可删', async () => {
  const forbidden = await req('POST', '/api/chat/delete', { body: { channelId, deviceId: DEVICE_B } });
  assert.equal(forbidden.status, 403);

  const ok = await req('POST', '/api/chat/delete', { body: { channelId, deviceId: DEVICE_A } });
  assert.equal(ok.status, 200);

  const gone = await req('GET', `/api/chat/channel?id=${channelId}&deviceId=${DEVICE_A}`);
  assert.equal(gone.status, 404);
});

/* ==================== 脚本上传 ==================== */

const XML_BODY = '<?xml version="1.0"?><map><string name="macro_测试">[]</string></map>';

test('POST /api/upload 保存脚本', async () => {
  const r = await req('POST', '/api/upload', {
    body: XML_BODY,
    headers: {
      'Content-Type': 'application/xml',
      'X-Device-Id': DEVICE_A,
      'X-Device-Name': 'Google Pixel 8',
      'X-Filename': 'slide_settings.xml',
    },
  });
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.saved, 'slide_settings.xml');
  assert.ok(fs.existsSync(path.join(dataDir, 'uploads', DEVICE_A, 'slide_settings.xml')));
});

test('GET /api/uploads 返回清单', async () => {
  const r = await req('GET', '/api/uploads');
  assert.equal(r.status, 200);
  assert.equal(r.json.files.length, 1);
  assert.equal(r.json.files[0].deviceId, DEVICE_A);
  assert.equal(r.json.files[0].size, Buffer.byteLength(XML_BODY));
});

test('GET /api/download 下载脚本原文', async () => {
  const r = await req('GET', `/api/download?deviceId=${DEVICE_A}&filename=slide_settings.xml`);
  assert.equal(r.status, 200);
  assert.equal(r.text, XML_BODY);
  assert.match(r.headers.get('content-disposition'), /attachment/);
});

test('GET /api/download 文件不存在返回 404', async () => {
  const r = await req('GET', `/api/download?deviceId=${DEVICE_A}&filename=nope.xml`);
  assert.equal(r.status, 404);
});

test('GET /api/download 缺参数返回 400', async () => {
  const r = await req('GET', '/api/download?deviceId=&filename=');
  assert.equal(r.status, 400);
});

/* ==================== 路径穿越防护 ==================== */

test('路径穿越：/view 不能读到数据目录外的文件', async () => {
  const r = await req('GET', '/view?deviceId=..&filename=chat.json');
  assert.notEqual(r.status, 200, '路径穿越成功，chat.json 被泄露');
  assert.ok(!r.text.includes('channels'), '响应体里出现了 chat.json 的内容');
});

test('路径穿越：/api/download 不能读到数据目录外的文件', async () => {
  const r = await req('GET', '/api/download?deviceId=..&filename=stats.json');
  assert.notEqual(r.status, 200, '路径穿越成功，stats.json 被泄露');
  assert.ok(!r.text.includes('install_count'), '响应体里出现了 stats.json 的内容');
});

test('路径穿越：/api/chat/image 不能读到数据目录外的文件', async () => {
  const r = await req('GET', '/api/chat/image?file=..%2F..%2Fstats.json');
  assert.notEqual(r.status, 200);
});

/* ==================== 请求体上限 ==================== */

test('超大请求体被拒绝且不挂起连接', async () => {
  // /api/report 上限 64KB，这里发 1MB
  const huge = JSON.stringify({ event: 'install', deviceId: 'x', pad: 'A'.repeat(1024 * 1024) });
  const r = await Promise.race([
    req('POST', '/api/report', { body: huge, headers: { 'Content-Type': 'application/json' } }).catch((e) => ({
      status: 'network-error',
      error: e,
    })),
    new Promise((resolve) => setTimeout(() => resolve({ status: 'timeout' }), 5000)),
  ]);
  assert.notEqual(r.status, 'timeout', '请求被静默挂起，readBody 没有 reject');
});

test('超大请求体后服务仍然可用', async () => {
  const r = await req('GET', '/api/stats');
  assert.equal(r.status, 200, '服务在处理超大请求后不可用');
});

/* ==================== HTML 页面 ==================== */

test('GET / 返回统计看板', async () => {
  const r = await req('GET', '/');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-type'), /text\/html/);
  assert.match(r.text, /统计后台/);
  assert.match(r.text, /Pixel 8/);
});

test('看板别名路径可访问', async () => {
  for (const p of ['/index.html', '/stats', '/stats/']) {
    const r = await req('GET', p);
    assert.equal(r.status, 200, `${p} 不可访问`);
  }
});

test('GET /uploads 返回文件列表页', async () => {
  const r = await req('GET', '/uploads');
  assert.equal(r.status, 200);
  assert.match(r.text, /脚本同步/);
  // deviceName 经 sanitize()，空格会被替换成下划线
  assert.match(r.text, /Google_Pixel_8/);
});

test('GET /view 返回脚本内容页且转义 HTML', async () => {
  const r = await req('GET', `/view?deviceId=${DEVICE_A}&filename=slide_settings.xml`);
  assert.equal(r.status, 200);
  assert.match(r.text, /脚本内容/);
  assert.match(r.text, /&lt;map&gt;/, 'XML 内容未被转义，存在 XSS 风险');
});

test('未知路径返回 404', async () => {
  const r = await req('GET', '/nope');
  assert.equal(r.status, 404);
});

/* ==================== 数据文件完整性 ==================== */

test('落盘的 JSON 始终可解析', async () => {
  for (const f of ['stats.json', 'chat.json', path.join('uploads', 'index.json')]) {
    const p = path.join(dataDir, f);
    if (!fs.existsSync(p)) continue;
    assert.doesNotThrow(() => JSON.parse(fs.readFileSync(p, 'utf8')), `${f} 不是合法 JSON`);
  }
});

test('不残留写入临时文件', async () => {
  const leftovers = fs.readdirSync(dataDir).filter((f) => f.includes('.tmp'));
  assert.deepEqual(leftovers, [], `残留临时文件: ${leftovers.join(', ')}`);
});

// 模拟「进程在写 stats.json 到一半时被 systemd 重启」留下的截断文件。
// 旧行为是 catch 掉解析错误、返回全新空对象，下一次写入就把历史统计永久抹掉。
// 期望行为：损坏文件被备份保留，人工可恢复。
test('stats.json 损坏时保留备份而非静默丢弃', async () => {
  const statsFile = path.join(dataDir, 'stats.json');
  const before = JSON.parse(fs.readFileSync(statsFile, 'utf8'));
  assert.ok(before.devices.length > 0, '前置条件：应已有设备数据');

  // 截断成半截 JSON
  fs.writeFileSync(statsFile, '{"install_count": 5, "devices": [{"deviceId": "half');

  // 触发一次写入
  const r = await req('POST', '/api/report', {
    body: { event: 'install', deviceId: 'after-corrupt', device: { model: 'Z', brand: 'Z' } },
  });
  assert.equal(r.status, 200);

  const backups = fs.readdirSync(dataDir).filter((f) => f.startsWith('stats.json.corrupt-'));
  assert.ok(backups.length > 0, '损坏的 stats.json 被静默丢弃，没有留下备份');

  // 备份的就是那份截断内容（本身不是合法 JSON），只要不为空即可人工抢救
  const backupText = fs.readFileSync(path.join(dataDir, backups[0]), 'utf8');
  assert.ok(backupText.includes('half'), '备份文件内容不是损坏前的原文');

  // 同时：新的 stats.json 必须是合法 JSON，服务已恢复正常
  const fresh = JSON.parse(fs.readFileSync(statsFile, 'utf8'));
  assert.ok(fresh.devices.some((d) => d.deviceId === 'after-corrupt'));
});
