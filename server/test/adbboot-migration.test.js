'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { once } = require('node:events');
const WebSocket = require('ws');

test('adbboot migration over real HTTP and WebSocket connections', { timeout: 20000 }, async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'autoslide-migration-'));
  process.env.AUTOSLIDE_DATA_DIR = dir;
  process.env.AUTOSLIDE_ADMIN_TOKEN = 'test-admin';
  process.env.AUTOSLIDE_REPORT_TOKEN = 'test-report';
  const { createRouter } = require('../src/http');
  const { readStats, updateStats } = require('../src/stats');
  const { trackIdFor, resolveDevice } = require('../src/identity');
  const router = createRouter();
  require('../src/commands').register(router);
  require('../src/track').register(router);
  require('../src/relay').register(router);
  const server = http.createServer((req, res) => router.handle(req, res));
  require('../src/relay').attach(server);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const connections = [];
  t.after(async () => {
    connections.forEach((ws) => ws.terminate());
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    const relative = path.relative(os.tmpdir(), dir);
    assert.ok(relative.startsWith('autoslide-migration-') && !relative.includes(path.sep));
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const post = (route, body, token = 'test-report') => fetch(base + route, {
    method: 'POST', headers: { 'content-type': 'application/json', 'x-report-token': token }, body: JSON.stringify(body),
  });
  const getTrack = async (query) => {
    const response = await fetch(base + '/api/track?' + query, { headers: { 'x-admin-token': 'test-admin' } });
    assert.equal(response.status, 200);
    return response.json();
  };
  const now = Date.now();
  const point = (offset) => ({ lat: 31 + offset / 10000, lon: 121, time: now + offset, accuracy: 8 });
  const connect = async (suffix) => {
    const ws = new WebSocket(base.replace('http:', 'ws:') + suffix);
    connections.push(ws);
    await once(ws, 'open');
    return ws;
  };

  await t.test('existing device keeps history and one identity after adbboot takes over', async () => {
    await updateStats((stats) => stats.devices.push({ deviceId: 'legacy_android_id', ecSerial: 'OLD001', deviceInfo: {}, installs: 1 }));
    assert.equal((await post('/api/track/batch', { deviceId: 'legacy_android_id', points: [point(1)] })).status, 200);
    assert.equal((await post('/api/report/network', { serial: 'OLD001', agent: 'adbboot', port: 5555, addresses: ['192.168.1.50'] })).status, 200);
    assert.equal((await post('/api/track/batch', { serial: 'OLD001', agent: 'adbboot', points: [point(2)] })).status, 200);
    const result = await getTrack('serial=OLD001');
    assert.equal(result.count, 2);
    assert.equal(result.deviceId, 'legacy_android_id');
    assert.equal(readStats().devices.filter((d) => d.ecSerial === 'OLD001').length, 1);
    // An old network watcher may still run during a rolling upgrade; it cannot replace new addresses.
    await post('/api/report/network', { deviceId: 'legacy_android_id', serial: 'OLD001', addresses: ['192.168.1.99'] });
    assert.deepEqual(readStats().devices.find((d) => d.ecSerial === 'OLD001').deviceInfo.network.addresses, ['192.168.1.50']);
    // Old pending points can still drain without installing or running AutoSlide on new devices.
    await post('/api/track/batch', { deviceId: 'legacy_android_id', points: [point(0)] });
    assert.equal((await getTrack('serial=OLD001')).count, 3);
  });

  await t.test('adbboot-only device can upload first, deduplicate retries and appear on the map', async () => {
    for (let i = 0; i < 2; i++) {
      assert.equal((await post('/api/track/batch', { serial: 'NEW:phone.1', agent: 'adbboot', points: [point(3)] })).status, 200);
    }
    const result = await getTrack('serial=NEW%3Aphone.1');
    assert.equal(result.count, 1);
    assert.equal(result.deviceId, 'serial:NEW:phone.1');
    assert.equal((await getTrack('deviceId=' + encodeURIComponent(result.deviceId))).count, 1);
    const device = readStats().devices.find((d) => d.ecSerial === 'NEW:phone.1');
    assert.match(trackIdFor(device), /^[A-Za-z0-9_-]+$/);
    assert.ok(device.location);
  });

  await t.test('merging an old shadow identity retains both track files and ownership', async () => {
    await post('/api/report/network', { serial: 'MERGE', agent: 'adbboot', addresses: ['192.168.1.2'] });
    await post('/api/track/batch', { serial: 'MERGE', points: [point(4)] });
    await updateStats((stats) => stats.devices.push({ deviceId: 'second_legacy_id', installs: 1 }));
    await post('/api/track/batch', { deviceId: 'second_legacy_id', points: [point(5)] });
    await post('/api/report/network', { serial: 'MERGE', deviceId: 'second_legacy_id', addresses: ['192.168.1.99'] });
    assert.equal((await getTrack('serial=MERGE')).count, 2);
    assert.equal(readStats().devices.find((d) => d.ecSerial === 'MERGE').agentOwner, 'adbboot');
    assert.deepEqual(readStats().devices.find((d) => d.ecSerial === 'MERGE').deviceInfo.network.addresses, ['192.168.1.2']);
  });

  await t.test('malformed IDs and missing credentials are rejected', async () => {
    assert.equal((await post('/api/track/batch', { serial: '../../bad', points: [point(6)] })).status, 400);
    assert.equal((await post('/api/track/batch', { serial: 'NOAUTH', points: [point(6)] }, 'wrong')).status, 401);
    assert.equal((await fetch(base + '/api/track?serial=OLD001')).status, 403);
    const before = readStats().devices.length;
    assert.equal((await post('/api/track/batch', { serial: 'EMPTY', points: [] })).status, 400);
    assert.equal(readStats().devices.length, before);
  });

  await t.test('adbboot replaces old relay, receives poke and carries binary traffic both ways', async () => {
    const old = await connect('/relay/control?serial=RELAY&token=test-report');
    const oldClosed = once(old, 'close');
    const agent = await connect('/relay/control?serial=RELAY&token=test-report&agent=adbboot');
    await oldClosed;
    const blocked = new WebSocket(base.replace('http:', 'ws:') + '/relay/control?serial=RELAY&token=test-report');
    connections.push(blocked);
    const [code] = await once(blocked, 'close');
    assert.equal(code, 4009);
    const poke = once(agent, 'message');
    const pokeReply = await (await fetch(base + '/relay/tcpip?serial=RELAY&token=test-admin')).json();
    assert.equal(pokeReply.delivered, true);
    assert.equal(JSON.parse((await poke)[0]).type, 'poke');
    const open = once(agent, 'message');
    const session = await (await fetch(base + '/relay/open?serial=RELAY&token=test-admin')).json();
    assert.equal(JSON.parse((await open)[0]).session, session.session);
    const left = await connect(`/relay/data?session=${session.session}&side=client`);
    const right = await connect(`/relay/data?session=${session.session}&side=agent`);
    const receivedRight = once(right, 'message');
    left.send(Buffer.from([0, 255, 1, 128]));
    assert.deepEqual((await receivedRight)[0], Buffer.from([0, 255, 1, 128]));
    const receivedLeft = once(left, 'message');
    right.send(Buffer.from('ADB reply'));
    assert.equal((await receivedLeft)[0].toString(), 'ADB reply');
    left.close(); right.close();
  });
});
