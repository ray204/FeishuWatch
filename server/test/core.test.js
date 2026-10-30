import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, statSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../src/store.js';
import { FeishuClient, AgendaService, ApiError, normalizeEvents } from '../src/feishu.js';

function makeStore(t) {
  const dir = mkdtempSync(join(tmpdir(), 'feishu-watch-')); t.after(() => rmSync(dir, { recursive: true, force: true }));
  return new Store(join(dir, 'state.enc'), 'ab'.repeat(32));
}
const fixture = (id, changes = {}) => ({ event_id: id, summary: '示例评审', start_time: { timestamp: '2000000000' }, end_time: { timestamp: '2000001800' }, ...changes });

test('store_encrypts_credentials_and_detects_tampering', t => {
  const store = makeStore(t); store.data.devices.watch = { refreshToken: 'secret-fixture' }; store.save();
  assert.equal(readFileSync(store.path, 'utf8').includes('secret-fixture'), false);
  assert.equal(statSync(store.path).mode & 0o777, 0o600);
  assert.equal(new Store(store.path, 'ab'.repeat(32)).data.devices.watch.refreshToken, 'secret-fixture');
  const blob = JSON.parse(readFileSync(store.path)); blob.tag = Buffer.alloc(16).toString('base64'); writeFileSync(store.path, JSON.stringify(blob));
  assert.throws(() => new Store(store.path, 'ab'.repeat(32)), /Cannot decrypt/);
});
test('normalization_filters_cancelled_declined_and_removed_instances', () => {
  assert.deepEqual(normalizeEvents([
    fixture('cancelled', { status: 'cancelled' }), fixture('declined', { rsvp_status: 'decline' }),
    fixture('removed', { rsvp_status: 'removed' }), fixture('accepted', { rsvp_status: 'accept', location: { name: '会议室 A' } }),
  ]), [{ id: 'accepted', title: '示例评审', startMs: 2000000000000, endMs: 2000001800000, allDay: false, startDate: '', endDate: '', location: '会议室 A' }]);
});
test('normalization_preserves_all_day_dates_and_instance_identity', () => {
  const rows = normalizeEvents([
    fixture('weekly_2000000000'), fixture('weekly_2000000000', { summary: '改名' }),
    fixture('day', { start_time: { date: '2033-05-18' }, end_time: { date: '2033-05-18' } }),
  ]);
  assert.equal(rows.length, 2); assert.equal(rows[0].allDay, true); assert.equal(rows[0].startDate, '2033-05-18');
  assert.equal(rows[0].startMs, 0); assert.equal(rows[1].title, '改名');
});
test('invalid_calendar_payload_fails_instead_of_erasing_cache', () => {
  for (const input of [undefined, {}, [fixture('bad', { start_time: {} })], [fixture('', {})], [fixture('bad', { end_time: { timestamp: '1' } })]])
    assert.throws(() => normalizeEvents(input), ApiError);
  assert.deepEqual(normalizeEvents([]), []);
});
test('feishu_client_uses_user_primary_calendar_and_instance_view', async () => {
  const requests = [];
  const client = new FeishuClient({ appId: 'fixture', appSecret: 'fixture', now: () => 2000000000000, fetcher: async (url, init) => {
    requests.push({ url, init });
    return { ok: true, json: async () => url.endsWith('/primary') ? { code: 0, data: { calendars: [{ calendar: { calendar_id: 'a/b' } }] } } : { code: 0, data: { items: [fixture('instance')] } } };
  } });
  const result = await client.agenda('user-fixture');
  assert.equal(requests[0].init.method, 'POST'); assert.equal(requests[0].init.headers.Authorization, 'Bearer user-fixture');
  assert.match(requests[1].url, /a%2Fb\/events\/instance_view/);
  assert.equal(new URL(requests[1].url).searchParams.get('start_time'), '1999913600');
  assert.equal(result.validUntil, 2000086400000); assert.equal(result.events[0].id, 'instance');
});
test('oauth_requests_offline_scope_and_requires_rotating_refresh_token', async () => {
  const client = new FeishuClient({ appId: 'id', appSecret: 'secret', redirectUri: 'https://example.test/oauth/callback', now: () => 100,
    fetcher: async (_url, init) => {
      assert.equal(JSON.parse(init.body).grant_type, 'authorization_code');
      return { ok: true, json: async () => ({ access_token: 'a', expires_in: 3600 }) };
    } });
  const url = new URL(client.authorizeUrl('state'));
  assert.ok(url.searchParams.get('scope').split(' ').includes('offline_access')); assert.equal(url.searchParams.get('state'), 'state');
  await assert.rejects(client.exchange('code'), e => e.code === 'offline_access_required');
});
test('refresh_is_serialized_and_rotated_credentials_survive_restart', async t => {
  const store = makeStore(t); store.data.devices.watch = { accessToken: 'old-a', refreshToken: 'old-r', expiresAt: 0 };
  let refreshes = 0, fetches = 0;
  const client = {
    refresh: async old => { assert.equal(old, 'old-r'); refreshes++; await new Promise(resolve => setTimeout(resolve, 10)); return { accessToken: 'new-a', refreshToken: 'new-r', expiresAt: 9999999 }; },
    agenda: async access => { assert.equal(access, 'new-a'); fetches++; return { syncedAt: 1000, validUntil: 86401000, events: [] }; },
  };
  const service = new AgendaService(store, client, () => 1000);
  const [a, b] = await Promise.all([service.get('watch'), service.get('watch')]);
  assert.deepEqual(a, b); assert.equal(refreshes, 1); assert.equal(fetches, 1);
  const disk = new Store(store.path, 'ab'.repeat(32)); assert.equal(disk.data.devices.watch.refreshToken, 'new-r'); assert.equal(disk.data.devices.watch.reauthorize, false);
  await service.get('watch'); assert.equal(fetches, 1);
});
test('ambiguous_refresh_is_not_replayed_and_previous_snapshot_survives', async t => {
  const store = makeStore(t); const snapshot = { syncedAt: 1, events: [{ id: 'old' }] };
  store.data.devices.watch = { refreshToken: 'single-use', expiresAt: 0, snapshot };
  let calls = 0;
  const service = new AgendaService(store, { refresh: async () => { calls++; throw new ApiError(502, 'feishu_unreachable'); } }, () => 100000);
  await assert.rejects(service.get('watch'), e => e.code === 'feishu_unreachable');
  await assert.rejects(service.get('watch'), e => e.code === 'reauthorize');
  assert.equal(calls, 1); assert.deepEqual(store.data.devices.watch.snapshot, snapshot);
  assert.equal(new Store(store.path, 'ab'.repeat(32)).data.devices.watch.reauthorize, true);
});
test('failed_sync_keeps_snapshot_and_successful_empty_sync_removes_events', async t => {
  const store = makeStore(t); const old = { syncedAt: 1, events: [{ id: 'cancelled-later' }] };
  store.data.devices.watch = { accessToken: 'a', expiresAt: 9999999, snapshot: old };
  const client = { agenda: async () => { throw new ApiError(502, 'unreachable'); } };
  const service = new AgendaService(store, client, () => 100000);
  await assert.rejects(service.get('watch')); assert.deepEqual(store.data.devices.watch.snapshot, old);
  client.agenda = async () => ({ syncedAt: 100000, validUntil: 86500000, events: [] });
  assert.deepEqual((await service.get('watch')).events, []); assert.deepEqual(store.data.devices.watch.snapshot.events, []);
});
test('revocation_during_sync_does_not_restore_device', async t => {
  const store = makeStore(t); store.data.devices.watch = { accessToken: 'a', expiresAt: 9999999 };
  const service = new AgendaService(store, { agenda: async () => { delete store.data.devices.watch; return { events: [] }; } }, () => 1000);
  await assert.rejects(service.get('watch'), e => e.code === 'device_revoked'); assert.equal(store.data.devices.watch, undefined);
});
