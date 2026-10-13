// Temporary, consent-scoped transport for first-time pairing. No arbitrary destination ports.
const crypto = require('crypto');
const WebSocket = require('ws');
const { readFields, sendJson } = require('./http');

function createPairTunnel(getOffer, isExpired) {
  const HIGH_WATER_BYTES = 1024 * 1024;
  const LOW_WATER_BYTES = 512 * 1024;
  const tunnels = new Map();
  const token = () => crypto.randomBytes(24).toString('hex');
  const terminal = o => !o || ['paired', 'failed'].includes(o.state) || isExpired(o, Date.now());
  function close(t) {
    if (!t || !tunnels.delete(t.id)) return;
    clearTimeout(t.timer);
    for (const ws of [t.agent, t.client]) if (ws && typeof ws.close === 'function') ws.close(1000, 'session ended');
  }
  function revoke(offerId) {
    for (const t of tunnels.values()) if (t.offerId === offerId) close(t);
  }
  function poll(o) {
    o.agentSeenAt = Date.now();
    return [...tunnels.values()].filter(t => t.offerId === o.offerId && !t.delivered).map(t => {
      t.delivered = true;
      return { id: t.id, key: t.agentKey, target: t.target };
    });
  }
  function register(router) {
    router.on('POST', '/api/pair/open', async (req, res) => {
      const field = await readFields(req);
      const o = getOffer(String(field('sid') || ''));
      if (terminal(o) || o.resultKey !== field('resultKey')) return sendJson(res, 403, {ok:false,error:'会话无效，请重新生成链接'});
      const target = field('target');
      if (!['pair', 'connect', 'tcpip'].includes(target) || !o.pairPort || !o.connectPort) return sendJson(res, 400, {ok:false,error:'配对信息尚未就绪'});
      if (!o.agentKey || Date.now() - (o.agentSeenAt || 0) > 20000) return sendJson(res, 409, {ok:false,error:'引导端已离线，请返回引导程序重试'});
      if (tunnels.size >= 256 || [...tunnels.values()].filter(t => t.offerId === o.offerId).length >= 4) return sendJson(res, 429, {ok:false,error:'连接过多，请稍后重试'});
      const t = { id:token(), clientKey:token(), agentKey:token(), offerId:o.offerId, sid:o.sid, target, queue:[], queued:0 };
      t.timer = setTimeout(() => close(t), 20000);
      tunnels.set(t.id, t);
      sendJson(res, 200, {ok:true, id:t.id, key:t.clientKey});
    });
  }
  function attach(server) {
    const wss = new WebSocket.Server({noServer:true, maxPayload:256 * 1024});
    server.on('upgrade', (req, socket, head) => {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname !== '/api/pair/tunnel') return;
      const t = tunnels.get(url.searchParams.get('id'));
      const side = url.searchParams.get('side');
      const o = t && getOffer(t.sid);
      if (!t || terminal(o) || !['client','agent'].includes(side) || t[side] || url.searchParams.get('key') !== t[side + 'Key']) {
        socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return;
      }
      // Reserve the side before completing the upgrade.
      t[side] = true;
      wss.handleUpgrade(req, socket, head, ws => {
        t[side] = ws;
        const other = side === 'client' ? 'agent' : 'client';
        ws.on('message', (data, binary) => {
          if (!binary || terminal(getOffer(t.sid))) return close(t);
          const peer = t[other];
          if (peer && peer.readyState === WebSocket.OPEN) {
            peer.send(data, {binary:true}, error => {
              if (error) return close(t);
              if (ws.isPaused && peer.bufferedAmount <= LOW_WATER_BYTES) ws.resume();
            });
            if (peer.bufferedAmount > HIGH_WATER_BYTES) ws.pause();
          } else {
            t.queued += data.length;
            if (t.queued > 256 * 1024) return close(t);
            t.queue.push({side, data});
          }
        });
        ws.on('error', () => close(t));
        ws.on('close', () => close(t));
        if (t.client?.readyState === WebSocket.OPEN && t.agent?.readyState === WebSocket.OPEN) {
          clearTimeout(t.timer);
          t.timer = setTimeout(() => close(t), 30 * 60 * 1000);
          for (const item of t.queue) t[item.side === 'agent' ? 'client' : 'agent'].send(item.data, {binary:true});
          t.queue = []; t.queued = 0;
        }
      });
    });
    server.on('close', () => { for (const t of [...tunnels.values()]) close(t); wss.close(); });
  }
  return {register, attach, poll, revoke};
}
module.exports = {createPairTunnel};
