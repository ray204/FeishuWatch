export class ApiError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

export function normalizeEvents(items) {
  if (!Array.isArray(items)) throw new ApiError(502, 'invalid_calendar_response');
  const result = new Map();
  for (const item of items) {
    if (item.status === 'cancelled' || ['decline', 'removed'].includes(item.rsvp_status)) continue;
    const allDay = Boolean(item.start_time?.date);
    const startMs = allDay ? 0 : Number(item.start_time?.timestamp) * 1000;
    const endMs = allDay ? 0 : Number(item.end_time?.timestamp) * 1000;
    if (!item.event_id || (!allDay && (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs))) {
      throw new ApiError(502, 'invalid_calendar_event');
    }
    if (allDay && (!/^\d{4}-\d{2}-\d{2}$/.test(item.start_time.date) || !/^\d{4}-\d{2}-\d{2}$/.test(item.end_time?.date ?? ''))) {
      throw new ApiError(502, 'invalid_calendar_event');
    }
    result.set(item.event_id, {
      id: item.event_id, title: String(item.summary || '未命名日程').slice(0, 500),
      startMs, endMs, allDay, startDate: item.start_time?.date || '', endDate: item.end_time?.date || '',
      location: String(item.location?.name || '').slice(0, 500),
    });
  }
  return [...result.values()].sort((a, b) => a.startMs - b.startMs || a.id.localeCompare(b.id));
}

export class FeishuClient {
  constructor({ appId, appSecret, redirectUri, fetcher = fetch, now = Date.now }) {
    Object.assign(this, { appId, appSecret, redirectUri, fetcher, now });
  }
  authorizeUrl(state) {
    const url = new URL('https://accounts.feishu.cn/open-apis/authen/v1/authorize');
    url.search = new URLSearchParams({ client_id: this.appId, response_type: 'code', redirect_uri: this.redirectUri,
      scope: 'calendar:calendar:read calendar:calendar.event:read offline_access', state });
    return url.toString();
  }
  async request(path, options = {}) {
    let response;
    try {
      response = await this.fetcher(`https://open.feishu.cn/open-apis${path}`, { ...options, signal: AbortSignal.timeout(15000), redirect: 'error' });
    } catch { throw new ApiError(502, 'feishu_unreachable'); }
    let body;
    try { body = await response.json(); } catch { throw new ApiError(502, 'invalid_feishu_response'); }
    if (!response.ok || (body.code !== undefined && body.code !== 0)) {
      if ([20024, 20037, 20064, 20003, 99991663, 99991668].includes(body.code) || response.status === 401) throw new ApiError(401, 'reauthorize');
      if (response.status === 403 || body.code === 99991672) throw new ApiError(403, 'calendar_permission_required');
      throw new ApiError(502, 'feishu_api_error');
    }
    return body;
  }
  async tokens(grant) {
    const body = await this.request('/authen/v2/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ client_id: this.appId, client_secret: this.appSecret, ...grant }) });
    if (!body.access_token || !body.refresh_token || !Number.isFinite(body.expires_in) || body.expires_in <= 0) throw new ApiError(401, 'offline_access_required');
    return { accessToken: body.access_token, refreshToken: body.refresh_token, expiresAt: this.now() + body.expires_in * 1000 };
  }
  exchange(code) { return this.tokens({ grant_type: 'authorization_code', code, redirect_uri: this.redirectUri }); }
  refresh(token) { return this.tokens({ grant_type: 'refresh_token', refresh_token: token }); }
  async agenda(accessToken) {
    const headers = { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' };
    const primary = await this.request('/calendar/v4/calendars/primary', { method: 'POST', headers, body: '{}' });
    const calendarId = primary.data?.calendars?.[0]?.calendar?.calendar_id;
    if (!calendarId) throw new ApiError(403, 'primary_calendar_unavailable');
    const now = this.now();
    const query = new URLSearchParams({ start_time: String(Math.floor(now / 1000) - 86400), end_time: String(Math.floor(now / 1000) + 7 * 86400) });
    const body = await this.request(`/calendar/v4/calendars/${encodeURIComponent(calendarId)}/events/instance_view?${query}`, { headers });
    return { syncedAt: now, validUntil: now + 24 * 3600_000, events: normalizeEvents(body.data?.items) };
  }
}

export class AgendaService {
  constructor(store, client, now = Date.now) { Object.assign(this, { store, client, now }); this.running = new Map(); }
  get(deviceKey) {
    if (this.running.has(deviceKey)) return this.running.get(deviceKey);
    const task = this.load(deviceKey).finally(() => this.running.delete(deviceKey));
    this.running.set(deviceKey, task);
    return task;
  }
  async load(key) {
    const device = this.store.data.devices[key];
    if (!device) throw new ApiError(401, 'device_revoked');
    if (device.reauthorize) throw new ApiError(401, 'reauthorize');
    if (device.snapshot && this.now() - device.snapshot.syncedAt < 60000) return device.snapshot;
    if (device.expiresAt <= this.now() + 60000) {
      try {
        // Persist ambiguity marker before using a single-use refresh token. A crash or
        // lost response requires reauth; it must never blindly replay the old token.
        device.reauthorize = true;
        this.store.save();
        const refreshed = await this.client.refresh(device.refreshToken);
        if (this.store.data.devices[key] !== device) throw new ApiError(401, 'device_revoked');
        Object.assign(device, refreshed, { reauthorize: false });
        this.store.save();
      } catch (error) { throw error; }
    }
    try {
      const snapshot = await this.client.agenda(device.accessToken);
      if (this.store.data.devices[key] !== device) throw new ApiError(401, 'device_revoked');
      device.snapshot = snapshot;
      this.store.save();
      return snapshot;
    } catch (error) {
      if (error.status === 401) { device.reauthorize = true; this.store.save(); }
      throw error;
    }
  }
}
