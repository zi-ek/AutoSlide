const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {once} = require('node:events');
const WebSocket = require('ws');

test('first-time pairing: authenticated join, progress, binary tunnels and cancellation', {timeout:20000}, async t => {
  process.env.AUTOSLIDE_REPORT_TOKEN = 'test-report';
  const {createRouter} = require('../src/http');
  const router = createRouter();
  const pair = require('../src/pair');
  pair.register(router);
  const server = http.createServer((req,res) => router.handle(req,res));
  // Production has both upgrade handlers; neither may destroy the other's socket.
  require('../src/relay').attach(server);
  pair.attach(server);
  server.listen(0, '127.0.0.1');
  await once(server,'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const sockets = [];
  t.after(async () => { sockets.forEach(ws => ws.terminate()); server.closeAllConnections(); await new Promise(r => server.close(r)); });
  const post = (path, body, headers={}) => fetch(base + '/api/pair/' + path, {method:'POST', headers:{'content-type':'application/json', ...headers}, body:JSON.stringify(body)});
  const alloc = await (await post('alloc',{protocol:2})).json();
  assert.equal((await post('join',{sid:alloc.sid})).status,401);
  assert.equal((await post('offer',{sid:alloc.sid,host:'192.168.1.1',pairPort:35001,connectPort:35002,code:'123456'},{'x-report-token':'test-report'})).status,409);
  assert.equal((await fetch(base + '/api/pair/fetch?sid=' + alloc.sid)).status,401);
  const report = {'x-report-token':'test-report'};
  const joined = await (await post('join',{sid:alloc.sid},report)).json();
  assert.ok(joined.agentKey);
  assert.equal((await post('join',{sid:alloc.sid},report)).status,409);
  const agentAuth = {'x-pair-key':joined.agentKey};
  const controllerAuth = {'x-pair-controller-key':alloc.resultKey};
  const pending = await fetch(base + '/api/pair/fetch?sid=' + alloc.sid,{headers:controllerAuth});
  assert.equal(pending.status,202);
  assert.equal((await pending.json()).online,true);
  const payload = {sid:alloc.sid,host:'192.168.1.50',pairPort:35001,connectPort:35002,code:'123456'};
  assert.equal((await post('offer',payload,report)).status,401);
  assert.equal((await post('offer',payload,{...report,...agentAuth})).status,200);
  assert.equal((await fetch(base + '/api/pair/fetch?sid=' + alloc.sid)).status,401);
  const offer = await (await fetch(base + '/api/pair/fetch?sid=' + alloc.sid,{headers:controllerAuth})).json();
  assert.equal(offer.relay,true);
  assert.equal(offer.resultKey,undefined);
  assert.equal((await post('open',{sid:alloc.sid,resultKey:'wrong',target:'pair'})).status,403);
  assert.equal((await post('open',{sid:alloc.sid,resultKey:alloc.resultKey,target:'22'})).status,400);
  const opened = await (await post('open',{sid:alloc.sid,resultKey:alloc.resultKey,target:'pair'})).json();
  const heartbeat = await (await post('agent',{offerId:joined.offerId,stage:'ready'},agentAuth)).json();
  assert.equal(heartbeat.tunnels.length,1);
  assert.equal(heartbeat.tunnels[0].target,'pair');
  const secondPoll = await (await post('agent',{offerId:joined.offerId},agentAuth)).json();
  assert.equal(secondPoll.tunnels.length,0);
  const wsUrl = (side,key) => base.replace('http:','ws:') + `/api/pair/tunnel?id=${opened.id}&side=${side}&key=${key}`;
  const bad = new WebSocket(wsUrl('client','wrong')); sockets.push(bad);
  await once(bad,'error');
  const client = new WebSocket(wsUrl('client',opened.key)); sockets.push(client); await once(client,'open');
  const packet = Buffer.from([0,255,1,0,128,42]);
  client.send(packet); // Must retain bytes sent before the agent opens its data channel.
  const agent = new WebSocket(wsUrl('agent',heartbeat.tunnels[0].key)); sockets.push(agent);
  const incoming = once(agent,'message'); await once(agent,'open');
  assert.deepEqual((await incoming)[0],packet);
  const reply = once(client,'message'); agent.send(Buffer.alloc(65536,239));
  assert.deepEqual((await reply)[0],Buffer.alloc(65536,239));
  assert.equal((await post('result',{sid:alloc.sid,resultKey:alloc.resultKey,state:'configuring'})).status,200);
  const afterConfig = once(client,'message'); agent.send(packet); assert.deepEqual((await afterConfig)[0],packet);
  // ADB helper installation pushes a multi-megabyte APK. Pausing the receiver
  // forces server-side backpressure; the relay must preserve every frame.
  const chunk = Buffer.alloc(32 * 1024, 0x5a);
  const chunks = 288; // 9 MiB
  const expected = chunk.length * chunks;
  let received = 0;
  const largeTransfer = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`relay stalled after ${received}/${expected} bytes`)), 12000);
    agent.on('message', data => {
      if (!data.equals(chunk)) { clearTimeout(timeout); reject(new Error('relay corrupted a chunk')); return; }
      received += data.length;
      if (received === expected) { clearTimeout(timeout); resolve(); }
    });
    agent.once('close', () => { clearTimeout(timeout); reject(new Error(`relay closed after ${received}/${expected} bytes`)); });
  });
  agent.pause();
  for (let i = 0; i < chunks; i++) client.send(chunk);
  setTimeout(() => agent.resume(), 250);
  await largeTransfer;
  assert.equal(received, expected);
  const closed = once(client,'close');
  assert.equal((await post('agent',{offerId:joined.offerId,cancel:true},agentAuth)).status,200);
  await closed;
  assert.equal((await post('open',{sid:alloc.sid,resultKey:alloc.resultKey,target:'connect'})).status,403);
  assert.equal((await post('result',{sid:alloc.sid,resultKey:alloc.resultKey,state:'paired'})).status,409);
});
