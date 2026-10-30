package io.github.ray204.feishuwatch;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import javax.net.ssl.HttpsURLConnection;

/** Personal-app credentials are provided at runtime. Never contains bundled secrets. */
public final class FeishuDirect {
    static final String ACCOUNTS = "https://accounts.feishu.cn";
    static final String OPEN = "https://open.feishu.cn/open-apis";
    static final String SCOPE = "calendar:calendar:read calendar:calendar.event:read offline_access";
    private static final Object LOCK = new Object();
    public interface State { JSONObject read() throws Exception; void save(JSONObject value) throws Exception; }
    public interface Transport { Response request(String url, String method, Map<String, String> headers, String body) throws Exception; }
    public static final class Response {
        final int status; final JSONObject body;
        public Response(int status, JSONObject body) { this.status = status; this.body = body; }
    }
    public static final class Reauthorize extends IOException {
        Reauthorize() { super("授权已失效，请重新扫码连接飞书"); }
    }
    private final State state; private final Transport transport; private final LongSupplier clock;
    public FeishuDirect(State state) { this(state, FeishuDirect::https, System::currentTimeMillis); }
    FeishuDirect(State state, Transport transport, LongSupplier clock) { this.state = state; this.transport = transport; this.clock = clock; }
    public void configure(String appId, String secret) throws Exception {
        synchronized (LOCK) {
            appId = appId.trim(); secret = secret.trim();
            if (!appId.matches("cli_[A-Za-z0-9_]{4,180}") || secret.isEmpty() || secret.length() > 1024)
                throw new IOException("请填写正确的 App ID 和 App Secret");
            state.save(new JSONObject().put("appId", appId).put("appSecret", secret));
        }
    }
    public void begin() throws Exception {
        synchronized (LOCK) {
            JSONObject old = state.read();
            JSONObject s = new JSONObject().put("appId", old.getString("appId")).put("appSecret", old.getString("appSecret"));
            // Abandon a previous code before requesting another; it cannot later replace this login.
            state.save(s);
            Map<String,String> headers = formHeaders();
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((s.getString("appId") + ":" + s.getString("appSecret")).getBytes(StandardCharsets.UTF_8)));
            Response response = transport.request(ACCOUNTS + "/oauth/v1/device_authorization", "POST", headers,
                form("client_id", s.getString("appId"), "scope", SCOPE));
            check(response);
            JSONObject b = response.body;
            String verify = b.optString("verification_uri_complete", b.optString("verification_uri"));
            URI uri = new URI(verify);
            if (!"https".equals(uri.getScheme()) || !"accounts.feishu.cn".equals(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1)
                throw new IOException("飞书返回了无法识别的授权地址");
            long expires = b.getLong("expires_in");
            if (expires <= 0 || expires > 3600 || b.getString("device_code").isEmpty() || b.getString("user_code").isEmpty()) throw new IOException("飞书授权响应不完整");
            int interval = Math.max(5, Math.min(60, b.optInt("interval", 5)));
            s.put("deviceCode", b.getString("device_code")).put("pairUrl", verify).put("pairCode", b.getString("user_code"))
                .put("pairExpires", clock.getAsLong() + expires * 1000).put("interval", interval).put("nextPoll", clock.getAsLong() + interval * 1000L);
            state.save(s);
        }
    }
    public boolean poll() throws Exception {
        synchronized (LOCK) {
            JSONObject s = state.read(); long now = clock.getAsLong();
            if (s.optLong("pairExpires") <= now) { clearPair(s); state.save(s); throw new IOException("二维码已过期，请重新扫码"); }
            if (now < s.optLong("nextPoll")) return false;
            int interval = s.optInt("interval", 5);
            // Persist the next attempt before I/O, including network failures and process restarts.
            s.put("nextPoll", now + interval * 1000L); state.save(s);
            Response response = transport.request(OPEN + "/authen/v2/oauth/token", "POST", formHeaders(),
                form("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", s.getString("appId"),
                    "client_secret", s.getString("appSecret"), "device_code", s.getString("deviceCode")));
            String error = response.body.optString("error"); int code = response.body.optInt("code");
            if ("authorization_pending".equals(error) || code == 20094) return false;
            if ("slow_down".equals(error)) {
                interval = Math.min(60, interval + 5); s.put("interval", interval).put("nextPoll", now + interval * 1000L); state.save(s); return false;
            }
            if ("access_denied".equals(error) || "expired_token".equals(error) || "invalid_grant".equals(error)) {
                String detail = response.body.optString("error_description", response.body.optString("msg"));
                for (String key : new String[]{"appSecret", "deviceCode", "accessToken", "refreshToken", "pairCode"}) {
                    String value = s.optString(key); if (!value.isEmpty()) detail = detail.replace(value, "[redacted]");
                }
                clearPair(s); s.put("lastAuthError", error).put("lastAuthDetail", detail.substring(0, Math.min(300, detail.length()))); state.save(s);
                throw new IOException("飞书设备授权失败（" + error + "），请重新扫码");
            }
            check(response);
            // A successful response consumes the device code even if its payload is
            // incomplete. Never poll that code again and obscure the original error.
            try { applyTokens(s, response.body, now); }
            finally { clearPair(s); state.save(s); }
            return true;
        }
    }
    public void cancelPair() throws Exception { synchronized (LOCK) { JSONObject s = state.read(); clearPair(s); state.save(s); } }
    static void clearPair(JSONObject s) { for (String k : new String[]{"deviceCode", "pairUrl", "pairCode", "pairExpires", "nextPoll", "interval"}) s.remove(k); }
    private static void applyTokens(JSONObject s, JSONObject b, long now) throws Exception {
        // Some Feishu gateways wrap the OAuth success in the standard code/data envelope.
        s.put("tokenEnvelope", b.optJSONObject("data") != null ? "data" : "flat");
        if (!b.has("access_token") && b.optJSONObject("data") != null) b = b.getJSONObject("data");
        String access = b.optString("access_token"), refresh = b.optString("refresh_token"); long expires = b.optLong("expires_in");
        s.put("tokenHasAccess", !access.isEmpty()).put("tokenHasRefresh", !refresh.isEmpty()).put("tokenLifetime", expires);
        if (access.isEmpty() || refresh.isEmpty() || expires <= 0 || expires > 366L * 86400)
            throw new IOException("未获得完整授权，请开通 offline_access 后重新扫码");
        s.put("accessToken", access).put("refreshToken", refresh).put("expiresAt", now + expires * 1000).put("reauthorize", false);
    }
    private JSONObject readyState() throws Exception {
        JSONObject s = state.read(); long now = clock.getAsLong();
        if (s.optBoolean("reauthorize") || s.optString("accessToken").isEmpty()) throw new Reauthorize();
        if (s.optLong("expiresAt") <= now + 60_000) {
            // A lost response can consume a rotating refresh token; don't replay it after a crash.
            s.put("reauthorize", true); state.save(s);
            try {
                Response r = transport.request(OPEN + "/authen/v2/oauth/token", "POST", formHeaders(),
                form("grant_type", "refresh_token", "client_id", s.getString("appId"), "client_secret", s.getString("appSecret"), "refresh_token", s.getString("refreshToken")));
                check(r); applyTokens(s, r.body, now); state.save(s);
            } catch (Exception e) { throw new Reauthorize(); }
        }
        return s;
    }
    public JSONObject agenda() throws Exception {
        synchronized (LOCK) {
            JSONObject s = readyState(); long now = clock.getAsLong();
            Map<String,String> headers = new HashMap<>(); headers.put("Authorization", "Bearer " + s.getString("accessToken")); headers.put("Content-Type", "application/json");
            try {
                Response primary = transport.request(OPEN + "/calendar/v4/calendars/primary", "POST", headers, "{}"); check(primary);
                String id = primary.body.getJSONObject("data").getJSONArray("calendars").getJSONObject(0).getJSONObject("calendar").getString("calendar_id");
                if (id.isEmpty()) throw new IOException("未找到主日历");
                Response events = transport.request(OPEN + "/calendar/v4/calendars/" + encode(id) + "/events/instance_view?start_time=" + (now / 1000 - 86400) + "&end_time=" + (now / 1000 + 7 * 86400), "GET", headers, null);
                check(events);
                JSONArray items = normalize(events.body.getJSONObject("data").getJSONArray("items"));
                return new JSONObject().put("syncedAt", now).put("validUntil", now + 24 * 3600_000L).put("events", items);
            } catch (Reauthorize e) { s.put("reauthorize", true); state.save(s); throw e; }
            catch (JSONException e) { throw new IOException("日历响应不完整，保留已有日程"); }
        }
    }
    /** Read people only when opening details; never delay the calendar snapshot. */
    public JSONObject attendees(String eventId) throws Exception {
        synchronized (LOCK) {
            if (eventId == null || eventId.isEmpty()) throw new IOException("无法识别这条日程");
            JSONObject s = readyState();
            Map<String,String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + s.getString("accessToken")); headers.put("Content-Type", "application/json");
            try {
                Response primary = transport.request(OPEN + "/calendar/v4/calendars/primary", "POST", headers, "{}"); check(primary);
                String calendar = primary.body.getJSONObject("data").getJSONArray("calendars").getJSONObject(0).getJSONObject("calendar").getString("calendar_id");
                String url = OPEN + "/calendar/v4/calendars/" + encode(calendar) + "/events/" + encode(eventId) + "/attendees";
                LinkedHashMap<String,String> people = new LinkedHashMap<>(); boolean partial = false, more = false;
                String page = ""; Set<String> pages = new HashSet<>(); int remaining = 10;
                do {
                    if (--remaining < 0 || !pages.add(page)) { more = true; break; }
                    Response r = transport.request(url + "?user_id_type=open_id&page_size=10" + (page.isEmpty() ? "" : "&page_token=" + encode(page)), "GET", headers, null);
                    if (r.body.optInt("code") == 194001) throw new IOException("此日程未开放参会人名单");
                    check(r);
                    JSONObject data = r.body.getJSONObject("data"); JSONArray items = data.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject person = items.getJSONObject(i);
                        if ("removed".equals(person.optString("rsvp_status")) || "resource".equals(person.optString("type"))) continue;
                        if ("chat".equals(person.optString("type"))) {
                            String groupPage = ""; Set<String> groupPages = new HashSet<>();
                            try {
                                do {
                                    if (--remaining < 0 || !groupPages.add(groupPage)) { partial = true; break; }
                                    Response group = transport.request(url + "/" + encode(person.getString("attendee_id")) + "/chat_members?user_id_type=open_id&page_size=10" + (groupPage.isEmpty() ? "" : "&page_token=" + encode(groupPage)), "GET", headers, null); check(group);
                                    JSONObject gd = group.body.getJSONObject("data"); JSONArray members = gd.getJSONArray("items");
                                    for (int j = 0; j < members.length(); j++) addPerson(people, members.getJSONObject(j));
                                    if (!gd.optBoolean("has_more")) break;
                                    groupPage = gd.optString("page_token");
                                    if (groupPage.isEmpty()) { partial = true; break; }
                                    if (people.size() >= 10) { more = true; break; }
                                } while (true);
                            } catch (Reauthorize e) { throw e; }
                            catch (IOException | JSONException e) { partial = true; }
                        } else addPerson(people, person);
                        if (people.size() > 10) break;
                    }
                    if (!data.optBoolean("has_more")) break;
                    page = data.optString("page_token");
                    if (people.size() >= 10 || page.isEmpty()) { more = true; break; }
                } while (true);
                JSONArray names = new JSONArray();
                for (String name : people.values()) { if (names.length() == 10) break; names.put(name); }
                return new JSONObject().put("names", names).put("hasMore", more || people.size() > 10).put("partial", partial);
            } catch (Reauthorize e) { s.put("reauthorize", true); state.save(s); throw e; }
            catch (JSONException e) { throw new IOException("参会人信息暂时不可用"); }
        }
    }
    private static void addPerson(Map<String,String> people, JSONObject person) {
        if ("removed".equals(person.optString("rsvp_status"))) return;
        String name = person.optString("display_name").trim();
        if (name.isEmpty()) name = "未公开姓名";
        String id = person.optString("user_id", person.optString("open_id", person.optString("attendee_id", person.optString("third_party_email", name))));
        people.putIfAbsent(id, name.substring(0, Math.min(100, name.length())) + (person.optBoolean("is_organizer") ? " · 组织者" : ""));
    }
    static JSONArray normalize(JSONArray items) throws Exception {
        Map<String,JSONObject> unique = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = items.getJSONObject(i);
            if ("cancelled".equals(e.optString("status")) || Arrays.asList("decline", "removed").contains(e.optString("rsvp_status"))) continue;
            String id = e.getString("event_id"); JSONObject start = e.getJSONObject("start_time"), end = e.getJSONObject("end_time");
            boolean allDay = !start.optString("date").isEmpty(); long startMs = 0, endMs = 0;
            if (allDay) { java.time.LocalDate.parse(start.getString("date")); java.time.LocalDate.parse(end.getString("date")); }
            else { startMs = Math.multiplyExact(start.getLong("timestamp"), 1000); endMs = Math.multiplyExact(end.getLong("timestamp"), 1000); }
            if (id.isEmpty() || (!allDay && endMs <= startMs)) throw new IOException("日程格式异常，保留已有日程");
            String title = e.optString("summary", "未命名日程"); if (title.isEmpty()) title = "未命名日程";
            JSONObject location = e.optJSONObject("location"); String place = location == null ? "" : location.optString("name");
            // Ordinary occurrences inherit the series' people. Exceptions must keep
            // their own ID because their attendee list may differ from the series.
            String attendeeId = id;
            if (e.has("is_exception") && !e.optBoolean("is_exception") && id.matches("[0-9a-fA-F-]{36}_[1-9][0-9]*"))
                attendeeId = id.substring(0, id.lastIndexOf('_')) + "_0";
            unique.put(id, new JSONObject().put("id", id).put("attendeeEventId", attendeeId).put("title", title.substring(0, Math.min(500,title.length())))
                .put("location", place.substring(0, Math.min(500,place.length()))).put("startMs", startMs).put("endMs", endMs).put("allDay", allDay)
                .put("startDate", start.optString("date")).put("endDate", end.optString("date")));
        }
        List<JSONObject> ordered = new ArrayList<>(unique.values()); ordered.sort(Comparator.comparingLong((JSONObject a) -> a.optLong("startMs")).thenComparing(a -> a.optString("id")));
        return new JSONArray(ordered);
    }
    static void check(Response r) throws IOException {
        int code = r.body.optInt("code"); String error = r.body.optString("error");
        if (r.status == 401 || Arrays.asList(20024,20037,20064,20003,99991663,99991668).contains(code) || "invalid_grant".equals(error)) throw new Reauthorize();
        if (r.status == 403 || code == 99991672) throw new IOException("飞书权限不足，请检查日历权限及应用可用范围");
        if (r.status < 200 || r.status >= 300 || code != 0 || !error.isEmpty())
            throw new IOException("飞书请求失败（HTTP " + r.status + "，错误码 " + code + "），请检查应用配置");
    }
    static Map<String,String> formHeaders() { Map<String,String> h = new HashMap<>(); h.put("Content-Type", "application/x-www-form-urlencoded"); return h; }
    static String encode(String s) throws UnsupportedEncodingException { return URLEncoder.encode(s, "UTF-8"); }
    static String form(String... pairs) throws UnsupportedEncodingException { StringJoiner j = new StringJoiner("&"); for (int i=0;i<pairs.length;i+=2) j.add(encode(pairs[i])+"="+encode(pairs[i+1])); return j.toString(); }
    private static Response https(String url, String method, Map<String,String> headers, String body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false); c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json"); for (Map.Entry<String,String> h : headers.entrySet()) c.setRequestProperty(h.getKey(),h.getValue());
        try {
            if (body != null) { c.setDoOutput(true); try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
            int status = c.getResponseCode(); InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            if (stream == null) throw new IOException("飞书暂时不可用");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = stream) { byte[] b = new byte[4096]; int n; while ((n=in.read(b))!=-1) { if(bytes.size()+n>2_000_000) throw new IOException("日程响应过大"); bytes.write(b,0,n); } }
            try { return new Response(status, new JSONObject(bytes.toString("UTF-8"))); } catch (JSONException e) { throw new IOException("飞书返回了无法解析的响应"); }
        } catch (IOException e) {
            // Transport exception text may include URLs; no provider response body or secret is surfaced.
            throw new IOException("无法连接飞书，请检查手表网络");
        } finally { c.disconnect(); }
    }
}
