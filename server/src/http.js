// HTTP 基础设施：请求体读取、响应封装、客户端 IP、路由表

const { LIMIT_JSON_BODY } = require('./config');

/**
 * 读取请求体，超过上限立刻中断。
 *
 * 超限时必须显式 reject：只 destroy 不 reject 的话 'end' 不再触发，
 * 这个 Promise 会永远挂起，上游的 await 不返回，连接与内存都释放不掉。
 */
function readBody(req, maxBytes = LIMIT_JSON_BODY) {
  return new Promise((resolve, reject) => {
    let body = '';
    let settled = false;
    const fail = (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    };
    req.on('data', (c) => {
      if (settled) return;
      body += c;
      if (body.length > maxBytes) {
        fail(new Error(`request body too large (> ${maxBytes} bytes)`));
        req.destroy();
      }
    });
    req.on('end', () => {
      if (!settled) {
        settled = true;
        resolve(body);
      }
    });
    req.on('error', fail);
    req.on('aborted', () => fail(new Error('request aborted')));
  });
}

/** 读取并解析 JSON 请求体，空体按 {} 处理 */
async function readJson(req, maxBytes) {
  return JSON.parse((await readBody(req, maxBytes)) || '{}');
}

/**
 * 读取表单或 JSON 请求体，返回统一的取值函数。
 * 看板页面的公告表单是 urlencoded，App 侧是 JSON，两边共用一个接口。
 */
async function readFields(req, maxBytes) {
  const raw = await readBody(req, maxBytes);
  if (String(req.headers['content-type'] || '').includes('application/json')) {
    const obj = JSON.parse(raw || '{}');
    return (key) => obj[key];
  }
  const params = new URLSearchParams(raw);
  return (key) => params.get(key);
}

function sendJson(res, code, obj) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(JSON.stringify(obj, null, 2));
}

function sendHtml(res, html) {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
}

function sendText(res, code, text) {
  res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end(text);
}

/* 客户端真实 IP：优先 Cloudflare 透传，其次反代链，最后 socket */
function clientIp(req) {
  return String(
    req.headers['cf-connecting-ip'] ||
      String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() ||
      req.socket.remoteAddress ||
      ''
  ).slice(0, 64);
}

/**
 * 极简路由表：把「12 个连续 if」换成一次 Map 查找，
 * 并把每个 handler 里重复的 try/catch 收到一处。
 *
 * handler 签名 (req, res, url)；抛出的异常带 status 就用它，否则按 400
 * （与重构前每个 handler 自己 catch 后返回 400 的行为一致）。
 */
function createRouter() {
  const routes = new Map();
  const key = (method, pathname) => `${method} ${pathname}`;

  return {
    on(method, pathname, handler) {
      routes.set(key(method, pathname), handler);
      return this;
    },

    /* 给同一个 handler 挂多个路径（如看板的 / 、/index.html 、/stats） */
    alias(method, from, to) {
      const handler = routes.get(key(method, to));
      if (!handler) throw new Error(`alias target not registered: ${key(method, to)}`);
      routes.set(key(method, from), handler);
      return this;
    },

    async handle(req, res) {
      const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
      const handler = routes.get(key(req.method, url.pathname));
      if (!handler) return sendText(res, 404, 'Not Found');
      try {
        await handler(req, res, url);
      } catch (e) {
        // 服务端留全量堆栈，对外只回一句话
        console.error(`[${req.method} ${url.pathname}]`, e);
        if (!res.headersSent) {
          sendJson(res, e.status || 400, { ok: false, error: String(e.message || e) });
        }
      }
    },
  };
}

module.exports = {
  readBody,
  readJson,
  readFields,
  sendJson,
  sendHtml,
  sendText,
  clientIp,
  createRouter,
};
