import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.js';
import { createApp } from '../src/app.js';

async function setup(t) {
  const dir = mkdtempSync(join(tmpdir(), 'watch-http-'));
  const store = new Store(join(dir, 'state'), 'cd'.repeat(32));
  let time = 1000000, exchanged = 0;
  const client = { authorizeUrl: state => `https://accounts.example.test/?state=${state}`,
    exchange: async () => { exchanged++; return { accessToken: 'fixture-access', refreshToken: 'fixture-refresh', expiresAt: time + 3600000 }; },
    agenda: async () => ({ syncedAt: time, validUntil: time + 86400000, events: [] }) };
  const server = createServer(createApp({ store, client, publicUrl: 'http://localhost', now: () => time }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  t.after(async () => { await new Promise(resolve => server.close(resolve)); rmSync(dir, { recursive: true, force: true }); });
  const request = (path, options) => fetch(base + path, { redirect: 'manual', ...options });
  async function pair() { return (await request('/v1/pair', { method: 'POST' })).json(); }
  async function authorize(pair) {
    const response = await request(`/auth/start/${pair.pairId}`);
    const state = new URL(response.headers.get('location')).searchParams.get('state');
    const cookie = response.headers.get('set-cookie').split(';')[0];
    return { state, cookie };
  }
  return { request, pair, authorize, store, advance: ms => time += ms, exchanged: () => exchanged };
}
test('pairing_requires_browser_state_and_poll_secret_then_retries_safely', async t => {
  const app = await setup(t), pair = await app.pair();
  assert.equal(pair.pollToken.length, 43); assert.ok(!pair.verificationUrl.includes(pair.pollToken));
  const landing = await app.request(`/pair/${pair.pairId}`); assert.ok((await landing.text()).includes(pair.pairingCode));
  const auth = await app.authorize(pair);
  const callback = `/oauth/callback?state=${auth.state}&code=fixture`;
  assert.equal((await app.request(callback)).status, 400); assert.equal(app.exchanged(), 0);
  assert.equal((await app.request(callback, { headers: { cookie: auth.cookie } })).status, 200);
  assert.equal((await app.request(callback, { headers: { cookie: auth.cookie } })).status, 400); assert.equal(app.exchanged(), 1);
  assert.equal((await app.request('/v1/pair/poll', { method: 'POST' })).status, 401);
  const headers = { Authorization: `Bearer ${pair.pollToken}` };
  const first = await (await app.request('/v1/pair/poll', { method: 'POST', headers })).json();
  const retry = await (await app.request('/v1/pair/poll', { method: 'POST', headers })).json();
  assert.equal(first.status, 'connected'); assert.equal(first.deviceToken, retry.deviceToken);
  const deviceHeaders = { Authorization: `Bearer ${first.deviceToken}` };
  assert.equal((await app.request('/v1/agenda')).status, 401);
  assert.deepEqual((await (await app.request('/v1/agenda', { headers: deviceHeaders })).json()).events, []);
  assert.equal((await app.request('/v1/device', { method: 'DELETE', headers: deviceHeaders })).status, 200);
  assert.equal((await app.request('/v1/agenda', { headers: deviceHeaders })).status, 401);
  assert.equal((await app.request('/v1/pair/poll', { method: 'POST', headers })).status, 401);
});
test('expired_pair_and_oauth_state_cannot_be_used', async t => {
  const app = await setup(t), pair = await app.pair(), auth = await app.authorize(pair);
  app.advance(600000);
  assert.equal((await app.request(`/pair/${pair.pairId}`)).status, 410);
  assert.equal((await app.request(`/oauth/callback?state=${auth.state}&code=fixture`, { headers: { cookie: auth.cookie } })).status, 400);
  assert.equal(app.exchanged(), 0);
});
test('oauth_cancel_and_rebinding_do_not_leak_or_replace_connected_credentials', async t => {
  const app = await setup(t), pair = await app.pair(), auth = await app.authorize(pair);
  assert.equal((await app.request(`/oauth/callback?state=${auth.state}&error=access_denied`, { headers: { cookie: auth.cookie } })).status, 400);
  assert.equal(Object.keys(app.store.data.devices).length, 0);
  const again = await app.authorize(pair);
  const done = await app.request(`/oauth/callback?state=${again.state}&code=fixture`, { headers: { cookie: again.cookie } });
  const html = await done.text(); assert.equal(html.includes('fixture-access'), false); assert.equal(html.includes('fixture-refresh'), false);
  assert.equal((await app.request(`/auth/start/${pair.pairId}`)).status, 410);
});
test('pair_creation_is_rate_limited_and_bodies_are_rejected', async t => {
  const app = await setup(t);
  assert.equal((await app.request('/v1/pair', { method: 'POST', body: 'unexpected' })).status, 400);
  for (let i = 0; i < 10; i++) assert.equal((await app.request('/v1/pair', { method: 'POST' })).status, 201);
  assert.equal((await app.request('/v1/pair', { method: 'POST' })).status, 429);
});
