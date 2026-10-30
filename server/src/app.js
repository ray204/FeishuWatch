import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { ApiError, AgendaService } from './feishu.js';

export const hash = value => createHash('sha256').update(value).digest('hex');
const token = () => randomBytes(32).toString('base64url');
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
const page = (title, body) => `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>body{background:#10181c;color:#eef8f4;font:18px system-ui;max-width:460px;margin:12vh auto;padding:28px;line-height:1.7}a{display:block;background:#b9f68b;color:#10200d;border-radius:16px;padding:14px;text-align:center;text-decoration:none}small{color:#adbfbb}h1{font-size:30px}</style><h1>${title}</h1>${body}</html>`;

export function createApp({ store, client, publicUrl, now = Date.now }) {
  const agenda = new AgendaService(store, client, now);
  const limits = new Map();
  function cleanup() {
    for (const [key, value] of Object.entries(store.data.pairs)) {
      if (value.expiresAt <= now()) {
        if (value.deviceKey && !value.claimed) delete store.data.devices[value.deviceKey];
        delete store.data.pairs[key];
      }
    }
    for (const [key, value] of Object.entries(store.data.states)) if (value.expiresAt <= now()) delete store.data.states[key];
    for (const [key, value] of limits) if (value.until <= now()) limits.delete(key);
  }
  function throttle(key, max) {
    const entry = limits.get(key) || { count: 0, until: now() + 60000 };
    entry.count++; limits.set(key, entry);
    if (entry.count > max) throw new ApiError(429, 'rate_limited');
  }
  return async (req, res) => {
    const send = (status, body, html = false) => {
      res.writeHead(status, { 'Content-Type': html ? 'text/html; charset=utf-8' : 'application/json', 'Cache-Control': 'no-store',
        'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY',
        'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'" });
      res.end(html ? body : JSON.stringify(body));
    };
    try {
      cleanup();
      const url = new URL(req.url, publicUrl);
      const path = url.pathname;
      const bearer = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '')?.[1];
      // Reject request bodies: these APIs deliberately carry all inputs in path/auth.
      if (Number(req.headers['content-length'] || 0) > 0 || req.headers['transfer-encoding']) throw new ApiError(400, 'unexpected_body');
      if (req.method === 'GET' && path === '/healthz') return send(200, { ok: true });
      throttle(req.socket.remoteAddress, 240); // Reverse-proxy source; do not trust arbitrary XFF.
      if (req.method === 'POST' && path === '/v1/pair') {
        throttle(`pair:${req.socket.remoteAddress}`, 10);
        if (Object.keys(store.data.pairs).length >= 100) throw new ApiError(429, 'too_many_pending_pairs');
        const id = token(), pollToken = token();
        const pairingCode = randomBytes(3).toString('hex').toUpperCase();
        const expiresAt = now() + 600000;
        store.data.pairs[id] = { pollHash: hash(pollToken), pairingCode, expiresAt };
        store.save();
        return send(201, { pairId: id, pollToken, pairingCode, expiresAt, verificationUrl: `${publicUrl}/pair/${id}` });
      }
      if (req.method === 'GET' && path.startsWith('/pair/')) {
        const id = path.slice(6), pair = store.data.pairs[id];
        if (!pair) throw new ApiError(410, 'pair_expired');
        return send(200, page('连接腕上日程', `<p>请确认这是你手表显示的连接码：</p><h2>${pair.pairingCode}</h2><p>授权后，该手表会显示你的飞书日程并提醒。</p><a href="/auth/start/${id}">确认并连接飞书</a><p><small>只连接自己的手表。不需要安装手机 App。</small></p>`), true);
      }
      if (req.method === 'GET' && path.startsWith('/auth/start/')) {
        const id = path.slice(12), pair = store.data.pairs[id];
        if (!pair || pair.deviceKey) throw new ApiError(410, 'pair_expired');
        if (Object.keys(store.data.states).length >= 200) throw new ApiError(429, 'too_many_auth_requests');
        const state = token(), browser = token();
        store.data.states[hash(state)] = { pairId: id, browserHash: hash(browser), expiresAt: pair.expiresAt };
        store.save();
        res.writeHead(302, { Location: client.authorizeUrl(state), 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
          'Set-Cookie': `fw_auth=${browser}; Path=/oauth/callback; HttpOnly; SameSite=Lax; Max-Age=600${publicUrl.startsWith('https:') ? '; Secure' : ''}` });
        return res.end();
      }
      if (req.method === 'GET' && path === '/oauth/callback') {
        const stateKey = hash(url.searchParams.get('state') || '');
        const state = store.data.states[stateKey];
        const browser = /(?:^|;\s*)fw_auth=([A-Za-z0-9_-]+)/.exec(req.headers.cookie || '')?.[1];
        if (!state || !browser || !equal(state.browserHash, hash(browser))) throw new ApiError(400, 'invalid_oauth_state');
        const pair = store.data.pairs[state.pairId];
        if (!pair || pair.deviceKey) throw new ApiError(410, 'pair_expired');
        delete store.data.states[stateKey]; store.save();
        if (url.searchParams.has('error')) throw new ApiError(400, 'authorization_cancelled');
        const code = url.searchParams.get('code');
        if (!code || code.length > 4096) throw new ApiError(400, 'missing_code');
        const credentials = await client.exchange(code);
        if (pair.expiresAt <= now() || pair.deviceKey) throw new ApiError(410, 'pair_expired');
        const deviceToken = token(), deviceKey = hash(deviceToken);
        store.data.devices[deviceKey] = credentials;
        Object.assign(pair, { deviceToken, deviceKey }); store.save();
        return send(200, page('已连接', '<p>现在可以回到手表。日程将在同步后自动提醒。</p><small>无需保持此页面打开。</small>'), true);
      }
      if (req.method === 'POST' && path === '/v1/pair/poll') {
        const pair = bearer && Object.values(store.data.pairs).find(p => equal(p.pollHash, hash(bearer)));
        if (!pair) throw new ApiError(401, 'pair_expired');
        if (!pair.deviceKey) return send(200, { status: 'pending' });
        pair.claimed = true; store.save();
        return send(200, { status: 'connected', deviceToken: pair.deviceToken });
      }
      const deviceKey = bearer && hash(bearer);
      if (path === '/v1/agenda' || path === '/v1/device') {
        if (!deviceKey || !store.data.devices[deviceKey]) throw new ApiError(401, 'device_revoked');
        if (path === '/v1/agenda' && req.method === 'GET') return send(200, await agenda.get(deviceKey));
        if (path === '/v1/device' && req.method === 'DELETE') {
          delete store.data.devices[deviceKey];
          for (const [id, pair] of Object.entries(store.data.pairs)) if (pair.deviceKey === deviceKey) delete store.data.pairs[id];
          store.save(); return send(200, { revoked: true });
        }
      }
      throw new ApiError(404, 'not_found');
    } catch (error) {
      const status = error instanceof ApiError ? error.status : 500;
      const code = error instanceof ApiError ? error.code : 'internal_error';
      send(status, { error: code });
    }
  };
}
